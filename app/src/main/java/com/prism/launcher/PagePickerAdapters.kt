package com.prism.launcher

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.prism.launcher.databinding.ItemPageOptionBinding
import com.prism.launcher.databinding.ItemPickerPositionBinding

sealed interface PagePickChoice {
    data object BuiltIn : PagePickChoice
    data object Browser : PagePickChoice
    data object DesktopGrid : PagePickChoice
    data object AppDrawer : PagePickChoice
    data object Messaging : PagePickChoice
    data object KineticHalo : PagePickChoice
    data object FileExplorer : PagePickChoice
    data object NebulaSocial : PagePickChoice
    data class PluginPage(val info: PluginPageInfo) : PagePickChoice
}

class PositionPickerAdapter(
    private val context: Context,
    private val pageCount: Int,
    private val onContinueForPosition: (Int) -> Unit,
    private val onDeletePosition: (Int) -> Unit,
) : RecyclerView.Adapter<PositionPickerAdapter.VH>() {

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun getItemCount(): Int = pageCount

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemPickerPositionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val displayIndex = position + 1
        holder.binding.positionTitle.text = "Page $displayIndex"
        holder.binding.positionSubtitle.text = "Configure content for this slot"
        
        holder.binding.positionContinue.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) {
                onContinueForPosition(pos)
            }
        }

        // --- POP DELETION LOGIC ---
        var isHeld = false
        val deleteAction = object : Runnable {
            override fun run() {
                if (!isHeld) return
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    onPopDelete(holder, pos)
                }
            }
        }

        holder.binding.cardRoot.setOnTouchListener { v, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    isHeld = true
                    holder.binding.deleteFill.animate().cancel()
                    holder.binding.deleteFill.alpha = 0f
                    holder.binding.deleteFill.scaleX = 0f
                    holder.binding.deleteFill.scaleY = 0f
                    
                    holder.binding.deleteFill.animate()
                        .alpha(1f)
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(1500)
                        .start()
                    
                    handler.postDelayed(deleteAction, 1500)
                    true
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    isHeld = false
                    handler.removeCallbacks(deleteAction)
                    holder.binding.deleteFill.animate()
                        .alpha(0f)
                        .scaleX(0f)
                        .scaleY(0f)
                        .setDuration(300)
                        .start()
                    if (event.action == android.view.MotionEvent.ACTION_UP) {
                        v.performClick()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun onPopDelete(holder: VH, position: Int) {
        // Enforce 1-page minimum
        if (pageCount <= 1) {
            android.widget.Toast.makeText(context, "Cannot delete last page", android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        // Balloon Pop Animation
        holder.binding.cardRoot.animate()
            .scaleX(1.3f)
            .scaleY(1.3f)
            .alpha(0f)
            .setDuration(300)
            .withEndAction {
                onDeletePosition(position)
            }
            .start()
    }

    class VH(val binding: ItemPickerPositionBinding) : RecyclerView.ViewHolder(binding.root)
}

class VerticalPageOptionsAdapter(
    private val context: Context,
    private val slotPageIndex: Int,
    private val plugins: List<PluginPageInfo>,
    private val onApply: (PagePickChoice) -> Unit,
) : RecyclerView.Adapter<VerticalPageOptionsAdapter.VH>() {

    override fun getItemCount(): Int = 7 + plugins.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemPageOptionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        if (position == 0) {
            holder.binding.optionTitle.text = context.getString(R.string.slot_browser)
            holder.binding.optionSubtitle.text = "Built-in private web browser"
            holder.binding.optionApply.setOnClickListener { onApply(PagePickChoice.Browser) }
        } else if (position == 1) {
            holder.binding.optionTitle.text = context.getString(R.string.slot_desktop)
            holder.binding.optionSubtitle.text = "Built-in app grid and folders"
            holder.binding.optionApply.setOnClickListener { onApply(PagePickChoice.DesktopGrid) }
        } else if (position == 2) {
            holder.binding.optionTitle.text = context.getString(R.string.slot_drawer)
            holder.binding.optionSubtitle.text = "Built-in alphabetical app drawer"
            holder.binding.optionApply.setOnClickListener { onApply(PagePickChoice.AppDrawer) }
        } else if (position == 3) {
            holder.binding.optionTitle.text = "Messaging"
            holder.binding.optionSubtitle.text = "Built-in SMS/MMS messages"
            holder.binding.optionApply.setOnClickListener { onApply(PagePickChoice.Messaging) }
        } else if (position == 4) {
            holder.binding.optionTitle.text = "Kinetic Halo"
            holder.binding.optionSubtitle.text = "Physics-based blind navigation"
            holder.binding.optionApply.setOnClickListener { onApply(PagePickChoice.KineticHalo) }
        } else if (position == 5) {
            holder.binding.optionTitle.text = "File Explorer"
            holder.binding.optionSubtitle.text = "Local files and directories"
            holder.binding.optionApply.setOnClickListener { onApply(PagePickChoice.FileExplorer) }
        } else if (position == 6) {
            holder.binding.optionTitle.text = "Nebula Social"
            holder.binding.optionSubtitle.text = "AI-powered social media graph"
            holder.binding.optionApply.setOnClickListener { onApply(PagePickChoice.NebulaSocial) }
        } else {
            val p = plugins[position - 7]
            holder.binding.optionTitle.text = p.label
            holder.binding.optionSubtitle.text = p.packageName
            holder.binding.optionApply.setOnClickListener { onApply(PagePickChoice.PluginPage(p)) }
        }
    }

    class VH(val binding: ItemPageOptionBinding) : RecyclerView.ViewHolder(binding.root)
}
