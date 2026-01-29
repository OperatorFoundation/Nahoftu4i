package org.nahoft.nahoft.viewmodels

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hoho.android.usbserial.driver.UsbSerialDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.nahoft.nahoft.models.Friend
import org.nahoft.nahoft.models.FriendStatus
import org.nahoft.nahoft.models.WSPRSpotItem
import org.nahoft.nahoft.services.ReceiveSessionService
import org.nahoft.nahoft.services.ReceiveSessionState
import org.operatorfoundation.audiocoder.models.WSPRCycleInformation
import org.operatorfoundation.audiocoder.models.WSPRStationState
import org.operatorfoundation.signalbridge.UsbAudioDeviceMonitor
import org.operatorfoundation.transmission.SerialConnection
import org.operatorfoundation.transmission.SerialConnectionFactory
import timber.log.Timber

class FriendInfoViewModel(application: Application) : AndroidViewModel(application)
{
    // ==================== Service Binding ====================

    private var receiveService: ReceiveSessionService? = null
    private var serviceBound = false

    private val _serviceConnected = MutableStateFlow(false)
    val serviceConnected: StateFlow<Boolean> = _serviceConnected.asStateFlow()

    private val serviceConnection = object : ServiceConnection
    {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?)
        {
            Timber.d("ReceiveSessionService connected")
            val localBinder = binder as ReceiveSessionService.LocalBinder
            receiveService = localBinder.getService()
            serviceBound = true
            _serviceConnected.value = true

            startServiceFlowRelay()
        }

