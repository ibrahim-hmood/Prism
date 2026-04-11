package com.prism.launcher.browser

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.prism.launcher.databinding.ItemBrowserTabCardBinding

class BrowserTabsAdapter(
    private val onSelect: (Long) -> Unit,
    private val onClose: (Long) -> Unit,
) : RecyclerView.Adapter<BrowserTabsAdapter.VH>() {

    private val items = ArrayList<TabCardUi>()

    fun submitList(next: List<TabCardUi>) {
        items.clear()
        items.addAll(next)
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemBrowserTabCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position], onSelect, onClose)
    }

    class VH(private val binding: ItemBrowserTabCardBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(
            item: TabCardUi,
            onSelect: (Long) -> Unit,
            onClose: (Long) -> Unit,
        ) {
            binding.tabPrivateBadge.isVisible = item.isPrivate
            if (item.isLocked && item.isPrivate) {
                binding.tabTitle.text = "****"
                binding.tabUrl.text = "Locked Content"
                binding.tabPreview.setImageResource(android.R.drawable.ic_lock_lock)
                binding.tabPreview.alpha = 0.3f
            } else {
                binding.tabTitle.text = item.title
                binding.tabUrl.text = item.url
                val bmp = item.preview
                binding.tabPreview.setImageBitmap(bmp)
                binding.tabPreview.alpha = 1.0f
            }
            binding.root.setOnClickListener { onSelect(item.id) }
            binding.tabClose.setOnClickListener { onClose(item.id) }
        }
    }
}

data class TabCardUi(
    val id: Long,
    val title: String,
    val url: String,
    val isPrivate: Boolean,
    val preview: Bitmap?,
    val isLocked: Boolean,
)

fun captureWebPreview(webView: android.webkit.WebView, maxW: Int, maxH: Int): Bitmap? {
    return try {
        val w = webView.width.coerceAtLeast(1)
        val h = webView.height.coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        webView.draw(canvas)
        val scale = minOf(maxW.toFloat() / w, maxH.toFloat() / h, 1f)
        val tw = (w * scale).toInt().coerceAtLeast(1)
        val th = (h * scale).toInt().coerceAtLeast(1)
        Bitmap.createScaledBitmap(bmp, tw, th, true).also {
            if (it != bmp) bmp.recycle()
        }
    } catch (_: Throwable) {
        null
    }
}
