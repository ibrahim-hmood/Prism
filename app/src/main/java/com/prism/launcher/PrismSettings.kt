package com.prism.launcher

import android.content.Context

/**
 * Typed, centralized access to all user-configurable Prism settings.
 * Reads/writes to SharedPreferences("prism_settings") immediately on every call.
 * No "Save" button is needed anywhere in the UI.
 */
object PrismSettings {

    private const val PREFS = "prism_settings"

    // ── Launcher ────────────────────────────────────────────────────────────

    /** Which pager page to show on launch: 0=Left, 1=Center, 2=Right */
    fun getDefaultPage(ctx: Context): Int =
        prefs(ctx).getInt(KEY_DEFAULT_PAGE, 1)

    fun setDefaultPage(ctx: Context, value: Int) =
        prefs(ctx).edit().putInt(KEY_DEFAULT_PAGE, value).apply()

    /** Whether app names are shown below icons in the drawer */
    fun getShowDrawerLabels(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_SHOW_DRAWER_LABELS, true)

    fun setShowDrawerLabels(ctx: Context, value: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_SHOW_DRAWER_LABELS, value).apply()

    // ── Browser ─────────────────────────────────────────────────────────────

    /**
     * Search engine identifier: "ddg" | "google" | "bing" | "custom".
     * When "custom", [getCustomSearchUrl] is used.
     */
    fun getSearchEngine(ctx: Context): String =
        prefs(ctx).getString(KEY_SEARCH_ENGINE, "ddg") ?: "ddg"

    fun setSearchEngine(ctx: Context, value: String) =
        prefs(ctx).edit().putString(KEY_SEARCH_ENGINE, value).apply()

    /** Custom search URL template. Use %s as query placeholder, e.g. "https://example.com/search?q=%s" */
    fun getCustomSearchUrl(ctx: Context): String =
        prefs(ctx).getString(KEY_CUSTOM_SEARCH_URL, "") ?: ""

    fun setCustomSearchUrl(ctx: Context, value: String) =
        prefs(ctx).edit().putString(KEY_CUSTOM_SEARCH_URL, value).apply()

    /** Returns the full search URL for a given query, based on current engine setting */
    fun buildSearchUrl(ctx: Context, query: String): String {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        return when (getSearchEngine(ctx)) {
            "google" -> "https://www.google.com/search?q=$encoded"
            "bing"   -> "https://www.bing.com/search?q=$encoded"
            "custom" -> getCustomSearchUrl(ctx).replace("%s", encoded)
            else     -> "https://duckduckgo.com/?q=$encoded"  // "ddg"
        }
    }

