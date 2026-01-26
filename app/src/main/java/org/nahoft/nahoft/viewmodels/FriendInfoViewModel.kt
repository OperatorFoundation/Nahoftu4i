package org.nahoft.nahoft.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hoho.android.usbserial.driver.UsbSerialDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.nahoft.nahoft.models.Friend
import org.nahoft.nahoft.models.FriendStatus
import org.nahoft.nahoft.models.WSPRSpotItem
import org.nahoft.nahoft.models.NahoftSpotStatus
import org.nahoft.nahoft.models.FailureReason
import org.operatorfoundation.signalbridge.UsbAudioConnection
import org.operatorfoundation.signalbridge.UsbAudioManager
import org.operatorfoundation.signalbridge.models.ConnectionStatus
import org.operatorfoundation.signalbridge.models.UsbAudioDevice
import org.operatorfoundation.transmission.SerialConnection
import org.operatorfoundation.transmission.SerialConnectionFactory
import org.operatorfoundation.audiocoder.WSPRStation
import org.operatorfoundation.audiocoder.WSPRTimingCoordinator
import org.operatorfoundation.audiocoder.models.WSPRStationConfiguration
import org.operatorfoundation.audiocoder.models.WSPRCycleInformation
import org.operatorfoundation.audiocoder.models.WSPRDecodeResult
import org.operatorfoundation.audiocoder.models.WSPRStationState
import org.operatorfoundation.signalbridge.SignalBridgeWSPRAudioSource
import org.operatorfoundation.signalbridge.models.AudioBufferConfiguration
import org.operatorfoundation.codex.symbols.WSPRMessage
import timber.log.Timber

class FriendInfoViewModel(application: Application) : AndroidViewModel(application)
{
    // ==================== Receive Session State Properties ====================

    private val _receiveSessionState = MutableStateFlow<ReceiveSessionState>(ReceiveSessionState.Idle)
    val receiveSessionState: StateFlow<ReceiveSessionState> = _receiveSessionState.asStateFlow()

    // WSPR station components (owned by ViewModel for session persistence)
    private var wsprStation: WSPRStation? = null
    private var audioSource: SignalBridgeWSPRAudioSource? = null
    private val timingCoordinator = WSPRTimingCoordinator()

    // Session data
    private val _receivedSpots = MutableStateFlow<List<WSPRSpotItem>>(emptyList())
    val receivedSpots: StateFlow<List<WSPRSpotItem>> = _receivedSpots.asStateFlow()

    private val _receivedMessages = mutableListOf<WSPRMessage>()
    val receivedMessageCount: Int get() = _receivedMessages.size

    private var currentNahoftGroupId = 0
    private var decryptionAttempts = 0
    private var _messageJustReceived = MutableStateFlow(false)
    val messageJustReceived: StateFlow<Boolean> = _messageJustReceived.asStateFlow()
    private val _lastReceivedMessage = MutableSharedFlow<ByteArray>(replay = 0)
    val lastReceivedMessage: SharedFlow<ByteArray> = _lastReceivedMessage.asSharedFlow()

    // Timing
    private var sessionStartTimeMs = 0L
    private var sessionJob: Job? = null
    private var waitForWindowJob: Job? = null

    // Configuration
    private val sessionTimeoutMs = 20 * 60 * 1000L  // 20 minutes, configurable

    // Flows exposed from WSPRStation (relayed when station is active)
    private val _stationState = MutableStateFlow<WSPRStationState?>(null)
    val stationState: StateFlow<WSPRStationState?> = _stationState.asStateFlow()

    private val _cycleInformation = MutableStateFlow<WSPRCycleInformation?>(null)
    val cycleInformation: StateFlow<WSPRCycleInformation?> = _cycleInformation.asStateFlow()

    private val _audioLevel = MutableStateFlow(0f)
    val audioLevel: StateFlow<Float> = _audioLevel.asStateFlow()

    // ==================== Friend State ====================

    private val _friend = MutableStateFlow<Friend?>(null)
    val friend: StateFlow<Friend?> = _friend.asStateFlow()

    fun initializeFriend(friend: Friend)
    {
        if (_friend.value == null)
        {
            _friend.value = friend
        }
    }

    // ==================== Serial Connection (WSPR Transmit) ====================

    private val connectionFactory = SerialConnectionFactory(application)

    /** Current serial connection state from the factory */
    val serialConnectionState: StateFlow<SerialConnectionFactory.ConnectionState> =
        connectionFactory.connectionState

