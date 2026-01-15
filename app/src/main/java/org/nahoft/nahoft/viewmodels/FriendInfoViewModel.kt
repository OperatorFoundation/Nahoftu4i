package org.nahoft.nahoft.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hoho.android.usbserial.driver.UsbSerialDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.nahoft.nahoft.models.Friend
import org.nahoft.nahoft.models.FriendStatus
import org.operatorfoundation.signalbridge.UsbAudioConnection
import org.operatorfoundation.signalbridge.UsbAudioManager
import org.operatorfoundation.signalbridge.models.ConnectionStatus
import org.operatorfoundation.signalbridge.models.UsbAudioDevice
import org.operatorfoundation.transmission.SerialConnection
import org.operatorfoundation.transmission.SerialConnectionFactory
import timber.log.Timber

class FriendInfoViewModel(application: Application) : AndroidViewModel(application)
{
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

    /**
     * Updates the friend's status locally.
     * Caller is responsible for persisting via Persist.updateFriend().
     */
    fun updateFriendStatus(newStatus: FriendStatus)
    {
        _friend.update { currentFriend ->
            currentFriend?.copy(status = newStatus)
        }
    }

    /**
     * Updates the friend's public key locally.
     * Caller is responsible for persisting via Persist.updateFriend().
     */
    fun updateFriendPublicKey(encodedKey: ByteArray?)
    {
        _friend.update { currentFriend ->
            currentFriend?.copy(publicKeyEncoded = encodedKey)
        }
    }

    /**
     * Updates the friend's name locally.
     * Caller is responsible for persisting via Persist.updateFriend().
     */
    fun updateFriendName(newName: String)
    {
        _friend.update { currentFriend ->
            currentFriend?.copy(name = newName)
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
                kotlinx.coroutines.delay(500) // Let USB stabilize
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

    // ==================== Cleanup ====================

    override fun onCleared()
    {
        super.onCleared()

        audioDeviceDiscoveryJob?.cancel()
        audioConnectionStatusJob?.cancel()

        viewModelScope.launch {
            try
            {
                _usbAudioConnection.value?.disconnect()
                usbAudioManager.cleanup()
            }
            catch (e: Exception)
            {
                Timber.w(e, "Error cleaning up USB audio")
            }
        }

        serialConnection?.close()
        connectionFactory.disconnect()
    }
}