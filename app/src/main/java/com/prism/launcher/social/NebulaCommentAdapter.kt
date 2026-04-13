package com.prism.launcher.social

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.prism.launcher.databinding.ItemSocialCommentBinding

class NebulaCommentAdapter(
    private val onProfileClick: (String) -> Unit
) : ListAdapter<SocialCommentEntity, NebulaCommentAdapter.VH>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemSocialCommentBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val comment = getItem(position)
        holder.binding.commentAuthorName.text = comment.authorName
        holder.binding.commentContent.text = comment.content
        
        holder.binding.commentAvatar.setOnClickListener { onProfileClick(comment.authorId) }
    }

    class VH(val binding: ItemSocialCommentBinding) : RecyclerView.ViewHolder(binding.root)

    class DiffCallback : DiffUtil.ItemCallback<SocialCommentEntity>() {
        override fun areItemsTheSame(oldItem: SocialCommentEntity, newItem: SocialCommentEntity) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: SocialCommentEntity, newItem: SocialCommentEntity) = oldItem == newItem
    }
}
