package org.nahoft.nahoft.activities

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.view.animation.LinearInterpolator
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Bundle
import android.os.Parcelable
import android.text.Layout
import android.text.SpannableString
import android.text.style.AlignmentSpan
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.view.setPadding
import kotlinx.coroutines.*
import org.libsodium.jni.keys.PublicKey
import androidx.lifecycle.ViewModelProvider

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
import org.nahoft.nahoft.fragments.ReceiveRadioBottomSheetFragment
import org.nahoft.nahoft.models.Friend
import org.nahoft.nahoft.models.FriendStatus
import org.nahoft.nahoft.models.Message
import org.nahoft.nahoft.models.slideNameChat
import org.nahoft.nahoft.viewmodels.FriendInfoViewModel
import org.operatorfoundation.audiocoder.WSPREncoder
import org.operatorfoundation.codex.symbols.WSPRMessageSequence
import org.operatorfoundation.ion.storage.NounType
import org.operatorfoundation.ion.storage.Word
import org.nahoft.nahoft.viewmodels.ReceiveSessionState

import timber.log.Timber
import java.math.BigInteger
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

class FriendInfoActivity: AppCompatActivity()
{
    private lateinit var viewModel: FriendInfoViewModel
    private lateinit var binding: ActivityFriendInfoBinding
    private var decodePayload: ByteArray? = null
    private lateinit var thisFriend: Friend

    private val parentJob = Job()
    private val coroutineScope = CoroutineScope(Dispatchers.Main + parentJob)
    private val menuFragmentTag = "MenuFragment"
    private var isShareImageButtonShow: Boolean = false
    private var indicatorAnimator: ObjectAnimator? = null