    /** Whether JavaScript is enabled in WebViews */
    fun getJsEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_JS_ENABLED, true)

    fun setJsEnabled(ctx: Context, value: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_JS_ENABLED, value).apply()

    /** Whether new tabs open in private mode by default */
    fun getPrivateByDefault(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_PRIVATE_BY_DEFAULT, false)

    fun setPrivateByDefault(ctx: Context, value: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_PRIVATE_BY_DEFAULT, value).apply()

    // ── VPN / Privacy ───────────────────────────────────────────────────────

    /** Whether the VPN starts automatically when a private tab is opened */
    fun getVpnAutoStart(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_VPN_AUTO_START, true)

    fun setVpnAutoStart(ctx: Context, value: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_VPN_AUTO_START, value).apply()

    /** Whether private browsing tabs require biometric unlock */
    fun getPrivateTabsLocked(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_PRIVATE_TABS_LOCKED, false)

    fun setPrivateTabsLocked(ctx: Context, value: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_PRIVATE_TABS_LOCKED, value).apply()

    /** Primary DNS resolver address used by the VPN tunnel */
    fun getPrimaryDns(ctx: Context): String =
        prefs(ctx).getString(KEY_PRIMARY_DNS, DEFAULT_DNS_A) ?: DEFAULT_DNS_A

    fun setPrimaryDns(ctx: Context, value: String) =
        prefs(ctx).edit().putString(KEY_PRIMARY_DNS, value.trim()).apply()

    /** Secondary DNS resolver address used by the VPN tunnel */
    fun getSecondaryDns(ctx: Context): String =
        prefs(ctx).getString(KEY_SECONDARY_DNS, DEFAULT_DNS_B) ?: DEFAULT_DNS_B

    fun setSecondaryDns(ctx: Context, value: String) =
        prefs(ctx).edit().putString(KEY_SECONDARY_DNS, value.trim()).apply()

    /** Primary accent color used for glowing borders and staccato highlights */
    fun getGlowColor(ctx: Context): Int =
        prefs(ctx).getInt(KEY_GLOW_COLOR, android.graphics.Color.parseColor("#FF7C9EFF"))

    fun setGlowColor(ctx: Context, value: Int) =
        prefs(ctx).edit().putInt(KEY_GLOW_COLOR, value).apply()

    // ── AI & Intelligence ───────────────────────────────────────────────────

    const val AI_MODE_LOCAL = "local"
    const val AI_MODE_CLOUD = "cloud"

    fun getAiMode(ctx: Context): String =
        prefs(ctx).getString(KEY_AI_MODE, AI_MODE_LOCAL) ?: AI_MODE_LOCAL

    fun setAiMode(ctx: Context, value: String) =
        prefs(ctx).edit().putString(KEY_AI_MODE, value).apply()

    fun getCloudAiKey(ctx: Context): String =
        prefs(ctx).getString(KEY_CLOUD_AI_KEY, "") ?: ""

    fun setCloudAiKey(ctx: Context, value: String) =
        prefs(ctx).edit().putString(KEY_CLOUD_AI_KEY, value).apply()

    fun getCloudAiBaseUrl(ctx: Context): String =
        prefs(ctx).getString(KEY_CLOUD_AI_BASE_URL, "https://api.openai.com/v1/") ?: "https://api.openai.com/v1/"

    fun setCloudAiBaseUrl(ctx: Context, value: String) =
        prefs(ctx).edit().putString(KEY_CLOUD_AI_BASE_URL, value).apply()

    fun getCloudAiModel(ctx: Context): String =
        prefs(ctx).getString(KEY_CLOUD_AI_MODEL, "gpt-4o") ?: "gpt-4o"

    fun setCloudAiModel(ctx: Context, value: String) =
        prefs(ctx).edit().putString(KEY_CLOUD_AI_MODEL, value).apply()

    fun getLocalAiModelPath(ctx: Context): String =
        prefs(ctx).getString(KEY_LOCAL_AI_MODEL_PATH, "") ?: ""

    fun setLocalAiModelPath(ctx: Context, value: String) =
        prefs(ctx).edit().putString(KEY_LOCAL_AI_MODEL_PATH, value).apply()

    fun getAiDownloadId(ctx: Context): Long =
        prefs(ctx).getLong(KEY_AI_DOWNLOAD_ID, -1L)

    fun setAiDownloadId(ctx: Context, value: Long) =
        prefs(ctx).edit().putLong(KEY_AI_DOWNLOAD_ID, value).apply()


    // ────────────────────────────────────────────────────────────────────────

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    const val DEFAULT_DNS_A = "1.1.1.1"
    const val DEFAULT_DNS_B = "1.0.0.1"

    private const val KEY_DEFAULT_PAGE       = "default_page"
    private const val KEY_SHOW_DRAWER_LABELS = "show_drawer_labels"
    private const val KEY_SEARCH_ENGINE      = "search_engine"
    private const val KEY_CUSTOM_SEARCH_URL  = "custom_search_url"
    private const val KEY_JS_ENABLED         = "js_enabled"
    private const val KEY_PRIVATE_BY_DEFAULT = "private_by_default"
    private const val KEY_VPN_AUTO_START     = "vpn_auto_start"
    private const val KEY_PRIVATE_TABS_LOCKED = "private_tabs_locked"
    private const val KEY_PRIMARY_DNS        = "primary_dns"
    private const val KEY_SECONDARY_DNS      = "secondary_dns"
    private const val KEY_GLOW_COLOR         = "glow_color"

    private const val KEY_AI_MODE            = "ai_mode"
    private const val KEY_CLOUD_AI_KEY       = "cloud_ai_key"
    private const val KEY_CLOUD_AI_BASE_URL  = "cloud_ai_base_url"
    private const val KEY_CLOUD_AI_MODEL     = "cloud_ai_model"
    private const val KEY_AI_DOWNLOAD_ID     = "ai_download_id"
    private const val KEY_LOCAL_AI_MODEL_PATH = "local_ai_model_path"

    // Model Download URLs
    const val MODEL_FALCON_1B = "https://huggingface.co/vshymanskyy/falcon-1b-it-tflite/resolve/main/falcon-1b-it-cpu-int4.bin"
    const val MODEL_PHI_2 = "https://huggingface.co/vshymanskyy/phi-2-tflite/resolve/main/phi-2-cpu-int4.bin"
    const val MODEL_QWEN_1_5 = "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.task"
    const val MODEL_MOBILEBERT = "https://huggingface.co/google/mobilebert/resolve/main/mobilebert.tflite"
}
