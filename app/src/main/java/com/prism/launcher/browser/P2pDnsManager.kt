package com.prism.launcher.browser

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import com.prism.launcher.PrismSettings
import java.io.File

/**
 * The core manager for Prism's Decentralized Name System.
 * Stores domain-to-IP mappings and synchronizes them across the mesh.
 */
object P2pDnsManager {

    private const val STORAGE_FILE = "p2p_dns_index.json"
    private val dnsIndex = mutableMapOf<String, DnsRecord>()

    private val _records = MutableStateFlow<Map<String, DnsRecord>>(emptyMap())
    val records: StateFlow<Map<String, DnsRecord>> = _records

    private val _resolutionState = MutableStateFlow<Map<String, ResolutionSource>>(emptyMap())
    val resolutionState: StateFlow<Map<String, ResolutionSource>> = _resolutionState

    data class DnsRecord(
        val ip: String,
        val timestamp: Long,
        val isVerified: Boolean = false,
        val source: ResolutionSource = ResolutionSource.P2P
    )

    enum class ResolutionSource {
        P2P, GLOBAL, UNKNOWN
    }

    fun getRecords(): Map<String, DnsRecord> = dnsIndex.toMap()

    fun updateRecord(context: Context, domain: String, ip: String, isVerified: Boolean = true, fromMesh: Boolean = false) {
        val record = DnsRecord(ip, System.currentTimeMillis(), isVerified, ResolutionSource.P2P)
        dnsIndex[domain] = record
        save(context)
        updateResolutionState(domain, ResolutionSource.P2P)
        
        if (!fromMesh) {
            val payload = JSONObject().apply {
                put("domain", domain)
                put("ip", ip)
                put("ts", record.timestamp)
            }.toString()
            com.prism.launcher.mesh.PrismMeshService.broadcastToOthers(0x05.toByte(), payload)
        }
    }

    fun deleteRecord(context: Context, domain: String) {
        dnsIndex.remove(domain)
        save(context)
        val current = _resolutionState.value.toMutableMap()
        current.remove(domain)
        _resolutionState.value = current
    }

    fun init(context: Context) {
        val storage = android.os.Environment.getExternalStorageDirectory()
        val prismDir = File(storage, "Prism")
        if (!prismDir.exists()) prismDir.mkdirs()
        
        val file = File(prismDir, STORAGE_FILE)
        val internalFile = File(context.filesDir, STORAGE_FILE)
        val targetFile = if (file.exists()) file else if (internalFile.exists()) internalFile else file

        if (targetFile.exists()) {
            try {
                val hostedDomains = PrismSettings.getP2pHostedSites(context).map { it.domain.lowercase() }.toSet()
                val json = JSONObject(targetFile.readText())
                json.keys().forEach { domain ->
                    val obj = json.getJSONObject(domain)
                    val srcStr = obj.optString("src", "")
                    
                    val source = if (srcStr.isNotEmpty()) {
                        try { ResolutionSource.valueOf(srcStr) } catch (e: Exception) { ResolutionSource.P2P }
                    } else {
                        // Legacy record scrubbing heuristic:
                        // Only treat as P2P if it has the .p2p extension or is in our local hosting list.
                        // Everything else (like duckduckgo.com) defaults to GLOBAL.
                        if (domain.endsWith(".p2p", ignoreCase = true) || hostedDomains.contains(domain.lowercase())) {
                            ResolutionSource.P2P
                        } else {
                            ResolutionSource.GLOBAL
                        }
                    }
                    
                    val record = DnsRecord(
                        obj.getString("ip"),
                        obj.getLong("ts"),
                        obj.optBoolean("v", false),
                        source
                    )
                    dnsIndex[domain] = record
                    
                    updateResolutionState(domain, source)
                }
            } catch (e: Exception) {}
        }
        _records.value = dnsIndex.toMap()
    }

    /**
     * Resolve a domain. If onlyP2p is true, it only returns a hit if the record
     * was obtained via the P2P mesh or ends in .p2p.
     */
    fun resolve(domain: String, onlyP2p: Boolean = false): String? {
        if (onlyP2p && !isP2pDomain(domain)) return null
        val record = dnsIndex[domain]
        return record?.ip
    }

    fun isP2pDomain(domain: String): Boolean {
        if (domain.isBlank()) return false
        if (domain.endsWith(".p2p", ignoreCase = true)) return true
        // Check both the record source and the in-memory state
        val record = dnsIndex[domain]
        if (record != null && record.source == ResolutionSource.P2P) return true
        return _resolutionState.value[domain] == ResolutionSource.P2P
    }

    fun seedFromGlobal(context: Context, domain: String, ip: String) {
        if (dnsIndex.containsKey(domain) && isP2pDomain(domain)) return
        val record = DnsRecord(ip, System.currentTimeMillis(), isVerified = true, source = ResolutionSource.GLOBAL)
        dnsIndex[domain] = record
        save(context)
        updateResolutionState(domain, ResolutionSource.GLOBAL)
    }

    fun ingestFromPeer(context: Context, peerRecords: Map<String, DnsRecord>) {
        var changed = false
        peerRecords.forEach { (domain, record) ->
            val existing = dnsIndex[domain]
            // We respect the source provided by the peer (P2P vs GLOBAL)
            if (existing == null || record.timestamp > existing.timestamp) {
                dnsIndex[domain] = record
                updateResolutionState(domain, record.source)
                changed = true
            }
        }
        if (changed) {
            save(context)
        }
    }

    private fun updateResolutionState(domain: String, source: ResolutionSource) {
        val current = _resolutionState.value.toMutableMap()
        current[domain] = source
        _resolutionState.value = current
        _records.value = dnsIndex.toMap()
    }

    private fun save(context: Context) {
        _records.value = dnsIndex.toMap()
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
                    put("src", record.source.name)
                }
                json.put(domain, obj)
            }
            File(prismDir, STORAGE_FILE).writeText(json.toString())
        } catch (e: Exception) {}
    }
}
