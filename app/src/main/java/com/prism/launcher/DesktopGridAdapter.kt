package com.prism.launcher

import android.content.ComponentName
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.prism.launcher.R
import com.prism.launcher.databinding.ItemDesktopCellBinding

class DesktopGridAdapter(
    private val packageManager: PackageManager,
    private val store: DesktopShortcutStore,
    private val cellCount: Int,
    private val onDataChanged: () -> Unit,
    private val onLaunch: (ComponentName) -> Unit,
) : RecyclerView.Adapter<DesktopGridAdapter.VH>() {

    private var cells: Array<ComponentName?> = store.readGrid(cellCount)

    fun refreshFromStore() {
        cells = store.readGrid(cellCount)
        notifyDataSetChanged()
    }

    fun move(from: Int, to: Int) {
        if (from == to) return
        val tmp = cells[from]
        cells[from] = cells[to]
        cells[to] = tmp
        persist()
        notifyItemMoved(from, to)
    }

    fun placeAt(index: Int, cn: ComponentName) {
        if (index !in cells.indices) return
        cells[index] = cn
        persist()
        notifyItemChanged(index)
    }

    private fun persist() {
        store.writeGrid(cells)
        onDataChanged()
    }

    override fun getItemCount(): Int = cellCount

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemDesktopCellBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val cn = cells[position]
        holder.binding.icon.isVisible = cn != null
        holder.binding.label.isVisible = cn != null
        if (cn != null) {
            holder.binding.label.text = resolveLabel(cn)
            holder.binding.icon.setImageDrawable(resolveIcon(cn))
            holder.itemView.setOnClickListener { onLaunch(cn) }
            holder.itemView.setTag(R.id.tag_prism_launcher_app_target, true)
            holder.binding.icon.setTag(R.id.tag_prism_launcher_app_target, true)
        } else {
            holder.binding.icon.setImageDrawable(null)
            holder.binding.label.text = ""
            holder.itemView.setOnClickListener(null)
            holder.itemView.setTag(R.id.tag_prism_launcher_app_target, null)
            holder.binding.icon.setTag(R.id.tag_prism_launcher_app_target, null)
        }
    }

    private fun resolveLabel(cn: ComponentName): String {
        return try {
            val ri = packageManager.resolveActivity(
                android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
                    addCategory(android.content.Intent.CATEGORY_LAUNCHER)
                    component = cn
                },
                0,
            )
            ri?.loadLabel(packageManager)?.toString() ?: cn.packageName
        } catch (_: Throwable) {
            cn.packageName
        }
    }

    private fun resolveIcon(cn: ComponentName): Drawable? {
        return try {
            packageManager.getActivityIcon(cn)
        } catch (_: Throwable) {
            try {
                packageManager.getApplicationIcon(cn.packageName)
            } catch (_: Throwable) {
                null
            }
        }
    }

    class VH(val binding: ItemDesktopCellBinding) : RecyclerView.ViewHolder(binding.root)
}