    /** Convenience accessor for the active connection, if any */
    val serialConnection: SerialConnection?
        get() = (serialConnectionState.value as? SerialConnectionFactory.ConnectionState.Connected)?.connection

    // Flag to prevent duplicate connection attempts
    private var isConnecting = false

    /**
     * Checks for available serial devices and initiates connection if found.
     */
    fun checkForSerialDevices()
    {
        if (isConnecting)
        {
            Timber.d("Serial connection already in progress, skipping check")
            return
        }

        viewModelScope.launch {
            val devices = withContext(Dispatchers.IO) {
                connectionFactory.findAvailableDevices()
            }

            if (devices.isNotEmpty() && serialConnection == null && !isConnecting)
            {
                Timber.d("Found ${devices.size} serial device(s), initiating connection...")
                isConnecting = true
                connectToSerialDevice(devices.first())
            }
        }
    }

    /**
     * Initiates connection to a serial device.
     * State changes are emitted via serialConnectionState.
     */
    fun connectToSerialDevice(driver: UsbSerialDriver)
    {
        connectionFactory.createConnection(driver.device)
    }

    fun onUsbDeviceAttached()
    {
        if (serialConnection == null && !isConnecting)
        {
            viewModelScope.launch {
                delay(500) // Let USB stabilize
                checkForSerialDevices()
            }
        }
    }

    fun onUsbDeviceDetached()
    {
        isConnecting = false
        // Factory will emit Disconnected state automatically
    }

    /**
     * Resets the isConnecting flag after connection completes or fails.
     * Call when observing Connected/Disconnected/Error states.
     */
    fun onSerialConnectionStateSettled()
    {
        isConnecting = false
    }

    // ==================== USB Audio Connection (WSPR Receive) ====================

    private val usbAudioManager = UsbAudioManager.create(application)

    private val _usbAudioConnection = MutableStateFlow<UsbAudioConnection?>(null)
    val usbAudioConnection: StateFlow<UsbAudioConnection?> = _usbAudioConnection.asStateFlow()

    /** Connection status from the audio manager */
    val audioConnectionStatus: Flow<ConnectionStatus> = usbAudioManager.getConnectionStatus()

    // Jobs for audio device discovery and status observation
    private var audioDeviceDiscoveryJob: Job? = null
    private var audioConnectionStatusJob: Job? = null

    /**
     * Starts discovery of USB audio devices.
     * Automatically connects to the first device found.
     */
    fun startAudioDeviceDiscovery()
    {
        audioDeviceDiscoveryJob = viewModelScope.launch {
            usbAudioManager.discoverDevices().collect { devices ->
                Timber.d("Discovered ${devices.size} USB audio device(s)")

                // Auto-connect to first device if not already connected
                if (devices.isNotEmpty() && _usbAudioConnection.value == null)
                {
                    connectToAudioDevice(devices.first())
                }
            }
        }
    }

    /**
     * Connects to a USB audio device.
     *
     * @param device The device to connect to
     * @param onPermissionNeeded Callback if RECORD_AUDIO permission is required
     */
    fun connectToAudioDevice(
        device: UsbAudioDevice,
        onPermissionNeeded: (() -> Unit)? = null
    ) {
        viewModelScope.launch {
            Timber.d("Attempting to connect to USB audio device: ${device.displayName}")

            val result = usbAudioManager.connectToDevice(device)

            if (result.isSuccess)
            {
                _usbAudioConnection.value = result.getOrNull()
                Timber.i("USB Audio connected: ${device.displayName}")
            }
            else
            {
                val exception = result.exceptionOrNull()
                Timber.e(exception, "Failed to connect to USB audio device")

                // Check if it's a permission issue
                if (exception?.message?.contains("permission", ignoreCase = true) == true)
                {
                    onPermissionNeeded?.invoke()
                }
            }
        }
    }

    /**
     * Starts observing audio connection status.
     * Updates internal state when connection/disconnection occurs.
     */
    fun observeAudioConnectionStatus()
    {
        audioConnectionStatusJob = viewModelScope.launch {
            audioConnectionStatus.collect { status ->
                Timber.d("USB Audio connection status: $status")

                when (status)
                {
                    is ConnectionStatus.Connected -> {
                        Timber.i("USB Audio connected")
                    }
                    is ConnectionStatus.Disconnected -> {
                        Timber.d("USB Audio disconnected")
                        _usbAudioConnection.value = null
                    }
                    is ConnectionStatus.Connecting -> {
                        Timber.d("USB Audio connecting...")
                    }
                    is ConnectionStatus.Error -> {
                        Timber.e("USB Audio error: ${status.message}")
                        _usbAudioConnection.value = null
                    }
                }
            }
        }
    }

