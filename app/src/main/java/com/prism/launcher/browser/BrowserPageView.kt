package com.prism.launcher.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.net.VpnService
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.EditText
import android.widget.FrameLayout
import androidx.core.view.isVisible
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.prism.launcher.LauncherActivity
import com.prism.launcher.PrismSettings
import com.prism.launcher.R
import com.prism.launcher.databinding.IncludeBrowserPageBinding

@SuppressLint("SetJavaScriptEnabled")
class BrowserPageView(context: Context) : FrameLayout(context) {

    private val host: LauncherActivity = context as LauncherActivity
    private val binding: IncludeBrowserPageBinding
    private val blocklist = PrismBlocklist.get(context.applicationContext)

    private data class Tab(
        val id: Long,
        val webView: WebView,
        val isPrivate: Boolean,
        var title: String,
        var lastUrl: String,
    )

    private val tabs = ArrayList<Tab>()
    private var activeTabId: Long = 0L
    private var activeCategoryIsPrivate: Boolean = false
    private var privateAuthenticated: Boolean = false
    private var lastVpnStateWants: Boolean? = null

    private val tabsAdapter = BrowserTabsAdapter(
        onSelect = { id -> selectTab(id) },
        onClose = { id -> closeTab(id) },
    )

    init {
        binding = IncludeBrowserPageBinding.inflate(LayoutInflater.from(context), this, true)
        binding.tabsRecycler.layoutManager = GridLayoutManager(context, 2)
        binding.tabsRecycler.adapter = tabsAdapter

        binding.tabsButton.setOnClickListener { openTabsOverlay() }
        binding.newTabButton.setOnClickListener { addTab(isPrivate = activeCategoryIsPrivate) }
        binding.tabsDone.setOnClickListener { closeTabsOverlay() }
        binding.tabsOverlay.setOnClickListener { closeTabsOverlay() }

        binding.tabCategoryGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val targetIsPrivate = checkedId == R.id.privateCategoryBtn
            if (targetIsPrivate && !privateAuthenticated && PrismSettings.getPrivateTabsLocked(context)) {
                requestBiometricUnlock {
                    activeCategoryIsPrivate = true
                    refreshTabsList()
                }
            } else {
                activeCategoryIsPrivate = targetIsPrivate
                refreshTabsList()
            }
        }

