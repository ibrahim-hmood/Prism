package com.prism.launcher

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.prism.launcher.databinding.ItemPageOptionBinding
import com.prism.launcher.databinding.ItemPickerPositionBinding

sealed interface PagePickChoice {
    data object BuiltIn : PagePickChoice
    data object Messaging : PagePickChoice
    data object KineticHalo : PagePickChoice
    data class PluginPage(val info: PluginPageInfo) : PagePickChoice
}

class PositionPickerAdapter(
    private val context: Context,
    private val onContinueForPosition: (Int) -> Unit,
) : RecyclerView.Adapter<PositionPickerAdapter.VH>() {

    override fun getItemCount(): Int = 3

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemPickerPositionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val title = when (position) {
            0 -> context.getString(R.string.position_left)
            1 -> context.getString(R.string.position_center)
            else -> context.getString(R.string.position_right)
        }
        val subtitle = when (position) {
            0 -> context.getString(R.string.position_left_detail)
            1 -> context.getString(R.string.position_center_detail)
            else -> context.getString(R.string.position_right_detail)
        }
        holder.binding.positionTitle.text = title
        holder.binding.positionSubtitle.text = subtitle
        holder.binding.positionContinue.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) {
                onContinueForPosition(pos)
            }
        }
    }

    class VH(val binding: ItemPickerPositionBinding) : RecyclerView.ViewHolder(binding.root)
}

class VerticalPageOptionsAdapter(
    private val context: Context,
    private val slotPageIndex: Int,
    private val plugins: List<PluginPageInfo>,
    private val onApply: (PagePickChoice) -> Unit,
) : RecyclerView.Adapter<VerticalPageOptionsAdapter.VH>() {

    private val builtInSubtitle: String
        get() = when (slotPageIndex) {
            0 -> context.getString(R.string.slot_browser)
            1 -> context.getString(R.string.slot_desktop)
            else -> context.getString(R.string.slot_drawer)
        }

    override fun getItemCount(): Int = 3 + plugins.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemPageOptionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        if (position == 0) {
            holder.binding.optionTitle.text = context.getString(R.string.default_page)
            holder.binding.optionSubtitle.text = builtInSubtitle
            holder.binding.optionApply.setOnClickListener { onApply(PagePickChoice.BuiltIn) }
        } else if (position == 1) {
            holder.binding.optionTitle.text = "Messaging"
            holder.binding.optionSubtitle.text = "Built-in SMS/MMS messages"
            holder.binding.optionApply.setOnClickListener { onApply(PagePickChoice.Messaging) }
        } else if (position == 2) {
            holder.binding.optionTitle.text = "Kinetic Halo"
            holder.binding.optionSubtitle.text = "Physics-based blind navigation"
            holder.binding.optionApply.setOnClickListener { onApply(PagePickChoice.KineticHalo) }
        } else {
            val p = plugins[position - 3]
            holder.binding.optionTitle.text = p.label
            holder.binding.optionSubtitle.text = p.packageName
            holder.binding.optionApply.setOnClickListener { onApply(PagePickChoice.PluginPage(p)) }
        }
    }

    class VH(val binding: ItemPageOptionBinding) : RecyclerView.ViewHolder(binding.root)
}
