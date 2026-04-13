package com.prism.launcher.social

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.prism.launcher.databinding.ItemSocialMessageIncomingBinding
import com.prism.launcher.databinding.ItemSocialMessageOutgoingBinding

class NebulaChatAdapter : ListAdapter<SocialMessageEntity, RecyclerView.ViewHolder>(DiffCallback()) {

    companion object {
        private const val TYPE_INCOMING = 0
        private const val TYPE_OUTGOING = 1
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
        } else if (holder is IncomingVH) {
            holder.binding.messageText.text = msg.content
        }
    }

    class IncomingVH(val binding: ItemSocialMessageIncomingBinding) : RecyclerView.ViewHolder(binding.root)
    class OutgoingVH(val binding: ItemSocialMessageOutgoingBinding) : RecyclerView.ViewHolder(binding.root)

    class DiffCallback : DiffUtil.ItemCallback<SocialMessageEntity>() {
        override fun areItemsTheSame(oldItem: SocialMessageEntity, newItem: SocialMessageEntity) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: SocialMessageEntity, newItem: SocialMessageEntity) = oldItem == newItem
    }
}
