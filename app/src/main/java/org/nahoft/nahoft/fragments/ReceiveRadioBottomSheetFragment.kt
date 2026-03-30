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
import androidx.fragment.app.activityViewModels
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.nahoft.nahoft.R
import org.nahoft.nahoft.databinding.FragmentBottomSheetReceiveRadioBinding
import org.nahoft.nahoft.models.DecryptedMessageRecord
import org.nahoft.nahoft.models.WSPRSpotItem
import org.nahoft.nahoft.services.ReceiveSessionService
import org.nahoft.nahoft.services.ReceiveSessionState
import org.nahoft.nahoft.viewmodels.FriendInfoViewModel
import org.operatorfoundation.audiocoder.models.WSPRCycleInformation
import org.operatorfoundation.audiocoder.models.WSPRStationState
import org.operatorfoundation.signalbridge.models.AudioLevelInfo

/**
 * BottomSheet for receiving encrypted messages via WSPR radio.
 */
class ReceiveRadioBottomSheetFragment : BottomSheetDialogFragment()
{
    private var _binding: FragmentBottomSheetReceiveRadioBinding? = null
    private val binding get() = _binding!!
    private val viewModel: FriendInfoViewModel by activityViewModels()

    // Coroutine scope for UI updates only
    private val uiScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Animation tracking
    private var currentAnimator: ObjectAnimator? = null

    // Spots dialog reference
    private var spotsDialog: WSPRSpotsDialogFragment? = null

    // Job for elapsed time updates
    private var elapsedTimeJob: Job? = null

    /**
     * Animation types for state icon
     */
    private enum class AnimationType {
        NONE,
        PULSE,      // For listening states
        ROTATE,     // For processing
        SCALE       // For success
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
        observeViewModel()
        startElapsedTimeUpdates()

        binding.tvNextWindow.text = ""

        (dialog as? BottomSheetDialog)?.behavior?.apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
            isHideable = true
        }

