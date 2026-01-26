package org.nahoft.nahoft.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.libsodium.jni.keys.PublicKey
import org.nahoft.codex.Encryption
import org.nahoft.nahoft.R
import org.nahoft.nahoft.activities.FriendInfoActivity
import org.nahoft.nahoft.models.*
import org.nahoft.nahoft.Persist
import org.operatorfoundation.audiocoder.WSPRStation
import org.operatorfoundation.audiocoder.WSPRTimingCoordinator
import org.operatorfoundation.audiocoder.models.WSPRCycleInformation
import org.operatorfoundation.audiocoder.models.WSPRDecodeResult
import org.operatorfoundation.audiocoder.models.WSPRStationConfiguration
import org.operatorfoundation.audiocoder.models.WSPRStationState
import org.operatorfoundation.codex.symbols.WSPRMessage
import org.operatorfoundation.signalbridge.SignalBridgeWSPRAudioSource
import org.operatorfoundation.signalbridge.UsbAudioConnection
import org.operatorfoundation.signalbridge.UsbAudioManager
import org.operatorfoundation.signalbridge.models.AudioBufferConfiguration
import org.operatorfoundation.signalbridge.models.ConnectionStatus
import timber.log.Timber
import java.math.BigInteger

/**
 * Foreground service managing radio receive sessions.
 *
 * Survives Activity lifecycle, maintains USB audio connection and WSPR decoding
 * across configuration changes and backgrounding. Shows persistent notification
 * with session progress.
 *
 * Usage:
 * - Start with ACTION_START_SESSION and friend extras
 * - Bind to observe StateFlows
 * - Stop with ACTION_STOP_SESSION or stopSelf()
 */
class ReceiveSessionService : Service()
{

    companion object
    {
        // Intent actions
        const val ACTION_START_SESSION = "org.nahoft.nahoft.action.START_SESSION"
        const val ACTION_STOP_SESSION = "org.nahoft.nahoft.action.STOP_SESSION"

        // Intent extras
        const val EXTRA_FRIEND_ID = "friend_id"
        const val EXTRA_FRIEND_NAME = "friend_name"
        const val EXTRA_FRIEND_PUBLIC_KEY = "friend_public_key"

        // Notification
        private const val NOTIFICATION_CHANNEL_ID = "nahoft_receive_session"
        private const val NOTIFICATION_ID = 1001

        // Timing
        private const val SESSION_TIMEOUT_MS = 20 * 60 * 1000L  // 20 minutes
        private const val NOTIFICATION_UPDATE_INTERVAL_MS = 1000L

        /**
         * Creates an Intent to start a receive session.
         */
        fun createStartIntent(
            context: Context,
            friendId: String,
            friendName: String,
            friendPublicKey: ByteArray
        ): Intent
        {
            return Intent(context, ReceiveSessionService::class.java).apply {
                action = ACTION_START_SESSION
                putExtra(EXTRA_FRIEND_ID, friendId)
                putExtra(EXTRA_FRIEND_NAME, friendName)
                putExtra(EXTRA_FRIEND_PUBLIC_KEY, friendPublicKey)
            }
        }

        /**
         * Creates an Intent to stop the current session.
         */
        fun createStopIntent(context: Context): Intent
        {
            return Intent(context, ReceiveSessionService::class.java).apply {
                action = ACTION_STOP_SESSION
            }
        }
    }

    // ==================== Binder ====================

    inner class LocalBinder : Binder()
    {
        fun getService(): ReceiveSessionService = this@ReceiveSessionService
    }

    private val binder = LocalBinder()

    // ==================== Session State (Observable) ====================

    private val _receiveSessionState = MutableStateFlow<ReceiveSessionState>(ReceiveSessionState.Idle)
    val receiveSessionState: StateFlow<ReceiveSessionState> = _receiveSessionState.asStateFlow()

    private val _receivedSpots = MutableStateFlow<List<WSPRSpotItem>>(emptyList())
    val receivedSpots: StateFlow<List<WSPRSpotItem>> = _receivedSpots.asStateFlow()

    private val _stationState = MutableStateFlow<WSPRStationState?>(null)
    val stationState: StateFlow<WSPRStationState?> = _stationState.asStateFlow()

