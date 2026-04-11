package com.prism.launcher.messaging

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.prism.launcher.databinding.ItemConversationBinding

data class ThreadInfo(
    val threadId: Long,
    val address: String,
    val snippet: String
)

class ConversationAdapter(
    private var threads: List<ThreadInfo>,
    private val onClick: (ThreadInfo) -> Unit
) : RecyclerView.Adapter<ConversationAdapter.VH>() {

    fun update(newThreads: List<ThreadInfo>) {
        threads = newThreads
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = threads.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemConversationBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val t = threads[position]
        holder.binding.conversationName.text = t.address
        holder.binding.conversationSnippet.text = t.snippet
        holder.itemView.setOnClickListener { onClick(t) }
    }

    class VH(val binding: ItemConversationBinding) : RecyclerView.ViewHolder(binding.root)
}
