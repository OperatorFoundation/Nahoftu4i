package org.nahoft.nahoft

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.operatorfoundation.ion.storage.NounType
import org.operatorfoundation.ion.storage.Word
import org.operatorfoundation.transmission.SerialConnection
import timber.log.Timber

/**
 * Abstraction layer for the Eden radio hardware.
 *
 * Eden has two radio chips sharing a single antenna via a relay:
 *   - SI5351 synthesizer for TX (CLK0 output)
 *   - SI4735 receiver for RX
 *
 * Communication is over USB serial using the ion binary protocol.
 * Commands are sent as ion INTEGER Words; Eden responds with ASCII
 * acknowledgement strings (e.g. "OK TX FREQ\r\n").
 *
 * Frequencies are expressed in centihertz (Hz × 100) internally to match
 * the SI5351 library's expected format. User-facing input is in kHz.
 *
 * Lifecycle: create when the serial connection is established, discard when
 * it is lost. The ViewModel owns the Eden instance.
 */
class Eden(private val connection: SerialConnection)
{
    // ==================== Public Types ====================

    /** Current antenna relay state. Matches EdenMode in eden.cpp. */
    enum class Mode { RX, TX }

    // ==================== Constants ====================

    companion object
    {
        /**
         * Integer control codes recognized by the Eden firmware.
         * Any integer not in this set is interpreted as a frequency in centihertz.
         */
        private const val CONTROL_OFF = 0   // Turn SI5351 CLK0 off (TX only)
        private const val CONTROL_ON  = 1   // Turn SI5351 CLK0 on  (TX only)
        private const val CONTROL_TX  = 2   // Switch relay to TX chip, mute USB audio
        private const val CONTROL_RX  = 3   // Switch relay to RX chip, unmute USB audio

        /** Millis to wait for Eden to process a command and respond. */
        private const val ACK_TIMEOUT_MS = 3000L

        /** Millis of silence after last byte before treating a response as complete. */
        private const val RESPONSE_IDLE_MS = 200L

        /** Poll interval while waiting for a response. */
        private const val POLL_INTERVAL_MS = 50L
    }

    // ==================== State ====================

    /** Last mode we sent to Eden. Null = unknown (Eden has just powered on). */
    var currentMode: Mode? = null
        private set

    // ==================== Public API ====================

    /**
     * Switches the antenna relay to the SI4735 receiver and sets the RX frequency.
     *
     * Call this before starting a WSPR receive session. You do not need to turn
     * the receiver on or off — RX mode is active whenever the relay is in RX
     * position and the USB audio connection is open.
     *
     * @param frequencyKHz Dial frequency in kilohertz (e.g. 14095 for 20m WSPR)
     * @return True if Eden acknowledged both commands
     */
    suspend fun startReceiving(frequencyKHz: Int): Boolean
    {
        Timber.d("Eden: startReceiving at ${frequencyKHz} kHz")

        if (!sendControlCode(CONTROL_RX)) return false
        currentMode = Mode.RX

        val frequencyCHz = frequencyKHz.toCentihertz()
        sendFrequency(frequencyCHz)
        if (awaitAck() == null) return false

        Timber.i("Eden: RX mode active at ${frequencyKHz} kHz")
        return true
    }

    /**
     * Transmits a WSPR message as a sequence of FSK symbols.
     *
     * Handles the full TX sequence:
     *   1. Switch relay to TX
     *   2. Set initial frequency and enable SI5351 output
     *   3. Step through all 162 symbol frequencies with correct timing
     *   4. Disable SI5351 output and switch relay back to RX
     *
     * Blocks on Dispatchers.IO for the ~110 second WSPR transmission.
     *
     * @param symbolFrequenciesCHz 162-element LongArray from WSPREncoder.encodeToFrequencies()
     * @param onSymbolSent Optional progress callback, called with (symbolIndex, total)
     * @return True if all symbols were sent and Eden acknowledged each
     */

