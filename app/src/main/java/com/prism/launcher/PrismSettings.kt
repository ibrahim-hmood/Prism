package com.prism.launcher

import android.content.Context
import com.prism.launcher.vpn.PrismProxyServer
import com.prism.launcher.vpn.VpnMultiplexer
import com.prism.launcher.vpn.WireguardController
import com.wireguard.crypto.Key
import com.wireguard.crypto.KeyPair

/**
 * Typed, centralized access to all user-configurable Prism settings.
 * Reads/writes to SharedPreferences("prism_settings") immediately on every call.
 * No "Save" button is needed anywhere in the UI.
 */
object PrismSettings {

    const val THEME_AUTO = 0
    const val THEME_LIGHT = 1
    const val THEME_DARK = 2

    private const val PREFS = "prism_settings"

    // ── Launcher ────────────────────────────────────────────────────────────

    /** Which pager page to show on launch: 0=Left, 1=Center, 2=Right */
    fun getDefaultPage(ctx: Context): Int =
        prefs(ctx).getInt(KEY_DEFAULT_PAGE, 1)

    fun setDefaultPage(ctx: Context, value: Int) =
        prefs(ctx).edit().putInt(KEY_DEFAULT_PAGE, value).apply()

    /** Which Icon Pack package is selected ("" for Default) */
    fun getIconPackPackage(ctx: Context): String =
        prefs(ctx).getString(KEY_ICON_PACK_PACKAGE, "") ?: ""

    fun setIconPackPackage(ctx: Context, value: String) =
        prefs(ctx).edit().putString(KEY_ICON_PACK_PACKAGE, value).apply()

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

    fun getThemeMode(ctx: Context): Int =
        prefs(ctx).getInt(KEY_THEME_MODE, THEME_AUTO)

    fun setThemeMode(ctx: Context, value: Int) =
        prefs(ctx).edit().putInt(KEY_THEME_MODE, value).apply()

    // ── VPN Tunneling ───────────────────────────────────────────────────────

    const val VPN_MODE_PRISM = "prism"
    const val VPN_MODE_EXTERNAL = "external"
    const val PRISM_ROLE_SERVER = "server"
    const val PRISM_ROLE_CLIENT = "client"

