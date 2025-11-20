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
import org.operatorfoundation.audiocoder.WSPREncoder
import org.operatorfoundation.iota.IotaObject
import org.operatorfoundation.iota.nouns.Noun
import org.operatorfoundation.iota.toIotaValue
import timber.log.Timber

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
                        // Disable button immediately for visual feedback
                        binding.sendViaSerial.isEnabled = false

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

                                // Always re-enable button
                                binding.sendViaSerial.isEnabled = true

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
        // Encryption must happen outside the IO coroutine since we're already being called from a coroutine in the click listener
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
                return@withContext false  // No connection, keep message
            }

            try
            {
                // FIXME: Test parameters - standard WSPR message (replace with CodexKotlin encoding of the encrypted message)
                val testCallsign = "Q000"
                val testGridSquare = "FN31"
                val testPower = 30 // 30 dBm = 1 watt

                // Generate frequency array using AudioCoder
                val frequencyArray: LongArray = WSPREncoder.encodeToFrequencies(
                    WSPREncoder.WSPRMessage(
                        testCallsign,
                        testGridSquare,
                        testPower,
                        0,
                        false
                    )
                )

                if (frequencyArray.isEmpty())
                {
                    withContext(Dispatchers.Main) {
                        showAlert("Failed to encode frequencies")
                    }

                    return@withContext false
                }

                // Create data structure to instruct radio what to do
                val delayTime = 0 // FIXME - put the actual delay time
                val messages = listOf(frequencyArray.toList()) // FIXME - replace with real list of lists rather than making a literal list of one item
                val payload = listOf(delayTime, messages)
                val iotaList = IotaObject.fromKotlin(payload.toIotaValue())

                // Serialize and send using IotaList
                val sent = try
                {
                    Noun.to_conn(connection, iotaList)
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
                    saveMessage(encryptedMessage, thisFriend, true)

                    // Update status based on response
                    if (response != null)
                    {
                        binding.serialStatusText.text = "Response: $response"
                        Timber.d("Message sent to serial device. response: \n$response")
                    }
                    else
                    {
                        binding.serialStatusText.text = "Message sent to serial device (no response)"
                        Timber.d("Message sent to serial device (no response)")
                    }

                    // Reset status after delay
                    delay(3000)
                    binding.serialStatusText.text = "✓ Serial Connected"
                }

                true  // SUCCESS - message sent
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
    }
}