        when
        {
            viewModel.isSessionActive() -> {
                // Session already running — show live state
                showFrequencyReadOnly()
            }

            viewModel.isEdenConnected.value -> {
                // Eden connected — show frequency input, user confirms before starting
                showFrequencyInput()
            }

            else -> {
                // No Eden — start immediately, no frequency input needed
                viewModel.startReceiveSession()
                showFrequencyReadOnly()
            }
        }
    }

    private fun setupClickListeners()
    {
        // Hide button (keeps session running)
        binding.btnClose.setOnClickListener {
            dismissAllowingStateLoss()
        }

        // Stop button (ends session)
        binding.btnStop.setOnClickListener {
            viewModel.stopReceiveSession()
            viewModel.resetSession()
            dismissAllowingStateLoss()
        }

        // Spots card opens dialog
        binding.cardSpots.setOnClickListener {
            showSpotsDialog()
        }

        binding.cardMessages.setOnClickListener {
            showReceivedMessagesDialog()
        }

        // Pre-fill frequency input from saved preference
        binding.etRxFrequency.setText(viewModel.getRxFrequencyKHz().toString())

        binding.btnRxFreqMinus.setOnClickListener {
            val current = binding.etRxFrequency.text.toString().toIntOrNull()
                ?: viewModel.getRxFrequencyKHz()
            binding.etRxFrequency.setText((current - 1).toString())
        }

        binding.btnRxFreqPlus.setOnClickListener {
            val current = binding.etRxFrequency.text.toString().toIntOrNull()
                ?: viewModel.getRxFrequencyKHz()
            binding.etRxFrequency.setText((current + 1).toString())
        }
    }

    /**
     * Observes ViewModel state flows and updates UI accordingly.
     */
    private fun observeViewModel()
    {
        // Observe session state
        uiScope.launch {
            viewModel.receiveSessionState.collect { state ->
                updateSessionStateUI(state)
            }
        }

        // Observe station state for status icon/animation
        uiScope.launch {
            viewModel.stationState.collect { state ->
                state?.let { updateStationStateUI(it) }
            }
        }

        // Observe cycle information for progress
        uiScope.launch {
            viewModel.cycleInformation.collect { info ->
                info?.let { updateCycleProgress(it) }
            }
        }

        // Observe spots
        uiScope.launch {
            viewModel.receivedSpots.collect { spots ->
                updateSpotsUI(spots)
            }
        }

        // Observe audio level
        uiScope.launch {
            viewModel.audioLevel.collect { info ->
                info?.let { updateAudioLevel(it) }
            }
        }

        // Observe message received flag
        uiScope.launch {
            viewModel.messageJustReceived.collect { received ->
                if (received) {
                    showMessageReceivedCelebration()
                    viewModel.clearMessageReceivedFlag()
                    updateSpotsUI(viewModel.receivedSpots.value)
                }
            }
        }

        uiScope.launch {
            viewModel.decryptedMessageRecords.collect { records ->
                updateMessagesCard(records)
            }
        }

        // Observe service connection for loading state
        uiScope.launch {
            viewModel.serviceConnected.collect { connected ->
                if (_binding == null) return@collect

                if (!connected && viewModel.isSessionActive())
                {
                    // Service running but not yet bound - show connecting state
                    updateStatus("Connecting to session...")
                }
            }
        }

        // Enable/disable Start button and status text — pre-session only
        uiScope.launch {
            viewModel.usbAudioAvailable.collect { available ->

                // Use the StateFlow value directly
                if (viewModel.receiveSessionState.value == ReceiveSessionState.Idle)
                {
                    binding.btnStop.isEnabled = available
                    updateStatus(
                        if (available) getString(R.string.tap_start_to_listen)
                        else getString(R.string.usb_audio_not_connected)
                    )
                }
            }
        }

        // Frequency section visibility — Eden connection controls this pre-session
        uiScope.launch {
            viewModel.isEdenConnected.collect { edenConnected ->
                if (viewModel.receiveSessionState.value != ReceiveSessionState.Idle) return@collect
                binding.rxFrequencySection.visibility =
                    if (edenConnected) View.VISIBLE else View.GONE
            }
        }
    }

    /**
     * Updates UI based on session state.
     */
    private fun updateSessionStateUI(state: ReceiveSessionState)
    {
        if (_binding == null) return

        when (state)
        {
            is ReceiveSessionState.Idle -> {
                updateStatus(getString(R.string.status_waiting))
                binding.vuMeter.reset()
            }

            is ReceiveSessionState.WaitingForWindow -> {
                showFrequencyReadOnly()
                updateStatus(getString(R.string.waiting_for_next_window))
                updateStateIcon(R.drawable.ic_access_time, R.color.coolGrey, AnimationType.NONE)
            }

            is ReceiveSessionState.Running -> {
                showFrequencyReadOnly()

                // Station state observer will override icon/status once it emits
                updateStateIcon(R.drawable.ic_radio, R.color.coolGrey, AnimationType.PULSE)
                updateStatus(getString(R.string.listening_for_signals))
            }

            is ReceiveSessionState.Stopped -> {
                updateStatus(getString(R.string.session_stopped))
                updateStateIcon(R.drawable.ic_sync, R.color.coolGrey, AnimationType.NONE)
                binding.vuMeter.reset()
                showFrequencyInput() // resets button and shows frequency stepper
            }

            is ReceiveSessionState.TimedOut -> {
                updateStatus(getString(R.string.session_timed_out))
                updateStateIcon(R.drawable.ic_access_time, R.color.tangerine, AnimationType.NONE)
            }
        }
    }

    /**
     * Updates UI based on WSPR station state.
     */
    private fun updateStationStateUI(state: WSPRStationState)
    {
        if (_binding == null || !isAdded) return

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
     * Shows visual feedback when a message is successfully received.
     */
    private fun showMessageReceivedCelebration()
    {
        if (_binding == null || !isAdded) return

        updateStatus(getString(R.string.message_received))
        updateStateIcon(R.drawable.ic_success, R.color.caribbeanGreen, AnimationType.SCALE)
    }

    /**
     * Updates the cycle progress UI.
     */
    private fun updateCycleProgress(cycleInfo: WSPRCycleInformation)
    {
        if (_binding == null || !isAdded) return

        binding.progressCycle.progress = cycleInfo.cyclePositionSeconds
        binding.tvCycleTime.text = getString(R.string.cycle_time_format, cycleInfo.cyclePositionSeconds, 120)

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
        if (_binding != null && isAdded) {
            binding.tvStatus.text = message
        }
    }

    /**
     * Starts updating elapsed time display.
     */
    private fun startElapsedTimeUpdates()
    {
        elapsedTimeJob = uiScope.launch {
            while (isActive) {
                val elapsedMs = viewModel.getSessionElapsedMs()
                val minutes = (elapsedMs / 60000).toInt()
                val seconds = ((elapsedMs % 60000) / 1000).toInt()

                if (_binding != null) {
                    binding.tvElapsedTime.text = String.format("%d:%02d", minutes, seconds)
                }

                delay(1000)
            }
        }
    }

    /**
     * Updates the WSPR spots card with the current Nahoft candidate count
     * toward the decryption threshold, and refreshes the spots dialog if open.
     */
    private fun updateSpotsUI(spots: List<WSPRSpotItem>)
    {
        if (_binding == null) return

        val total = spots.size
        val threshold = ReceiveSessionService.MIN_SPOTS_FOR_DECRYPTION
        binding.tvSpotsCount.text = if (total >= threshold)
            "$total / $threshold+"
        else
            "$total / $threshold"

        spotsDialog?.updateSpots(spots)
    }

    /**
     * Updates the middle card with the count of fully decrypted messages
     * received during this session.
     */
    private fun updateMessagesCard(records: List<DecryptedMessageRecord>)
    {
        if (_binding == null) return
        binding.tvMessagesReceived.text = records.size.toString()
        binding.tvMessagesLabel.text = getString(R.string.messages_this_session)
    }

    private fun showReceivedMessagesDialog()
    {
        ReceivedMessagesDialogFragment()
            .show(childFragmentManager, "ReceivedMessagesDialog")
    }

    /**
     * Updates audio level display.
     */
    private fun updateAudioLevel(info: AudioLevelInfo)
    {
        if (_binding == null) return
        binding.vuMeter.update(info)
    }

    private fun showSpotsDialog()
    {
        spotsDialog = WSPRSpotsDialogFragment.newInstance().also { dialog ->
            dialog.updateSpots(viewModel.receivedSpots.value)
            dialog.show(childFragmentManager, "WSPRSpotsDialog")
        }
    }

    /**
     * Shows the frequency input section and wires the Start button.
     * The session does not start until the user confirms the frequency.
     */
    private fun showFrequencyInput()
    {
        binding.rxFrequencySection.visibility = if (viewModel.isEdenConnected.value) View.VISIBLE else View.GONE

        // Hide the close button — there is no running session to close yet
        binding.btnClose.visibility = View.GONE

        // Start is the only action available at this point
        binding.btnStop.text = getString(R.string.start_session)

        // Check current USB state immediately — the flow may not re-emit
        val usbAvailable = viewModel.usbAudioAvailable.value
        binding.btnStop.isEnabled = usbAvailable
        updateStatus(
            if (usbAvailable) getString(R.string.tap_start_to_listen)
            else getString(R.string.usb_audio_not_connected)
        )

        binding.btnStop.setOnClickListener {
            val freqKHz = binding.etRxFrequency.text.toString().toIntOrNull()
                ?: viewModel.getRxFrequencyKHz()

            viewModel.saveRxFrequencyKHz(freqKHz)
            viewModel.startReceiveSession()
            showFrequencyReadOnly()
        }
    }

    /**
     * Shows the frequency as a read-only label during an active session.
     * Saved preference reflects what the session is tuned to.
     */
    private fun showFrequencyReadOnly()
    {
        binding.rxFrequencySection.visibility = if (viewModel.isEdenConnected.value) View.VISIBLE else View.GONE
        binding.etRxFrequency.isEnabled = false
        binding.btnRxFreqMinus.isEnabled = false
        binding.btnRxFreqPlus.isEnabled = false

        binding.btnClose.visibility = View.VISIBLE
        binding.btnStop.isEnabled = true
        binding.btnStop.text = getString(R.string.stop_session)
        binding.btnStop.setOnClickListener {
            viewModel.stopReceiveSession()
            viewModel.resetSession()
            dismissAllowingStateLoss()
        }
    }

    override fun onDestroyView()
    {
        super.onDestroyView()

        currentAnimator?.cancel()
        elapsedTimeJob?.cancel()
        uiScope.cancel()

        spotsDialog = null
        _binding = null
    }
}