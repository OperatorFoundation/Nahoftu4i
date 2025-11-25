package org.nahoft.nahoft.activities

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Bundle
import android.os.Parcelable
import android.provider.MediaStore
import android.text.Layout
import android.text.SpannableString
import android.text.style.AlignmentSpan
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.view.setPadding
import kotlinx.coroutines.*
import org.libsodium.jni.keys.PublicKey
import org.nahoft.codex.Codex
import org.nahoft.codex.Encryption
import org.nahoft.codex.KeyOrMessage
import org.nahoft.nahoft.*
import org.nahoft.nahoft.databinding.ActivityFriendInfoBinding
import org.nahoft.nahoft.fragments.*
import org.nahoft.org.nahoft.swatch.Decoder
import org.nahoft.org.nahoft.swatch.Encoder
import org.nahoft.util.RequestCodes
import org.nahoft.util.ShareUtil
import org.nahoft.util.showAlert

import org.operatorfoundation.transmission.SerialConnectionFactory
import org.operatorfoundation.transmission.SerialConnection
import com.hoho.android.usbserial.driver.UsbSerialDriver
import org.nahoft.Nahoft.fragments.ReceiveRadioBottomSheetFragment
import org.operatorfoundation.audiocoder.WSPREncoder
import org.operatorfoundation.codex.symbols.WSPRMessageSequence
import org.operatorfoundation.ion.storage.NounType
import org.operatorfoundation.ion.storage.Word
import org.operatorfoundation.signalbridge.UsbAudioConnection
import org.operatorfoundation.signalbridge.UsbAudioManager
import org.operatorfoundation.signalbridge.models.ConnectionStatus
import org.operatorfoundation.signalbridge.models.UsbAudioDevice

import timber.log.Timber
import java.math.BigInteger

class FriendInfoActivity: AppCompatActivity()
{
    private lateinit var binding: ActivityFriendInfoBinding
    private var decodePayload: ByteArray? = null
    private lateinit var thisFriend: Friend

    private val parentJob = Job()
    private val coroutineScope = CoroutineScope(Dispatchers.Main + parentJob)
    private val TAG = "FriendInfoActivity"
    private val menuFragmentTag = "MenuFragment"