    // ==================== Receive Session State ====================

    /**
     * Starts a new receive session.
     * If entered mid-cycle, waits for the next fresh window before collecting.
     */
    fun startReceiveSession()
    {
        if (_receiveSessionState.value != ReceiveSessionState.Idle &&
            _receiveSessionState.value != ReceiveSessionState.Stopped) {
            Timber.d("Session already active, ignoring start request")
            return
        }

        val connection = _usbAudioConnection.value
        if (connection == null) {
            Timber.w("Cannot start receive session: no USB audio connection")
            return
        }

        sessionStartTimeMs = System.currentTimeMillis()
        _messageJustReceived.value = false
        decryptionAttempts = 0

        // Check if we're early enough in the cycle to start collecting
        if (timingCoordinator.isEarlyEnoughToStartCollection()) {
            startWSPRStation(connection)
        } else {
            _receiveSessionState.value = ReceiveSessionState.WaitingForWindow
            waitForNextWindowAndStart(connection)
        }

        // Start timeout monitor
        startTimeoutMonitor()
    }

    /**
     * Waits for the next WSPR window, then starts the station.
     */
    private fun waitForNextWindowAndStart(connection: UsbAudioConnection)
    {
        waitForWindowJob = viewModelScope.launch {
            while (isActive && _receiveSessionState.value == ReceiveSessionState.WaitingForWindow) {
                val windowInfo = timingCoordinator.getTimeUntilNextDecodeWindow()

                if (windowInfo.secondsUntilWindow <= 0) {
                    startWSPRStation(connection)
                    return@launch
                }

                // Update cycle info for UI
                _cycleInformation.value = timingCoordinator.getCurrentCycleInformation()

                delay(1000)
            }
        }
    }

    /**
     * Creates and starts the WSPR station.
     */
    private fun startWSPRStation(connection: UsbAudioConnection)
    {
        sessionJob = viewModelScope.launch {
            try {
                audioSource = SignalBridgeWSPRAudioSource(
                    usbAudioConnection = connection,
                    bufferConfiguration = AudioBufferConfiguration.createDefault()
                )

                val config = WSPRStationConfiguration.createDefault()
                wsprStation = WSPRStation(audioSource!!, config)

                Timber.d("Starting WSPR station")
                val startResult = wsprStation!!.startStation()

                if (startResult.isFailure) {
                    Timber.e("Failed to start WSPR station: ${startResult.exceptionOrNull()?.message}")
                    _receiveSessionState.value = ReceiveSessionState.Stopped
                    return@launch
                }

                _receiveSessionState.value = ReceiveSessionState.Running

                // Start observing station flows
                launch { observeStationState() }
                launch { observeCycleInformation() }
                launch { observeDecodeResults() }
                launch { observeAudioLevels(connection) }

            } catch (e: Exception) {
                Timber.e(e, "Error starting WSPR station")
                _receiveSessionState.value = ReceiveSessionState.Stopped
            }
        }
    }

    /**
     * Stops the current receive session.
     */
    fun stopReceiveSession()
    {
        Timber.d("Stopping receive session")

        // Mark any pending spots as incomplete
        if (_receivedMessages.isNotEmpty()) {
            markCurrentGroupAsFailed(FailureReason.INCOMPLETE)
        }

        waitForWindowJob?.cancel()
        sessionJob?.cancel()

        viewModelScope.launch {
            try {
                wsprStation?.stopStation()
                audioSource?.cleanup()
            } catch (e: Exception) {
                Timber.w(e, "Error during session cleanup")
            }

            wsprStation = null
            audioSource = null
        }

        _receiveSessionState.value = ReceiveSessionState.Stopped
    }

    /**
     * Resets session to idle state
     */
    fun resetSession()
    {
        _receiveSessionState.value = ReceiveSessionState.Idle
        _receivedSpots.value = emptyList()
        _receivedMessages.clear()
        _messageJustReceived.value = false
        decryptionAttempts = 0
        currentNahoftGroupId = 0
        sessionStartTimeMs = 0L
    }

    /**
     * Returns whether a receive session is currently active.
     */
    fun isSessionActive(): Boolean
    {
        return _receiveSessionState.value == ReceiveSessionState.Running ||
                _receiveSessionState.value == ReceiveSessionState.WaitingForWindow
    }