        override fun onServiceDisconnected(name: ComponentName?)
        {
            Timber.d("ReceiveSessionService disconnected")
            receiveService = null
            serviceBound = false
            _serviceConnected.value = false
        }
    }

    // ==================== Receive Session State (Relayed from Service) ====================

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

    val receivedMessageCount: Int
        get() = receiveService?.receivedMessageCount ?: 0

    private var flowRelayJob: Job? = null

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

    // ==================== USB Audio Connection (for UI visibility) ====================

    private val _usbAudioAvailable = MutableStateFlow(false)
    val usbAudioAvailable: StateFlow<Boolean> = _usbAudioAvailable.asStateFlow()

    /**
     * Starts monitoring USB audio input device availability.
     * Uses lightweight monitor - does not create connections.
     */
    fun startAudioDeviceDiscovery()
    {
        viewModelScope.launch {
            UsbAudioDeviceMonitor.observeAvailability(
                context = getApplication(),
                requireInput = true  // WSPR receive needs input capability
            ).collect { available ->
                Timber.d("USB audio input available: $available")
                _usbAudioAvailable.value = available
            }
        }
    }

    // ==================== Service Lifecycle ====================

    /**
     * Binds to ReceiveSessionService if it's running.
     * Call from Activity.onStart().
     */
    fun bindToServiceIfRunning()
    {
        val context = getApplication<Application>()
        val intent = Intent(context, ReceiveSessionService::class.java)

        // bindService returns false if service isn't running
        val bound = context.bindService(intent, serviceConnection, 0)
        Timber.d("Attempted to bind to service: $bound")
    }

    /**
     * Unbinds from ReceiveSessionService.
     * Call from Activity.onStop().
     */
    fun unbindFromService()
    {
        if (serviceBound)
        {
            flowRelayJob?.cancel()
            flowRelayJob = null

            try
            {
                getApplication<Application>().unbindService(serviceConnection)
            }
            catch (e: Exception)
            {
                Timber.w(e, "Error unbinding from service")
            }

            serviceBound = false
            _serviceConnected.value = false
            receiveService = null
        }
    }

    // ==================== Receive Session Control ====================

    /**
     * Starts a receive session via the foreground service.
     */
    fun startReceiveSession()
    {
        val currentFriend = _friend.value
        if (currentFriend == null)
        {
            Timber.w("Cannot start session: no friend initialized")
            return
        }

        val publicKey = currentFriend.publicKeyEncoded
        if (publicKey == null)
        {
            Timber.w("Cannot start session: friend has no public key")
            return
        }

        if (!_usbAudioAvailable.value)
        {
            Timber.w("Cannot start session: no USB audio available")
            return
        }

        // Check if service already has an active session
        receiveService?.let { service ->
            if (service.isSessionActive())
            {
                val activeId = service.currentFriendName.value
                if (activeId == currentFriend.name)
                {
                    Timber.d("Session already active for this friend")
                    return
                }
                else
                {
                    Timber.w("Session active for different friend ($activeId)")
                    return
                }
            }
        }

        val context = getApplication<Application>()

        // Start the foreground service
        val startIntent = ReceiveSessionService.createStartIntent(
            context = context,
            friendName = currentFriend.name,
            friendPublicKey = publicKey
        )

        context.startForegroundService(startIntent)

        // Bind to observe state
        viewModelScope.launch {
            delay(100) // Brief delay for service to start
            val intent = Intent(context, ReceiveSessionService::class.java)
            context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    /**
     * Stops the current receive session.
     */
    fun stopReceiveSession()
    {
        receiveService?.stopSession()

        val context = getApplication<Application>()
        val stopIntent = ReceiveSessionService.createStopIntent(context)
        context.startService(stopIntent)
    }

    /**
     * Resets session state after stopping.
     */
    fun resetSession()
    {
        receiveService?.resetSession()

        // Also reset local state
        _receiveSessionState.value = ReceiveSessionState.Idle
        _receivedSpots.value = emptyList()
        _messageJustReceived.value = false
    }

    /**
     * Returns whether a receive session is currently active.
     */
    fun isSessionActive(): Boolean
    {
        return receiveService?.isSessionActive() ?: false
    }

    /**
     * Returns elapsed session time in milliseconds, or 0 if no session.
     */
    fun getSessionElapsedMs(): Long
    {
        return receiveService?.getSessionElapsedMs() ?: 0L
    }

    /**
     * Returns current decryption attempts count.
     */
    fun getDecryptionAttempts(): Int
    {
        return receiveService?.getDecryptionAttempts() ?: 0
    }

    /**
     * Resets the messageJustReceived flag (call after UI has shown celebration).
     */
    fun clearMessageReceivedFlag()
    {
        receiveService?.clearMessageReceivedFlag()
        _messageJustReceived.value = false
    }

    // ==================== Flow Relay ====================

    /**
     * Starts collecting from service StateFlows and relaying to ViewModel flows.
     */
    private fun startServiceFlowRelay()
    {
        flowRelayJob?.cancel()

        val service = receiveService ?: return

        flowRelayJob = viewModelScope.launch {
            launch {
                service.receiveSessionState.collect { _receiveSessionState.value = it }
            }
            launch {
                service.receivedSpots.collect { _receivedSpots.value = it }
            }
            launch {
                service.stationState.collect { _stationState.value = it }
            }
            launch {
                service.cycleInformation.collect { _cycleInformation.value = it }
            }
            launch {
                service.audioLevel.collect { _audioLevel.value = it }
            }
            launch {
                service.messageJustReceived.collect { _messageJustReceived.value = it }
            }
            launch {
                service.lastReceivedMessage.collect { _lastReceivedMessage.emit(it) }
            }
        }
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
    val canReceiveRadio: Flow<Boolean> = combine(_usbAudioAvailable, _friend)
    { audioAvailable, friend ->

        val hasValidStatus =
            friend?.status == FriendStatus.Verified || friend?.status == FriendStatus.Approved

        audioAvailable && hasValidStatus
    }

    // ==================== Cleanup ====================

    override fun onCleared()
    {
        super.onCleared()

        // Don't stop the service - it should persist independently
        // Just unbind
        unbindFromService()

        serialConnection?.close()
        connectionFactory.disconnect()
    }
}