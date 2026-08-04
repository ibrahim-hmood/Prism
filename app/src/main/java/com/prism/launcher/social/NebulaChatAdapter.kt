package com.prism.launcher.social

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.prism.launcher.databinding.ItemSocialMessageIncomingBinding
import com.prism.launcher.databinding.ItemSocialMessageOutgoingBinding
import java.text.SimpleDateFormat
import java.util.Locale

class NebulaChatAdapter : ListAdapter<SocialMessageEntity, RecyclerView.ViewHolder>(DiffCallback()) {

    private val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())

    /** Display name used for the "<name> is thinking..." placeholder row, set by the caller. */
    var thinkingBotName: String = "Bot"

    companion object {
        private const val TYPE_INCOMING = 0
        private const val TYPE_OUTGOING = 1

        /** Sentinel id for the pre-first-token "is thinking..." animated placeholder row. */
        const val THINKING_ID = -1L

        /** Sentinel id for a live-streaming row with real content (reasoning or answer) — plain text, not animated. */
        const val LIVE_ID = -2L
    }

    override fun getItemViewType(position: Int): Int {
        return if (getItem(position).senderId == "user") TYPE_OUTGOING else TYPE_INCOMING
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_OUTGOING) {
            OutgoingVH(ItemSocialMessageOutgoingBinding.inflate(inflater, parent, false))
        } else {
            IncomingVH(ItemSocialMessageIncomingBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val msg = getItem(position)
        if (holder is OutgoingVH) {
            holder.binding.messageText.text = msg.content
            holder.binding.messageTime.text = timeFormat.format(msg.timestamp)
        } else if (holder is IncomingVH) {
            if (msg.id == THINKING_ID) {
                com.prism.launcher.messaging.ThinkingTextAnimator.start(holder.binding.messageText, thinkingBotName)
            } else {
                com.prism.launcher.messaging.ThinkingTextAnimator.stop(holder.binding.messageText)
                holder.binding.messageText.text = msg.content
            }
            holder.binding.messageTime.text = timeFormat.format(msg.timestamp)
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        super.onViewRecycled(holder)
        if (holder is IncomingVH) {
            com.prism.launcher.messaging.ThinkingTextAnimator.stop(holder.binding.messageText)
        }
    }

    class IncomingVH(val binding: ItemSocialMessageIncomingBinding) : RecyclerView.ViewHolder(binding.root)
    class OutgoingVH(val binding: ItemSocialMessageOutgoingBinding) : RecyclerView.ViewHolder(binding.root)

    class DiffCallback : DiffUtil.ItemCallback<SocialMessageEntity>() {
        override fun areItemsTheSame(oldItem: SocialMessageEntity, newItem: SocialMessageEntity) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: SocialMessageEntity, newItem: SocialMessageEntity) = oldItem == newItem
    }
}