    private val _cycleInformation = MutableStateFlow<WSPRCycleInformation?>(null)
    val cycleInformation: StateFlow<WSPRCycleInformation?> = _cycleInformation.asStateFlow()

    private val _audioLevel = MutableStateFlow(0f)
    val audioLevel: StateFlow<Float> = _audioLevel.asStateFlow()

    private val _messageJustReceived = MutableStateFlow(false)
    val messageJustReceived: StateFlow<Boolean> = _messageJustReceived.asStateFlow()

    private val _lastReceivedMessage = MutableSharedFlow<ByteArray>(replay = 0)
    val lastReceivedMessage: SharedFlow<ByteArray> = _lastReceivedMessage.asSharedFlow()

    // Current friend info (for UI display)
    private val _currentFriendName = MutableStateFlow<String?>(null)
    val currentFriendName: StateFlow<String?> = _currentFriendName.asStateFlow()

    private val _currentFriendId = MutableStateFlow<String?>(null)
    val currentFriendId: StateFlow<String?> = _currentFriendId.asStateFlow()

    // ==================== Internal State ====================

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // WSPR components
    private var wsprStation: WSPRStation? = null
    private var audioSource: SignalBridgeWSPRAudioSource? = null
    private val timingCoordinator = WSPRTimingCoordinator()

    // USB Audio
    private lateinit var usbAudioManager: UsbAudioManager
    private var usbAudioConnection: UsbAudioConnection? = null

    // Friend context for decryption
    private var friendId: String? = null
    private var friendName: String? = null
    private var friendPublicKey: ByteArray? = null

    // Message accumulation
    private val receivedMessages = mutableListOf<WSPRMessage>()
    val receivedMessageCount: Int get() = receivedMessages.size
    private var currentNahoftGroupId = 0
    private var decryptionAttempts = 0

    // Timing
    private var sessionStartTimeMs = 0L
    private var sessionJob: Job? = null
    private var waitForWindowJob: Job? = null
    private var timeoutJob: Job? = null
    private var notificationUpdateJob: Job? = null

    // Wake lock
    private var wakeLock: PowerManager.WakeLock? = null

    // Notification
    private lateinit var notificationManager: NotificationManager

    // ==================== Service Lifecycle ====================

