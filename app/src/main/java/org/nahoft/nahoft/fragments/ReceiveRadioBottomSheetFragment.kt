package org.nahoft.nahoft.fragments

import android.animation.ObjectAnimator
import android.graphics.PorterDuff
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import androidx.core.animation.ValueAnimator
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.*
import org.libsodium.jni.keys.PublicKey
import org.nahoft.codex.Encryption
import org.nahoft.nahoft.models.Friend
import org.nahoft.nahoft.R
import org.nahoft.nahoft.databinding.FragmentBottomSheetReceiveRadioBinding
import org.operatorfoundation.audiocoder.WSPRStation
import org.operatorfoundation.audiocoder.models.WSPRCycleInformation
import org.operatorfoundation.audiocoder.models.WSPRDecodeResult
import org.operatorfoundation.audiocoder.models.WSPRStationConfiguration
import org.operatorfoundation.audiocoder.models.WSPRStationState
import org.operatorfoundation.codex.symbols.WSPRMessage
import org.operatorfoundation.codex.symbols.WSPRMessageSequence
import org.operatorfoundation.signalbridge.SignalBridgeWSPRAudioSource
import org.operatorfoundation.signalbridge.UsbAudioConnection
import org.operatorfoundation.signalbridge.models.AudioBufferConfiguration
import timber.log.Timber
import java.math.BigInteger

/**
 * BottomSheet for receiving encrypted messages via WSPR radio.
 *
 * This handles the complete receive flow:
 * 1. Monitors WSPR timing cycles
 * 2. Collects and decodes WSPR signals
 * 3. Accumulates messages with 'Q' prefix (encoded Nahoft messages)
 * 4. Attempts decryption after each decode cycle
 * 5. Reports success via callback when message is decrypted
 */
class ReceiveRadioBottomSheetFragment : BottomSheetDialogFragment()
{

    private var _binding: FragmentBottomSheetReceiveRadioBinding? = null
    private val binding get() = _binding!!

    // Dependencies passed via newInstance
    private var usbAudioConnection: UsbAudioConnection? = null
    private var friend: Friend? = null
    private var onMessageReceived: ((ByteArray) -> Unit)? = null

    // WSPR station components
    private var wsprStation: WSPRStation? = null
    private var audioSource: SignalBridgeWSPRAudioSource? = null

    // Coroutine management
    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var stationJob: Job? = null
    private var elapsedTimeJob: Job? = null

    // State tracking
    private val receivedMessages = mutableListOf<WSPRMessage>()
    private var decodeAttempts = 0
    private var startTimeMs = 0L

    // Animation tracking
    private var currentAnimator: ObjectAnimator? = null

    /**
     * Animation types for state icon
     */
    private enum class AnimationType {
        NONE,
        PULSE,      // For listening states
        ROTATE,     // For processing
        SCALE       // For success
    }

