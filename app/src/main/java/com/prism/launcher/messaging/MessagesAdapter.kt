package com.prism.launcher.messaging

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.prism.launcher.databinding.ItemMessageReceivedBinding
import com.prism.launcher.databinding.ItemMessageSentBinding
import com.prism.launcher.NeonGlowDrawable

class MessagesAdapter(
    private var messages: List<MessageInfo>
) : RecyclerView.Adapter<MessagesAdapter.VH>() {

    companion object {
        const val TYPE_RECEIVED = 1
        const val TYPE_SENT = 2
    }

    fun update(newMessages: List<MessageInfo>) {
        messages = newMessages
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int {
        return if (messages[position].isSent) TYPE_SENT else TYPE_RECEIVED
    }

    override fun getItemCount(): Int = messages.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        return if (viewType == TYPE_SENT) {
            val b = ItemMessageSentBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            VH.Sent(b)
        } else {
            val b = ItemMessageReceivedBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            VH.Received(b)
        }
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val msg = messages[position]
        val context = holder.itemView.context
        val glowColor = com.prism.launcher.PrismSettings.getGlowColor(context)

        when (holder) {
            is VH.Sent -> {
                holder.binding.messageText.text = msg.text
                holder.binding.messageBubble.background = NeonGlowDrawable(glowColor, 8f * context.resources.displayMetrics.density)
                bindMedia(holder.binding.mediaContainer, holder.binding.messageImage, holder.binding.messageVideo, holder.binding.videoPlayIcon, msg)
            }
            is VH.Received -> {
                holder.binding.messageText.text = msg.text
                holder.binding.messageBubble.background = NeonGlowDrawable(0x33FFFFFF, 0f)
                bindMedia(holder.binding.mediaContainer, holder.binding.messageImage, holder.binding.messageVideo, holder.binding.videoPlayIcon, msg)
            }
        }
    }

    private fun bindMedia(
        container: android.view.View,
        imageView: android.widget.ImageView,
        videoView: android.widget.VideoView,
        playIcon: android.widget.ImageView,
        msg: MessageInfo
    ) {
        if (msg.mediaUri != null) {
            container.visibility = android.view.View.VISIBLE
            if (msg.mediaType == "video") {
                imageView.visibility = android.view.View.GONE
                videoView.visibility = android.view.View.VISIBLE
                playIcon.visibility = android.view.View.VISIBLE
                
                videoView.setVideoURI(msg.mediaUri)
                container.setOnClickListener {
                    if (videoView.isPlaying) {
                        videoView.pause()
                        playIcon.visibility = android.view.View.VISIBLE
                    } else {
                        videoView.start()
                        playIcon.visibility = android.view.View.GONE
                    }
                }
            } else {
                // Default to Image
                imageView.visibility = android.view.View.VISIBLE
                videoView.visibility = android.view.View.GONE
                playIcon.visibility = android.view.View.GONE
                imageView.setImageURI(msg.mediaUri)
                container.setOnClickListener(null)
            }
        } else {
            container.visibility = android.view.View.GONE
        }
    }

    sealed class VH(view: android.view.View) : RecyclerView.ViewHolder(view) {
        class Sent(val binding: ItemMessageSentBinding) : VH(binding.root)
        class Received(val binding: ItemMessageReceivedBinding) : VH(binding.root)
    }
}