        binding.goButton.setOnClickListener { navigate(binding.urlField) }
        binding.urlField.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                navigate(v as EditText)
                true
            } else {
                false
            }
        }

        if (tabs.isEmpty()) {
            val defaultPrivate = PrismSettings.getPrivateByDefault(context)
            addTab(isPrivate = defaultPrivate, initialUrl = "https://duckduckgo.com/")
        }

        host.attachBrowserPage(this)
    }

    override fun onDetachedFromWindow() {
        host.attachBrowserPage(null)
        super.onDetachedFromWindow()
    }

    fun resyncPrivateVpn() {
        val active = tabs.firstOrNull { it.id == activeTabId }
        applyVpnForTab(active)
    }

    fun handleBack(): Boolean {
        if (binding.tabsOverlay.isVisible) {
            closeTabsOverlay()
            return true
        }
        val active = tabs.firstOrNull { it.id == activeTabId } ?: return false
        return if (active.webView.canGoBack()) {
            active.webView.goBack()
            true
        } else {
            false
        }
    }

    private fun addTab(isPrivate: Boolean, initialUrl: String? = null) {
        val wv = createWebView(isPrivate)
        val id = System.nanoTime()
        val startUrl = initialUrl ?: "https://duckduckgo.com/"
        val tab = Tab(
            id = id,
            webView = wv,
            isPrivate = isPrivate,
            title = if (isPrivate) context.getString(R.string.browser_private) else context.getString(R.string.browser_new_tab),
            lastUrl = startUrl,
        )
        tabs.add(tab)
        binding.webContainer.addView(
            wv,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        wv.visibility = View.GONE
        selectTab(id)
        wv.loadUrl(startUrl, privateHeaders(isPrivate))
    }

    private fun privateHeaders(isPrivate: Boolean): Map<String, String> {
        if (!isPrivate) return emptyMap()
        return mapOf(
            "DNT" to "1",
            "Sec-GPC" to "1",
        )
    }

    private fun createWebView(isPrivate: Boolean): WebView {
        val wv = WebView(context)
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(wv, !isPrivate)

        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = !isPrivate
            @Suppress("DEPRECATION")
            databaseEnabled = !isPrivate
            builtInZoomControls = true
            displayZoomControls = false
            mixedContentMode = if (isPrivate) {
                WebSettings.MIXED_CONTENT_NEVER_ALLOW
            } else {
                WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                safeBrowsingEnabled = true
            }
        }

        wv.setBackgroundColor(Color.TRANSPARENT)

        val client = PrismWebViewClient(
            blocklist = blocklist,
            isPrivateTab = isPrivate,
            onTitle = { t ->
                post {
                    val tab = tabs.firstOrNull { it.webView === wv } ?: return@post
                    tab.title = t
                    if (tab.id == activeTabId) {
                        // no-op
                    }
                }
            },
            onUrl = { u ->
                post {
                    val tab = tabs.firstOrNull { it.webView === wv } ?: return@post
                    tab.lastUrl = u
                    if (tab.id == activeTabId) {
                        binding.urlField.setText(u)
                    }
                }
            },
        )
        wv.webViewClient = client
        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                binding.loadProgress.isVisible = newProgress in 1..99 && view?.visibility == View.VISIBLE
                binding.loadProgress.progress = newProgress
            }
        }
        return wv
    }

    private fun selectTab(id: Long) {
        val tabToSelect = tabs.firstOrNull { it.id == id } ?: return
        activeTabId = id
        for (tab in tabs) {
            val show = tab.id == id
            tab.webView.visibility = if (show) View.VISIBLE else View.GONE
            if (show) {
                binding.urlField.setText(tab.lastUrl)
            }
        }
        applyVpnForTab(tabToSelect)
        closeTabsOverlay()
    }

    private fun closeTab(id: Long) {
        val idx = tabs.indexOfFirst { it.id == id }
        if (idx < 0) return
        val tab = tabs.removeAt(idx)
        binding.webContainer.removeView(tab.webView)
        tab.webView.stopLoading()
        tab.webView.destroy()

        if (tabs.isEmpty()) {
            addTab(isPrivate = activeCategoryIsPrivate)
            return
        }
        if (activeTabId == id) {
            val next = tabs[(idx.coerceAtMost(tabs.lastIndex)).coerceAtLeast(0)]
            selectTab(next.id)
        } else {
            val active = tabs.firstOrNull { it.id == activeTabId }
            applyVpnForTab(active)
        }
    }

    private fun openTabsOverlay() {
        // Automatically switch overlay category to match current tab type
        val active = tabs.firstOrNull { it.id == activeTabId }
        activeCategoryIsPrivate = active?.isPrivate ?: false
        binding.tabCategoryGroup.check(if (activeCategoryIsPrivate) R.id.privateCategoryBtn else R.id.publicCategoryBtn)
        
        refreshTabsList()
        binding.tabsOverlay.isVisible = true
    }

    private fun refreshTabsList() {
        val lockActive = PrismSettings.getPrivateTabsLocked(context) && !privateAuthenticated
        val displayed = tabs.filter { it.isPrivate == activeCategoryIsPrivate }
        
        val cards = displayed.map { tab ->
            TabCardUi(
                id = tab.id,
                title = tab.title,
                url = tab.lastUrl,
                isPrivate = tab.isPrivate,
                preview = if (tab.isPrivate && lockActive) null else captureWebPreview(tab.webView, 720, 900),
                isLocked = lockActive
            )
        }
        tabsAdapter.submitList(cards)
    }

    private fun closeTabsOverlay() {
        binding.tabsOverlay.isVisible = false
    }

    private fun requestBiometricUnlock(onSuccess: () -> Unit) {
        val activity = context as? FragmentActivity ?: return
        val executor = ContextCompat.getMainExecutor(context)
        val prompt = BiometricPrompt(activity, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                privateAuthenticated = true
                onSuccess()
            }
        })

        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Private Tabs")
            .setSubtitle("Use your biometric credential to access private tabs")
            .setNegativeButtonText("Cancel")
            .build()

        prompt.authenticate(info)
    }

    private fun navigate(field: EditText) {
        var url = field.text?.toString()?.trim().orEmpty()
        if (url.isEmpty()) return
        if (!url.contains("://")) {
            url = PrismSettings.buildSearchUrl(context, url)
        }
        val active = tabs.firstOrNull { it.id == activeTabId } ?: return
        active.lastUrl = url
        active.webView.loadUrl(url, privateHeaders(active.isPrivate))
    }

    private fun applyVpnForTab(tab: Tab?) {
        val wants = tab?.isPrivate == true
        if (wants == lastVpnStateWants) return
        lastVpnStateWants = wants

        if (!wants) {
            PrivateDnsVpnService.stop(context.applicationContext)
            return
        }
        val autoStart = PrismSettings.getVpnAutoStart(context)
        if (!autoStart) return
        val prep = VpnService.prepare(host)
        if (prep != null) {
            host.requestVpnPermission(prep)
            return
        }
        PrivateDnsVpnService.start(context.applicationContext)
    }
}
