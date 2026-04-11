package com.prism.launcher.browser

import android.content.Context
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

/**
 * Hostname blocklist similar in spirit to DNS/hosts blockers (e.g. AdAway / StevenBlack hosts).
 * Used for WebView request interception and by the private DNS VPN path.
 */
class HostBlocklist(context: Context) {
    private val appContext = context.applicationContext
    private val blockedHosts = AtomicReference<Set<String>>(emptySet())
    private val blockedSuffixes = AtomicReference<List<String>>(emptyList())
    private val customPrefs by lazy {
        appContext.getSharedPreferences(CUSTOM_PREFS, Context.MODE_PRIVATE)
    }

    init {
        reloadFromDisk()
        Thread({ tryDownloadStevenBlack() }, "prism-blocklist").start()
    }

    fun snapshotBlockedHosts(): Set<String> = blockedHosts.get()
    fun snapshotSuffixes(): List<String> = blockedSuffixes.get()

    /** Returns all blocked entries (hosts + suffix patterns) sorted for display in UI. */
    fun snapshotAllDomains(): List<String> {
        val result = mutableListOf<String>()
        result.addAll(blockedHosts.get())
        blockedSuffixes.get().forEach { result.add("*.$it") }
        result.sort()
        return result
    }

    /** Returns only the user-added custom domains (not from downloaded lists). */
    fun snapshotCustomDomains(): Set<String> =
        customPrefs.getStringSet(KEY_CUSTOM_DOMAINS, emptySet()) ?: emptySet()

    /**
     * Adds [host] to the user-managed custom domain set and reloads the merged blocklist.
     * Safe to call from any thread.
     */
    fun addCustomDomain(host: String) {
        val clean = host.lowercase(Locale.US).trim().trimEnd('.')
        if (clean.isEmpty()) return
        val current = customPrefs.getStringSet(KEY_CUSTOM_DOMAINS, emptySet())?.toMutableSet() ?: mutableSetOf()
        current.add(clean)
        customPrefs.edit().putStringSet(KEY_CUSTOM_DOMAINS, current).commit()
        reloadFromDisk()
    }

    /**
     * Removes [host] from the user-managed custom domain set and reloads.
     * Safe to call from any thread.
     */
    fun removeCustomDomain(host: String) {
        val clean = host.lowercase(Locale.US).trim().trimEnd('.')
        val current = customPrefs.getStringSet(KEY_CUSTOM_DOMAINS, emptySet())?.toMutableSet() ?: return
        current.remove(clean)
        customPrefs.edit().putStringSet(KEY_CUSTOM_DOMAINS, current).commit()
        reloadFromDisk()
    }

    /**
     * Bulk-replaces the user custom domain set with [hosts] (merged with existing).
     * Used when importing a hosts file. Existing custom domains are kept unless explicitly cleared.
     */
    fun mergeCustomDomains(hosts: Collection<String>) {
        val cleaned = hosts.map { it.lowercase(Locale.US).trim().trimEnd('.') }.filter { it.isNotEmpty() }.toSet()
        val current = customPrefs.getStringSet(KEY_CUSTOM_DOMAINS, emptySet())?.toMutableSet() ?: mutableSetOf()
        current.addAll(cleaned)
        customPrefs.edit().putStringSet(KEY_CUSTOM_DOMAINS, current).commit()
        reloadFromDisk()
    }

    /** Removes all user-added custom domains. Does not affect downloaded lists. */
    fun clearCustomDomains() {
        customPrefs.edit().remove(KEY_CUSTOM_DOMAINS).commit()
        reloadFromDisk()
    }

    fun shouldBlockHost(host: String): Boolean {
        val h = host.lowercase(Locale.US).trimEnd('.')
        if (h.isEmpty()) return false
        if (blockedHosts.get().contains(h)) return true
        for (suf in blockedSuffixes.get()) {
            if (h == suf || h.endsWith(".$suf")) return true
        }
        return false
    }

    fun reloadFromDisk() {
        val merged = LinkedHashSet<String>()
        // 1. Asset seed list
        try {
            appContext.assets.open("mini_blocklist.txt").bufferedReader().useLines { lines ->
                parseHostsLines(lines, merged)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "mini_blocklist missing", e)
        }
        // 2. Downloaded StevenBlack list
        try {
            val f = appContext.getFileStreamPath(DOWNLOADED_FILE)
            if (f.exists()) {
                f.bufferedReader().useLines { lines -> parseHostsLines(lines, merged) }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "downloaded blocklist read failed", e)
        }
        // 3. User custom domains
        try {
            val custom = customPrefs.getStringSet(KEY_CUSTOM_DOMAINS, emptySet()) ?: emptySet()
            merged.addAll(custom)
        } catch (e: Throwable) {
            Log.w(TAG, "custom domains read failed", e)
        }
        applyMerged(merged)
    }

    private fun tryDownloadStevenBlack() {
        try {
            val url = URL(STEVENBLACK_RAW)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 12_000
                readTimeout = 20_000
                instanceFollowRedirects = true
            }
            conn.inputStream.bufferedReader().useLines { lines ->
                val merged = LinkedHashSet(snapshotBlockedHosts())
                parseHostsLines(lines, merged)
                applyMerged(merged)
                appContext.openFileOutput(DOWNLOADED_FILE, Context.MODE_PRIVATE).bufferedWriter().use { out ->
                    for (h in merged.sorted()) {
                        out.append("0.0.0.0 ").append(h).append('\n')
                    }
                }
            }
            conn.disconnect()
        } catch (e: Throwable) {
            Log.i(TAG, "Remote blocklist update skipped: ${e.message}")
        }
    }

    private fun applyMerged(merged: Set<String>) {
        val hosts = HashSet<String>()
        val suffixes = ArrayList<String>()
        for (entry in merged) {
            val h = entry.lowercase(Locale.US).trimEnd('.')
            if (h.isEmpty() || h == "localhost") continue
            if (h.startsWith("*.")) {
                suffixes.add(h.removePrefix("*."))
            } else {
                hosts.add(h)
            }
        }
        suffixes.sortByDescending { it.length }
        blockedHosts.set(hosts)
        blockedSuffixes.set(suffixes)
    }

    private fun parseHostsLines(lines: Sequence<String>, out: MutableSet<String>) {
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val parts = line.split(Regex("\\s+"))
            if (parts.size < 2) continue
            val host = parts[1].lowercase(Locale.US).trimEnd('.')
            if (host.isEmpty()) continue
            out.add(host)
        }
    }

    companion object {
        private const val TAG = "HostBlocklist"
        private const val DOWNLOADED_FILE = "prism_hosts_merged.txt"
        private const val CUSTOM_PREFS = "prism_custom_blocks"
        private const val KEY_CUSTOM_DOMAINS = "domains"
        private const val STEVENBLACK_RAW =
            "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts"

        fun shouldBlock(host: String, hosts: Set<String>, suffixes: List<String>): Boolean {
            val h = host.lowercase(Locale.US).trimEnd('.')
            if (h.isEmpty()) return false
            if (hosts.contains(h)) return true
            for (suf in suffixes) {
                if (h == suf || h.endsWith(".$suf")) return true
            }
            return false
        }
    }
}
