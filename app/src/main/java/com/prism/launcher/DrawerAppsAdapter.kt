package com.prism.launcher

import android.content.ClipData
import android.content.ComponentName
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.prism.launcher.R
import com.prism.launcher.databinding.ItemDrawerAppBinding

data class DrawerAppEntry(
    val component: ComponentName,
    val label: String,
    val icon: Drawable?,
)

private object DrawerDiff : DiffUtil.ItemCallback<DrawerAppEntry>() {
    override fun areItemsTheSame(old: DrawerAppEntry, new: DrawerAppEntry) =
        old.component == new.component

    override fun areContentsTheSame(old: DrawerAppEntry, new: DrawerAppEntry) =
        old.label == new.label && old.icon === new.icon
}

class DrawerAppsAdapter(
    private val onLaunch: (ComponentName) -> Unit,
    private val allowDragToDesktop: () -> Boolean,
) : ListAdapter<DrawerAppEntry, DrawerAppsAdapter.VH>(DrawerDiff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemDrawerAppBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val e = getItem(position)
        holder.binding.drawerLabel.text = e.label
        holder.binding.drawerIcon.setImageDrawable(e.icon)
        holder.itemView.setTag(R.id.tag_prism_launcher_app_target, true)
        holder.binding.drawerIcon.setTag(R.id.tag_prism_launcher_app_target, true)
        holder.itemView.setOnClickListener { onLaunch(e.component) }
        holder.itemView.setOnLongClickListener {
            if (!allowDragToDesktop()) return@setOnLongClickListener false
            val clip = ClipData.newPlainText("prism_app", e.component.flattenToString())
            val shadow = View.DragShadowBuilder(holder.binding.drawerIcon)
            val started = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                holder.itemView.startDragAndDrop(
                    clip,
                    shadow,
                    e.component,
                    View.DRAG_FLAG_GLOBAL,
                )
            } else {
                @Suppress("DEPRECATION")
                holder.itemView.startDrag(clip, shadow, e.component, 0)
            }
            started
        }
    }

    class VH(val binding: ItemDrawerAppBinding) : RecyclerView.ViewHolder(binding.root)
}

// ---------------------------------------------------------------------------
// PackageManager helpers (used by AppSyncWorker and DrawerPageView)
// ---------------------------------------------------------------------------

fun loadLauncherApps(pm: android.content.pm.PackageManager): List<DrawerAppEntry> {
    val intent = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
        addCategory(android.content.Intent.CATEGORY_LAUNCHER)
    }
    val list = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
        pm.queryIntentActivities(intent, android.content.pm.PackageManager.ResolveInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        pm.queryIntentActivities(intent, 0)
    }
    return list
        .sortedBy { it.loadLabel(pm).toString().lowercase() }
        .map { it.toEntry(pm) }
}

private fun ResolveInfo.toEntry(pm: android.content.pm.PackageManager): DrawerAppEntry {
    val cn = ComponentName(activityInfo.packageName, activityInfo.name)
    return DrawerAppEntry(
        component = cn,
        label = loadLabel(pm).toString(),
        icon = try {
            loadIcon(pm)
        } catch (_: Throwable) {
            null
        },
    )
}

