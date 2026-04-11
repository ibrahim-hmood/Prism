package com.prism.launcher

import android.content.ComponentName
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.prism.launcher.browser.BrowserPageView
import com.prism.launcher.messaging.MessagingPageView

class MainDesktopPagerAdapter(
    private val activity: LauncherActivity,
    private val desktopShortcutStore: DesktopShortcutStore,
    private var assignments: List<SlotAssignment>,
    private val onLaunch: (ComponentName) -> Unit,
    private val onDesktopChanged: () -> Unit,
    private val allowDrawerDrag: () -> Boolean,
    private val acceptDesktopDrawerDrops: () -> Boolean,
) : RecyclerView.Adapter<MainDesktopPagerAdapter.PageHolder>() {

    fun updateAssignments(next: List<SlotAssignment>) {
        assignments = next
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = 3

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
        val container = FrameLayout(parent.context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        return PageHolder(container)
    }

    override fun onBindViewHolder(holder: PageHolder, position: Int) {
        val assignment = assignments[position]
        val key = cacheKey(position, assignment)
        if (holder.boundKey == key && holder.container.childCount > 0) return
        holder.boundKey = key
        holder.container.removeAllViews()
        val page = buildPage(holder.container.context, position, assignment)
        holder.container.addView(
            page,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    override fun onViewRecycled(holder: PageHolder) {
        holder.container.removeAllViews()
        holder.boundKey = null
        super.onViewRecycled(holder)
    }

    private fun buildPage(ctx: android.content.Context, position: Int, assignment: SlotAssignment): View {
        return when (assignment) {
            is SlotAssignment.Custom -> {
                val v = DynamicPageLoader.inflateView(
                    ctx,
                    assignment.packageName,
                    assignment.viewClassName,
                )
                v ?: errorView(ctx)
            }

            SlotAssignment.Messaging -> MessagingPageView(activity)

            SlotAssignment.KineticHalo -> KineticHaloPageView(activity, onLaunch)

            SlotAssignment.Default -> when (position) {
                0 -> BrowserPageView(activity)
                1 -> DesktopGridPage(
                    ctx,
                    desktopShortcutStore,
                    onLaunch = onLaunch,
                    onDataChanged = onDesktopChanged,
                    acceptDrawerDrops = acceptDesktopDrawerDrops,
                )

                else -> DrawerPageView(
                    ctx,
                    onLaunch,
                    allowDragToDesktop = allowDrawerDrag,
                )
            }
        }
    }

    private fun errorView(ctx: android.content.Context): View {
        return TextView(ctx).apply {
            text = ctx.getString(R.string.failed_load_page)
            setTextColor(ContextCompat.getColor(ctx, R.color.prism_text_muted))
            textSize = 16f
            setPadding(48, 48, 48, 48)
        }
    }

    private fun cacheKey(position: Int, assignment: SlotAssignment): String =
        "$position:${assignment.serialize()}"

    class PageHolder(val container: FrameLayout) : RecyclerView.ViewHolder(container) {
        var boundKey: String? = null
    }
}
