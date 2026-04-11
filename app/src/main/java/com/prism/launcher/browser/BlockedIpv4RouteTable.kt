package com.prism.launcher.browser

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.util.Log
import java.net.Inet4Address
import java.net.InetAddress
import java.util.Locale

/**
 * Resolves blocklist hostnames to IPv4 addresses so [PrivateDnsVpnService] can install /32 routes
 * and drop any packets (TCP/UDP/ICMP…) destined to those IPs—covering stale caches and non-DNS
 * paths, at the cost of possible false positives on shared IPs.
 */
object BlockedIpv4RouteTable {
    private const val TAG = "BlockedIpv4RouteTable"

    const val MAX_HOSTS_TO_RESOLVE = 450
    const val MAX_UNIQUE_IPS = 400

    fun compute(context: Context, blocklist: HostBlocklist): RouteComputation {
        val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network: Network = cm.activeNetwork ?: return RouteComputation(emptySet(), emptyList())

        val hosts = blocklist.snapshotBlockedHosts()
            .asSequence()
            .map { it.lowercase(Locale.US).trimEnd('.') }
            .filter { it.isNotEmpty() && !it.startsWith("*.") }
            .distinct()
            .sorted()
            .take(MAX_HOSTS_TO_RESOLVE)
            .toList()

        val packed = LinkedHashSet<Int>()
        val dotted = ArrayList<String>()

        for (h in hosts) {
            if (packed.size >= MAX_UNIQUE_IPS) break
            try {
                val addrs: Array<InetAddress> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    network.getAllByName(h)
                } else {
                    InetAddress.getAllByName(h)
                }
                for (a in addrs) {
                    if (a !is Inet4Address) continue
                    val ip = a.hostAddress ?: continue
                    if (isReservedDnsResolverIp(ip)) continue
                    val p = packIpv4(a.address)
                    if (packed.add(p)) {
                        dotted.add(ip)
                    }
                    if (packed.size >= MAX_UNIQUE_IPS) break
                }
            } catch (e: Throwable) {
                Log.d(TAG, "resolve skipped for $h: ${e.message}")
            }
        }

        return RouteComputation(packed.toSet(), dotted.toList())
    }

    private fun isReservedDnsResolverIp(ip: String): Boolean {
        return ip == "1.1.1.1" || ip == "1.0.0.1"
    }

    fun packIpv4(addr4: ByteArray): Int {
        require(addr4.size == 4)
        return ((addr4[0].toInt() and 0xff) shl 24) or
            ((addr4[1].toInt() and 0xff) shl 16) or
            ((addr4[2].toInt() and 0xff) shl 8) or
            (addr4[3].toInt() and 0xff)
    }

    fun destinationPackedIpv4(packet: ByteArray): Int? {
        if (packet.size < 20) return null
        if ((packet[0].toInt() ushr 4) != 4) return null
        val ihl = (packet[0].toInt() and 0x0f) * 4
        if (ihl < 20 || packet.size < ihl) return null
        return packIpv4(packet.copyOfRange(16, 20))
    }
}

data class RouteComputation(
    /** Packed big-endian IPv4 as Int for quick membership tests */
    val packedIpv4: Set<Int>,
    /** Dotted-quad strings for [VpnService.Builder.addRoute] */
    val ipv4DottedForRoutes: List<String>,
)
