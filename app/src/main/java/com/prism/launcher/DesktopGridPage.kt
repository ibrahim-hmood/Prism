package com.prism.launcher

import android.content.ComponentName
import android.content.Context
import android.view.DragEvent
import android.view.LayoutInflater
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.prism.launcher.databinding.ItemHotseatAppBinding
import com.prism.launcher.databinding.PageDesktopRootBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DesktopGridPage(
    context: Context,
    private val store: DesktopShortcutStore,
    private val onLaunch: (ComponentName) -> Unit,
    private val onDataChanged: () -> Unit,
    private val acceptDrawerDrops: () -> Boolean,
) : android.widget.LinearLayout(context) {

    private val binding: PageDesktopRootBinding
    private val adapter: DesktopGridAdapter
    private val cellCount: Int = 24
    private val grid: RecyclerView

    init {
        orientation = VERTICAL
        binding = PageDesktopRootBinding.inflate(LayoutInflater.from(context), this, true)
        grid = binding.desktopGrid
        adapter = DesktopGridAdapter(
            context.packageManager,
            store,
            cellCount,
            onDataChanged,
            onLaunch,
        )
        grid.layoutManager = GridLayoutManager(context, 4)
        grid.adapter = adapter
        grid.setHasFixedSize(true)

        val touchHelper = ItemTouchHelper(
            object : ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT,
                0,
            ) {
                override fun onMove(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder,
                    target: RecyclerView.ViewHolder,
                ): Boolean {
                    adapter.move(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
                    return true
                }

                override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit
            },
        )
        touchHelper.attachToRecyclerView(grid)

        grid.setOnDragListener { _, event ->
            if (!acceptDrawerDrops()) return@setOnDragListener false
            when (event.action) {
                DragEvent.ACTION_DROP -> {
                    val cn = (event.localState as? ComponentName)
                        ?: event.clipData?.getItemAt(0)?.text?.toString()
                            ?.let { ComponentName.unflattenFromString(it) }
                        ?: return@setOnDragListener false
                    val child = grid.findChildViewUnder(event.x, event.y)
                    val pos = if (child == null) {
                        RecyclerView.NO_POSITION
                    } else {
                        grid.getChildAdapterPosition(child)
                    }
                    if (pos != RecyclerView.NO_POSITION) {
                        adapter.placeAt(pos, cn)
                        true
                    } else {
                        false
                    }
                }

                else -> true
            }
        }
    }

    fun refreshFromStore() {
        adapter.refreshFromStore()
        updateHotseat()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateHotseat()
    }

    private fun updateHotseat() {
        val lifecycleOwner = context as? LifecycleOwner ?: return
        lifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val predictions = HotseatPredictor.getPredictions(context)
            withContext(Dispatchers.Main) {
                binding.hotseatContainer.removeAllViews()
                val pm = context.packageManager
                for (cnStr in predictions) {
                    val cn = ComponentName.unflattenFromString(cnStr) ?: continue
                    val icon = resolveIcon(pm, cn) ?: continue
                    
                    val itemBinding = ItemHotseatAppBinding.inflate(LayoutInflater.from(context), binding.hotseatContainer, false)
                    itemBinding.hotseatIcon.setImageDrawable(icon)
                    itemBinding.root.setOnClickListener { onLaunch(cn) }
                    binding.hotseatContainer.addView(itemBinding.root)
                }
            }
        }
    }

    private fun resolveIcon(pm: android.content.pm.PackageManager, cn: ComponentName): android.graphics.drawable.Drawable? {
        return try {
            pm.getActivityIcon(cn)
        } catch (_: Throwable) {
            try {
                pm.getApplicationIcon(cn.packageName)
            } catch (_: Throwable) {
                null
            }
        }
    }
}
