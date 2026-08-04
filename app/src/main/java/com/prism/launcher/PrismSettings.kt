package com.prism.launcher

import android.content.Context
import com.prism.launcher.vpn.PrismProxyServer
import com.prism.launcher.vpn.VpnMultiplexer
import com.prism.launcher.vpn.WireguardController
import com.wireguard.crypto.Key
import com.wireguard.crypto.KeyPair
import java.io.File

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

    // ── DNS Proxy (for Access Points) ───────────────────────────────────────

    /** Whether the DNS Proxy Service is enabled (listens on 0.0.0.0:53) */
    fun getDnsProxyEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_DNS_PROXY_ENABLED, false)

    fun setDnsProxyEnabled(ctx: Context, value: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_DNS_PROXY_ENABLED, value).apply()

    /** DNS Proxy mode: "p2p_only" | "fallback" */
    fun getDnsProxyMode(ctx: Context): String =
        prefs(ctx).getString(KEY_DNS_PROXY_MODE, "p2p_only") ?: "p2p_only"

    fun setDnsProxyMode(ctx: Context, value: String) =
        prefs(ctx).edit().putString(KEY_DNS_PROXY_MODE, value).apply()

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

    // ── P2P AI Model Hosting ────────────────────────────────────────────────

    /** The "Host My Active Model" checkbox — only meaningful when tunneling is on and role is server. */
    fun getP2pModelHostingEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_P2P_MODEL_HOSTING_ENABLED, false)

    fun setP2pModelHostingEnabled(ctx: Context, value: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_P2P_MODEL_HOSTING_ENABLED, value).apply()

    /** The peer + model a client picked from the discovered P2pModelRegistry entries. */
    data class SelectedP2pModel(val peerIp: String, val modelName: String)

    fun getSelectedP2pModel(ctx: Context): SelectedP2pModel? {
        val raw = prefs(ctx).getString(KEY_SELECTED_P2P_MODEL, null) ?: return null
        val p = raw.split("::", limit = 2)
        if (p.size < 2) return null
        return SelectedP2pModel(p[0], p[1])
    }

    fun setSelectedP2pModel(ctx: Context, peerIp: String, modelName: String) {
        prefs(ctx).edit().putString(KEY_SELECTED_P2P_MODEL, "$peerIp::$modelName").apply()
    }

    fun clearSelectedP2pModel(ctx: Context) {
        prefs(ctx).edit().remove(KEY_SELECTED_P2P_MODEL).apply()
    }

    // ── Mesh Mirroring (P2P CDN) ──────────────────────────────────────────

    data class P2pMirroredSite(
        val domain: String,
        val localPath: String,
        val originalHost: String,
        val lastSync: Long,
        var isActive: Boolean = true
    )

    fun getP2pMirroredSites(ctx: Context): List<P2pMirroredSite> {
        val raw = prefs(ctx).getString(KEY_P2P_MIRRORED_SITES, "") ?: ""
        if (raw.isEmpty()) return emptyList()
        
        return raw.split(";;;").filter { it.isNotEmpty() }.mapNotNull { line ->
            val p = line.split("::")
            if (p.size < 5) null else P2pMirroredSite(p[0], p[1], p[2], p[3].toLong(), p[4] == "1")
        }
    }

    fun setP2pMirroredSites(ctx: Context, sites: List<P2pMirroredSite>) {
        val encoded = sites.joinToString(";;;") { 
            "${it.domain}::${it.localPath}::${it.originalHost}::${it.lastSync}::${if(it.isActive) "1" else "0"}"
        }
        prefs(ctx).edit().putString(KEY_P2P_MIRRORED_SITES, encoded).apply()
    }

    fun getMirrorsDir(ctx: Context): java.io.File {
        val storage = android.os.Environment.getExternalStorageDirectory()
        val mirrors = java.io.File(storage, "Prism/Mirrors")
        if (!mirrors.exists()) mirrors.mkdirs()
        return mirrors
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

    // ── Access Points ───────────────────────────────────────────────────────

    fun getAccessPoints(ctx: Context): List<com.prism.launcher.accesspoint.AccessPointConfig> {
        val raw = prefs(ctx).getString(KEY_ACCESS_POINTS, "") ?: ""
        if (raw.isEmpty()) return emptyList()
        return raw.split(";;;").filter { it.isNotEmpty() }.mapNotNull { line ->
            val p = line.split("::")
            if (p.size < 13) null else try {
                com.prism.launcher.accesspoint.AccessPointConfig(
                    id = p[0].toLong(),
                    ssidName = p[1],
                    isActive = p[2] == "1",
                    networkType = com.prism.launcher.accesspoint.NetworkType.valueOf(p[3]),
                    maxConnections = p[4].toInt(),
                    authType = com.prism.launcher.accesspoint.AuthType.valueOf(p[5]),
                    password = p[6],
                    ipRange = p[7],
                    gatewayIp = p[8],
                    allowedBandwidth = p[9].toLong(),
                    createdAt = p[10].toLong(),
                    lastModified = p[11].toLong(),
                    description = p[12]
                )
            } catch (e: Exception) { null }
        }
    }

    fun saveAccessPoint(ctx: Context, ap: com.prism.launcher.accesspoint.AccessPointConfig) {
        val current = getAccessPoints(ctx).toMutableList()
        val now = System.currentTimeMillis()
        if (ap.id == 0L) {
            val newId = now
            current.add(ap.copy(id = newId, createdAt = now, lastModified = now))
        } else {
            val idx = current.indexOfFirst { it.id == ap.id }
            val updated = ap.copy(lastModified = now)
            if (idx >= 0) current[idx] = updated else current.add(updated)
        }
        setAccessPoints(ctx, current)
    }

    fun removeAccessPoint(ctx: Context, apId: Long) {
        setAccessPoints(ctx, getAccessPoints(ctx).filter { it.id != apId })
    }

    private fun setAccessPoints(ctx: Context, list: List<com.prism.launcher.accesspoint.AccessPointConfig>) {
        val encoded = list.joinToString(";;;") {
            "${it.id}::${it.ssidName}::${if (it.isActive) "1" else "0"}::${it.networkType.name}::${it.maxConnections}::${it.authType.name}::${it.password}::${it.ipRange}::${it.gatewayIp}::${it.allowedBandwidth}::${it.createdAt}::${it.lastModified}::${it.description}"
        }
        prefs(ctx).edit().putString(KEY_ACCESS_POINTS, encoded).apply()
    }

    // ── AI & Intelligence ───────────────────────────────────────────────────

    const val AI_MODE_LOCAL = "local"
    const val AI_MODE_CLOUD = "cloud"
    const val AI_MODE_LOCAL_CLOUD = "local_cloud"

    /** The Ollama server + model a user picked after a LAN discovery scan (AI_MODE_LOCAL_CLOUD). */
    data class OllamaEndpoint(val host: String, val port: Int, val model: String)

    fun getSelectedOllamaEndpoint(ctx: Context): OllamaEndpoint? {
        val raw = prefs(ctx).getString(KEY_OLLAMA_ENDPOINT, null) ?: return null
        val p = raw.split("::")
        if (p.size < 3) return null
        val port = p[1].toIntOrNull() ?: return null
        return OllamaEndpoint(p[0], port, p[2])
    }

    fun setSelectedOllamaEndpoint(ctx: Context, endpoint: OllamaEndpoint) {
        prefs(ctx).edit().putString(KEY_OLLAMA_ENDPOINT, "${endpoint.host}::${endpoint.port}::${endpoint.model}").apply()
    }

    private const val KEY_AUTO_MIRROR = "browser_auto_mirror"

    fun getAutoMirror(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_AUTO_MIRROR, false)

    fun setAutoMirror(ctx: Context, value: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_AUTO_MIRROR, value).apply()

    fun getAiMode(ctx: Context): String =
        prefs(ctx).getString(KEY_AI_MODE, AI_MODE_LOCAL) ?: AI_MODE_LOCAL

    fun setAiMode(ctx: Context, value: String) =
        prefs(ctx).edit().putString(KEY_AI_MODE, value).apply()

    // ── Cloud Model Profiles ─────────────────────────────────────────────────
    // Replaces the old single api-key/base-url/model-id settings with a saved list the user
    // can switch between (see CloudModelsActivity). getCloudModels() lazily migrates whatever
    // was in the old single-profile fields into the first saved entry the first time it's
    // called after this update, so an existing Gemini/OpenAI key survives the upgrade.

    data class CloudModelProfile(
        val id: String,
        val apiKey: String,
        val baseUrl: String,
        val modelId: String
    )

    fun getCloudModels(ctx: Context): List<CloudModelProfile> {
        migrateLegacyCloudModelIfNeeded(ctx)
        val raw = prefs(ctx).getString(KEY_CLOUD_MODELS, "") ?: ""
        if (raw.isEmpty()) return emptyList()
        return raw.split(";;;").filter { it.isNotEmpty() }.mapNotNull { line ->
            val p = line.split("::")
            if (p.size < 4) null else CloudModelProfile(p[0], p[1], p[2], p[3])
        }
    }

    fun setCloudModels(ctx: Context, models: List<CloudModelProfile>) {
        val encoded = models.joinToString(";;;") {
            "${it.id}::${it.apiKey}::${it.baseUrl}::${it.modelId}"
        }
        prefs(ctx).edit().putString(KEY_CLOUD_MODELS, encoded).apply()
    }

    fun addCloudModel(ctx: Context, model: CloudModelProfile) {
        val current = getCloudModels(ctx).filter { it.id != model.id }.toMutableList()
        current.add(model)
        setCloudModels(ctx, current)
    }

    fun removeCloudModel(ctx: Context, id: String) {
        setCloudModels(ctx, getCloudModels(ctx).filter { it.id != id })
        if (getActiveCloudModelId(ctx) == id) setActiveCloudModelId(ctx, null)
    }

    fun getActiveCloudModelId(ctx: Context): String? =
        prefs(ctx).getString(KEY_ACTIVE_CLOUD_MODEL_ID, null)

    fun setActiveCloudModelId(ctx: Context, id: String?) =
        prefs(ctx).edit().putString(KEY_ACTIVE_CLOUD_MODEL_ID, id).apply()

    fun getActiveCloudModel(ctx: Context): CloudModelProfile? {
        val id = getActiveCloudModelId(ctx) ?: return null
        return getCloudModels(ctx).find { it.id == id }
    }

    private fun migrateLegacyCloudModelIfNeeded(ctx: Context) {
        if (prefs(ctx).getBoolean(KEY_CLOUD_MODELS_MIGRATED, false)) return
        prefs(ctx).edit().putBoolean(KEY_CLOUD_MODELS_MIGRATED, true).apply()

        val legacyKey = prefs(ctx).getString(KEY_CLOUD_AI_KEY, "") ?: ""
        if (legacyKey.isBlank()) return

        val legacyBaseUrl = prefs(ctx).getString(KEY_CLOUD_AI_BASE_URL, "https://api.openai.com/v1/")
            ?: "https://api.openai.com/v1/"
        val legacyModel = prefs(ctx).getString(KEY_CLOUD_AI_MODEL, "gpt-4o") ?: "gpt-4o"

        val profile = CloudModelProfile(
            id = java.util.UUID.randomUUID().toString(),
            apiKey = legacyKey,
            baseUrl = legacyBaseUrl,
            modelId = legacyModel
        )
        prefs(ctx).edit()
            .putString(KEY_CLOUD_MODELS, "${profile.id}::${profile.apiKey}::${profile.baseUrl}::${profile.modelId}")
            .putString(KEY_ACTIVE_CLOUD_MODEL_ID, profile.id)
            .apply()
    }

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

    /** Whether the in-flight download tracked by [getAiDownloadId] is an image (vs. text) model. */
    fun getAiDownloadIsImage(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_AI_DOWNLOAD_IS_IMAGE, false)

    fun setAiDownloadIsImage(ctx: Context, value: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_AI_DOWNLOAD_IS_IMAGE, value).apply()

    // ── OS Virtualization ────────────────────────────────────────────────────

    const val VIRT_MODE_PRISM_OS   = "prism_os"
    const val VIRT_MODE_CUSTOM_ISO = "custom_iso"

    fun getVirtualizationEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_VIRT_ENABLED, false)

    fun setVirtualizationEnabled(ctx: Context, v: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_VIRT_ENABLED, v).apply()

    fun getVirtualizationMode(ctx: Context): String =
        prefs(ctx).getString(KEY_VIRT_MODE, VIRT_MODE_PRISM_OS) ?: VIRT_MODE_PRISM_OS

    fun setVirtualizationMode(ctx: Context, v: String) =
        prefs(ctx).edit().putString(KEY_VIRT_MODE, v).apply()

    fun getCustomIsoPath(ctx: Context): String =
        prefs(ctx).getString(KEY_VIRT_ISO_PATH, "") ?: ""

    fun setCustomIsoPath(ctx: Context, v: String) =
        prefs(ctx).edit().putString(KEY_VIRT_ISO_PATH, v).apply()

    /** Whether AI responses stream in token-by-token instead of waiting for completion */
    fun getStreamingEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_STREAMING_ENABLED, true)

    fun setStreamingEnabled(ctx: Context, value: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_STREAMING_ENABLED, value).apply()

    /** Cap on generated tokens per response. -1 = unlimited (generate until the model stops) */
    fun getMaxTokens(ctx: Context): Int =
        prefs(ctx).getInt(KEY_MAX_TOKENS, -1)

    fun setMaxTokens(ctx: Context, value: Int) =
        prefs(ctx).edit().putInt(KEY_MAX_TOKENS, value).apply()

    /** Returns true if a local image model exists in internal storage */
    fun isLocalImageModelImported(ctx: Context): Boolean {
        val path = getLocalImageModelPath(ctx)
        if (path.isEmpty()) return false
        val file = java.io.File(path)
        return file.exists() && file.absolutePath.startsWith(ctx.filesDir.absolutePath)
    }

    // ── Imported Model Registry ─────────────────────────────────────────────

    const val MODEL_TYPE_TEXT = "text"
    const val MODEL_TYPE_IMAGE = "image"

    data class ImportedModel(
        val path: String,
        val displayName: String,
        val type: String, // MODEL_TYPE_TEXT | MODEL_TYPE_IMAGE
        val importedAt: Long = System.currentTimeMillis()
    )

    fun getImportedModels(ctx: Context): List<ImportedModel> {
        val raw = prefs(ctx).getString(KEY_IMPORTED_MODELS, "") ?: ""
        if (raw.isEmpty()) return emptyList()
        return raw.split(";;;").filter { it.isNotEmpty() }.mapNotNull { line ->
            val p = line.split("::")
            if (p.size < 4) null else try {
                ImportedModel(p[0], p[1], p[2], p[3].toLong())
            } catch (e: Exception) { null }
        }
    }

    fun setImportedModels(ctx: Context, models: List<ImportedModel>) {
        val encoded = models.joinToString(";;;") {
            "${it.path}::${it.displayName}::${it.type}::${it.importedAt}"
        }
        prefs(ctx).edit().putString(KEY_IMPORTED_MODELS, encoded).apply()
    }

    fun addImportedModel(ctx: Context, model: ImportedModel) {
        val current = getImportedModels(ctx).filter { it.path != model.path }.toMutableList()
        current.add(model)
        setImportedModels(ctx, current)
    }

    fun removeImportedModel(ctx: Context, path: String) {
        setImportedModels(ctx, getImportedModels(ctx).filter { it.path != path })
    }

    // ── KV Cache Compression (GGUF / llama.cpp engine) ──────────────────────

    const val KV_CACHE_F16 = "f16"
    const val KV_CACHE_Q8_0 = "q8_0"
    const val KV_CACHE_Q4_0 = "q4_0"

    /** Default matches OGAM: Q8_0 KV cache whenever flash attention is active (the common case). */
    fun getKvCacheQuant(ctx: Context): String =
        prefs(ctx).getString(KEY_KV_CACHE_QUANT, KV_CACHE_Q8_0) ?: KV_CACHE_Q8_0

    fun setKvCacheQuant(ctx: Context, value: String) =
        prefs(ctx).edit().putString(KEY_KV_CACHE_QUANT, value).apply()

    // ── AI Backend (forces CPU or GPU for both the GGUF/llama.cpp engine and the ────
    // ── MediaPipe/.task engine) ──────────────────────────────────────────────

    const val AI_BACKEND_CPU = 0
    const val AI_BACKEND_GPU = 1
    const val AI_BACKEND_NPU = 2

    /**
     * Defaults to GPU — safe even on devices without a GPU/OpenCL driver for GGUF models,
     * since model load automatically falls back to CPU on failure (see
     * GgufInferenceService/nativeLoadModel). For MediaPipe .task models, LocalAiService also
     * retries on CPU if GPU session init fails. Only actually accelerates Q4_0/Q8_0 GGUF quants;
     * K-quants (e.g. Q2_K) always run CPU. Force CPU here if generation is slow or unstable.
     */
    fun getAiBackend(ctx: Context): Int =
        prefs(ctx).getInt(KEY_AI_BACKEND, AI_BACKEND_GPU)

    fun setAiBackend(ctx: Context, value: Int) =
        prefs(ctx).edit().putInt(KEY_AI_BACKEND, value).apply()

    // ── Agentic Tools ────────────────────────────────────────────────────────

    /** Master switch: whether AiManager attempts tool-calling at all. Off by default since it
     * changes prompt construction and adds a network/latency round-trip per tool call. */
    fun getAgenticToolsEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_AGENTIC_ENABLED, false)

    fun setAgenticToolsEnabled(ctx: Context, value: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_AGENTIC_ENABLED, value).apply()

    /**
     * Which imported "syntax" (custom prompt-injection + tool-call extraction format) to use.
     * Null means "use the backend's native structured tool-calling" (Cloud/Ollama's own `tools`
     * request field) -- for local/GGUF models there is no native tool-calling API at all, so a
     * syntax is *required* for local tool use; if none is selected, AgenticEngine just skips
     * tool injection for local mode rather than failing.
     */
    fun getActiveAgenticSyntaxId(ctx: Context): String? =
        prefs(ctx).getString(KEY_ACTIVE_AGENTIC_SYNTAX_ID, null)

    fun setActiveAgenticSyntaxId(ctx: Context, id: String?) =
        prefs(ctx).edit().putString(KEY_ACTIVE_AGENTIC_SYNTAX_ID, id).apply()

    // ── Nebula Social background generation ──────────────────────────────────

    /** How often (in hours) the background service generates new Nebula posts/personas. Default 2. */
    fun getNebulaGenerationIntervalHours(ctx: Context): Int =
        prefs(ctx).getInt(KEY_NEBULA_INTERVAL_HOURS, 2)

    fun setNebulaGenerationIntervalHours(ctx: Context, hours: Int) =
        prefs(ctx).edit().putInt(KEY_NEBULA_INTERVAL_HOURS, hours).apply()

    // ────────────────────────────────────────────────────────────────────────

    // ── Nora ────────────────────────────────────────────────────────────────

    /**
     * Whether Nora's live connectome visualization runs.
     *
     * It renders on its own thread and reads telemetry through a lock-free snapshot, so it does
     * not block training -- but it is still real work on a device that is already saturated.
     * Turning it off is the right call on a hot or low-end phone.
     */
    fun getNoraVisualizerEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_NORA_VISUALIZER, true)

    fun setNoraVisualizerEnabled(ctx: Context, value: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_NORA_VISUALIZER, value).apply()

    /**
     * Whether training corrupts its inputs and learns to reconstruct the clean original.
     *
     * On by default: it multiplies the supervision per training image several-fold at no
     * generation-time cost, which matters a great deal when the dataset is a few dozen photos.
     * Turn it off to compare against plain reconstruction.
     */
    fun getNoraDenoisingEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_NORA_DENOISING, true)

    fun setNoraDenoisingEnabled(ctx: Context, value: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_NORA_DENOISING, value).apply()

    /**
     * Whether Nora retrains herself on her own dataset periodically, unattended.
     *
     * Opt-in, and off by default. Training is not a background nicety -- it holds a wake lock,
     * saturates the CPU for as long as it runs, and writes to storage every epoch. Turning that
     * on without being asked would be a battery and thermal decision made on the user's behalf,
     * which is not a decision to take silently.
     */
    fun getNoraAutoTrainEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_NORA_AUTOTRAIN, false)

    fun setNoraAutoTrainEnabled(ctx: Context, value: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_NORA_AUTOTRAIN, value).apply()

    /** Hours between unattended training runs. 1..24, default 10. */
    fun getNoraAutoTrainIntervalHours(ctx: Context): Int =
        prefs(ctx).getInt(KEY_NORA_AUTOTRAIN_HOURS, 10).coerceIn(1, 24)

    fun setNoraAutoTrainIntervalHours(ctx: Context, hours: Int) =
        prefs(ctx).edit().putInt(KEY_NORA_AUTOTRAIN_HOURS, hours.coerceIn(1, 24)).apply()

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
    private const val KEY_NORA_VISUALIZER    = "nora_visualizer"
    private const val KEY_NORA_DENOISING     = "nora_denoising"
    private const val KEY_NORA_AUTOTRAIN     = "nora_autotrain"
    private const val KEY_NORA_AUTOTRAIN_HOURS = "nora_autotrain_hours"

    private const val KEY_AI_MODE            = "ai_mode"
    private const val KEY_OLLAMA_ENDPOINT    = "ollama_endpoint"
    // These three are read-only-for-migration now -- CloudModelProfile replaced them as the
    // live storage, but a pre-update install's values still need to be read once to migrate.
    private const val KEY_CLOUD_AI_KEY       = "cloud_ai_key"
    private const val KEY_CLOUD_AI_BASE_URL  = "cloud_ai_base_url"
    private const val KEY_CLOUD_AI_MODEL     = "cloud_ai_model"
    private const val KEY_CLOUD_MODELS       = "cloud_models"
    private const val KEY_ACTIVE_CLOUD_MODEL_ID = "active_cloud_model_id"
    private const val KEY_CLOUD_MODELS_MIGRATED  = "cloud_models_migrated"
    private const val KEY_AI_DOWNLOAD_ID     = "ai_download_id"
    private const val KEY_AI_DOWNLOAD_IS_IMAGE = "ai_download_is_image"
    private const val KEY_AI_MODEL           = "ai_model"
    private const val KEY_LOCAL_AI_MODEL_PATH = "local_ai_model_path"
    private const val KEY_LOCAL_IMAGE_MODEL_PATH = "local_image_model_path"
    private const val KEY_STREAMING_ENABLED    = "ai_streaming_enabled"
    private const val KEY_MAX_TOKENS           = "ai_max_tokens"
    private const val KEY_IMPORTED_MODELS      = "imported_models"
    private const val KEY_KV_CACHE_QUANT       = "kv_cache_quant"
    private const val KEY_AI_BACKEND           = "ai_backend_mode"
    private const val KEY_NEBULA_INTERVAL_HOURS = "nebula_generation_interval_hours"
    private const val KEY_AGENTIC_ENABLED       = "agentic_tools_enabled"
    private const val KEY_ACTIVE_AGENTIC_SYNTAX_ID = "active_agentic_syntax_id"
    
    private const val KEY_DNS_PROXY_ENABLED  = "dns_proxy_enabled"
    private const val KEY_DNS_PROXY_MODE     = "dns_proxy_mode"
    
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
    private const val KEY_P2P_MODEL_HOSTING_ENABLED = "p2p_model_hosting_enabled"
    private const val KEY_SELECTED_P2P_MODEL     = "selected_p2p_model"
    private const val KEY_P2P_MIRRORED_SITES     = "p2p_mirrored_sites"
    private const val KEY_NETWORK_STORAGES       = "network_storages"
    private const val KEY_ACCESS_POINTS          = "access_points"
    private const val KEY_FONT_STYLE             = "font_style"
    private const val KEY_CUSTOM_FONT_PATH       = "custom_font_path"
    private const val KEY_VIRT_ENABLED           = "virt_enabled"
    private const val KEY_VIRT_MODE              = "virt_mode"
    private const val KEY_VIRT_ISO_PATH          = "virt_iso_path"
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
