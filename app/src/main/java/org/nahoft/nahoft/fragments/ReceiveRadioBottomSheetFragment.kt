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
import org.nahoft.nahoft.models.FailureReason
import org.nahoft.nahoft.models.NahoftSpotStatus
import org.nahoft.nahoft.models.WSPRSpotItem
import org.operatorfoundation.audiocoder.WSPRStation
import org.operatorfoundation.audiocoder.WSPRTimingCoordinator
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
import kotlin.coroutines.coroutineContext

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
    private var startTimeMs = 0L
    private val allSpots = mutableListOf<WSPRSpotItem>()
    private var currentNahoftGroupId = 0
    private var spotsDialog: WSPRSpotsDialogFragment? = null
    private var isWaitingForFreshWindow = false
    private var currentAnimator: ObjectAnimator? = null
    private var decryptionAttempts = 0
    private var messageJustReceived = false

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
    companion object
    {
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

    override fun onViewCreated(view: View, savedInstanceState: Bundle?)
    {
        super.onViewCreated(view, savedInstanceState)

        isCancelable = false

        setupClickListeners()
        startReceiving()
    }

    private fun setupClickListeners()
    {
        binding.btnClose.setOnClickListener { cancelAndDismiss() }
        binding.btnStop.setOnClickListener { cancelAndDismiss() }
        binding.cardSpots.setOnClickListener { showSpotsDialog() }
    }

    /**
     * Updates the Status card based on current decryption state.
     */
    private fun updateStatusCard()
    {
        if (_binding == null) return

        val context = requireContext()

        when {
            messageJustReceived -> {
                // Success state
                binding.ivStatusIcon.setImageResource(R.drawable.ic_success)
                binding.ivStatusIcon.setColorFilter(
                    ContextCompat.getColor(context, R.color.caribbeanGreen),
                    PorterDuff.Mode.SRC_IN
                )
                binding.tvStatusTitle.text = getString(R.string.status)
                binding.tvStatusSubtitle.text = getString(R.string.status_received)
                binding.tvStatusSubtitle.setTextColor(
                    ContextCompat.getColor(context, R.color.caribbeanGreen)
                )
                binding.cardStatus.setCardBackgroundColor(
                    ContextCompat.getColor(context, R.color.lightGrey)
                )
            }
            decryptionAttempts > 0 -> {
                // Tried but incomplete
                binding.ivStatusIcon.setImageResource(R.drawable.ic_sync)
                binding.ivStatusIcon.setColorFilter(
                    ContextCompat.getColor(context, R.color.tangerine),
                    PorterDuff.Mode.SRC_IN
                )
                binding.tvStatusTitle.text = getString(R.string.status)
                binding.tvStatusSubtitle.text = getString(R.string.status_incomplete)
                binding.tvStatusSubtitle.setTextColor(
                    ContextCompat.getColor(context, R.color.coolGrey)
                )
            }
            else -> {
                // Waiting for parts
                binding.ivStatusIcon.setImageResource(R.drawable.ic_sync)
                binding.ivStatusIcon.setColorFilter(
                    ContextCompat.getColor(context, R.color.coolGrey),
                    PorterDuff.Mode.SRC_IN
                )
                binding.tvStatusTitle.text = getString(R.string.status)
                binding.tvStatusSubtitle.text = getString(R.string.status_waiting)
                binding.tvStatusSubtitle.setTextColor(
                    ContextCompat.getColor(context, R.color.coolGrey)
                )
            }
        }
    }

    /**
     * Starts the WSPR receive operation.
     * If entered mid-cycle, waits for the next fresh window before collecting.
     */
    private fun startReceiving()
    {
        val connection = usbAudioConnection
        if (connection == null)
        {
            updateStatus(getString(R.string.usb_audio_not_connected))
            return
        }

        startTimeMs = System.currentTimeMillis()
        startElapsedTimeUpdates()

        // Check if we're early enough in the cycle to start collecting
        val timingCoordinator = WSPRTimingCoordinator()

        if (timingCoordinator.isEarlyEnoughToStartCollection()) {
            // Good timing - start immediately
            isWaitingForFreshWindow = false
            startWSPRStation(connection)
        } else {
            // Entered mid-cycle - wait for next window
            isWaitingForFreshWindow = true
            updateStatus(getString(R.string.waiting_for_next_window))
            updateStateIcon(R.drawable.ic_access_time, R.color.coolGrey, AnimationType.NONE)

            // Start a coroutine to wait and then begin
            stationJob = coroutineScope.launch {
                waitForNextWindowAndStart(connection, timingCoordinator)
            }
        }
    }

    /**
     * Waits until the next WSPR window begins, then starts the station.
     */
    private suspend fun waitForNextWindowAndStart(
        connection: UsbAudioConnection,
        timingCoordinator: WSPRTimingCoordinator
    ) {
        // Update UI with countdown while waiting
        while (coroutineContext.isActive && isWaitingForFreshWindow) {
            val windowInfo = timingCoordinator.getTimeUntilNextDecodeWindow()
            val secondsRemaining = windowInfo.secondsUntilWindow

            if (secondsRemaining <= 0) {
                // Window is starting now
                isWaitingForFreshWindow = false
                withContext(Dispatchers.Main) {
                    startWSPRStation(connection)
                }
                return
            }

            withContext(Dispatchers.Main) {
                binding.tvNextWindow.text = getString(
                    R.string.next_decode_window_in,
                    secondsRemaining.toInt()
                )
            }

            delay(1000)
        }
    }

    /**
     * Creates and starts the WSPR station for audio collection.
     * Extracted from original startReceiving() to allow delayed start.
     */
    private fun startWSPRStation(connection: UsbAudioConnection)
    {
        stationJob = coroutineScope.launch {
            try
            {
                // Create audio source from USB connection
                audioSource = SignalBridgeWSPRAudioSource(
                    usbAudioConnection = connection,
                    bufferConfiguration = AudioBufferConfiguration.createDefault()
                )

                // Create WSPR station configuration
                val config = WSPRStationConfiguration.createDefault()
                wsprStation = WSPRStation(audioSource!!, config)

                // Start the station
                Timber.d("Starting WSPR station")
                val startResult = wsprStation!!.startStation()

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

            }
            catch (e: Exception)
            {
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

            Timber.d("NAHOFT-STATE: Received station state: ${state::class.simpleName}")

            when (state)
            {
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
            // Mark any pending spots as incomplete
            if (receivedMessages.isNotEmpty()) {
                markCurrentGroupAsFailed(FailureReason.INCOMPLETE)
            }

            val minutes = (TIMEOUT_MS / 60000).toInt()
            updateStatus(getString(R.string.receive_timeout, minutes))
            delay(2000)
            cancelAndDismiss()
        }
    }

    /**
     * Processes decode results from a WSPR decode cycle.
     *
     * - Adds ALL spots to allSpots list (with appropriate status)
     * - Filters Nahoft messages for decryption tracking
     * - Updates spots badge count
     */
    private fun processDecodeResults(results: List<WSPRDecodeResult>)
    {
        // Process each decode result
        for (result in results) {
            val isNahoftMessage = WSPRMessage.isEncodedMessage(result.callsign)
            val timestamp = System.currentTimeMillis()

            if (isNahoftMessage) {
                // Nahoft message - try to parse it
                try {
                    val message = WSPRMessage.fromWSPRFields(
                        result.callsign,
                        result.gridSquare,
                        result.powerLevelDbm
                    )

                    val isDuplicate = receivedMessages.any { existing ->
                        existing.toWSPRFields() == message.toWSPRFields()
                    }

                    if (!isDuplicate) {
                        receivedMessages.add(message)
                        Timber.d("Added new WSPR message: ${message.toWSPRFields()}")
                    }

                    // Determine part number within current group
                    val partNumber = receivedMessages.size  // 1-based after adding

                    // Create spot with Pending status (successfully parsed)
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
                    allSpots.add(0, spot)  // Add at beginning (newest first)

                }
                catch (e: Exception)
                {
                    Timber.w(e, "Failed to parse WSPR message: ${result.callsign}")

                    // Create spot with Failed status (parse error)
                    val spot = WSPRSpotItem(
                        callsign = result.callsign,
                        gridSquare = result.gridSquare,
                        powerDbm = result.powerLevelDbm,
                        snrDb = result.signalToNoiseRatioDb,
                        timestamp = timestamp,
                        nahoftStatus = NahoftSpotStatus.Failed(
                            groupId = currentNahoftGroupId,
                            partNumber = receivedMessages.size + 1,  // Would have been this part
                            reason = FailureReason.PARSE_ERROR
                        )
                    )
                    allSpots.add(0, spot)
                }
            }
            else
            {
                // Regular WSPR spot
                val spot = WSPRSpotItem(
                    callsign = result.callsign,
                    gridSquare = result.gridSquare,
                    powerDbm = result.powerLevelDbm,
                    snrDb = result.signalToNoiseRatioDb,
                    timestamp = timestamp,
                    nahoftStatus = NahoftSpotStatus.Spotted
                )
                allSpots.add(0, spot)  // Add at beginning (newest first)
            }
        }

        // Update UI
        updateSpotsCount()
        updateSpotsDialog()
        updateStatusCard()

        // Update message count
        binding.tvMessagesReceived.text = receivedMessages.size.toString()

        // Attempt decryption if we have at least 2 Nahoft messages
        if (receivedMessages.size >= 2) {
            attemptDecryption()
        }
    }

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

        if (friendKeyBytes == null)
        {
            Timber.w("No friend public key available for decryption")
            return
        }

        decryptionAttempts++
        updateStatus(getString(R.string.attempting_decrypt))

        try
        {
            val sequence = WSPRMessageSequence.fromMessages(receivedMessages.toList())
            val numericValue = sequence.decode()
            val encryptedBytes = bigIntegerToByteArray(numericValue)

            Timber.d("Attempting decryption of ${encryptedBytes.size} bytes from ${receivedMessages.size} WSPR messages")

            val friendPublicKey = PublicKey(friendKeyBytes)
            val decryptedText = Encryption().decrypt(friendPublicKey, encryptedBytes)

            Timber.i("Successfully decrypted message from ${receivedMessages.size} WSPR messages")

            // Mark success and show celebration
            messageJustReceived = true
            updateStatusCard()
            showMessageReceivedCelebration()

            // Mark spots as decrypted before clearing receivedMessages
            markCurrentGroupAsDecrypted(receivedMessages.size)

            // Clear for next message
            receivedMessages.clear()
            decryptionAttempts = 0

            coroutineScope.launch {
                delay(500)
                onMessageReceived?.invoke(encryptedBytes)
                dismiss()
            }
        }
        catch (e: SecurityException)
        {
            // Decryption failed - might need more messages, or might be wrong key/corrupted
            Timber.d("Decryption attempt failed (may need more messages): ${e.message}")
            updateStatus(getString(R.string.listening_for_signals))
            updateStatusCard()
            // Don't mark as failed yet - we might just need more parts
        }
        catch (e: Exception)
        {
            // Other error - likely a real failure
            Timber.w(e, "Error during decryption attempt")
            updateStatus(getString(R.string.listening_for_signals))
            updateStatusCard()
            // Don't mark as failed yet - could be transient
        }
    }

    /**
     * Shows visual feedback when a message is successfully received and decrypted.
     */
    private fun showMessageReceivedCelebration()
    {
        if (_binding == null) return

        // Flash the status card green
        val originalColor = ContextCompat.getColor(requireContext(), R.color.lightGrey)
        val successColor = ContextCompat.getColor(requireContext(), R.color.caribbeanGreen)

        // Animate card background
        val colorAnim = android.animation.ValueAnimator.ofArgb(successColor, originalColor).apply {
            duration = 1000
            addUpdateListener { animator ->
                binding.cardStatus.setCardBackgroundColor(animator.animatedValue as Int)
            }
        }
        colorAnim.start()

        // Scale animation on status icon
        val scaleX = ObjectAnimator.ofFloat(binding.ivStatusIcon, "scaleX", 1f, 1.5f, 1f).apply {
            duration = 500
        }
        val scaleY = ObjectAnimator.ofFloat(binding.ivStatusIcon, "scaleY", 1f, 1.5f, 1f).apply {
            duration = 500
        }
        scaleX.start()
        scaleY.start()

        // Update main status area too
        updateStatus(getString(R.string.message_received))
        updateStateIcon(R.drawable.ic_success, R.color.caribbeanGreen, AnimationType.SCALE)
    }

    /**
     * Converts a BigInteger to a byte array for decryption.
     *
     * Handles the sign byte that BigInteger.toByteArray() may prepend.
     */
    private fun bigIntegerToByteArray(value: BigInteger): ByteArray
    {
        val bytes = value.toByteArray()

        // If the first byte is 0x00 (sign byte for positive numbers), remove it
        return if (bytes.isNotEmpty() && bytes[0] == 0.toByte() && bytes.size > 1) {
            bytes.copyOfRange(1, bytes.size)
        }
        else { bytes }
    }

    /**
     * Updates the cycle progress UI.
     */
    private fun updateCycleProgress(cycleInfo: WSPRCycleInformation)
    {
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
    private fun updateStatus(message: String)
    {
        if (_binding != null) {
            binding.tvStatus.text = message
        }
    }

    /**
     * Starts updating elapsed time display.
     */
    private fun startElapsedTimeUpdates()
    {
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
     * Updates the spots badge count in the UI.
     */
    private fun updateSpotsCount()
    {
        if (_binding == null) return

        binding.tvSpotsCount.text = allSpots.size.toString()
    }

    /**
     * Shows the WSPR spots dialog.
     */
    private fun showSpotsDialog()
    {
        spotsDialog = WSPRSpotsDialogFragment.newInstance().also { dialog ->
            dialog.updateSpots(allSpots)
            dialog.show(childFragmentManager, "WSPRSpotsDialog")
        }
    }

    /**
     * Updates the spots dialog if it's currently visible.
     */
    private fun updateSpotsDialog()
    {
        spotsDialog?.updateSpots(allSpots)
    }

    /**
     * @param totalParts Total number of parts in the completed message
     */
    private fun markCurrentGroupAsDecrypted(totalParts: Int)
    {
        val updatedSpots = allSpots.map { spot ->
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
                    } else {
                        spot
                    }
                }
                else -> spot
            }
        }

        allSpots.clear()
        allSpots.addAll(updatedSpots)

        // Increment group ID for next message
        currentNahoftGroupId++

        // Update dialog if open
        updateSpotsDialog()
    }

    /**
     * @param reason The reason for failure
     */
    private fun markCurrentGroupAsFailed(reason: FailureReason)
    {
        val updatedSpots = allSpots.map { spot ->
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
                    else { spot }
                }
                else -> spot
            }
        }

        allSpots.clear()
        allSpots.addAll(updatedSpots)

        // Increment group ID for next message attempt
        currentNahoftGroupId++

        // Update dialog if open
        updateSpotsDialog()
    }

    /**
     * Cancels the receive operation and dismisses the sheet.
     * Marks any pending Nahoft spots as incomplete.
     */
    private fun cancelAndDismiss()
    {
        Timber.d("Receive operation cancelled")

        // Mark any pending spots as incomplete before cleanup
        if (receivedMessages.isNotEmpty())
        {
            markCurrentGroupAsFailed(FailureReason.INCOMPLETE)
        }

        stopReceiving()
        dismissAllowingStateLoss()
    }

    /**
     * Stops all receive operations and cleans up resources.
     */
    private fun stopReceiving()
    {
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

    override fun onDestroyView()
    {
        super.onDestroyView()
        stopReceiving()
        coroutineScope.cancel()
        _binding = null
    }
}