    private fun processDecodeResults(results: List<WSPRDecodeResult>)
    {
        val currentSpots = _receivedSpots.value.toMutableList()

        for (result in results) {
            val isNahoftMessage = WSPRMessage.isEncodedMessage(result.callsign)
            val timestamp = System.currentTimeMillis()

            if (isNahoftMessage) {
                try {
                    val message = WSPRMessage.fromWSPRFields(
                        result.callsign,
                        result.gridSquare,
                        result.powerLevelDbm
                    )

                    val isDuplicate = _receivedMessages.any { existing ->
                        existing.toWSPRFields() == message.toWSPRFields()
                    }

                    if (!isDuplicate) {
                        _receivedMessages.add(message)
                        Timber.d("Added new WSPR message: ${message.toWSPRFields()}")
                    }

                    val partNumber = _receivedMessages.size

                    val spot = WSPRSpotItem(
                        callsign = result.callsign,
                        gridSquare = result.gridSquare,
                        powerDbm = result.powerLevelDbm,
                        snrDb = result.signalToNoiseRatioDb,
                        timestamp = timestamp,
                        nahoftStatus = NahoftSpotStatus.Pending(
                            groupId = currentNahoftGroupId,
                            partNumber = partNumber
                        )
                    )
                    currentSpots.add(0, spot)

                } catch (e: Exception) {
                    Timber.w(e, "Failed to parse WSPR message: ${result.callsign}")

                    val spot = WSPRSpotItem(
                        callsign = result.callsign,
                        gridSquare = result.gridSquare,
                        powerDbm = result.powerLevelDbm,
                        snrDb = result.signalToNoiseRatioDb,
                        timestamp = timestamp,
                        nahoftStatus = NahoftSpotStatus.Failed(
                            groupId = currentNahoftGroupId,
                            partNumber = _receivedMessages.size + 1,
                            reason = FailureReason.PARSE_ERROR
                        )
                    )
                    currentSpots.add(0, spot)
                }
            } else {
                val spot = WSPRSpotItem(
                    callsign = result.callsign,
                    gridSquare = result.gridSquare,
                    powerDbm = result.powerLevelDbm,
                    snrDb = result.signalToNoiseRatioDb,
                    timestamp = timestamp,
                    nahoftStatus = NahoftSpotStatus.Spotted
                )
                currentSpots.add(0, spot)
            }
        }

        _receivedSpots.value = currentSpots

        // Attempt decryption if we have at least 2 Nahoft messages
        if (_receivedMessages.size >= 2) {
            attemptDecryption()
        }
    }

    private fun attemptDecryption()
    {
        val friendKeyBytes = _friend.value?.publicKeyEncoded

        if (friendKeyBytes == null) {
            Timber.w("No friend public key available for decryption")
            return
        }

        decryptionAttempts++

        try {
            val sequence = org.operatorfoundation.codex.symbols.WSPRMessageSequence.fromMessages(_receivedMessages.toList())
            val numericValue = sequence.decode()
            val encryptedBytes = bigIntegerToByteArray(numericValue)

            Timber.d("Attempting decryption of ${encryptedBytes.size} bytes")

            val friendPublicKey = org.libsodium.jni.keys.PublicKey(friendKeyBytes)

            // Validate decryption succeeds (throws SecurityException if invalid/incomplete)
            org.nahoft.codex.Encryption().decrypt(friendPublicKey, encryptedBytes)

            Timber.i("Successfully decrypted message from ${_receivedMessages.size} WSPR messages")

            _messageJustReceived.value = true
            markCurrentGroupAsDecrypted(_receivedMessages.size)

            _receivedMessages.clear()
            decryptionAttempts = 0

            viewModelScope.launch {
                _lastReceivedMessage.emit(encryptedBytes)
            }

        }
        catch (e: SecurityException)
        {
            Timber.d("Decryption attempt failed (may need more messages): ${e.message}")
        }
        catch (e: Exception)
        {
            Timber.w(e, "Error during decryption attempt")
        }
    }

    private fun bigIntegerToByteArray(value: java.math.BigInteger): ByteArray
    {
        val bytes = value.toByteArray()
        return if (bytes.isNotEmpty() && bytes[0] == 0.toByte() && bytes.size > 1) {
            bytes.copyOfRange(1, bytes.size)
        } else {
            bytes
        }
    }

    private fun markCurrentGroupAsDecrypted(totalParts: Int)
    {
        val updatedSpots = _receivedSpots.value.map { spot ->
            when (val status = spot.nahoftStatus) {
                is NahoftSpotStatus.Pending -> {
                    if (status.groupId == currentNahoftGroupId) {
                        spot.copy(
                            nahoftStatus = NahoftSpotStatus.Decrypted(
                                groupId = status.groupId,
                                partNumber = status.partNumber,
                                totalParts = totalParts
                            )
                        )
                    } else spot
                }
                else -> spot
            }
        }

        _receivedSpots.value = updatedSpots
        currentNahoftGroupId++
    }

