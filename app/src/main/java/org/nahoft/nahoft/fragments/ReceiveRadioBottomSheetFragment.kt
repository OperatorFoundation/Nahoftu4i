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
import org.nahoft.nahoft.models.WSPRSpotItem
import org.nahoft.nahoft.viewmodels.FriendInfoViewModel
import org.nahoft.nahoft.viewmodels.ReceiveSessionState
import org.operatorfoundation.audiocoder.models.WSPRCycleInformation
import org.operatorfoundation.audiocoder.models.WSPRStationState
import timber.log.Timber

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

        // Start session if not already running
        if (!viewModel.isSessionActive())
        {
            val friend = viewModel.friend.value
            if (friend?.publicKeyEncoded != null) {
                viewModel.startReceiveSession()
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
            viewModel.audioLevel.collect { level ->
                updateAudioLevel(level)
            }
        }

        // Observe message received flag
        uiScope.launch {
            viewModel.messageJustReceived.collect { received ->
                if (received) {
                    showMessageReceivedCelebration()
                    viewModel.clearMessageReceivedFlag()
                }
            }
        }
    }

    /**
     * Updates UI based on session state.
     */
    private fun updateSessionStateUI(state: ReceiveSessionState)
    {
        if (_binding == null) return

        when (state) {
            is ReceiveSessionState.Idle -> {
                updateStatus(getString(R.string.status_waiting))
            }
            is ReceiveSessionState.WaitingForWindow -> {
                updateStatus(getString(R.string.waiting_for_next_window))
                updateStateIcon(R.drawable.ic_access_time, R.color.coolGrey, AnimationType.NONE)
            }
            is ReceiveSessionState.Running -> {
                // Station state observer will handle the specific status
            }
            is ReceiveSessionState.Stopped -> {
                updateStatus(getString(R.string.session_stopped))
                updateStateIcon(R.drawable.ic_sync, R.color.coolGrey, AnimationType.NONE)
            }
        }

        updateStatusCard()
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
     * Updates the Status card based on current state.
     */
    private fun updateStatusCard()
    {
        if (_binding == null || !isAdded) return

        val context = requireContext()
        val decryptionAttempts = viewModel.getDecryptionAttempts()
        val messageReceived = viewModel.messageJustReceived.value

        when {
            messageReceived -> {
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
            }
            decryptionAttempts > 0 -> {
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

        val originalColor = ContextCompat.getColor(requireContext(), R.color.lightGrey)
        val successColor = ContextCompat.getColor(requireContext(), R.color.caribbeanGreen)

        val colorAnim = android.animation.ValueAnimator.ofArgb(successColor, originalColor).apply {
            duration = 1000
            addUpdateListener { animator ->
                _binding?.cardStatus?.setCardBackgroundColor(animator.animatedValue as Int)
            }
        }
        colorAnim.start()

        val scaleX = ObjectAnimator.ofFloat(binding.ivStatusIcon, "scaleX", 1f, 1.5f, 1f).apply {
            duration = 500
        }
        val scaleY = ObjectAnimator.ofFloat(binding.ivStatusIcon, "scaleY", 1f, 1.5f, 1f).apply {
            duration = 500
        }
        scaleX.start()
        scaleY.start()

        updateStatus(getString(R.string.message_received))
        updateStateIcon(R.drawable.ic_success, R.color.caribbeanGreen, AnimationType.SCALE)
        updateStatusCard()
    }

    /**
     * Updates the cycle progress UI.
     */
    private fun updateCycleProgress(cycleInfo: WSPRCycleInformation)
    {
        if (_binding == null || !isAdded) return

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
     * Updates spots-related UI elements.
     */
    private fun updateSpotsUI(spots: List<WSPRSpotItem>)
    {
        if (_binding == null) return

        binding.tvSpotsCount.text = spots.size.toString()
        binding.tvMessagesReceived.text = viewModel.receivedMessageCount.toString()

        // Update dialog if open
        spotsDialog?.updateSpots(spots)
    }

    /**
     * Updates audio level display.
     */
    private fun updateAudioLevel(level: Float)
    {
        if (_binding == null) return

        val percent = (level * 100).toInt()
        binding.progressAudio.progress = percent
        binding.tvAudioLevelPercent.text = "$percent%"
    }

    private fun showSpotsDialog()
    {
        spotsDialog = WSPRSpotsDialogFragment.newInstance().also { dialog ->
            dialog.updateSpots(viewModel.receivedSpots.value)
            dialog.show(childFragmentManager, "WSPRSpotsDialog")
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