    // Configuration
    companion object {
        private const val TAG = "ReceiveRadioBottomSheet"

        /** Maximum time to listen before timing out (20 minutes) */
        private const val TIMEOUT_MS = 20 * 60 * 1000L

        /**
         * Creates a new instance of the BottomSheet.
         *
         * @param connection Active USB audio connection
         * @param friend The friend we're expecting a message from
         * @param onMessageReceived Callback when message is successfully decrypted
         */
        fun newInstance(
            connection: UsbAudioConnection,
            friend: Friend,
            onMessageReceived: (ByteArray) -> Unit
        ): ReceiveRadioBottomSheetFragment {
            return ReceiveRadioBottomSheetFragment().apply {
                this.usbAudioConnection = connection
                this.friend = friend
                this.onMessageReceived = onMessageReceived
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentBottomSheetReceiveRadioBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupClickListeners()
        startReceiving()
    }

    private fun setupClickListeners() {
        binding.btnClose.setOnClickListener { cancelAndDismiss() }
        binding.btnCancel.setOnClickListener { cancelAndDismiss() }
    }

    /**
     * Starts the WSPR receive operation.
     */
    private fun startReceiving() {
        val connection = usbAudioConnection
        if (connection == null) {
            updateStatus(getString(R.string.usb_audio_not_connected))
            return
        }

        startTimeMs = System.currentTimeMillis()
        startElapsedTimeUpdates()

        stationJob = coroutineScope.launch {
            try {
                // Create audio source from USB connection
                audioSource = SignalBridgeWSPRAudioSource(
                    usbAudioConnection = connection,
                    bufferConfiguration = AudioBufferConfiguration.createDefault()
                )

                // Create WSPR station configuration
                val config = WSPRStationConfiguration.createDefault()
                wsprStation = WSPRStation(audioSource!!, config)

                // Start the station
                Timber.d("NAHOFT-TEST-12345: About to start WSPR station")
                val startResult = wsprStation!!.startStation()
                Timber.d("NAHOFT-TEST-12345: startStation returned: ${startResult.isSuccess}")

                if (startResult.isFailure) {
                    updateStatus("Failed to start: ${startResult.exceptionOrNull()?.message}")
                    return@launch
                }

                // Start monitoring flows
                launch { observeStationState() }
                launch { observeCycleInformation() }
                launch { observeDecodeResults() }
                launch { observeAudioLevels() }
                launch { monitorTimeout() }

            } catch (e: Exception) {
                Timber.e(e, "Error starting WSPR receive")
                updateStatus("Error: ${e.message}")
            }
        }
    }

    /**
     * Observes WSPR station state changes and updates UI.
     */
    /**
     * Observes WSPR station state changes and updates UI with icons and animations.
     *
     * State mappings:
     * - Running: Green radio icon with pulse animation (listening actively)
     * - WaitingForNextWindow: Grey clock icon, no animation (waiting for timing)
     * - CollectingAudio: Green radio icon with pulse, shows audio level section
     * - ProcessingAudio: Orange sync icon with rotation (working on decode)
     * - DecodeCompleted: Green checkmark with scale animation (success!)
     * - Error: Red error icon, no animation (something went wrong)
     */
    private suspend fun observeStationState()
    {
        wsprStation?.stationState?.collect { state ->
            when (state) {
                is WSPRStationState.Running -> {
                    updateStateIcon(R.drawable.ic_radio, R.color.caribbeanGreen, AnimationType.PULSE)
                    updateStatus(getString(R.string.listening_for_signals))
                }
                is WSPRStationState.WaitingForNextWindow -> {
                    updateStateIcon(R.drawable.ic_access_time, R.color.coolGrey, AnimationType.NONE)
                    updateStatus(getString(R.string.waiting_for_wspr_window))
                }
                is WSPRStationState.CollectingAudio -> {
                    updateStateIcon(R.drawable.ic_radio, R.color.caribbeanGreen, AnimationType.PULSE)
                    updateStatus(getString(R.string.collecting_audio))
                    // Show audio level section when actively collecting
                    binding.audioLevelSection.visibility = View.VISIBLE
                }
                is WSPRStationState.ProcessingAudio -> {
                    updateStateIcon(R.drawable.ic_sync, R.color.tangerine, AnimationType.ROTATE)
                    updateStatus(getString(R.string.processing_decode))
                }
                is WSPRStationState.DecodeCompleted -> {
                    updateStateIcon(R.drawable.ic_success, R.color.caribbeanGreen, AnimationType.SCALE)
                    updateStatus(getString(R.string.decode_complete))
                }
                is WSPRStationState.Error -> {
                    updateStateIcon(R.drawable.ic_error, R.color.madderLake, AnimationType.NONE)
                    updateStatus("Error: ${state.errorDescription}")
                }
                else -> {
                    updateStateIcon(R.drawable.ic_radio, R.color.coolGrey, AnimationType.NONE)
                    updateStatus(state::class.simpleName ?: "Unknown")
                }
            }
        }
    }

    /**
     * Updates the state icon with color and animation
     */
    private fun updateStateIcon(iconRes: Int, colorRes: Int, animationType: AnimationType)
    {
        if (_binding == null) return

        // Cancel any existing animation
        currentAnimator?.cancel()
        currentAnimator = null

        // Reset transformations
        binding.ivStateIcon.rotation = 0f
        binding.ivStateIcon.scaleX = 1f
        binding.ivStateIcon.scaleY = 1f
        binding.ivStateIcon.alpha = 1f

        // Set icon and color
        binding.ivStateIcon.setImageResource(iconRes)
        binding.ivStateIcon.setColorFilter(
            ContextCompat.getColor(requireContext(), colorRes),
            PorterDuff.Mode.SRC_IN
        )

        // Apply animation based on type
        when (animationType) {
            AnimationType.PULSE -> startPulseAnimation()
            AnimationType.ROTATE -> startRotateAnimation()
            AnimationType.SCALE -> startScaleAnimation()
            AnimationType.NONE -> {} // No animation
        }
    }

    /**
     * Pulse animation for listening states
     */
    private fun startPulseAnimation()
    {
        currentAnimator = ObjectAnimator.ofFloat(binding.ivStateIcon, "alpha", 1f, 0.4f, 1f).apply {
            duration = 2000
            repeatCount = ValueAnimator.INFINITE
            repeatMode = android.animation.ValueAnimator.RESTART
            start()
        }
    }

    /**
     * Rotation animation for processing state
     */
    private fun startRotateAnimation()
    {
        currentAnimator = ObjectAnimator.ofFloat(binding.ivStateIcon, "rotation", 0f, 360f).apply {
            duration = 1500
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }
    }

    /**
     * Scale animation for success (plays once)
     */
    private fun startScaleAnimation()
    {
        val scaleX = ObjectAnimator.ofFloat(binding.ivStateIcon, "scaleX", 0.8f, 1.2f, 1f).apply {
            duration = 400
        }
        val scaleY = ObjectAnimator.ofFloat(binding.ivStateIcon, "scaleY", 0.8f, 1.2f, 1f).apply {
            duration = 400
        }

        scaleX.start()
        scaleY.start()
        currentAnimator = scaleX // Track one for cleanup
    }

    /**
     * Observes WSPR cycle timing information and updates progress UI.
     */
    private suspend fun observeCycleInformation()
    {
        wsprStation?.cycleInformation?.collect { cycleInfo ->
            updateCycleProgress(cycleInfo)
        }
    }

    /**
     * Observes decode results and attempts message reconstruction/decryption.
     */
    private suspend fun observeDecodeResults()
    {
        Timber.d("NAHOFT: observeDecodeResults STARTED")

        wsprStation?.decodeResults?.collect { results ->
            Timber.d("NAHOFT: Received ${results.size} decode results")

            results.forEach { result ->
                Timber.d("NAHOFT-DETAIL: call='${result.callsign}', grid='${result.gridSquare}', power=${result.powerLevelDbm}, snr=${result.signalToNoiseRatioDb}, msg='${result.completeMessage}'")
            }

            if (results.isNotEmpty()) processDecodeResults(results)
        }
    }

    /**
     * Observes audio level from USB connection for visual feedback.
     */
    private suspend fun observeAudioLevels()
    {
        usbAudioConnection?.getAudioLevel()?.collect { levelInfo ->
            val percent = (levelInfo.currentLevel * 100).toInt()
            binding.progressAudio.progress = percent
            binding.tvAudioLevelPercent.text = "$percent%"
        }
    }

    /**
     * Monitors for timeout condition.
     */
    private suspend fun monitorTimeout()
    {
        delay(TIMEOUT_MS)

        // If we get here without being cancelled, we timed out
        withContext(Dispatchers.Main) {
            val minutes = (TIMEOUT_MS / 60000).toInt()
            updateStatus(getString(R.string.receive_timeout, minutes))
            delay(2000)
            cancelAndDismiss()
        }
    }

    /**
     * Processes decode results from a WSPR decode cycle.
     *
     * Filters for encoded Nahoft messages (Q prefix), accumulates them,
     * and attempts decryption.
     */
    private fun processDecodeResults(results: List<WSPRDecodeResult>)
    {
        decodeAttempts++
        binding.tvDecodeAttempts.text = decodeAttempts.toString()

        // Filter for encoded Nahoft messages (callsign starts with Q)
        val nahoftMessages = results.filter { result ->
            WSPRMessage.isEncodedMessage(result.callsign)
        }

        if (nahoftMessages.isEmpty()) {
            Timber.d("No Nahoft messages in this decode cycle")
            return
        }

        // Convert decode results to WSPRMessage objects
        for (result in nahoftMessages) {
            try {
                val message = WSPRMessage.fromWSPRFields(
                    result.callsign,
                    result.gridSquare,
                    result.powerLevelDbm
                )

                // Check if we already have this message (deduplicate)
                val isDuplicate = receivedMessages.any { existing ->
                    existing.toWSPRFields() == message.toWSPRFields()
                }

                if (!isDuplicate) {
                    receivedMessages.add(message)
                    Timber.d("Added new WSPR message: ${message.toWSPRFields()}")
                }
            } catch (e: Exception) {
                Timber.w(e, "Failed to parse WSPR message: ${result.callsign}")
            }
        }

        // Update message count
        binding.tvMessagesReceived.text = receivedMessages.size.toString()

        // Attempt decryption if we have at least 2 messages
        if (receivedMessages.size >= 2) {
            attemptDecryption()
        }
    }

    /**
     * Attempts to decode and decrypt accumulated WSPR messages.
     *
     * Tries with current message count. If decryption fails,
     * we continue listening for more messages.
     */
    /**
     * Attempts to decode and decrypt accumulated WSPR messages.
     *
     * Tries with current message count. If decryption fails,
     * we continue listening for more messages.
     *
     * On success, passes the encrypted bytes to the callback for saving.
     * The Message class stores cipher text and handles decryption at display time.
     */
    private fun attemptDecryption()
    {
        val friendKeyBytes = friend?.publicKeyEncoded
        if (friendKeyBytes == null) {
            Timber.w("No friend public key available for decryption")
            return
        }

        updateStatus(getString(R.string.attempting_decrypt))

        try {
            // Create message sequence from accumulated messages
            val sequence = WSPRMessageSequence.fromMessages(receivedMessages.toList())

            // Decode to BigInteger then to bytes (this is the encrypted data)
            val numericValue = sequence.decode()
            val encryptedBytes = bigIntegerToByteArray(numericValue)

            Timber.d("Attempting decryption of ${encryptedBytes.size} bytes from ${receivedMessages.size} WSPR messages")

            // Attempt decryption to verify we have all the messages
            // decrypt() throws SecurityException if ciphertext is invalid/incomplete
            val friendPublicKey = PublicKey(friendKeyBytes)
            val decryptedText = Encryption().decrypt(friendPublicKey, encryptedBytes)

            // If we get here without exception, decryption succeeded
            Timber.i("Successfully decrypted message from ${receivedMessages.size} WSPR messages")
            updateStatus(getString(R.string.message_received))

            // Callback with ENCRYPTED bytes (Message stores cipher text)
            coroutineScope.launch {
                delay(500) // Brief pause to show success message
                onMessageReceived?.invoke(encryptedBytes)
                dismiss()
            }
        } catch (e: SecurityException) {
            // Decryption failed - likely need more messages
            Timber.d("Decryption attempt failed (may need more messages): ${e.message}")
            updateStatus(getString(R.string.listening_for_signals))
        } catch (e: Exception) {
            // Other error (parsing, etc.)
            Timber.w(e, "Error during decryption attempt")
            updateStatus(getString(R.string.listening_for_signals))
        }
    }

    /**
     * Converts a BigInteger to a byte array for decryption.
     *
     * Handles the sign byte that BigInteger.toByteArray() may prepend.
     */
    private fun bigIntegerToByteArray(value: BigInteger): ByteArray {
        val bytes = value.toByteArray()

        // If the first byte is 0x00 (sign byte for positive numbers), remove it
        return if (bytes.isNotEmpty() && bytes[0] == 0.toByte() && bytes.size > 1) {
            bytes.copyOfRange(1, bytes.size)
        } else {
            bytes
        }
    }

    /**
     * Updates the cycle progress UI.
     */
    private fun updateCycleProgress(cycleInfo: WSPRCycleInformation) {
        binding.progressCycle.progress = cycleInfo.cyclePositionSeconds
        binding.tvCycleTime.text = "${cycleInfo.cyclePositionSeconds}s / 120s"

        val nextWindow = cycleInfo.nextDecodeWindowInfo
        binding.tvNextWindow.text = getString(
            R.string.next_decode_window_in,
            nextWindow.secondsUntilWindow.toInt()
        )
    }

    /**
     * Updates the status message.
     */
    private fun updateStatus(message: String) {
        if (_binding != null) {
            binding.tvStatus.text = message
        }
    }

    /**
     * Starts updating elapsed time display.
     */
    private fun startElapsedTimeUpdates() {
        elapsedTimeJob = coroutineScope.launch {
            while (isActive) {
                val elapsedMs = System.currentTimeMillis() - startTimeMs
                val minutes = (elapsedMs / 60000).toInt()
                val seconds = ((elapsedMs % 60000) / 1000).toInt()
                binding.tvElapsedTime.text = String.format("%d:%02d", minutes, seconds)
                delay(1000)
            }
        }
    }

    /**
     * Cancels the receive operation and dismisses the sheet.
     */
    private fun cancelAndDismiss() {
        Timber.d("Receive operation cancelled")
        stopReceiving()
        dismissAllowingStateLoss()
    }

    /**
     * Stops all receive operations and cleans up resources.
     */
    private fun stopReceiving() {
        stationJob?.cancel()
        elapsedTimeJob?.cancel()

        coroutineScope.launch {
            try {
                wsprStation?.stopStation()
                audioSource?.cleanup()
            } catch (e: Exception) {
                Timber.w(e, "Error during cleanup")
            }
        }

        wsprStation = null
        audioSource = null
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopReceiving()
        coroutineScope.cancel()
        _binding = null
    }
}