    private val usbReceiver = object : BroadcastReceiver()
    {
        override fun onReceive(context: Context, intent: Intent)
        {
            when (intent.action)
            {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    Timber.d("USB device attached")
                    viewModel.onUsbDeviceAttached()

                }

                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    Timber.d("USB device detached")
                    viewModel.onUsbDeviceDetached()
                }
            }
        }
    }

    // Audio permission launcher (for USB audio recording)
    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->

        if (isGranted)
        {
            Timber.d("RECORD_AUDIO permission granted, retrying audio device discovery")
            viewModel.startAudioDeviceDiscovery()
        }
        else
        {
            Timber.w("RECORD_AUDIO permission denied")
            Toast.makeText(this, "Microphone permission is required for USB audio", Toast.LENGTH_LONG).show()
        }
    }

    // Image picker for saving encoded image locally
    @OptIn(ExperimentalUnsignedTypes::class)
    private val pickImageForSavingLauncher: ActivityResultLauncher<PickVisualMediaRequest> =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            uri?.let { handlePickedImageForEncoding(it, saveImage = true) }
        }

    // Image picker for sharing encoded image
    @OptIn(ExperimentalUnsignedTypes::class)
    private val pickImageForSharingLauncher: ActivityResultLauncher<PickVisualMediaRequest> =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            uri?.let { handlePickedImageForEncoding(it, saveImage = false) }
        }

    // Image picker for importing/decoding
    @OptIn(ExperimentalUnsignedTypes::class)
    private val pickImageForImportLauncher: ActivityResultLauncher<PickVisualMediaRequest> =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            uri?.let { decodeImage(it) }
        }

    @OptIn(ExperimentalUnsignedTypes::class)
    override fun onCreate(savedInstanceState: Bundle?)
    {
        super.onCreate(savedInstanceState)

        binding = ActivityFriendInfoBinding.inflate(layoutInflater)
        setContentView(binding.root)
        viewModel = ViewModelProvider(this)[FriendInfoViewModel::class.java]

        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE,
                        WindowManager.LayoutParams.FLAG_SECURE)

        if (!Persist.accessIsAllowed()) { sendToLogin() }

        // Get our pending friend
        val maybeFriend = intent.getSerializableExtra(RequestCodes.friendExtraTaskDescription) as? Friend

        if (maybeFriend == null)
        { // this should never happen, get out of this activity.
            Timber.e("Attempted to open FriendInfoActivity, but Friend was null.")
            return
        }
        else
        {
            thisFriend = maybeFriend
            viewModel.initializeFriend(thisFriend)
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

        setupConnectionObservers()
        viewModel.checkForSerialDevices()
        viewModel.observeAudioConnectionStatus()

        // Before starting audio discovery, check permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED)
        {
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
        else { viewModel.startAudioDeviceDiscovery() }
    }

    override fun onResume() {
        super.onResume()

        setupViewByStatus()
    }

    /**
     * Sets up observers for connection state from ViewModel.
     */
    private fun setupConnectionObservers()
    {
        // Observe serial connection state
        coroutineScope.launch {
            viewModel.serialConnectionState.collect { state ->
                handleSerialConnectionState(state)
            }
        }

        // Observe USB audio connection for receive button visibility
        coroutineScope.launch {
            viewModel.canReceiveRadio.collect { canReceive ->
                binding.btnReceiveRadio.visibility = if (canReceive) View.VISIBLE else View.GONE
            }
        }

        // Observe serial connection for send button visibility
        coroutineScope.launch {
            viewModel.canSendViaSerial.collect { canSend ->
                binding.sendViaSerial.visibility = if (canSend) View.VISIBLE else View.GONE
            }
        }

        // Observe receive session state for indicator
        coroutineScope.launch {
            viewModel.receiveSessionState.collect { state ->
                updateReceiveButtonState(state)
            }
        }

        // Observe received messages and save them
        coroutineScope.launch {
            viewModel.lastReceivedMessage.collect { encryptedBytes ->
                saveMessage(encryptedBytes, thisFriend, false)
                setupViewByStatus()
            }
        }
    }

    /**
     * Updates visibility of the receive radio container based on connection and friend status.
     */
    private fun updateReceiveButtonVisibility()
    {
        val shouldShow = viewModel.usbAudioConnection.value != null &&
                (thisFriend.status == FriendStatus.Verified || thisFriend.status == FriendStatus.Approved)

        binding.btnReceiveRadio.visibility = if (shouldShow) View.VISIBLE else View.GONE
    }

    /**
     * Updates the receive button appearance based on session state.
     * Animates the button icon when a session is active.
     */
    private fun updateReceiveButtonState(state: ReceiveSessionState)
    {
        when (state) {
            is ReceiveSessionState.Running,
            is ReceiveSessionState.WaitingForWindow -> {
                // Tint green and start pulse animation
                binding.btnReceiveRadio.drawable?.setTint(
                    ContextCompat.getColor(this, R.color.caribbeanGreen)
                )
                startButtonPulseAnimation()
            }

            is ReceiveSessionState.Idle,
            is ReceiveSessionState.Stopped -> {
                // Reset to white and stop animation
                stopButtonAnimation()
                binding.btnReceiveRadio.drawable?.setTint(
                    ContextCompat.getColor(this, R.color.white)
                )
            }
        }
    }

    /**
     * Starts a pulse animation on the receive button.
     */
    private fun startButtonPulseAnimation()
    {
        // Cancel any existing animation
        indicatorAnimator?.cancel()

        indicatorAnimator = ObjectAnimator.ofFloat(
            binding.btnReceiveRadio,
            "alpha",
            1f, 0.4f, 1f
        ).apply {
            duration = 1500
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = LinearInterpolator()
            start()
        }
    }

    /**
     * Stops the button animation and resets alpha.
     */
    private fun stopButtonAnimation()
    {
        indicatorAnimator?.cancel()
        indicatorAnimator = null
        binding.btnReceiveRadio.alpha = 1f
    }

    /**
     * Handles the receive via radio button click.
     * Opens the ReceiveRadioBottomSheetFragment.
     * If a session is already active, reopens the sheet.
     */
    private fun receiveViaRadioClicked()
    {
        // Check if sheet is already showing
        val existingSheet = supportFragmentManager.findFragmentByTag("ReceiveRadioBottomSheet")

        if (existingSheet != null) return // Already showing

        // If no active session, validate prerequisites before starting
        if (!viewModel.isSessionActive())
        {
            val connection = viewModel.usbAudioConnection.value
            if (connection == null) {
                showAlert(getString(R.string.usb_audio_not_connected))
                return
            }

            if (thisFriend.publicKeyEncoded == null) {
                showAlert(getString(R.string.alert_text_verified_friends_only))
                return
            }
        }

        // Show the bottom sheet - it will start or resume session via ViewModel
        val bottomSheet = ReceiveRadioBottomSheetFragment()
        bottomSheet.show(supportFragmentManager, "ReceiveRadioBottomSheet")
    }

    /**
     * Handles serial connection state changes and updates UI accordingly.
     * Called from setupConnectionObservers() when state flow emits.
     */
    private fun handleSerialConnectionState(state: SerialConnectionFactory.ConnectionState)
    {
        Timber.d("Connection state changed: $state")

        when (state)
        {
            is SerialConnectionFactory.ConnectionState.Connected -> {

                Timber.d("🔔 FriendInfoActivity received state: $state")

                viewModel.onSerialConnectionStateSettled()

                // Button visibility now handled by canSendViaSerial flow observer

                binding.serialStatusContainer.visibility = View.VISIBLE
                binding.serialStatusText.text = "✔ Serial Connected"
                Timber.d("Serial connected successfully")

                // Auto-hide status after 3 seconds
                coroutineScope.launch {
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
                viewModel.onSerialConnectionStateSettled()

                // Button visibility now handled by canSendViaSerial flow observer

                // Only show disconnected message if we were previously connected
                if (binding.serialStatusContainer.isVisible) {
                    binding.serialStatusText.text = "✗ Disconnected"
                    coroutineScope.launch {
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
                viewModel.onSerialConnectionStateSettled()

                val errorMessage = "✗ Error: ${state.message}"
                binding.serialStatusContainer.visibility = View.VISIBLE
                binding.serialStatusText.text = errorMessage
                Timber.e("Serial connection error: ${state.message}")

                coroutineScope.launch {
                    delay(5000)
                    binding.serialStatusContainer.visibility = View.GONE
                }
            }
        }
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
                        val pulseAnimator = ObjectAnimator.ofFloat(
                            binding.sendViaSerial,
                            "alpha",
                            1f, 0.5f, 1f
                        ).apply {
                            duration = 800
                            repeatCount = ObjectAnimator.INFINITE
                            repeatMode = ObjectAnimator.REVERSE
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
            // Show consent dialog before proceeding with image save
            if (hasImageSaveConsentBeenShown())
            {
                trySendingOrSavingMessage(isImage = true, saveImage = true)
            }
            else
            {
                // Only show if the user hasn't opted out
                showImageSaveConsentDialog {
                    trySendingOrSavingMessage(isImage = true, saveImage = true)
                }
            }
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

    /**
     * Shows an informed consent dialog before saving an encoded image to shared storage.
     * Explains that the image will be visible in the gallery and accessible to other apps.
     * Includes a "Don't show again" option.
     */
    private fun showImageSaveConsentDialog(onConsent: () -> Unit)
    {
        val builder = AlertDialog.Builder(ContextThemeWrapper(this, R.style.AppTheme_AddFriendAlertDialog))
        val title = SpannableString("Save to Gallery?")

        title.setSpan(
            AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER),
            0,
            title.length,
            0
        )
        builder.setTitle(title)

        // Create container for message and checkbox
        val container = LinearLayout(this)
        container.orientation = LinearLayout.VERTICAL
        container.setPadding(50, 40, 50, 20)

        // Message text
        val message = """
        The encoded image will be saved to your device's Pictures folder where:
        
        • It will appear in your gallery app
        • Other apps can access it
        • It remains visible after uninstalling Nahoft
        
        The hidden message is encrypted and can only be decoded with your keys.
        """.trimIndent()

        val textView = TextView(this)
        textView.text = message
        textView.setTextColor(ContextCompat.getColor(this, R.color.royalBlueDark))
        container.addView(textView)

        // "Don't show again" checkbox
        val checkBox = android.widget.CheckBox(this)
        checkBox.text = "Don't show this again"
        checkBox.setTextColor(ContextCompat.getColor(this, R.color.royalBlueDark))
        checkBox.setPadding(0, 30, 0, 0)
        container.addView(checkBox)

        builder.setView(container)

        builder.setPositiveButton("Save to Gallery") { _, _ ->
            // Save preference if checkbox is checked
            if (checkBox.isChecked) {
                markImageSaveConsentShown()
            }
            onConsent()
        }

        builder.setNegativeButton("Cancel") { dialog, _ ->
            dialog.cancel()
        }

        builder.create().show()
    }

    /**
     * Checks if the user has already seen and accepted the image save consent dialog.
     */
    private fun hasImageSaveConsentBeenShown(): Boolean
    {
        return Persist.loadBooleanKey(Persist.sharedPrefImageSaveConsentShownKey)
    }

    /**
     * Marks that the user has seen the image save consent dialog.
     */
    private fun markImageSaveConsentShown()
    {
        Persist.saveBooleanKey(Persist.sharedPrefImageSaveConsentShownKey, true)
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

        val userCode = codex.encodeKey(Encryption().ensureKeysExist().toBytes())
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

    private fun setupViewByStatus()
    {
        binding.tvFriendName.text =
            if (thisFriend.name.length <= 10) thisFriend.name
            else thisFriend.name.substring(0, 8) + "..."

        binding.profilePicture.text = thisFriend.name.substring(0, 1)

        binding.sendViaSerial.visibility =
            if ((thisFriend.status == FriendStatus.Verified || thisFriend.status == FriendStatus.Approved) && viewModel.serialConnection != null) View.VISIBLE
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
                binding.btnReceiveRadio.isVisible = viewModel.usbAudioConnection.value != null
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
                binding.btnReceiveRadio.isVisible = viewModel.usbAudioConnection.value != null
                binding.btnResendInvite.isVisible = false
                binding.sendMessageContainer.isVisible = true
            }
        }

        updateReceiveButtonVisibility()
    }

    fun inviteClicked()
    {
        // Get user's public key to send to contact
        val userPublicKey = Encryption().ensureKeysExist()
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
        builder.setNeutralButton(resources.getString(R.string.stop_button)) { dialog, _->
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
            val connection: SerialConnection? = viewModel.serialConnection

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
                            14095600, // 20m WSPR calling frequency
                            false
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
                    for(frequencyArray in frequencyArrays)
                    {
                        Timber.d("Waiting until even minute...")
                        Thread.sleep(millisUntilEvenMinute())
                        Timber.d("It's go time!")

                        var first = true

                        for(frequency in frequencyArray)
                        {
                            val ionFrequency = Word.make(frequency.toInt(), NounType.INTEGER.value)
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
                    withContext(Dispatchers.Main) { showAlert("Failed to write to Serial device.") }
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

    fun millisUntilEvenMinute(): Long {
        val now = LocalDateTime.now()
        val nextEvenMinute = now
            .plusMinutes(1)
            .withSecond(0)
            .withNano(0)

        return ChronoUnit.MILLIS.between(now, nextEvenMinute)
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

            Timber.d("Data: ${data.size} bytes, $bitsNeeded bits → estimated $estimatedMessages WSPR messages")

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

    private fun pickImageFromGallery(saveImage: Boolean)
    {
        val request = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        if (saveImage) pickImageForSavingLauncher.launch(request)
        else pickImageForSharingLauncher.launch(request)
    }

    private fun handleImageImport()
    {
        val request = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        pickImageForImportLauncher.launch(request)
    }

    @ExperimentalUnsignedTypes
    private fun handlePickedImageForEncoding(imageUri: Uri, saveImage: Boolean)
    {
        // We can only share/save an image if a recipient with a public key has been selected
        thisFriend.publicKeyEncoded?.let { publicKey ->
            val message = binding.messageEditText.text.toString()
            binding.imageImportProgressBar.visibility = View.VISIBLE
            shareOrSaveAsImage(imageUri, message, publicKey, saveImage)
            binding.messageEditText.text?.clear()
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

    fun changeFriendsName(newName: String)
    {
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
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).apply {
            hideSoftInputFromWindow(editText.windowToken, 0)
        }
    }

    override fun onDestroy()
    {
        super.onDestroy()

        stopButtonAnimation()

        try
        {
            unregisterReceiver(usbReceiver)
        }
        catch (e: Exception)
        {
            // Receiver wasn't registered
        }

        parentJob.cancel()
    }
}
