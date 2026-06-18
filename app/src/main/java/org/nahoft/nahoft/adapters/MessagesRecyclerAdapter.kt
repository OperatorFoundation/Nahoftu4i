package org.nahoft.nahoft.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.nahoft.nahoft.R
import org.nahoft.nahoft.databinding.MessageItemRowBinding
import org.nahoft.nahoft.models.Message

/**
 * Displays a conversation's [Message]s.
 *
 * Each row's content is resolved off the main thread, one coroutine per bound row,
 * because decryption performs blocking work that must not run on the UI thread.
 * Updates go through [submitList] and are diffed on a background thread, so rows
 * that have not changed are not rebound and therefore not re-decrypted.
 *
 * @param scope          Lifecycle-scoped scope (pass viewLifecycleOwner.lifecycleScope).
 *                       Every decrypt job is a child of this scope and is cancelled
 *                       automatically when the host view is destroyed.
 * @param resolveContent Returns the display text for a message, or null when content
 *                       cannot be produced (locked, missing key, or failure). Invoked
 *                       on Dispatchers.IO. Injected so the adapter holds no crypto
 *                       dependency.
 */
class MessagesRecyclerAdapter(
    private val scope: CoroutineScope,
    private val resolveContent: suspend (Message) -> String?
) : ListAdapter<Message, MessagesRecyclerAdapter.MessageViewHolder>(DIFF_CALLBACK)
{
    var onItemLongClick: ((Message) -> Unit)? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder
    {
        val binding = MessageItemRowBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return MessageViewHolder(binding)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int)
    {
        holder.bind(getItem(position))
    }

    override fun onViewRecycled(holder: MessageViewHolder)
    {
        super.onViewRecycled(holder)
        holder.cancelPendingDecrypt()
    }

    inner class MessageViewHolder(private val binding: MessageItemRowBinding) :
        RecyclerView.ViewHolder(binding.root)
    {
        private var boundMessage: Message? = null
        private var decryptJob: Job? = null

        init {
            binding.root.setOnLongClickListener {
                boundMessage?.let { onItemLongClick?.invoke(it) }
                true
            }
        }

        fun bind(message: Message)
        {
            boundMessage = message
            cancelPendingDecrypt()

            binding.dateTextView.text = message.getDateStringForDetail()

            // Every recycled property is set in both branches; a recycled holder
            // may carry layout direction, background, or sender initial from a
            // message of the opposite direction.
            if (message.fromMe)
            {
                binding.root.layoutDirection = View.LAYOUT_DIRECTION_RTL
                binding.statusImageView.text = "Me"
                binding.messageTextView.setBackgroundResource(
                    R.drawable.transparent_overlay_message
                )
            }
            else
            {
                binding.root.layoutDirection = View.LAYOUT_DIRECTION_LTR
                binding.statusImageView.text = message.sender?.name?.take(1)
                binding.messageTextView.setBackgroundResource(
                    R.drawable.transparent_overlay_message_white
                )
            }

            // Clear stale text so a recycled row does not flash the previous
            // message's content while this row's content is being resolved.
            binding.messageTextView.text = ""

            decryptJob = scope.launch {
                val content = withContext(Dispatchers.IO) { resolveContent(message) }

                // Apply only if the holder is still bound to the same message, in
                // case the blocking work finished before the cancel on rebind.
                if (boundMessage == message)
                {
                    binding.messageTextView.text = content ?: FALLBACK_TEXT
                }
            }
        }

        fun cancelPendingDecrypt()
        {
            decryptJob?.cancel()
            decryptJob = null
        }
    }

    companion object
    {
        private const val FALLBACK_TEXT = "Unable to decrypt message"

        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<Message>()
        {
            // Ciphertext is unique per message (each carries a distinct libsodium
            // nonce), so it serves as a stable identity.
            override fun areItemsTheSame(oldItem: Message, newItem: Message): Boolean =
                oldItem.cipherText.contentEquals(newItem.cipherText)

            // Messages are immutable once saved, so identity implies content equality.
            override fun areContentsTheSame(oldItem: Message, newItem: Message): Boolean =
                oldItem.cipherText.contentEquals(newItem.cipherText)
        }
    }
}