    override fun onCreate()
    {
        super.onCreate()
        Timber.d("ReceiveSessionService created")

        usbAudioManager = UsbAudioManager.create(applicationContext)
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        createNotificationChannel()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int
    {
        Timber.d("onStartCommand: action=${intent?.action}")

        when (intent?.action) {
            ACTION_START_SESSION -> {
                val id = intent.getStringExtra(EXTRA_FRIEND_ID)
                val name = intent.getStringExtra(EXTRA_FRIEND_NAME)
                val key = intent.getByteArrayExtra(EXTRA_FRIEND_PUBLIC_KEY)

                if (id != null && name != null && key != null) {
                    startSession(id, name, key)
                } else {
                    Timber.e("Missing required extras for START_SESSION")
                    stopSelf()
                }
            }
            ACTION_STOP_SESSION -> {
                stopSession()
                stopSelf()
            }
            else -> {
                Timber.w("Unknown action: ${intent?.action}")
            }
        }

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder
    {
        Timber.d("Service bound")
        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean
    {
        Timber.d("Service unbound")
        // Return true to receive onRebind() calls
        return true
    }

    override fun onRebind(intent: Intent?)
    {
        Timber.d("Service rebound")
    }

    override fun onDestroy()
    {
        Timber.d("ReceiveSessionService destroyed")

        stopSession()
        releaseWakeLock()
        serviceScope.cancel()

        super.onDestroy()
    }

    // ==================== Session Management ====================

    private fun startSession(id: String, name: String, key: ByteArray)
    {
        // Check if session already active
        if (isSessionActive())
        {
            if (friendId == id)
            {
                Timber.d("Session already active for this friend")
                return
            }
            else
            {
                Timber.w("Session active for different friend (${friendName}), ignoring")
                return
            }
        }

        Timber.d("Starting receive session for friend: $name")

        // Store friend context
        friendId = id
        friendName = name
        friendPublicKey = key
        _currentFriendId.value = id
        _currentFriendName.value = name

        // Reset session state
        _receivedSpots.value = emptyList()
        receivedMessages.clear()
        _messageJustReceived.value = false
        decryptionAttempts = 0
        currentNahoftGroupId = 0
        sessionStartTimeMs = System.currentTimeMillis()

        // Start foreground with notification
        startForegroundWithNotification()

        // Connect to USB audio and start WSPR station
        serviceScope.launch {
            val connected = connectToUsbAudio()
            if (!connected)
            {
                Timber.e("Failed to connect to USB audio")
                _receiveSessionState.value = ReceiveSessionState.Stopped
                stopSelf()
                return@launch
            }

            val connection = usbAudioConnection
            if (connection == null)
            {
                Timber.e("USB audio connection is null after connect")
                _receiveSessionState.value = ReceiveSessionState.Stopped
                stopSelf()
                return@launch
            }

            // Check if early enough in cycle to start immediately
            if (timingCoordinator.isEarlyEnoughToStartCollection())
            {
                startWSPRStation(connection)
            }
            else
            {
                _receiveSessionState.value = ReceiveSessionState.WaitingForWindow
                waitForNextWindowAndStart(connection)
            }

            // Start timeout monitor
            startTimeoutMonitor()

            // Start notification updates
            startNotificationUpdates()
        }
    }

    fun stopSession()
    {
        Timber.d("Stopping receive session")

        // Mark any pending spots as incomplete
        if (receivedMessages.isNotEmpty()) {
            markCurrentGroupAsFailed(FailureReason.INCOMPLETE)
        }

        // Cancel jobs
        waitForWindowJob?.cancel()
        sessionJob?.cancel()
        timeoutJob?.cancel()
        notificationUpdateJob?.cancel()

        // Cleanup WSPR components
        serviceScope.launch {
            try
            {
                wsprStation?.stopStation()
                audioSource?.cleanup()
                usbAudioConnection?.disconnect()
            }
            catch (e: Exception)
            {
                Timber.w(e, "Error during session cleanup")
            }

            wsprStation = null
            audioSource = null
            usbAudioConnection = null
        }

        // Reset state
        _receiveSessionState.value = ReceiveSessionState.Stopped
        friendId = null
        friendName = null
        friendPublicKey = null
        _currentFriendId.value = null
        _currentFriendName.value = null
    }

    fun resetSession()
    {
        _receiveSessionState.value = ReceiveSessionState.Idle
        _receivedSpots.value = emptyList()
        receivedMessages.clear()
        _messageJustReceived.value = false
        decryptionAttempts = 0
        currentNahoftGroupId = 0
        sessionStartTimeMs = 0L
    }

    fun isSessionActive(): Boolean
    {
        return _receiveSessionState.value == ReceiveSessionState.Running ||
                _receiveSessionState.value == ReceiveSessionState.WaitingForWindow
    }

    fun getSessionElapsedMs(): Long
    {
        return if (sessionStartTimeMs > 0)
        {
            System.currentTimeMillis() - sessionStartTimeMs
        }
        else 0L
    }

    fun getDecryptionAttempts(): Int = decryptionAttempts

    fun clearMessageReceivedFlag()
    {
        _messageJustReceived.value = false
    }

    // ==================== USB Audio ====================

    private suspend fun connectToUsbAudio(): Boolean
    {
        return withContext(Dispatchers.IO) {
            try
            {
                // Discover devices
                val devices = usbAudioManager.discoverDevices().first()
                if (devices.isEmpty())
                {
                    Timber.w("No USB audio devices found")
                    return@withContext false
                }

                // Connect to first device
                val result = usbAudioManager.connectToDevice(devices.first())
                if (result.isSuccess)
                {
                    usbAudioConnection = result.getOrNull()
                    Timber.i("USB Audio connected: ${devices.first().displayName}")
                    true
                }
                else
                {
                    Timber.e(result.exceptionOrNull(), "Failed to connect to USB audio")
                    false
                }
            }
            catch (e: Exception)
            {
                Timber.e(e, "Error connecting to USB audio")
                false
            }
        }
    }

    // ==================== WSPR Station ====================

    private fun waitForNextWindowAndStart(connection: UsbAudioConnection)
    {
        waitForWindowJob = serviceScope.launch {
            while (isActive && _receiveSessionState.value == ReceiveSessionState.WaitingForWindow) {
                val windowInfo = timingCoordinator.getTimeUntilNextDecodeWindow()

                if (windowInfo.secondsUntilWindow <= 0)
                {
                    startWSPRStation(connection)
                    return@launch
                }

                _cycleInformation.value = timingCoordinator.getCurrentCycleInformation()
                delay(1000)
            }
        }
    }

    private fun startWSPRStation(connection: UsbAudioConnection)
    {
        sessionJob = serviceScope.launch {
            try
            {
                audioSource = SignalBridgeWSPRAudioSource(
                    usbAudioConnection = connection,
                    bufferConfiguration = AudioBufferConfiguration.createDefault()
                )

                val config = WSPRStationConfiguration.createDefault()
                wsprStation = WSPRStation(audioSource!!, config)

                Timber.d("Starting WSPR station")
                val startResult = wsprStation!!.startStation()

                if (startResult.isFailure)
                {
                    Timber.e("Failed to start WSPR station: ${startResult.exceptionOrNull()?.message}")
                    _receiveSessionState.value = ReceiveSessionState.Stopped
                    return@launch
                }

                _receiveSessionState.value = ReceiveSessionState.Running

                // Observe station flows
                launch { observeStationState() }
                launch { observeCycleInformation() }
                launch { observeDecodeResults() }
                launch { observeAudioLevels(connection) }

            }
            catch (e: Exception)
            {
                Timber.e(e, "Error starting WSPR station")
                _receiveSessionState.value = ReceiveSessionState.Stopped
            }
        }
    }

    private suspend fun observeStationState()
    {
        wsprStation?.stationState?.collect { state ->
            _stationState.value = state
            updateNotification()
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
                updateNotification()
            }
        }
    }

    private suspend fun observeAudioLevels(connection: UsbAudioConnection)
    {
        connection.getAudioLevel().collect { levelInfo ->
            _audioLevel.value = levelInfo.currentLevel
        }
    }

    // ==================== Decode Processing ====================

    private fun processDecodeResults(results: List<WSPRDecodeResult>)
    {
        val currentSpots = _receivedSpots.value.toMutableList()

        for (result in results)
        {
            val isNahoftMessage = WSPRMessage.isEncodedMessage(result.callsign)
            val timestamp = System.currentTimeMillis()

            if (isNahoftMessage)
            {
                try
                {
                    val message = WSPRMessage.fromWSPRFields(
                        result.callsign,
                        result.gridSquare,
                        result.powerLevelDbm
                    )

                    val isDuplicate = receivedMessages.any { existing ->
                        existing.toWSPRFields() == message.toWSPRFields()
                    }

                    if (!isDuplicate)
                    {
                        receivedMessages.add(message)
                        Timber.d("Added new WSPR message: ${message.toWSPRFields()}")
                    }

                    val partNumber = receivedMessages.size

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

                }
                catch (e: Exception)
                {
                    Timber.w(e, "Failed to parse WSPR message: ${result.callsign}")

                    val spot = WSPRSpotItem(
                        callsign = result.callsign,
                        gridSquare = result.gridSquare,
                        powerDbm = result.powerLevelDbm,
                        snrDb = result.signalToNoiseRatioDb,
                        timestamp = timestamp,
                        nahoftStatus = NahoftSpotStatus.Failed(
                            groupId = currentNahoftGroupId,
                            partNumber = receivedMessages.size + 1,
                            reason = FailureReason.PARSE_ERROR
                        )
                    )
                    currentSpots.add(0, spot)
                }
            }
            else
            {
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
        if (receivedMessages.size >= 2) attemptDecryption()
    }

    private fun attemptDecryption()
    {
        val keyBytes = friendPublicKey
        if (keyBytes == null)
        {
            Timber.w("No friend public key available for decryption")
            return
        }

        decryptionAttempts++

        try
        {
            val sequence = org.operatorfoundation.codex.symbols.WSPRMessageSequence.fromMessages(
                receivedMessages.toList()
            )
            val numericValue = sequence.decode()
            val encryptedBytes = bigIntegerToByteArray(numericValue)

            Timber.d("Attempting decryption of ${encryptedBytes.size} bytes")

            val friendPubKey = PublicKey(keyBytes)

            // Validate decryption succeeds
            Encryption().decrypt(friendPubKey, encryptedBytes)

            Timber.i("Successfully decrypted message from ${receivedMessages.size} WSPR messages")

            _messageJustReceived.value = true
            markCurrentGroupAsDecrypted(receivedMessages.size)

            // Save message directly
            saveReceivedMessage(encryptedBytes)

            receivedMessages.clear()
            decryptionAttempts = 0

            serviceScope.launch {
                _lastReceivedMessage.emit(encryptedBytes)
            }

            updateNotification()

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

    private fun saveReceivedMessage(encryptedBytes: ByteArray)
    {
        val id = friendId ?: return
        val name = friendName ?: return
        val key = friendPublicKey ?: return

        // Reconstruct Friend object for Message
        val friend = Persist.friendList.find { it.name == name }
        if (friend == null)
        {
            Timber.e("Could not find friend in Persist.friendList: $name")
            return
        }

        val message = Message(encryptedBytes, friend, false)
        message.save(applicationContext)

        Timber.i("Saved received message for friend: $name")
    }

    private fun bigIntegerToByteArray(value: BigInteger): ByteArray
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

            when (val status = spot.nahoftStatus)
            {
                is NahoftSpotStatus.Pending -> {
                    if (status.groupId == currentNahoftGroupId)
                    {
                        spot.copy(
                            nahoftStatus = NahoftSpotStatus.Failed(
                                groupId = status.groupId,
                                partNumber = status.partNumber,
                                reason = reason
                            )
                        )
                    }
                    else spot
                }
                else -> spot
            }
        }

        _receivedSpots.value = updatedSpots
        currentNahoftGroupId++
    }

    // ==================== Timeout ====================

    private fun startTimeoutMonitor()
    {
        timeoutJob = serviceScope.launch {
            delay(SESSION_TIMEOUT_MS)

            if (isSessionActive())
            {
                Timber.d("Session timeout reached")
                stopSession()
                stopSelf()
            }
        }
    }

    // ==================== Notification ====================

    private fun createNotificationChannel()
    {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
        {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "WSPR Receive Session",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows progress of WSPR radio message reception"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun startForegroundWithNotification()
    {
        val notification = buildNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        }
        else startForeground(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(): Notification
    {
        // Intent to open FriendInfoActivity when notification tapped
        val contentIntent = Intent(this, FriendInfoActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Stop action
        val stopIntent = createStopIntent(this)
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val elapsedMs = getSessionElapsedMs()
        val minutes = (elapsedMs / 60000).toInt()
        val seconds = ((elapsedMs % 60000) / 1000).toInt()
        val elapsedText = String.format("%d:%02d", minutes, seconds)

        val spots = _receivedSpots.value.size
        val parts = receivedMessages.size

        val statusText = when (_receiveSessionState.value) {
            is ReceiveSessionState.Running -> "Listening for signals"
            is ReceiveSessionState.WaitingForWindow -> "Waiting for WSPR window"
            else -> "Session active"
        }

        val contentText = "$statusText • $spots spots • $parts parts • $elapsedText"

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_radio)
            .setContentTitle("Receiving from ${friendName ?: "friend"}")
            .setContentText(contentText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.btn_close_small_24, "Stop", stopPendingIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification()
    {
        if (isSessionActive())
        {
            notificationManager.notify(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun startNotificationUpdates()
    {
        notificationUpdateJob = serviceScope.launch {
            while (isActive && isSessionActive())
            {
                updateNotification()
                delay(NOTIFICATION_UPDATE_INTERVAL_MS)
            }
        }
    }

    // ==================== Wake Lock ====================

    private fun acquireWakeLock()
    {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "Nahoft:ReceiveSessionWakeLock"
        ).apply {
            acquire(SESSION_TIMEOUT_MS + 60_000) // Timeout + buffer
        }

        Timber.d("Wake lock acquired")
    }

    private fun releaseWakeLock()
    {
        wakeLock?.let {
            if (it.isHeld)
            {
                it.release()
                Timber.d("Wake lock released")
            }
        }
        wakeLock = null
    }
}

/**
 * Represents the current state of a WSPR receive session.
 */
sealed class ReceiveSessionState
{
    /** No active session */
    object Idle : ReceiveSessionState()

    /** Session started, waiting for a fresh WSPR window */
    object WaitingForWindow : ReceiveSessionState()

    /** Actively collecting and decoding WSPR signals */
    object Running : ReceiveSessionState()

    /** Session stopped (manually or by timeout) */
    object Stopped : ReceiveSessionState()
}