    private fun markCurrentGroupAsFailed(reason: FailureReason)
    {
        val updatedSpots = _receivedSpots.value.map { spot ->
            when (val status = spot.nahoftStatus) {
                is NahoftSpotStatus.Pending -> {
                    if (status.groupId == currentNahoftGroupId) {
                        spot.copy(
                            nahoftStatus = NahoftSpotStatus.Failed(
                                groupId = status.groupId,
                                partNumber = status.partNumber,
                                reason = reason
                            )
                        )
                    } else spot
                }
                else -> spot
            }
        }

        _receivedSpots.value = updatedSpots
        currentNahoftGroupId++
    }

    /**
     * Returns elapsed session time in milliseconds, or 0 if no session.
     */
    fun getSessionElapsedMs(): Long
    {
        return if (sessionStartTimeMs > 0) {
            System.currentTimeMillis() - sessionStartTimeMs
        } else 0L
    }

    /**
     * Returns current decryption attempts count.
     */
    fun getDecryptionAttempts(): Int = decryptionAttempts

    /**
     * Resets the messageJustReceived flag (call after UI has shown celebration).
     */
    fun clearMessageReceivedFlag()
    {
        _messageJustReceived.value = false
    }

    // ==================== Derived State ====================

    /**
     * Whether the send via serial button should be visible.
     * True when: serial connected AND friend is Verified or Approved.
     */
    val canSendViaSerial: Flow<Boolean> = combine(
        serialConnectionState,
        _friend
    ) { connectionState, friend ->
        val isConnected = connectionState is SerialConnectionFactory.ConnectionState.Connected
        val hasValidStatus = friend?.status == FriendStatus.Verified ||
                friend?.status == FriendStatus.Approved
        isConnected && hasValidStatus
    }

    /**
     * Whether the receive via radio button should be visible.
     * True when: audio connected AND friend is Verified or Approved.
     */
    val canReceiveRadio: Flow<Boolean> = combine(
        _usbAudioConnection,
        _friend
    ) { audioConnection, friend ->
        val isConnected = audioConnection != null
        val hasValidStatus = friend?.status == FriendStatus.Verified ||
                friend?.status == FriendStatus.Approved
        isConnected && hasValidStatus
    }

    private suspend fun observeStationState()
    {
        wsprStation?.stationState?.collect { state ->
            _stationState.value = state
        }
    }

    private suspend fun observeCycleInformation()
    {
        wsprStation?.cycleInformation?.collect { info ->
            _cycleInformation.value = info
        }
    }

    private suspend fun observeDecodeResults()
    {
        wsprStation?.decodeResults?.collect { results ->
            if (results.isNotEmpty()) {
                processDecodeResults(results)
            }
        }
    }

    private suspend fun observeAudioLevels(connection: UsbAudioConnection)
    {
        connection.getAudioLevel().collect { levelInfo ->
            _audioLevel.value = levelInfo.currentLevel
        }
    }

    private fun startTimeoutMonitor()
    {
        viewModelScope.launch {
            delay(sessionTimeoutMs)

            if (isSessionActive()) {
                Timber.d("Session timeout reached")
                stopReceiveSession()
            }
        }
    }

    // ==================== Cleanup ====================

    override fun onCleared()
    {
        super.onCleared()

        // Stop any active receive session
        if (isSessionActive()) {
            stopReceiveSession()
        }

        audioDeviceDiscoveryJob?.cancel()
        audioConnectionStatusJob?.cancel()

        viewModelScope.launch {
            try {
                _usbAudioConnection.value?.disconnect()
                usbAudioManager.cleanup()
            } catch (e: Exception) {
                Timber.w(e, "Error cleaning up USB audio")
            }
        }

        serialConnection?.close()
        connectionFactory.disconnect()
    }
}

/**
 * Represents the current state of a WSPR receive session.
 */
sealed class ReceiveSessionState
{
    /** No active session */
    object Idle : ReceiveSessionState()

    /** Session started, waiting for a fresh WSPR window to begin collection */
    object WaitingForWindow : ReceiveSessionState()

    /** Actively collecting and decoding WSPR signals */
    object Running : ReceiveSessionState()

    /** Session stopped (manually or by timeout) */
    object Stopped : ReceiveSessionState()
}