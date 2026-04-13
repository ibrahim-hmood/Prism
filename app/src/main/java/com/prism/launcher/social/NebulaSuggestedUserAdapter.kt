package com.prism.launcher.social

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.prism.launcher.databinding.ItemSocialSuggestedUserBinding

class NebulaSuggestedUserAdapter(
    private val onProfileClick: (SocialBotEntity) -> Unit,
    private val onFollowClick: (SocialBotEntity) -> Unit
) : ListAdapter<SocialBotEntity, NebulaSuggestedUserAdapter.VH>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemSocialSuggestedUserBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val bot = getItem(position)
        holder.binding.suggestedName.text = bot.name
        holder.binding.suggestedHandle.text = bot.handle
        
        // In a real app, use Glide/Coil for avatarUrl
        // holder.binding.suggestedAvatar.load(bot.avatarUrl)

        holder.itemView.setOnClickListener { onProfileClick(bot) }
        holder.binding.followBtn.setOnClickListener { onFollowClick(bot) }
    }

    class VH(val binding: ItemSocialSuggestedUserBinding) : RecyclerView.ViewHolder(binding.root)

    class DiffCallback : DiffUtil.ItemCallback<SocialBotEntity>() {
        override fun areItemsTheSame(oldItem: SocialBotEntity, newItem: SocialBotEntity) = oldItem.botId == newItem.botId
        override fun areContentsTheSame(oldItem: SocialBotEntity, newItem: SocialBotEntity) = oldItem == newItem
    }
}