    private val usbReceiver = object : BroadcastReceiver()
    {
        override fun onReceive(context: Context, intent: Intent)
        {
            when (intent.action)
            {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    Timber.d("USB device attached")
                    // Only check if not already connected or connecting
                    if (serialConnection == null && !isConnecting)
                    {
                        // Delay slightly to let USB stabilize
                        coroutineScope.launch {
                            delay(500)
                            checkForSerialDevices()
                        }
                    }
                }

                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    Timber.d("USB device detached")
                    if (serialConnection != null)
                    {
                        coroutineScope.launch {
                            handleDeviceDisconnected()
                        }
                    }

                    isConnecting = false  // Clear flag on disconnect
                }
            }
        }
    }

    private var isShareImageButtonShow: Boolean = false

    // Serial connection properties
    private var connectionStateJob: Job? = null
    private lateinit var connectionFactory: SerialConnectionFactory
    private var serialConnection: SerialConnection? = null
    private var isConnecting = false

    // USB Audio connection properties (for WSPR receive)
    private lateinit var usbAudioManager: UsbAudioManager
    private var usbAudioConnection: UsbAudioConnection? = null
    private var audioDeviceDiscoveryJob: Job? = null
    private var audioConnectionStatusJob: Job? = null



    override fun onCreate(savedInstanceState: Bundle?)
    {
        super.onCreate(savedInstanceState)

        binding = ActivityFriendInfoBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE,
                        WindowManager.LayoutParams.FLAG_SECURE)

        if (!Persist.accessIsAllowed()) { sendToLogin() }

        // Get our pending friend
        val maybeFriend = intent.getSerializableExtra(RequestCodes.friendExtraTaskDescription) as? Friend

        if (maybeFriend == null)
        { // this should never happen, get out of this activity.
            Log.e(TAG, "Attempted to open FriendInfoActivity, but Friend was null.")
            return
        }
        else
        {
            thisFriend = maybeFriend
        }

        setClickListeners()
        setupViewByStatus()
        receivedSharedMessage()

        // Register USB receiver
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        registerReceiver(usbReceiver, filter)

        setupSerialConnection()
        setupUsbAudioConnection()
    }

    override fun onResume() {
        super.onResume()

        setupViewByStatus()
    }

    private fun setupSerialConnection()
    {
        connectionFactory = SerialConnectionFactory(this)
        observeConnectionState()
        checkForSerialDevices()
    }

    /**
     * Sets up USB audio device discovery and connection management.
     * Mirrors the pattern used for serial connections.
     */
    private fun setupUsbAudioConnection()
    {
        usbAudioManager = UsbAudioManager.create(this)
        observeAudioConnectionStatus()
        startAudioDeviceDiscovery()
    }

    /**
     * Observes USB audio connection status changes.
     */
    private fun observeAudioConnectionStatus()
    {
        audioConnectionStatusJob = coroutineScope.launch {
            usbAudioManager.getConnectionStatus().collect { status ->

                Timber.d("USB Audio connection status: $status")

                when (status)
                {
                    is ConnectionStatus.Connected -> {
                        Timber.i("USB Audio connected")
                        updateReceiveButtonVisibility()
                    }

                    is ConnectionStatus.Disconnected -> {
                        Timber.d("USB Audio disconnected")
                        usbAudioConnection = null
                        updateReceiveButtonVisibility()
                    }

                    is ConnectionStatus.Connecting -> {
                        Timber.d("USB Audio connecting...")
                    }

                    is ConnectionStatus.Error -> {
                        Timber.e("USB Audio error: ${status.message}")
                        usbAudioConnection = null
                        updateReceiveButtonVisibility()
                    }
                }
            }
        }
    }

    /**
     * Starts discovery of USB audio devices.
     * When a device is found and we're not connected, auto-connects.
     */
    private fun startAudioDeviceDiscovery()
    {
        audioDeviceDiscoveryJob = coroutineScope.launch {
            usbAudioManager.discoverDevices().collect { devices ->

                Timber.d("Discovered ${devices.size} USB audio device(s)")

                // Auto-connect to first device if not already connected
                if (devices.isNotEmpty() && usbAudioConnection == null) connectToAudioDevice(devices.first())
            }
        }
    }

    /**
     * Connects to a USB audio device.
     */
    private fun connectToAudioDevice(device: UsbAudioDevice)
    {
        coroutineScope.launch {
            Timber.d("Attempting to connect to USB audio device: ${device.displayName}")

            val result = usbAudioManager.connectToDevice(device)

            if (result.isSuccess)
            {
                usbAudioConnection = result.getOrNull()
                Timber.i("USB Audio connected: ${device.displayName}")
                updateReceiveButtonVisibility()
            }
            else Timber.e(result.exceptionOrNull(), "Failed to connect to USB audio device")
        }
    }

    /**
     * Updates visibility of the receive radio button based on connection and friend status.
     */
    private fun updateReceiveButtonVisibility()
    {
        val shouldShow = usbAudioConnection != null &&
                (thisFriend.status == FriendStatus.Verified || thisFriend.status == FriendStatus.Approved)

        binding.btnReceiveRadio.visibility = if (shouldShow) View.VISIBLE else View.GONE
    }

    /**
     * Handles the receive via radio button click.
     * Opens the ReceiveRadioBottomSheetFragment.
     */
    private fun receiveViaRadioClicked()
    {
        val connection = usbAudioConnection
        if (connection == null) {
            showAlert(getString(R.string.usb_audio_not_connected))
            return
        }

        if (thisFriend.publicKeyEncoded == null) {
            showAlert(getString(R.string.alert_text_verified_friends_only))
            return
        }

        val bottomSheet = ReceiveRadioBottomSheetFragment.newInstance(
            connection = connection,
            friend = thisFriend,
            onMessageReceived = { encryptedBytes ->
                // Handle received message - encryptedBytes is cipher text
                handleReceivedRadioMessage(encryptedBytes)
            }
        )

        bottomSheet.show(supportFragmentManager, "ReceiveRadioBottomSheet")
    }

    /**
     * Handles a successfully received radio message.
     * The bytes are encrypted (cipher text) - saveMessage stores them as-is.
     * Decryption happens at display time.
     */
    private fun handleReceivedRadioMessage(encryptedBytes: ByteArray)
    {
        runOnUiThread {
            saveMessage(encryptedBytes, thisFriend, false)
            setupViewByStatus() // Refresh to show new message
        }
    }

    /**
     * Observes the connection factory's state flow and updates UI accordingly.
     */
    private fun observeConnectionState()
    {
        connectionStateJob = coroutineScope.launch {
            connectionFactory.connectionState.collect { state ->

                Timber.d("Connection state changed: $state")

                when (state)
                {
                    is SerialConnectionFactory.ConnectionState.Connected -> {

                        Timber.d("🔔 FriendInfoActivity received state: $state")

                        isConnecting = false
                        serialConnection = state.connection

                        // Only show button if friend status allows sending
                        if (thisFriend.status == FriendStatus.Verified ||
                            thisFriend.status == FriendStatus.Approved) {
                            binding.sendViaSerial.visibility = View.VISIBLE
                        }

                        binding.serialStatusContainer.visibility = View.VISIBLE
                        binding.serialStatusText.text = "✓ Serial Connected"
                        Timber.d("Serial connected successfully")

                        // Auto-hide status after 3 seconds
                        launch {
                            delay(3000)
                            binding.serialStatusContainer.animate()
                                .alpha(0f)
                                .setDuration(500)
                                .withEndAction {
                                    binding.serialStatusContainer.visibility = View.GONE
                                    binding.serialStatusContainer.alpha = 1f
                                }
                        }
                    }

                    is SerialConnectionFactory.ConnectionState.Disconnected -> {
                        isConnecting = false
                        serialConnection = null
                        binding.sendViaSerial.visibility = View.GONE

                        // Only show disconnected message if we were previously connected
                        if (binding.serialStatusContainer.visibility == View.VISIBLE) {
                            binding.serialStatusText.text = "✗ Disconnected"
                            launch {
                                delay(2000)
                                binding.serialStatusContainer.visibility = View.GONE
                            }
                        }
                    }

                    is SerialConnectionFactory.ConnectionState.RequestingPermission -> {
                        binding.serialStatusContainer.visibility = View.VISIBLE
                        binding.serialStatusText.text = "Requesting USB permission..."
                        Timber.d("Waiting for USB permission...")
                    }

                    is SerialConnectionFactory.ConnectionState.Connecting -> {
                        binding.serialStatusContainer.visibility = View.VISIBLE
                        binding.serialStatusText.text = "Connecting to device..."
                        Timber.d("Establishing serial connection...")
                    }

                    is SerialConnectionFactory.ConnectionState.Error -> {
                        isConnecting = false
                        val errorMessage = "✗ Error: ${state.message}"
                        binding.serialStatusContainer.visibility = View.VISIBLE
                        binding.serialStatusText.text = errorMessage
                        Timber.e("Serial connection error: ${state.message}")

                        launch {
                            delay(5000)
                            binding.serialStatusContainer.visibility = View.GONE
                        }
                    }
                }
            }
        }
    }

    private fun checkForSerialDevices()
    {
        if (isConnecting)
        {
            Timber.d("Connection already in progress, skipping check")
            return
        }

        coroutineScope.launch {
            val devices = withContext(Dispatchers.IO)
            {
                connectionFactory.findAvailableDevices()
            }

            if (devices.isNotEmpty() && serialConnection == null && !isConnecting)
            {
                Timber.d("Found ${devices.size} device(s), initiating connection...")
                isConnecting = true
                connectToDevice(devices.first())
            }
        }
    }

    // Handle disconnection
    private suspend fun handleDeviceDisconnected() {
        serialConnection?.close()
        serialConnection = null

        withContext(Dispatchers.Main) {
            binding.sendViaSerial.visibility = View.GONE
            binding.serialStatusContainer.visibility = View.VISIBLE
            binding.serialStatusText.text = "✗ Device Disconnected"

            // Hide after 2 seconds
            delay(2000)
            binding.serialStatusContainer.visibility = View.GONE
        }
    }

    /**
     * Initiates connection to a device.
     * State changes are observed by observeConnectionState().
     */
    private fun connectToDevice(driver: UsbSerialDriver)
    {
//        connectionFactory.createConnection(driver.device, 9600)
        connectionFactory.createConnection(driver.device)
    }

    @ExperimentalUnsignedTypes
    private fun receivedSharedMessage()
    {
        intent.getStringExtra(Intent.EXTRA_TEXT)?.let{
            //Attempt to decode the message
            decodeStringMessage(it)
        }

        intent.getParcelableExtra<Parcelable>(Intent.EXTRA_STREAM)?.let {
            try
            {
                // See if we received an image message
                val extraStream = intent.getParcelableExtra<Parcelable>(Intent.EXTRA_STREAM)
                if (extraStream != null)
                {
                    (extraStream as? Uri)?.let {
                        decodeImage(it)
                    }
                }
            }
            catch (e:Exception)
            {
                showAlert(getString(R.string.alert_text_unable_to_process_request))
            }
        }
    }


    override fun onBackPressed() {
        returnButtonPressed()
    }

    private fun setClickListeners()
    {
        binding.btnResendInvite.setOnClickListener {
            inviteClicked()
        }

        binding.buttonBack.setOnClickListener {
            returnButtonPressed()
        }

        binding.btnReceiveRadio.setOnClickListener {
            receiveViaRadioClicked()
        }

        binding.sendAsText.setOnClickListener {
            if (binding.messageEditText.text.isNotEmpty())
            {
                if (binding.messageEditText.text.length > 5000)
                {
                    showAlert(getString(R.string.alert_text_message_too_long))
                } else {
                    val decodeResult = Codex().decode(binding.messageEditText.text.toString())
                    if (decodeResult != null) {
                        showConfirmationForImport()
                    } else {
                        trySendingOrSavingMessage(isImage = false, saveImage = false)
                    }
                }
            }
            else
            {
                showAlert(getString(R.string.alert_text_write_a_message_to_send))
            }
        }

        binding.sendViaSerial.setOnClickListener {
            if (binding.messageEditText.text.isNotEmpty())
            {
                if (binding.messageEditText.text.length > 5000)
                {
                    showAlert(getString(R.string.alert_text_message_too_long))
                }
                else
                {
                    val decodeResult = Codex().decode(binding.messageEditText.text.toString())

                    if (decodeResult != null)
                    {
                        showConfirmationForImport()
                    }
                    else
                    {
                        // Disable button and text field immediately for visual feedback
                        binding.sendViaSerial.isEnabled = false
                        binding.messageEditText.isEnabled = false

                        // Hide keyboard while sending
                        hideSoftKeyboard(binding.messageEditText)

                        // Pulsing animation
                        val pulseAnimator = android.animation.ObjectAnimator.ofFloat(
                            binding.sendViaSerial,
                            "alpha",
                            1f, 0.5f, 1f
                        ).apply {
                            duration = 800
                            repeatCount = android.animation.ObjectAnimator.INFINITE
                            repeatMode = android.animation.ObjectAnimator.REVERSE
                            start()
                        }

                        coroutineScope.launch {
                            try
                            {
                                val success = sendViaSerial(binding.messageEditText.text.toString())
                                if (success)
                                {
                                    // Only clear on successful send
                                    binding.messageEditText.text?.clear()
                                }
                            }
                            finally
                            {
                                pulseAnimator.cancel()
                                binding.sendViaSerial.alpha = 1f

                                // Always re-enable button and text field
                                binding.sendViaSerial.isEnabled = true
                                binding.messageEditText.isEnabled = true
                            }
                        }
                    }
                }
            }

            else showAlert(getString(R.string.alert_text_write_a_message_to_send))
        }

        binding.sendAsImage.setOnClickListener {
            showHideShareImageButtons()
        }

        binding.saveAsImage.setOnClickListener {
            trySendingOrSavingMessage(isImage = true, saveImage = true)
        }

        binding.shareAsImage.setOnClickListener {
            trySendingOrSavingMessage(isImage = true, saveImage = false)
        }

        binding.btnImportText.setOnClickListener {
            importInvitationClicked()
        }

        binding.btnImportImage.setOnClickListener {
            handleImageImport()
        }

        binding.btnHelp.setOnClickListener {
            val slideActivity = Intent(this, SlideActivity::class.java)
            slideActivity.putExtra(Intent.EXTRA_TEXT, slideNameChat)
            startActivity(slideActivity)
        }

        binding.profilePicture.setOnClickListener {
            showMenuFragment()
        }

        binding.tvFriendName.setOnClickListener {
            showMenuFragment()
        }
    }

    private fun showConfirmationForImport()
    {
        val builder: AlertDialog.Builder = AlertDialog.Builder(ContextThemeWrapper(this, R.style.AppTheme_AddFriendAlertDialog))
        val title = SpannableString(getString(R.string.import_text))

        // alert dialog title align center
        title.setSpan(
            AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER),
            0,
            title.length,
            0
        )
        builder.setTitle(title)

        val alertDialogContent = LinearLayout(this)
        alertDialogContent.orientation = LinearLayout.VERTICAL

        // Set the input - EditText
        val textView = TextView(this)
        textView.setBackgroundResource(R.drawable.btn_bkgd_light_grey_outline_8)
        textView.textAlignment = View.TEXT_ALIGNMENT_CENTER
        textView.text = getString(R.string.confirmation_for_import)
        textView.compoundDrawablePadding = 10
        textView.setPadding(25)
        textView.setTextColor(ContextCompat.getColor(this, R.color.royalBlueDark))
        alertDialogContent.addView(textView)

        builder.setView(alertDialogContent)

        // Set the Add and Cancel Buttons
        builder.setPositiveButton(resources.getString(R.string.yes))
        { _, _->
            decodeStringMessage(binding.messageEditText.text.toString())
            binding.messageEditText.setText("")
        }

        builder.setNeutralButton(resources.getString(R.string.no))
        { dialog, _->
            trySendingOrSavingMessage(isImage = false, saveImage = false)
            dialog.cancel()
        }
            .create()
            .show()
    }

    private fun showHideShareImageButtons()
    {
        binding.shareAsImage.animate().apply {
            duration = 500
            translationY(if (isShareImageButtonShow) 0F else -175F)
            translationX(if (isShareImageButtonShow) 0F else 150F)
        }
        binding.saveAsImage.animate().apply {
            duration = 500
            translationY(if (isShareImageButtonShow) 0F else -175F)
        }
        isShareImageButtonShow = !isShareImageButtonShow
    }

    private fun returnButtonPressed()
    {
        val lastFragment = supportFragmentManager.fragments.last()
        if (lastFragment.tag == menuFragmentTag) {
            setupViewByStatus()
        } else {
            finish()
        }
    }

    private fun showMenuFragment() {
        val ft = supportFragmentManager.beginTransaction()
        val codex = Codex()
        val friendCode =
            if (thisFriend.status == FriendStatus.Approved || thisFriend.status == FriendStatus.Verified) {
                codex.encodeKey(PublicKey(thisFriend.publicKeyEncoded).toBytes())
            }
            else ""

        val userCode = codex.encodeKey(Encryption().ensureKeysExist().publicKey.toBytes())
        ft.replace(
            R.id.frame_placeholder,
            MenuFragment.newInstance(thisFriend, userCode, friendCode),
            menuFragmentTag
        )
        ft.commit()
        binding.btnImportImage.isVisible = false
        binding.btnImportText.isVisible = false
        binding.btnResendInvite.isVisible = false
        binding.sendMessageContainer.isVisible = false
        if (isShareImageButtonShow) showHideShareImageButtons()
    }

    fun showVerificationStep() {
        if (thisFriend.status == FriendStatus.Approved || thisFriend.status == FriendStatus.Verified) {
            val ft = supportFragmentManager.beginTransaction()
            val codex = Codex()
            val friendCode = codex.encodeKey(PublicKey(thisFriend.publicKeyEncoded).toBytes())
            val userCode = codex.encodeKey(Encryption().ensureKeysExist().publicKey.toBytes())
            ft.replace(
                R.id.frame_placeholder,
                VerifyStatusFragment.newInstance(userCode, friendCode, thisFriend.name),
                menuFragmentTag
            )
            ft.commit()
            binding.btnImportImage.isVisible = false
            binding.btnImportText.isVisible = false
            binding.btnResendInvite.isVisible = true
            binding.sendMessageContainer.isVisible = false
        }
    }

    private fun setupViewByStatus()
    {
        binding.tvFriendName.text =
            if (thisFriend.name.length <= 10) thisFriend.name
            else thisFriend.name.substring(0, 8) + "..."

        binding.profilePicture.text = thisFriend.name.substring(0, 1)

        binding.sendViaSerial.visibility =
            if ((thisFriend.status == FriendStatus.Verified || thisFriend.status == FriendStatus.Approved) && serialConnection != null) View.VISIBLE
            else View.GONE

        val ft = supportFragmentManager.beginTransaction()

        when (thisFriend.status)
        {
            FriendStatus.Default -> {
                binding.statusIconImageView.setImageResource(FriendStatus.Default.getIcon())
                ft.replace(R.id.frame_placeholder, DefaultStatusFragment.newInstance(thisFriend))
                ft.commit()
                binding.btnImportImage.isVisible = false
                binding.btnImportText.isVisible = false
                binding.btnResendInvite.isVisible = false
                binding.sendMessageContainer.isVisible = false
            }

            FriendStatus.Requested -> {
                binding.statusIconImageView.setImageResource(FriendStatus.Requested.getIcon())
                ft.replace(R.id.frame_placeholder, RequestedStatusFragment.newInstance(thisFriend))
                ft.commit()
                binding.btnImportImage.isVisible = false
                binding.btnImportText.isVisible = false
                binding.btnResendInvite.isVisible = false
                binding.sendMessageContainer.isVisible = false
            }

            FriendStatus.Invited -> {
                binding.statusIconImageView.setImageResource(FriendStatus.Invited.getIcon())
                ft.replace(R.id.frame_placeholder, InvitedStatusFragment.newInstance(thisFriend))
                ft.commit()
                binding.btnImportImage.isVisible = false
                binding.btnImportText.isVisible = false
                binding.btnResendInvite.isVisible = true
                binding.sendMessageContainer.isVisible = false
            }

            FriendStatus.Verified -> {
                binding.statusIconImageView.setImageResource(FriendStatus.Verified.getIcon())
                ft.replace(R.id.frame_placeholder, VerifiedStatusFragment.newInstance(thisFriend))
                ft.commit()
                binding.btnImportImage.isVisible = true
                binding.btnImportText.isVisible = true
                binding.btnReceiveRadio.isVisible = usbAudioConnection != null
                binding.btnResendInvite.isVisible = false
                binding.sendMessageContainer.isVisible = true
                binding.verifiedStatusIconImageView.isVisible = true
            }

            FriendStatus.Approved -> {
                binding.statusIconImageView.setImageResource(FriendStatus.Approved.getIcon())
                ft.replace(R.id.frame_placeholder, VerifiedStatusFragment.newInstance(thisFriend))
                ft.commit()
                binding.btnImportImage.isVisible = true
                binding.btnImportText.isVisible = true
                binding.btnReceiveRadio.isVisible = usbAudioConnection != null
                binding.btnResendInvite.isVisible = false
                binding.sendMessageContainer.isVisible = true
//                val codex = Codex()
//                val friendCode = codex.encodeKey(PublicKey(thisFriend.publicKeyEncoded).toBytes())
//                val userCode = codex.encodeKey(Encryption().ensureKeysExist().publicKey.toBytes())
//                ft.replace(R.id.frame_placeholder, VerifyStatusFragment.newInstance(userCode, friendCode, thisFriend.name))
//                ft.commit()
//                btn_import_image.isVisible = false
//                btn_import_text.isVisible = false
//                btn_resend_invite.isVisible = true
//                send_message_container.isVisible = false
            }
        }

        updateReceiveButtonVisibility()
    }

    fun inviteClicked()
    {
        // Get user's public key to send to contact
        val userPublicKey = Encryption().ensureKeysExist().publicKey
        val keyBytes = userPublicKey.toBytes()
        ShareUtil.shareKey(this, keyBytes)

        // Share the key
//        if (Persist.loadBooleanKey(Persist.sharedPrefUseSmsAsDefaultKey)) { // && (thisFriend.phone?.isNotEmpty() == true)) {
//            try {
//                val codex = Codex()
//                val encodedKey = codex.encodeKey(keyBytes)
//                val smsManager: SmsManager = if (Build.VERSION.SDK_INT >= 31) {
//                    this.getSystemService(SmsManager::class.java)
//                } else {
//                    SmsManager.getDefault()
//                }
//                val parts = smsManager.divideMessage(encodedKey)
//                smsManager.sendMultipartTextMessage(
//                    thisFriend.phone,
//                    null,
//                    parts,
//                    null,
//                    null
//                )
//            } catch (e: Exception) {
//                this.showAlert(getString(R.string.unable_to_send_sms))
//                return
//            }
//        } else {
//          ShareUtil.shareKey(this, keyBytes)
//        }

        if (thisFriend.status == FriendStatus.Requested)
        {
            // We have already received an invitation from this friend.
            // Set friend status to approved.
            thisFriend.status = FriendStatus.Approved
            Persist.updateFriend(this, thisFriend, newStatus = FriendStatus.Approved)
            setupViewByStatus()
        }
        else
        {
            // We have not received an invitation from this friend.
            // Set friend status to Invited
            thisFriend.status = FriendStatus.Invited
            Persist.updateFriend(this, thisFriend, newStatus = FriendStatus.Invited)
            setupViewByStatus()
        }
    }

    fun importInvitationClicked() {
        val builder: AlertDialog.Builder = AlertDialog.Builder(ContextThemeWrapper(this, R.style.AppTheme_AddFriendAlertDialog))
        val title = SpannableString(getString(R.string.import_text))

        // alert dialog title align center
        title.setSpan(
            AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER),
            0,
            title.length,
            0
        )
        builder.setTitle(title)

        // Set the input - EditText
        val inputEditText = EditText(this)
        inputEditText.setBackgroundResource(R.drawable.btn_bkgd_light_grey_outline_8)
        inputEditText.textAlignment = View.TEXT_ALIGNMENT_VIEW_END
        inputEditText.setPadding(20)
        inputEditText.height = 500
        inputEditText.gravity = Gravity.TOP
        inputEditText.setTextColor(ContextCompat.getColor(this, R.color.royalBlueDark))
        builder.setView(inputEditText)
        builder.setPositiveButton(resources.getString(R.string.import_text))
        { _, _->
            if (inputEditText.text.isNotEmpty())
            {
                if (inputEditText.text.length > 5000)
                {
                    showAlert(getString(R.string.alert_text_message_too_long))
                } else {
                    decodeStringMessage(inputEditText.text.toString())
                }
            }
        }
        builder.setNeutralButton(resources.getString(R.string.cancel_button)) { dialog, _->
            dialog.cancel()
        }.create().show()
    }

    private fun trySendingOrSavingMessage(isImage: Boolean, saveImage: Boolean)
    {
        // Make sure there is a message to send
        val message = binding.messageEditText.text.toString()

        if (message.isBlank()) {
            showAlert(getString(R.string.alert_text_write_a_message_to_send))
            return
        }

        if (message.length > 5000) {
            showAlert(getString(R.string.alert_text_message_too_long))
            return
        }

        if (isImage)
        {
            // If the message is sent as an image
            ActivityCompat.requestPermissions(
                this@FriendInfoActivity,
                arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE),
                1
            )
            pickImageFromGallery(saveImage)
        }
        else
        {
            // If the message is sent as text
            if (thisFriend.publicKeyEncoded != null)
            {
                val encryptedMessage = Encryption().encrypt(thisFriend.publicKeyEncoded!!, message)
                ShareUtil.shareText(this, message, thisFriend.publicKeyEncoded!!)
                saveMessage(encryptedMessage, thisFriend, true)

                binding.messageEditText.text?.clear()
            }
            else
            {
                this.showAlert(getString(R.string.alert_text_verified_friends_only))
                return
            }
        }
    }

    private suspend fun sendViaSerial(message: String): Boolean
    {
        // Encryption must happen outside the IO coroutine
        val encryptedMessage = try
        {
            Encryption().encrypt(thisFriend.publicKeyEncoded!!, message)
        }
        catch (error: Exception)
        {
            withContext(Dispatchers.Main) {
                showAlert(getString(R.string.alert_text_unable_to_process_request))
            }
            Timber.e(error, "Failed to encrypt message for broadcast")
            return false
        }

        // Switch to IO thread for serial communication
        return withContext(Dispatchers.IO) {
            val connection: SerialConnection? = serialConnection

            if (connection == null)
            {
                withContext(Dispatchers.Main) {
                    showAlert(getString(R.string.alert_text_serial_not_connected))
                }
                return@withContext false
            }

            try
            {
                // Encode encrypted message to WSPR messages
                val wsprMessages = encodeDataToWSPRMessages(encryptedMessage)

                if (wsprMessages == null)
                {
                    withContext(Dispatchers.Main) {
                        showAlert("Failed to encode message - data too large")
                    }
                    return@withContext false
                }

                Timber.d("Successfully encoded ${encryptedMessage.size} bytes to ${wsprMessages.size} WSPR message(s)")

                // Convert WSPR messages to frequency arrays
                val frequencyArrays = wsprMessages.map { (callsign, gridSquare, powerDbm) ->
                    WSPREncoder.encodeToFrequencies(
                        WSPREncoder.WSPRMessage(
                            callsign,
                            gridSquare,
                            powerDbm,
                            0,      // mode
                            false   // includeSync
                        )
                    ).toList()
                }

                if (frequencyArrays.any { it.isEmpty() })
                {
                    withContext(Dispatchers.Main) {
                        showAlert("Failed to encode frequencies")
                    }
                    return@withContext false
                }

                // Serialize and send using ion
                val sent = try
                {
                    for(frequencyArray in frequencyArrays) {
                        // FIXME - pause until 2 minute mark each time through loop

                        var first = true
                        for(frequency in frequencyArray) {
                            val ionFrequency = Word.make(frequency.toInt(), NounType.INTEGER.value);
                            Word.to_conn(connection, ionFrequency)

                            if(first)
                            {
                                first = false

                                // Turn on transmitter after setting initial frequency
                                Word.to_conn(connection, Word.make(1, NounType.INTEGER.value))
                            }

                            Thread.sleep(683) // Wait for tone duration
                        }

                        // Turn off transmitter after waiting for last tone
                        Word.to_conn(connection, Word.make(0, NounType.INTEGER.value))
                    }
                    true
                }
                catch (e: Exception)
                {
                    Timber.e(e, "IotaList serialization failed")
                    false
                }

                if (!sent)
                {
                    withContext(Dispatchers.Main) {
                        showAlert("Failed to write to Serial device.")
                    }
                    return@withContext false
                }

                // Wait for response
                val response = waitForAnyResponse(connection, timeoutMs = 3000)

                withContext(Dispatchers.Main)
                {
                    if (response != null)
                    {
                        // Only save if we got a response
                        saveMessage(encryptedMessage, thisFriend, true)
                        binding.serialStatusText.text = "✓ Sent ${wsprMessages.size} WSPR message(s)"
                        Timber.d("Sent ${wsprMessages.size} WSPR message(s), response: \n$response")
                    }
                    else
                    {
                        showAlert("No response from radio device. Message not sent.")
                        binding.serialStatusText.text = "Message sent (no response)"
                        Timber.d("Message sent to serial device (no response)")
                    }

                    // Reset status after delay
                    delay(3000)
                    binding.serialStatusContainer.visibility = View.GONE
                }

                response != null
            }
            catch (e: Exception)
            {
                Timber.e(e, "Error sending message via serial")

                withContext(Dispatchers.Main) {
                    showAlert("Serial transmission error: ${e.message}")
                }

                false
            }
        }
    }

    // FIXME: Move to CodexKotlin once tested
    /**
     * Encodes binary data into WSPR message format with automatic message count detection.
     *
     * Finds the minimum number of WSPR messages needed to encode the data by starting
     * with a low estimate and incrementing until encoding succeeds.
     *
     * @param data Binary data to encode (typically encrypted message bytes)
     * @return List of WSPR messages (callsign, gridSquare, powerDbm), or null if encoding fails
     */
    private fun encodeDataToWSPRMessages(data: ByteArray): List<Triple<String, String, Int>>?
    {
        try
        {
            Timber.d("=== WSPR Encoding Debug ===")
            Timber.d("Input data: ${data.size} bytes")
            Timber.d("Input hex: ${data.joinToString("") { "%02x".format(it) }}")

            // Convert encrypted bytes to BigInteger (unsigned)
            val numericValue = BigInteger(1, data)

            Timber.d("BigInteger value: $numericValue")
            Timber.d("BigInteger bits: ${numericValue.bitLength()}")

            // Calculate bits needed
            val bitsNeeded = numericValue.bitLength()

            // Each WSPR message provides ~50 bits of capacity (rough estimate)
            // Start with a reasonable minimum based on data size
            val estimatedMessages = maxOf(1, (bitsNeeded / 50) + 1)

            Timber.d("Data: ${data.size} bytes, ${bitsNeeded} bits → estimated ${estimatedMessages} WSPR messages")

            // Try encoding with increasing message counts until successful
            val maxAttempts = 20 // Reasonable upper limit
            var messageCount = estimatedMessages

            while (messageCount <= maxAttempts)
            {
                try
                {
                    val encoded = WSPRMessageSequence.encode(numericValue)
                    // Encoding succeeded! Now parse into individual WSPR messages
                    val wsprMessages = encoded.toWSPRFields()

                    return wsprMessages
                }
                catch (e: Exception)
                {
                    // Encoding failed, try more messages
                    Timber.d("Failed with $messageCount messages: ${e.message}")
                    messageCount++
                }
            }

            Timber.e("Could not encode data even with $maxAttempts WSPR messages")
            return null
        }
        catch (e: Exception)
        {
            Timber.e(e, "Error encoding data to WSPR messages")
            return null
        }
    }

    /**
     * Decodes a power byte back to dBm value.
     * WSPR standard power levels: 0, 3, 7, 10, 13, 17, 20, 23, 27, 30,
     *                              33, 37, 40, 43, 47, 50, 53, 57, 60 dBm
     */
    private fun decodePowerByte(powerByte: Byte): Int
    {
        val powerLevels = listOf(
            0, 3, 7, 10, 13, 17, 20, 23, 27, 30,
            33, 37, 40, 43, 47, 50, 53, 57, 60
        )

        // The Power symbol likely encodes 0-18 as an index
        val index = powerByte.toInt() and 0xFF

        return if (index in powerLevels.indices) {
            powerLevels[index]
        } else {
            // Fallback: treat as ASCII digit if possible
            val char = powerByte.toInt().toChar()
            if (char.isDigit()) {
                powerLevels[char.digitToInt()]
            } else {
                Timber.w("Unknown power byte: $powerByte, defaulting to 30dBm")
                30 // Safe default
            }
        }
    }

    private fun pickImageFromGallery(saveImage: Boolean)
    {
        // Calling GetContent contract
        val pickImageIntent =
            Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        if (saveImage)
        {
            startActivityForResult(pickImageIntent, RequestCodes.selectImageForSavingCode)
        }
        else
        {
            startActivityForResult(pickImageIntent, RequestCodes.selectImageForSharingCode)
        }
    }

    private fun handleImageImport()
    {
        val intent = Intent(Intent.ACTION_PICK)
        intent.type = "image/*"
        startActivityForResult(intent, RequestCodes.selectImageForImport)
    }

    @ExperimentalUnsignedTypes
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?)
    {
        super.onActivityResult(requestCode, resultCode, data)

        if (resultCode == Activity.RESULT_OK)
        {
            if (requestCode == RequestCodes.selectImageForImport)
            {
                // get data?.data as URI
                val imageURI = data?.data
                imageURI?.let {
                    decodeImage(it)
                }
            }
            else if (requestCode == RequestCodes.selectImageForSharingCode || requestCode == RequestCodes.selectImageForSavingCode) {
                // We can only share an image if a recipient with a public key has been selected
                thisFriend.publicKeyEncoded?.let {
                    // Get the message text
                    val message = binding.messageEditText.text.toString()
                    // get data?.data as URI
                    val imageURI = data?.data
                    imageURI?.let {
                        binding.imageImportProgressBar.visibility = View.VISIBLE
                        shareOrSaveAsImage(
                            imageURI,
                            message,
                            thisFriend.publicKeyEncoded!!,
                            requestCode == RequestCodes.selectImageForSavingCode
                        )
                        binding.messageEditText.text?.clear()
                    }
                }
            }
        }
    }

    @ExperimentalUnsignedTypes
    private fun shareOrSaveAsImage(imageUri: Uri, message: String, encodedFriendPublicKey: ByteArray, saveImage: Boolean)
    {
        try
        {
            // Encrypt the message
            val encryptedMessage = Encryption().encrypt(encodedFriendPublicKey, message)
            makeWait()
            // Encode the image
            val newUri: Deferred<Uri?> =
                coroutineScope.async(Dispatchers.IO) {
                    val swatch = Encoder()
                    return@async swatch.encode(
                        applicationContext,
                        encryptedMessage,
                        imageUri,
                        saveImage
                    )
                }

            coroutineScope.launch(Dispatchers.Main) {
                val maybeUri = newUri.await()
                noMoreWaiting()
                binding.imageImportProgressBar.visibility = View.INVISIBLE

                if (maybeUri != null)
                {
                    if (saveImage) {
                        showAlert(getString(R.string.alert_text_image_saved))
                    }
                    else {
                        ShareUtil.shareImage(applicationContext, maybeUri)
                    }
                    saveMessage(encryptedMessage, thisFriend, true)
                }
                else
                {
                    applicationContext.showAlert(applicationContext.getString(R.string.alert_text_unable_to_process_request))
                }
            }

        } catch (exception: SecurityException) {
            applicationContext.showAlert(applicationContext.getString(R.string.alert_text_unable_to_process_request))
            print("Unable to send message as photo, we were unable to encrypt the mess56age.")
            return
        }
    }

    @ExperimentalUnsignedTypes
    private fun decodeImage(imageUri: Uri)
    {
        makeWait()

        val decodeResult: Deferred<ByteArray?> =
            coroutineScope.async(Dispatchers.IO) {
                val swatch = Decoder()
                return@async swatch.decode(applicationContext, imageUri)
            }

        coroutineScope.launch(Dispatchers.Main) {
            try
            {
                val maybeDecodeResult = decodeResult.await()
                noMoreWaiting()

                if (maybeDecodeResult != null)
                {
                    decodePayload = maybeDecodeResult
                    handleImageDecodeResult()
                }
                else
                {
                    showAlert(getString(R.string.alert_text_unable_to_decode_message))
                }
            }
            catch (e: Exception)
            {
                noMoreWaiting()
                showAlert(getString(R.string.alert_text_unable_to_decode_message))
            }
        }
    }

    private fun handleImageDecodeResult() {
        if (decodePayload == null)
        {
            showAlert(getString(R.string.alert_text_unable_to_decode_message))
            return
        }
        saveMessage(decodePayload!!, thisFriend, false)
    }

    private fun saveMessage(cipherBytes: ByteArray, messageSender: Friend, fromMe: Boolean) {
        val newMessage = Message(cipherBytes, messageSender, fromMe)
        newMessage.save(this)

        // Add to messages
        setupViewByStatus()
    }

    private fun makeWait() {
        binding.imageImportProgressBar.visibility = View.VISIBLE
        binding.btnImportImage.isEnabled = false
        binding.btnImportImage.isClickable = false
    }

    private fun noMoreWaiting() {
        binding.imageImportProgressBar.visibility = View.INVISIBLE
        binding.btnImportImage.isEnabled = true
        binding.btnImportImage.isClickable = true
    }

    private fun decodeStringMessage(messageString: String) {
        // Update UI to reflect text being shared
        val decodeResult = Codex().decode(messageString)

        if (decodeResult != null)
        {
            when (decodeResult.type)
            {
                KeyOrMessage.EncryptedMessage ->
                {
                    // Check for message type if user is not approved
                    if (thisFriend.status == FriendStatus.Invited) {
                        this.showAlert("The input was a message. You have to import your friend's public key.")
                        return
                    }

                    // Create Message Instance
                    val newMessage = Message(decodeResult.payload, thisFriend, false)
                    newMessage.save(this)

                    // Add to messages
                    setupViewByStatus()
                }
                KeyOrMessage.Key ->
                {
                    updateKeyAndStatus(decodeResult.payload)
                }
            }
        }
        else
        {
            this.showAlert(getString(R.string.alert_text_unable_to_decode_message))
        }
    }

    private fun updateKeyAndStatus(keyData: ByteArray) {
        when (thisFriend.status)
        {
            FriendStatus.Default ->
            {
                Persist.updateFriend(
                    context = this,
                    friendToUpdate = thisFriend,
                    newStatus = FriendStatus.Requested,
                    encodedPublicKey = keyData
                )
                thisFriend.status = FriendStatus.Requested
                setupViewByStatus()
            }
            FriendStatus.Invited ->
            {
                Persist.updateFriend(
                    context = this,
                    friendToUpdate = thisFriend,
                    newStatus = FriendStatus.Approved,
                    encodedPublicKey = keyData
                )
                thisFriend.status = FriendStatus.Approved
                thisFriend.publicKeyEncoded = keyData
                setupViewByStatus()
            }
            else ->
                this.showAlert(getString(R.string.alert_text_unable_to_update_friend_status))
        }
    }

    private suspend fun waitForAnyResponse(connection: SerialConnection, timeoutMs: Long): String?
    {
        return withContext(Dispatchers.IO) {
            withTimeoutOrNull(timeoutMs) {
                val buffer = StringBuilder()
                val startTime = System.currentTimeMillis()
                var lastDataTime = startTime

                while (System.currentTimeMillis() - startTime < timeoutMs)
                {
                    try
                    {
                        val data = connection.readAvailable()

                        if (data != null && data.isNotEmpty())
                        {
                            lastDataTime = System.currentTimeMillis()

                            for (byte in data)
                            {
                                val char = byte.toInt().toChar()
                                if (char.isISOControl().not() && char != '\u0000')
                                {
                                    buffer.append(char)
                                }
                            }
                        }
                        else
                        {
                            // If we have data and haven't received more for 200ms, consider it complete
                            if (buffer.isNotEmpty() &&
                                System.currentTimeMillis() - lastDataTime > 200)
                            {
                                return@withTimeoutOrNull buffer.toString().trim()
                            }

                            delay(50)
                        }

                    }
                    catch (e: Exception)
                    {
                        Timber.w("Error while waiting for any response: ${e.message}")
                        delay(100)
                    }
                }

                // Return whatever we got
                if (buffer.isNotEmpty()) buffer.toString().trim()
                else null
            }
        }
    }

    fun approveVerifyFriend() {
        Persist.updateFriend(this, thisFriend, newStatus = FriendStatus.Verified,
            encodedPublicKey = thisFriend.publicKeyEncoded)
        thisFriend.status = FriendStatus.Verified
        setupViewByStatus()
    }

    fun declineVerifyFriend() {
        thisFriend.publicKeyEncoded = null
        Persist.updateFriend(this, thisFriend, newStatus = FriendStatus.Default)
        thisFriend.status = FriendStatus.Default
        setupViewByStatus()
    }

    private fun sendToLogin() {
        // If the status is not either NotRequired, or Logged in, request login
        this.showAlert(getString(R.string.alert_text_passcode_required_to_proceed))
        // Send user to the Login Activity
        val loginIntent = Intent(applicationContext, LogInActivity::class.java)
        startActivity(loginIntent)
        finish()
    }

    fun changeFriendsName(newName: String) {
        Persist.updateFriend(this, thisFriend, newName)
        thisFriend.name = if (newName.length <= 10) newName else newName.substring(0, 8) + "..."
        binding.tvFriendName.text = thisFriend.name
        binding.profilePicture.text = thisFriend.name.substring(0, 1)
        showAlert("New name saved")
    }

//    fun changeFriendsPhone(newPhoneNumber: String) {
//        Persist.updateFriendsPhone(this, thisFriend, newPhoneNumber)
//        thisFriend.phone = newPhoneNumber
//        showAlert("New phone number saved")
//    }

    fun Activity.hideSoftKeyboard(editText: EditText) {
        (getSystemService(AppCompatActivity.INPUT_METHOD_SERVICE) as InputMethodManager).apply {
            hideSoftInputFromWindow(editText.windowToken, 0)
        }
    }

    override fun onDestroy()
    {
        super.onDestroy()

        try
        {
            unregisterReceiver(usbReceiver)
        }
        catch (e: Exception)
        {
            // Receiver wasn't registered
        }

        // Cancel the state observer
        connectionStateJob?.cancel()

        serialConnection?.close()
        connectionFactory.disconnect()
        parentJob.cancel()

        audioDeviceDiscoveryJob?.cancel()
        audioConnectionStatusJob?.cancel()

        coroutineScope.launch {
            try {
                usbAudioConnection?.disconnect()
                usbAudioManager.cleanup()
            } catch (e: Exception) {
                Timber.w(e, "Error cleaning up USB audio")
            }
        }
    }
}
