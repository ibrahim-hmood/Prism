package com.prism.launcher.social

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.prism.launcher.databinding.ItemSocialChatListBinding
import java.text.SimpleDateFormat
import java.util.Locale

class NebulaRecentChatsAdapter(
    private val onChatClick: (String) -> Unit
) : ListAdapter<SocialMessageEntity, NebulaRecentChatsAdapter.VH>(DiffCallback()) {

    /** Resolved bot names/handles keyed by botId, so rows can show a real name instead of the raw id. */
    private var botsById: Map<String, SocialBotEntity> = emptyMap()
    private val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())

    fun submitChats(chats: List<SocialMessageEntity>, bots: Map<String, SocialBotEntity>) {
        botsById = bots
        submitList(chats)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemSocialChatListBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val chat = getItem(position)
        val bot = botsById[chat.chatId]
        val name = bot?.name ?: chat.chatId

        holder.binding.chatName.text = name
        holder.binding.chatLastMessage.text = chat.content
        holder.binding.chatAvatarInitial.text = name.trim().firstOrNull()?.uppercase() ?: "?"
        holder.binding.chatTime.text = timeFormat.format(chat.timestamp)

        holder.itemView.setOnClickListener { onChatClick(chat.chatId) }
    }

    class VH(val binding: ItemSocialChatListBinding) : RecyclerView.ViewHolder(binding.root)

    class DiffCallback : DiffUtil.ItemCallback<SocialMessageEntity>() {
        override fun areItemsTheSame(oldItem: SocialMessageEntity, newItem: SocialMessageEntity) = oldItem.chatId == newItem.chatId
        override fun areContentsTheSame(oldItem: SocialMessageEntity, newItem: SocialMessageEntity) = oldItem == newItem
    }
}
