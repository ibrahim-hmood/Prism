package com.prism.launcher

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.prism.launcher.browser.BrowserPageView
import com.prism.launcher.browser.PrivateDnsVpnService
import com.prism.launcher.databinding.ActivityLauncherBinding
import com.prism.launcher.messaging.MessagingPageView
import kotlinx.coroutines.launch

class LauncherActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLauncherBinding
    lateinit var slotPreferences: SlotPreferences
        private set
    private lateinit var desktopShortcutStore: DesktopShortcutStore
    private lateinit var mainAdapter: MainDesktopPagerAdapter
    private var browserPage: BrowserPageView? = null
    private var discoveredPlugins: List<PluginPageInfo> = emptyList()
    private var pendingPickerSlot: Int = 1
    
    // Overscroll Gesture State
    private var startTouchX = 0f
    private var startTouchY = 0f
    private var touchSlop = 0f
    private var velocityTracker: VelocityTracker? = null
    private var overscrollTriggered = false

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        if (VpnService.prepare(this) == null) {
            PrivateDnsVpnService.start(this)
        }
        browserPage?.resyncPrivateVpn()
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* result is informational; VPN notification works regardless */ }

    private val smsPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        if (results.all { it.value }) {
            // Permission granted, find the messaging page if it's visible and refresh
            val pos = binding.desktopPager.currentItem
            val v = findPageViewAt(pos)
            if (v is MessagingPageView) {
                // We'd need to expose a refresh method or rely on onAttachedToWindow
            }
        }
    }

    fun requestMessagingPermissions() {
        smsPermissionLauncher.launch(arrayOf(
            Manifest.permission.READ_SMS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_CONTACTS
        ))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLauncherBinding.inflate(layoutInflater)
        setContentView(binding.root)

        slotPreferences = SlotPreferences(this)
        desktopShortcutStore = DesktopShortcutStore(this)
        touchSlop = ViewConfiguration.get(this).scaledTouchSlop.toFloat()

        mainAdapter = MainDesktopPagerAdapter(
            activity = this,
            desktopShortcutStore = desktopShortcutStore,
            assignments = slotPreferences.assignmentsInOrder(),
            onLaunch = { launchComponent(it) },
            onDesktopChanged = { },
            allowDrawerDrag = {
                val allowed = slotPreferences.get(SlotIndex.DRAWER) is SlotAssignment.Default &&
                              slotPreferences.get(SlotIndex.DESKTOP) is SlotAssignment.Default
                if (allowed) binding.desktopPager.setCurrentItem(1, true)
                allowed
            },
            acceptDesktopDrawerDrops = {
                slotPreferences.get(SlotIndex.DESKTOP) is SlotAssignment.Default &&
                    slotPreferences.get(SlotIndex.DRAWER) is SlotAssignment.Default
            },
        )
        binding.desktopPager.adapter = mainAdapter
        binding.desktopPager.setCurrentItem(PrismSettings.getDefaultPage(this), false)
        binding.desktopPager.offscreenPageLimit = 2

        binding.desktopPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {
                // position 0: Browser, position 1: Desktop, position 2: Drawer
                val progress = when {
                    position >= 2 -> 1f // Stable on App Drawer
                    position == 1 -> positionOffset.coerceIn(0f, 1f) // Swiping to Drawer
                    else -> 0f // Swiping to Browser or on Browser
                }

                // 1. Zoom and Scrim (Fallbacks)
                val scale = 1f - (progress * 0.08f) 
                binding.mainHost.scaleX = scale
                binding.mainHost.scaleY = scale
                binding.scrimLayer.alpha = progress * 0.65f // Heavier dark atmosphere
                
                // 2. System Background Blur (API 31+)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val blurRadius = (progress * 180f).toInt()
                    window.setBackgroundBlurRadius(blurRadius)
                }
            }
        })

        binding.pickerMainPager.orientation = ViewPager2.ORIENTATION_VERTICAL

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    when {
                        binding.pagePickerOverlay.visibility == View.VISIBLE -> {
                            if (binding.pickerBack.visibility == View.VISIBLE) {
                                showPickerPositionsStep(pendingPickerSlot)
                            } else {
                                hidePagePicker()
                            }
                        }
                        tryConsumeBrowserBack() -> Unit
                        else -> {
                            isEnabled = false
                            onBackPressedDispatcher.onBackPressed()
                            isEnabled = true
                        }
                    }
                }
            },
        )

        binding.pickerClose.setOnClickListener { hidePagePicker() }
        binding.pickerBack.setOnClickListener { showPickerPositionsStep(pendingPickerSlot) }

        requestNotificationPermissionIfNeeded()
    }

    /**
     * On Android 13+ (API 33) POST_NOTIFICATIONS is a runtime permission.
     * We show the dialog once — on first ever launch — tracked via SharedPreferences.
     */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val prefs = getSharedPreferences("prism_prefs", MODE_PRIVATE)
        if (prefs.getBoolean("notif_perm_asked", false)) return
        prefs.edit().putBoolean("notif_perm_asked", true).apply()
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun attachBrowserPage(page: BrowserPageView?) {
        browserPage = page
    }

    fun requestVpnPermission(intent: Intent) {
        vpnPermissionLauncher.launch(intent)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        handleOverscrollDetection(ev)
        return super.dispatchTouchEvent(ev)
    }

    private fun handleOverscrollDetection(ev: MotionEvent) {
        if (binding.pagePickerOverlay.visibility == View.VISIBLE) return

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startTouchX = ev.rawX
                startTouchY = ev.rawY
                overscrollTriggered = false
                velocityTracker?.clear()
                velocityTracker = velocityTracker ?: VelocityTracker.obtain()
                velocityTracker?.addMovement(ev)
            }
            MotionEvent.ACTION_MOVE -> {
                if (overscrollTriggered) return
                
                velocityTracker?.addMovement(ev)
                val dy = ev.rawY - startTouchY
                val dx = ev.rawX - startTouchX

                // If vertical movement is dominant and significant
                if (Math.abs(dy) > touchSlop && Math.abs(dy) > Math.abs(dx)) {
                    val threshold = 700f // Harder threshold to avoid accidental trigger
                    if (Math.abs(dy) > threshold) {
                        velocityTracker?.computeCurrentVelocity(1000)
                        val yVel = velocityTracker?.yVelocity ?: 0f
                        
                        val currentPage = findPageViewAt(binding.desktopPager.currentItem)
                        val direction = if (dy > 0) -1 else 1 // -1 is top, 1 is bottom
                        
                        // If we are at the edge OR the page isn't scrollable at all
                        val isAtEdge = currentPage?.canScrollVertically(direction) == false
                        
                        if (isAtEdge && Math.abs(yVel) > 1500f) {
                            overscrollTriggered = true
                            window.decorView.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                            openPagePicker(binding.desktopPager.currentItem)
                        }
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                velocityTracker?.recycle()
                velocityTracker = null
            }
        }
    }

    private fun launchComponent(cn: ComponentName) {
        try {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                component = cn
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            }
            startActivity(intent)
        } catch (e: Exception) {
            // Usually uninstalled while app is running
        }
        
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val db = AppDatabase.get(this@LauncherActivity)
                val hourOfDay = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
                val cnStr = cn.flattenToString()
                
                // 1. Overall & Hourly Stats
                val statDao = db.appLaunchStatDao()
                if (statDao.increment(cnStr, hourOfDay) == 0) {
                    statDao.insertOrIgnore(AppLaunchStatEntity(cnStr, hourOfDay, 1))
                }
                
            } catch (e: Exception) {
                // Ignore DB logging errors during launch
            }
        }
    }

    private fun openPagePicker(initialSlot: Int) {
        binding.pagePickerOverlay.visibility = View.VISIBLE
        animateMainZoom(true)
        discoveredPlugins = PluginPageDiscovery.discover(packageManager)
        showPickerPositionsStep(initialSlot.coerceIn(0, 2))
    }

    private fun showPickerPositionsStep(initialSlot: Int) {
        pendingPickerSlot = initialSlot.coerceIn(0, 2)
        binding.pickerSubtitle.setText(R.string.page_picker_step_positions)
        binding.pickerBack.visibility = View.GONE
        binding.pickerMainPager.adapter = PositionPickerAdapter(this) { slot ->
            pendingPickerSlot = slot
            showPickerOptionsStep()
        }
        binding.pickerMainPager.setCurrentItem(pendingPickerSlot, false)
    }

    private fun showPickerOptionsStep() {
        binding.pickerSubtitle.setText(R.string.page_picker_step_options)
        binding.pickerBack.visibility = View.VISIBLE
        binding.pickerMainPager.adapter = VerticalPageOptionsAdapter(
            this,
            pendingPickerSlot,
            discoveredPlugins,
        ) { choice ->
            applySlotPick(pendingPickerSlot, choice)
            hidePagePicker()
        }
        binding.pickerMainPager.setCurrentItem(0, false)
    }

    private fun hidePagePicker() {
        binding.pagePickerOverlay.visibility = View.GONE
        binding.pickerMainPager.adapter = null
        binding.pickerBack.visibility = View.GONE
        animateMainZoom(false)
    }

    private fun animateMainZoom(zoomOut: Boolean) {
        val scale = if (zoomOut) 0.86f else 1f
        binding.mainHost.animate()
            .scaleX(scale)
            .scaleY(scale)
            .setDuration(240L)
            .start()
    }

    private fun applySlotPick(slot: Int, choice: PagePickChoice) {
        val slotIndex = SlotIndex.entries[slot.coerceIn(0, 2)]
        val assignment = when (choice) {
            PagePickChoice.BuiltIn -> SlotAssignment.Default
            PagePickChoice.Messaging -> SlotAssignment.Messaging
            PagePickChoice.KineticHalo -> SlotAssignment.KineticHalo
            is PagePickChoice.PluginPage -> SlotAssignment.Custom(
                choice.info.packageName,
                choice.info.viewClassName,
            )
        }
        slotPreferences.set(slotIndex, assignment)
        mainAdapter.updateAssignments(slotPreferences.assignmentsInOrder())
    }

    private fun tryConsumeBrowserBack(): Boolean {
        if (binding.desktopPager.currentItem != 0) return false
        if (slotPreferences.get(SlotIndex.BROWSER) !is SlotAssignment.Default) return false
        val browser = findPageViewAt(0) as? BrowserPageView ?: return false
        return browser.handleBack()
    }

    private fun findPageViewAt(adapterPosition: Int): View? {
        val rv = binding.desktopPager.getChildAt(0) as? RecyclerView ?: return null
        val holder = rv.findViewHolderForAdapterPosition(adapterPosition) ?: return null
        if (holder !is MainDesktopPagerAdapter.PageHolder) return null
        return holder.container.getChildAt(0)
    }

    private fun isUnderLauncherAppTarget(rawX: Float, rawY: Float): Boolean {
        val v = findViewAt(binding.root, rawX, rawY) ?: return false
        var cur: View? = v
        while (cur != null) {
            if (cur.getTag(R.id.tag_prism_launcher_app_target) == true) return true
            cur = cur.parent as? View
        }
        return false
    }

    private fun findViewAt(root: View, rawX: Float, rawY: Float): View? {
        if (!root.isShown) return null
        val loc = IntArray(2)
        root.getLocationOnScreen(loc)
        val left = loc[0]
        val top = loc[1]
        val right = left + root.width
        val bottom = top + root.height
        if (rawX < left || rawX > right || rawY < top || rawY > bottom) return null
        if (root is ViewGroup) {
            for (i in root.childCount - 1 downTo 0) {
                val child = root.getChildAt(i)
                val hit = findViewAt(child, rawX, rawY)
                if (hit != null) return hit
            }
        }
        return root
    }

    companion object {
        // Constants replaced by dynamic overshoot logic
    }
}
