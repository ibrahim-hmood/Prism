package com.prism.launcher.social

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.prism.launcher.databinding.ItemSocialChatListBinding

class NebulaRecentChatsAdapter(
    private val onChatClick: (String) -> Unit
) : ListAdapter<SocialMessageEntity, NebulaRecentChatsAdapter.VH>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemSocialChatListBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val chat = getItem(position)
        // Note: For recent chats, we group by chatId. chatId is the botId.
        // In a real implementation, we'd fetch the bot name from the DB.
        holder.binding.chatName.text = "Chat with " + chat.chatId
        holder.binding.chatLastMessage.text = chat.content
        
        holder.itemView.setOnClickListener { onChatClick(chat.chatId) }
    }

    class VH(val binding: ItemSocialChatListBinding) : RecyclerView.ViewHolder(binding.root)

    class DiffCallback : DiffUtil.ItemCallback<SocialMessageEntity>() {
        override fun areItemsTheSame(oldItem: SocialMessageEntity, newItem: SocialMessageEntity) = oldItem.chatId == newItem.chatId
        override fun areContentsTheSame(oldItem: SocialMessageEntity, newItem: SocialMessageEntity) = oldItem == newItem
    }
}