    suspend fun transmitWSPR(symbolFrequenciesCHz: LongArray, onSymbolSent: ((Int, Int) -> Unit)? = null): Boolean = withContext(Dispatchers.IO)
    {
        require(symbolFrequenciesCHz.size == 162)
        {
            "WSPR requires exactly 162 symbols, got ${symbolFrequenciesCHz.size}"
        }

        Timber.d("Eden: beginning WSPR transmission (${symbolFrequenciesCHz.size} symbols)")

        if (!sendControlCode(CONTROL_TX)) return@withContext false
        currentMode = Mode.TX

        var firstSymbol = true

        for ((index, frequencyCHz) in symbolFrequenciesCHz.withIndex())
        {
            sendFrequency(frequencyCHz)

            if (firstSymbol)
            {
                // Eden is idle before TX starts — wait for frequency ack,
                // then enable the oscillator output.
                if (awaitAck() == null) return@withContext false
                if (!sendControlCode(CONTROL_ON)) return@withContext false
                firstSymbol = false
            }

            // Mid-transmission: Eden updates the tone silently, no ack.

            onSymbolSent?.invoke(index, symbolFrequenciesCHz.size)

            Thread.sleep(683L)
        }

        if (!sendControlCode(CONTROL_OFF)) return@withContext false

        if (!sendControlCode(CONTROL_RX)) return@withContext false
        currentMode = Mode.RX

        Timber.i("Eden: WSPR transmission complete")
        true
    }

    /**
     * Closes the serial connection. Call when the ViewModel is cleared or the
     * USB device is detached.
     */
    fun close()
    {
        try
        {
            connection.close()
        }
        catch (e: Exception)
        {
            Timber.w(e, "Eden: error closing connection")
        }
    }

    // ==================== Private Helpers ====================

    /**
     * Sends a single integer control code and waits for acknowledgement.
     */
    private suspend fun sendControlCode(code: Int): Boolean = withContext(Dispatchers.IO)
    {
        Timber.d("Eden: sending control code $code")
        try
        {
            Word.to_conn(connection, Word.make(code, NounType.INTEGER.value))
            awaitAck() != null
        }
        catch (e: Exception)
        {
            Timber.e(e, "Eden: failed to send control code $code")
            false
        }
    }

    /**
     * Sends a frequency value in centihertz. Fire-and-forget — does not wait
     * for acknowledgement. Callers are responsible for calling awaitAck() when
     * a response is expected (i.e. before transmission starts).
     *
     * FIXME: Word.make() takes Int. Frequencies above ~21 MHz expressed in cHz
     * will overflow.
     */
    private suspend fun sendFrequency(frequencyCHz: Long) = withContext(Dispatchers.IO)
    {
        Timber.d("Eden: sending frequency ${frequencyCHz} cHz")
        try
        {
            Word.to_conn(connection, Word.make(frequencyCHz.toInt(), NounType.INTEGER.value))
        }
        catch (e: Exception)
        {
            Timber.e(e, "Eden: failed to send frequency ${frequencyCHz} cHz")
        }
    }

    /**
     * Reads from the serial connection until a complete response is received or
     * the timeout expires.
     *
     * Considers a response complete after [RESPONSE_IDLE_MS] of silence following
     * the last received byte.
     *
     * @return Response string (e.g. "OK TX FREQ"), or null on timeout
     */
    private suspend fun awaitAck(): String? = withContext(Dispatchers.IO)
    {
        withTimeoutOrNull(ACK_TIMEOUT_MS) {
            val buffer = StringBuilder()
            val startTime = System.currentTimeMillis()
            var lastByteTime = startTime

            while (System.currentTimeMillis() - startTime < ACK_TIMEOUT_MS)
            {
                try
                {
                    val data = connection.readAvailable()

                    if (data != null && data.isNotEmpty())
                    {
                        lastByteTime = System.currentTimeMillis()

                        for (byte in data)
                        {
                            val char = byte.toInt().toChar()
                            // Collect printable ASCII only (strip control chars and nulls)
                            if (!char.isISOControl() && char != '\u0000')
                            {
                                buffer.append(char)
                            }
                        }
                    }
                    else
                    {
                        // No new data — check if we have a complete response
                        val idleMs = System.currentTimeMillis() - lastByteTime
                        if (buffer.isNotEmpty() && idleMs > RESPONSE_IDLE_MS)
                        {
                            return@withTimeoutOrNull buffer.toString().trim()
                        }

                        delay(POLL_INTERVAL_MS)
                    }
                }
                catch (e: Exception)
                {
                    Timber.w(e, "Eden: error reading response")
                    delay(POLL_INTERVAL_MS)
                }
            }

            // Return whatever arrived before timeout, or null
            buffer.toString().trim().ifEmpty { null }
        }
    }

    // ==================== Extensions ====================

    /**
     * Converts a user-facing kilohertz value to the centihertz value Eden expects.
     *
     * Example: 14095 kHz → 1,409,500,000 cHz
     *
     * WSPREncoder adds a 1500 Hz audio center offset on top of this, so the
     * effective RF frequency is (frequencyKHz * 1000 + 1500) Hz, matching the
     * WSPR convention of dial frequency + 1500 Hz audio tone.
     */
    private fun Int.toCentihertz(): Long = this * 100_000L
}