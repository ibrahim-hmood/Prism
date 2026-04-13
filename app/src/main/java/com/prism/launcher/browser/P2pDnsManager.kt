package com.prism.launcher.browser

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.File

/**
 * The core manager for Prism's Decentralized Name System.
 * Stores domain-to-IP mappings and synchronizes them across the mesh.
 */
object P2pDnsManager {

    private const val STORAGE_FILE = "p2p_dns_index.json"
    private val dnsIndex = mutableMapOf<String, DnsRecord>()

    // Current resolution state for the browser UI
    private val _resolutionState = MutableStateFlow<Map<String, ResolutionSource>>(emptyMap())
    val resolutionState: StateFlow<Map<String, ResolutionSource>> = _resolutionState

    data class DnsRecord(
        val ip: String,
        val timestamp: Long,
        val isVerified: Boolean = false
    )

    enum class ResolutionSource {
        P2P, GLOBAL, UNKNOWN
    }

    fun getRecords(): Map<String, DnsRecord> = dnsIndex.toMap()

    fun updateRecord(context: Context, domain: String, ip: String, isVerified: Boolean = true) {
        dnsIndex[domain] = DnsRecord(ip, System.currentTimeMillis(), isVerified)
        save(context)
        updateResolutionState(domain, ResolutionSource.P2P)
    }

    fun deleteRecord(context: Context, domain: String) {
        dnsIndex.remove(domain)
        save(context)
        // Refresh resolution state to trigger UI update
        val current = _resolutionState.value.toMutableMap()
        current.remove(domain)
        _resolutionState.value = current
    }

    fun init(context: Context) {
        val storage = android.os.Environment.getExternalStorageDirectory()
        val prismDir = File(storage, "Prism")
        if (!prismDir.exists()) prismDir.mkdirs()
        
        val file = File(prismDir, STORAGE_FILE)
        // Migration check: check internal filesDir if external doesn't exist yet
        val internalFile = File(context.filesDir, STORAGE_FILE)
        
        val targetFile = if (file.exists()) file else if (internalFile.exists()) internalFile else file

        if (targetFile.exists()) {
            try {
                val json = JSONObject(file.readText())
                json.keys().forEach { domain ->
                    val obj = json.getJSONObject(domain)
                    dnsIndex[domain] = DnsRecord(
                        obj.getString("ip"),
                        obj.getLong("ts"),
                        obj.optBoolean("v", false)
                    )
                }
                Log.d("PrismDNS", "Loaded ${dnsIndex.size} P2P DNS records")
            } catch (e: Exception) {
                Log.e("PrismDNS", "Failed to load DNS index", e)
            }
        }
    }

    /**
     * Attempts to resolve a domain via the local P2P index.
     */
    fun resolve(domain: String): String? {
        val record = dnsIndex[domain]
        if (record != null) {
            updateResolutionState(domain, ResolutionSource.P2P)
            return record.ip
        }
        return null
    }

    /**
     * Seeds the P2P index with a new record (usually from global DNS fallback).
     */
    fun seedFromGlobal(context: Context, domain: String, ip: String) {
        if (dnsIndex.containsKey(domain)) return // Prefer existing P2P record
        
        Log.i("PrismDNS", "Auto-populating P2P record for $domain -> $ip")
        val record = DnsRecord(ip, System.currentTimeMillis(), isVerified = true)
        dnsIndex[domain] = record
        save(context)
        updateResolutionState(domain, ResolutionSource.GLOBAL)
    }

    /**
     * Ingests a batch of records from a mesh peer.
     */
    fun ingestFromPeer(context: Context, peerRecords: Map<String, DnsRecord>) {
        var changed = false
        peerRecords.forEach { (domain, record) ->
            val existing = dnsIndex[domain]
            if (existing == null || record.timestamp > existing.timestamp) {
                dnsIndex[domain] = record
                changed = true
            }
        }
        if (changed) save(context)
    }

    private fun updateResolutionState(domain: String, source: ResolutionSource) {
        val current = _resolutionState.value.toMutableMap()
        current[domain] = source
        _resolutionState.value = current
    }

    private fun save(context: Context) {
        try {
            val storage = android.os.Environment.getExternalStorageDirectory()
            val prismDir = File(storage, "Prism")
            if (!prismDir.exists()) prismDir.mkdirs()
            
            val json = JSONObject()
            dnsIndex.forEach { (domain, record) ->
                val obj = JSONObject().apply {
                    put("ip", record.ip)
                    put("ts", record.timestamp)
                    put("v", record.isVerified)
                }
                json.put(domain, obj)
            }
            File(prismDir, STORAGE_FILE).writeText(json.toString())
        } catch (e: Exception) {
            Log.e("PrismDNS", "Failed to save DNS index", e)
        }
    }
}
