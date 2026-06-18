package org.nahoft.nahoft.fragments

import android.os.Bundle
import android.text.Layout
import android.text.SpannableString
import android.text.style.AlignmentSpan
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import org.libsodium.jni.keys.PublicKey
import org.nahoft.codex.Encryption
import org.nahoft.nahoft.*
import org.nahoft.nahoft.adapters.MessagesRecyclerAdapter
import org.nahoft.nahoft.databinding.FragmentVerifiedStatusBinding
import org.nahoft.nahoft.models.Friend
import org.nahoft.nahoft.models.Message
import timber.log.Timber

// the fragment initialization parameters
private const val FRIEND = "friend"

class VerifiedStatusFragment : Fragment()
{
    var _binding: FragmentVerifiedStatusBinding? = null
    val binding get() = _binding!!
    private var friend: Friend? = null
    private lateinit var filteredMessages: ArrayList<Message>

    private lateinit var linearLayoutManager: LinearLayoutManager
    private lateinit var adapter: MessagesRecyclerAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            friend = it.getSerializable(FRIEND) as Friend?
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View?
    {
        // Inflate the layout for this fragment
        _binding = FragmentVerifiedStatusBinding.inflate(layoutInflater)
        return binding.root
    }

    override fun onResume() {
        super.onResume()
        refreshMessages()
    }

    companion object {
        /**
         * Use this factory method to create a new instance of
         * this fragment using the provided parameters.
         *
         * @param friend Parameter 1.
         * @return A new instance of fragment VerifiedStatusFragment.
         */
        @JvmStatic
        fun newInstance(friend: Friend) =
            VerifiedStatusFragment().apply {
                arguments = Bundle().apply {
                    putSerializable(FRIEND, friend)
                }
            }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Setup the messages RecyclerView
        linearLayoutManager = LinearLayoutManager(context)
        filteredMessages = Persist.messageList.filter { message ->  message.sender == friend } as ArrayList<Message>

        if (filteredMessages.size > 0) binding.noDataLayout.isVisible = false

        // Hide the empty-state hints when the keyboard is open. The hints are corner-anchored
        // to the fragment's full-height ConstraintLayout, so when adjustResize shrinks the
        // frame they collide in the middle. Hiding them on IME visibility avoids the collision
        // and is appropriate UX — the user is actively typing, not reading first-run guidance.
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            val hasMessages = filteredMessages.isNotEmpty()

            // Only show hints when there are no messages AND the keyboard is closed.
            binding.noDataLayout.isVisible = !hasMessages && !imeVisible

            insets
        }
        ViewCompat.requestApplyInsets(view)

        adapter = MessagesRecyclerAdapter(
            scope = viewLifecycleOwner.lifecycleScope,
            resolveContent = ::resolveMessageContent
        )

        binding.messagesRecyclerView.layoutManager = linearLayoutManager
        binding.messagesRecyclerView.adapter = adapter
        adapter.onItemLongClick = { message ->
            showDeleteConfirmationDialog(message)
        }

        // Submit the initial list, then scroll to the newest message once the diff commits.
        // With async diffing, itemCount is 0 immediately after submitList, so the scroll
        // must run in the commit callback rather than inline.
        adapter.submitList(filteredMessages.toList()) {
            if (adapter.itemCount > 0) {
                binding.messagesRecyclerView.scrollToPosition(adapter.itemCount - 1)
            }
        }
    }

    private fun showDeleteConfirmationDialog(message: Message)
    {
        val builder: AlertDialog.Builder = AlertDialog.Builder(requireActivity(), R.style.AppTheme_DeleteAlertDialog)
        val title = SpannableString(getString(R.string.are_you_sure_to_delete))
        // alert dialog title align center
        title.setSpan(
            AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER),
            0,
            title.length,
            0
        )
        builder.setTitle(title)

        builder.setPositiveButton(resources.getString(R.string.button_label_delete))
        { _, _->
            //delete friend
            deleteMessage(message)
        }

        builder.setNeutralButton(resources.getString(R.string.button_label_cancel))
        { _, _ ->
            //cancel
        }
        builder.create()
        builder.show()
    }

    private fun deleteMessage(message: Message)
    {
        context?.let {
            Persist.deleteMessage(it, message)
            refreshMessages()
        }
    }

    /**
     * Re-reads this friend's messages from the store and submits a fresh list.
     * A new list instance is required for ListAdapter to diff against the old one,
     * so we never mutate the list already held by the adapter.
     */
    private fun refreshMessages()
    {
        if (_binding == null) return

        filteredMessages = Persist.messageList.filter { it.sender == friend } as ArrayList<Message>
        adapter.submitList(filteredMessages.toList())

        // Let the window-insets listener recompute empty-state visibility — it reads
        // filteredMessages live and accounts for keyboard (IME) state.
        ViewCompat.requestApplyInsets(binding.root)
    }

    /**
     * Produces the display text for a message row. The adapter invokes this on
     * Dispatchers.IO, so the blocking decrypt (key load + forced GC/finalization)
     * never runs on the UI thread. Returns null when content can't be produced —
     * app locked, missing sender key, or decrypt failure — and the adapter renders
     * a fallback in that case.
     */
    private suspend fun resolveMessageContent(message: Message): String?
    {
        // Unencrypted messages are stored as raw UTF-8 — no key needed.
        if (!message.isEncrypted) return String(message.cipherText, Charsets.UTF_8)

        val senderKeyBytes = message.sender?.publicKeyEncoded ?: return null

        return try
        {
            Encryption().decrypt(PublicKey(senderKeyBytes), message.cipherText)
        }
        catch (_: SecurityException)
        {
            // App is locked — content stays redacted.
            null
        }
        catch (e: Exception)
        {
            Timber.w(e, "Decryption failed for message row")
            null
        }
    }

    // Clean up binding reference when Fragment's view is destroyed
    // This is important to prevent memory leaks in Fragments
    override fun onDestroyView()
    {
        super.onDestroyView()
        _binding = null
    }
}