    /** Whether VPN Tunneling is enabled */
    fun getVpnTunnelingEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_VPN_TUNNELING_ENABLED, false)

    fun setVpnTunnelingEnabled(ctx: Context, value: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_VPN_TUNNELING_ENABLED, value).apply()

    /** Which VPN Mode is selected: "prism" | "external" */
    fun getVpnMode(ctx: Context): String =
        prefs(ctx).getString(KEY_VPN_MODE, VPN_MODE_PRISM) ?: VPN_MODE_PRISM

    fun setVpnMode(ctx: Context, value: String) =
        prefs(ctx).edit().putString(KEY_VPN_MODE, value).apply()

    /** Whether the Prism Server should stay active in the background without browsing */
    fun getVpnServerAlwaysOn(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_VPN_SERVER_ALWAYS_ON, false)

    fun setVpnServerAlwaysOn(ctx: Context, value: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_VPN_SERVER_ALWAYS_ON, value).apply()

    /** Prism VPN Role: "server" | "client" */
    fun getPrismVpnRole(ctx: Context): String =
        prefs(ctx).getString(KEY_PRISM_VPN_ROLE, PRISM_ROLE_CLIENT) ?: PRISM_ROLE_CLIENT

    fun setPrismVpnRole(ctx: Context, value: String) =
        prefs(ctx).edit().putString(KEY_PRISM_VPN_ROLE, value).apply()

    fun getPrismVpnPort(ctx: Context): String {
        val prefs = prefs(ctx)
        val storedPort = prefs.getString(KEY_PRISM_VPN_PORT, "")
        // Enforce exclusion of 8080 and other reserved ports for the Proxy
        if (storedPort.isNullOrBlank() || storedPort == "8080" || storedPort == "8081") {
            val port = MeshUtils.findAvailablePort().toString()
            setPrismVpnPort(ctx, port)
            return port
        }
        return storedPort
    }

    fun setPrismVpnPort(ctx: Context, value: String) {
        val isReserved = value == "8080" || value == "8081"
        val finalValue = if (value.isBlank() || isReserved) MeshUtils.findAvailablePort().toString() else value
        prefs(ctx).edit().putString(KEY_PRISM_VPN_PORT, finalValue).apply()
    }

    const val VPN_PROTOCOL_AUTO = "auto"
    const val VPN_PROTOCOL_IKEV2 = "ikev2"
    const val VPN_PROTOCOL_L2TP = "l2tp"
    const val VPN_PROTOCOL_PROXY = "proxy"

    fun getVpnProtocolMode(ctx: Context): String =
        prefs(ctx).getString(KEY_VPN_PROTOCOL_MODE, VPN_PROTOCOL_AUTO) ?: VPN_PROTOCOL_AUTO

    fun setVpnProtocolMode(ctx: Context, value: String) =
        prefs(ctx).edit().putString(KEY_VPN_PROTOCOL_MODE, value).apply()

    fun getPrismVpnTargetIp(ctx: Context): String =
        prefs(ctx).getString(KEY_PRISM_VPN_TARGET_IP, "") ?: ""

    fun setPrismVpnTargetIp(ctx: Context, value: String) =
        prefs(ctx).edit().putString(KEY_PRISM_VPN_TARGET_IP, value.trim()).apply()

    /** Primary Bootstrap server for the Mesh Network */
    fun getMeshBootstrapAddress(ctx: Context): String {
        val addr = prefs(ctx).getString(KEY_MESH_BOOTSTRAP_ADDRESS, "") ?: ""
        if (addr.isEmpty()) {
            val legacy = getPrismVpnTargetIp(ctx)
            if (legacy.isNotEmpty()) {
                setMeshBootstrapAddress(ctx, legacy)
                return legacy
            }
        }
        return addr
    }

    fun setMeshBootstrapAddress(ctx: Context, value: String) =
        prefs(ctx).edit().putString(KEY_MESH_BOOTSTRAP_ADDRESS, value.trim()).apply()

    fun getMeshBootstrapPort(ctx: Context): String =
        prefs(ctx).getString(KEY_MESH_BOOTSTRAP_PORT, "8081") ?: "8081"

    fun setMeshBootstrapPort(ctx: Context, value: String) =
        prefs(ctx).edit().putString(KEY_MESH_BOOTSTRAP_PORT, value.trim()).apply()

    fun getExternalVpnProfile(ctx: Context): String =
        prefs(ctx).getString(KEY_EXTERNAL_VPN_PROFILE, "") ?: ""
        
    fun setExternalVpnProfile(ctx: Context, value: String) =
        prefs(ctx).edit().putString(KEY_EXTERNAL_VPN_PROFILE, value).apply()

    fun getPrismVpnUsername(ctx: Context): String {
        val u = prefs(ctx).getString(KEY_PRISM_VPN_USERNAME, "") ?: ""
        if (u.isEmpty()) {
            val gen = "prism_user_" + (1000..9999).random()
            setPrismVpnUsername(ctx, gen)
            return gen
        }
        return u
    }

    fun setPrismVpnUsername(ctx: Context, value: String) =
        prefs(ctx).edit().putString(KEY_PRISM_VPN_USERNAME, value).apply()

    fun getPrismVpnPassword(ctx: Context): String {
        val p = prefs(ctx).getString(KEY_PRISM_VPN_PASSWORD, "") ?: ""
        if (p.isEmpty()) {
            val gen = generatePass()
            setPrismVpnPassword(ctx, gen)
            return gen
        }
        return p
    }

    fun setPrismVpnPassword(ctx: Context, value: String) =
        prefs(ctx).edit().putString(KEY_PRISM_VPN_PASSWORD, value).apply()

    fun getAppWhitelist(ctx: Context): Set<String> =
        prefs(ctx).getStringSet(KEY_APP_WHITELIST, emptySet()) ?: emptySet()

    fun setAppWhitelist(ctx: Context, packages: Set<String>) =
        prefs(ctx).edit().putStringSet(KEY_APP_WHITELIST, packages).apply()

    private fun generatePass(): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$%^&*"
        return (1..30).map { chars.random() }.joinToString("")
    }

    // ── Prism Server Fleet ──────────────────────────────────────────────────

    data class PrismServer(
        val id: String = java.util.UUID.randomUUID().toString(),
        val name: String,
        val address: String,
        val port: Int,
        val username: String,
        val password: String,
        var isActive: Boolean = false
    )

    fun getPrismServers(ctx: Context): List<PrismServer> {
        val raw = prefs(ctx).getString(KEY_PRISM_SERVER_LIST, "") ?: ""
        if (raw.isEmpty()) {
            // Migration: Create first server from legacy settings
            val legacyIp = prefs(ctx).getString(KEY_PRISM_VPN_TARGET_IP, "") ?: ""
            if (legacyIp.isEmpty()) return emptyList()
            
            val legacyServer = PrismServer(
                name = "Default Server",
                address = legacyIp,
                port = getPrismVpnPort(ctx).toIntOrNull() ?: 8888,
                username = getPrismVpnUsername(ctx),
                password = getPrismVpnPassword(ctx),
                isActive = true
            )
            val list = listOf(legacyServer)
            setPrismServers(ctx, list)
            return list
        }
        
        // De-serialize simple CSV for now to avoid bulky JSON libraries
        return raw.split(";;;").filter { it.isNotEmpty() }.mapNotNull { line ->
            val p = line.split("::")
            if (p.size < 7) null else PrismServer(p[0], p[1], p[2], p[3].toInt(), p[4], p[5], p[6] == "1")
        }
    }

    fun setPrismServers(ctx: Context, servers: List<PrismServer>) {
        val encoded = servers.joinToString(";;;") { 
            "${it.id}::${it.name}::${it.address}::${it.port}::${it.username}::${it.password}::${if(it.isActive) "1" else "0"}"
        }
        prefs(ctx).edit().putString(KEY_PRISM_SERVER_LIST, encoded).apply()
    }

    fun getP2pSelfId(ctx: Context): String {
        val id = prefs(ctx).getString(KEY_P2P_SELF_ID, "") ?: ""
        if (id.isEmpty()) {
            // Default to sanitized device model (e.g. "SM-S901U")
            val model = android.os.Build.MODEL.replace(" ", "-")
            prefs(ctx).edit().putString(KEY_P2P_SELF_ID, model).apply()
            return model
        }
        return id
    }

    fun getActiveServer(ctx: Context): PrismServer? {
        return getPrismServers(ctx).find { it.isActive }
    }

    /** Returns all known static mesh node addresses (Bootstrap + Fleet Servers) */
    fun getAllMeshNodes(ctx: Context): List<String> {
        val nodes = mutableSetOf<String>()
        val bootstrap = getMeshBootstrapAddress(ctx)
        if (bootstrap.isNotEmpty()) nodes.add(bootstrap)
        
        getPrismServers(ctx).forEach { 
            if (it.address.isNotEmpty()) nodes.add(it.address)
        }
        return nodes.toList()
    }

    // ── P2P Web Hosting ─────────────────────────────────────────────────────

    data class P2pHostedSite(
        val id: String = java.util.UUID.randomUUID().toString(),
        val domain: String,
        val localPath: String,
        var isActive: Boolean = true
    )

    fun getP2pHostedSites(ctx: Context): List<P2pHostedSite> {
        val raw = prefs(ctx).getString(KEY_P2P_HOSTED_SITES, "") ?: ""
        if (raw.isEmpty()) return emptyList()
        
        return raw.split(";;;").filter { it.isNotEmpty() }.mapNotNull { line ->
            val p = line.split("::")
            if (p.size < 4) null else P2pHostedSite(p[0], p[1], p[2], p[3] == "1")
        }
    }

    fun setP2pHostedSites(ctx: Context, sites: List<P2pHostedSite>) {
        val encoded = sites.joinToString(";;;") { 
            "${it.id}::${it.domain}::${it.localPath}::${if(it.isActive) "1" else "0"}"
        }
        prefs(ctx).edit().putString(KEY_P2P_HOSTED_SITES, encoded).apply()
    }

    // ── Networked Storage ───────────────────────────────────────────────────

    data class NetworkStorage(
        val id: String = java.util.UUID.randomUUID().toString(),
        val name: String,
        val protocol: String, // ftp, p2p, webdav, etc.
        val host: String,
        val port: Int,
        val username: String = "",
        val password: String = ""
    )

    fun getNetworkStorages(ctx: Context): List<NetworkStorage> {
        val raw = prefs(ctx).getString(KEY_NETWORK_STORAGES, "") ?: ""
        if (raw.isEmpty()) return emptyList()
        
        return raw.split(";;;").filter { it.isNotEmpty() }.mapNotNull { line ->
            val p = line.split("::")
            if (p.size < 7) null else NetworkStorage(p[0], p[1], p[2], p[3], p[4].toInt(), p[5], p[6])
        }
    }

    fun setNetworkStorages(ctx: Context, list: List<NetworkStorage>) {
        val encoded = list.joinToString(";;;") { 
            "${it.id}::${it.name}::${it.protocol}::${it.host}::${it.port}::${it.username}::${it.password}"
        }
        prefs(ctx).edit().putString(KEY_NETWORK_STORAGES, encoded).apply()
    }

    fun addNetworkStorage(ctx: Context, item: NetworkStorage) {
        val current = getNetworkStorages(ctx).toMutableList()
        current.add(item)
        setNetworkStorages(ctx, current)
    }

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

    fun getLocalImageModelPath(ctx: Context): String =
        prefs(ctx).getString(KEY_LOCAL_IMAGE_MODEL_PATH, "") ?: ""

    fun setLocalImageModelPath(ctx: Context, value: String) =
        prefs(ctx).edit().putString(KEY_LOCAL_IMAGE_MODEL_PATH, value).apply()

    fun getAiDownloadId(ctx: Context): Long =
        prefs(ctx).getLong(KEY_AI_DOWNLOAD_ID, -1L)

    fun setAiDownloadId(ctx: Context, value: Long) =
        prefs(ctx).edit().putLong(KEY_AI_DOWNLOAD_ID, value).apply()

    /** Returns true if a local image model exists in internal storage */
    fun isLocalImageModelImported(ctx: Context): Boolean {
        val path = getLocalImageModelPath(ctx)
        if (path.isEmpty()) return false
        val file = java.io.File(path)
        return file.exists() && file.absolutePath.startsWith(ctx.filesDir.absolutePath)
    }


    // ────────────────────────────────────────────────────────────────────────

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    const val DEFAULT_DNS_A = "1.1.1.1"
    const val DEFAULT_DNS_B = "1.0.0.1"

    private const val KEY_ICON_PACK_PACKAGE  = "icon_pack_package"
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
    private const val KEY_AI_MODEL           = "ai_model"
    private const val KEY_LOCAL_AI_MODEL_PATH = "local_ai_model_path"
    private const val KEY_LOCAL_IMAGE_MODEL_PATH = "local_image_model_path"
    
    private const val KEY_VPN_TUNNELING_ENABLED = "vpn_tunneling_enabled"
    private const val KEY_VPN_MODE           = "vpn_mode"
    private const val KEY_PRISM_VPN_ROLE     = "prism_vpn_role"
    private const val KEY_PRISM_VPN_PORT     = "prism_vpn_port"
    private const val KEY_PRISM_VPN_TARGET_IP = "prism_vpn_target_ip"
    private const val KEY_EXTERNAL_VPN_PROFILE = "external_vpn_profile"
    private const val KEY_PRISM_VPN_USERNAME = "prism_vpn_username"
    private const val KEY_PRISM_VPN_PASSWORD  = "prism_vpn_password"
    private const val KEY_APP_WHITELIST       = "app_whitelist"
    private const val KEY_VPN_PROTOCOL_MODE   = "vpn_protocol_mode"
    private const val KEY_PRISM_SERVER_LIST   = "prism_server_list"
    private const val KEY_VPN_SERVER_ALWAYS_ON = "vpn_server_always_on"
    private const val KEY_P2P_SELF_ID         = "p2p_self_id"
    private const val KEY_WG_SERVER_PRIVATE_KEY = "wg_server_private_key"
    private const val KEY_WG_SERVER_PUBLIC_KEY = "wg_server_public_key"
    private const val KEY_WG_SERVER_PORT         = "wg_server_port"
    private const val KEY_WG_ALLOWED_IPS        = "wg_allowed_ips"
    private const val KEY_MESH_BOOTSTRAP_ADDRESS = "mesh_bootstrap_address"
    private const val KEY_MESH_BOOTSTRAP_PORT    = "mesh_bootstrap_port"
    private const val KEY_P2P_HOSTED_SITES       = "p2p_hosted_sites"
    private const val KEY_NETWORK_STORAGES       = "network_storages"
    private const val KEY_FONT_STYLE             = "font_style"
    private const val KEY_CUSTOM_FONT_PATH       = "custom_font_path"
    private const val KEY_THEME_MODE             = "theme_mode"

    const val FONT_STYLE_DEFAULT = "default"
    const val FONT_STYLE_NASALIZATION = "nasalization"
    const val FONT_STYLE_CUSTOM = "custom"

    fun getFontStyle(ctx: Context): String =
        prefs(ctx).getString(KEY_FONT_STYLE, FONT_STYLE_DEFAULT) ?: FONT_STYLE_DEFAULT

    fun setFontStyle(ctx: Context, value: String) =
        prefs(ctx).edit().putString(KEY_FONT_STYLE, value).apply()

    fun getCustomFontPath(ctx: Context): String =
        prefs(ctx).getString(KEY_CUSTOM_FONT_PATH, "") ?: ""

    fun setCustomFontPath(ctx: Context, value: String) =
        prefs(ctx).edit().putString(KEY_CUSTOM_FONT_PATH, value).apply()

    fun getWgAllowedIps(ctx: Context): String =
        prefs(ctx).getString(KEY_WG_ALLOWED_IPS, "0.0.0.0/0") ?: "0.0.0.0/0"

    fun setWgAllowedIps(ctx: Context, value: String) =
        prefs(ctx).edit().putString(KEY_WG_ALLOWED_IPS, value.trim()).apply()

    fun getWgServerPrivateKey(ctx: Context): String {
        var priv = prefs(ctx).getString(KEY_WG_SERVER_PRIVATE_KEY, "") ?: ""
        if (priv.isEmpty()) {
            val kp = KeyPair()
            priv = kp.privateKey.toBase64()
            val pub = kp.publicKey.toBase64()
            prefs(ctx).edit()
                .putString(KEY_WG_SERVER_PRIVATE_KEY, priv)
                .putString(KEY_WG_SERVER_PUBLIC_KEY, pub)
                .apply()
        }
        return priv
    }

    fun getWgServerPublicKey(ctx: Context): String {
        getWgServerPrivateKey(ctx) // Ensure generated
        return prefs(ctx).getString(KEY_WG_SERVER_PUBLIC_KEY, "") ?: ""
    }

    fun getWgServerPort(ctx: Context): Int =
        prefs(ctx).getInt(KEY_WG_SERVER_PORT, 51820)

    fun setWgServerPort(ctx: Context, value: Int) =
        prefs(ctx).edit().putInt(KEY_WG_SERVER_PORT, value).apply()

    fun generateWgClientConfig(ctx: Context): String {
        val serverIp = "YOUR_PHONE_IP_HERE"
        val serverPort = getWgServerPort(ctx)
        val serverPubKey = getWgServerPublicKey(ctx)
        val allowedIps = getWgAllowedIps(ctx)
        
        val clientKeys = KeyPair()
        val clientPriv = clientKeys.privateKey.toBase64()
        
        return """
            [Interface]
            PrivateKey = $clientPriv
            Address = 10.8.0.2/24
            DNS = 10.8.0.1
 
            [Peer]
            PublicKey = $serverPubKey
            Endpoint = $serverIp:$serverPort
            AllowedIPs = $allowedIps
        """.trimIndent()
    }

    // Model Download URLs
    const val MODEL_FALCON_1B = "https://huggingface.co/vshymanskyy/falcon-1b-it-tflite/resolve/main/falcon-1b-it-cpu-int4.bin"
    const val MODEL_PHI_2 = "https://huggingface.co/vshymanskyy/phi-2-tflite/resolve/main/phi-2-cpu-int4.bin"
    const val MODEL_QWEN_1_5 = "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.task"
    const val MODEL_MOBILEBERT = "https://huggingface.co/google/mobilebert/resolve/main/mobilebert.tflite"

    // Diffusion Models
    const val MODEL_SD_1_5_CPU = "https://huggingface.co/sayakpaul/sd-1.5-openvino-tflite/resolve/main/sd-v1-5-int8-bundle.task"
}
