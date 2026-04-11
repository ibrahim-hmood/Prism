package com.prism.launcher.browser

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationCompat
import com.prism.launcher.LauncherActivity
import com.prism.launcher.PrismSettings
import com.prism.launcher.R
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.concurrent.thread

/**
 * VPN tunnel that:
 * 1) Intercepts DNS (UDP/53) to configured resolvers, applies the hostname blocklist (NXDOMAIN or forward).
 * 2) Adds a /32 route per resolved blocked-host IPv4 so TCP/UDP/ICMP to those addresses is delivered to
 *    this TUN and **dropped**—ignoring connections that would otherwise use a cached IP or non-DNS path.
 *
 * Return traffic from blocked servers typically does not need to be handled here because outbound SYNs
 * never leave the device once sink routes are installed (no full userspace TCP stack required).
 */
class PrivateDnsVpnService : VpnService() {

    private val tunnelLock = Any()
    private var tunInterface: ParcelFileDescriptor? = null
    private var ioThread: Thread? = null
    private var routeRefreshThread: Thread? = null

    @Volatile
    private var ioRunning = false

    @Volatile
    private var serviceRunning = false

    @Volatile
    private var sinkPackedIpv4: Set<Int> = emptySet()

    private val blocklist by lazy { PrismBlocklist.get(applicationContext) }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // ALWAYS call startForeground immediately for any intent that triggers a service start
        // to satisfy Android 14+ background service requirements.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }

        if (intent?.action == ACTION_STOP) {
            stopForegroundCompat()
            shutdownTunnelAndThreads()
            stopSelf()
            return START_NOT_STICKY
        }

        if (tunInterface != null && serviceRunning) {
            return START_STICKY
        }

        thread(name = "prism-vpn-bootstrap") { bootstrapTunnel() }
        return START_STICKY
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(Service.STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    override fun onDestroy() {
        shutdownTunnelAndThreads()
        super.onDestroy()
    }

    override fun onRevoke() {
        shutdownTunnelAndThreads()
        stopSelf()
        super.onRevoke()
    }

    private fun bootstrapTunnel() {
        var shouldStop = false
        synchronized(tunnelLock) {
            shutdownTunnelLocked()
            serviceRunning = true
            ioRunning = true
            try {
                // Establish the VPN tunnel immediately with minimal routes so the
                // tunnel is active right away. No DNS resolution happens here.
                // Blocked-IP sink routes are installed by the first route-refresh pass.
                val pfd = buildMinimalTunnel() ?: run {
                    Log.e(TAG, "establish() returned null — VPN permission may have been revoked")
                    serviceRunning = false
                    ioRunning = false
                    shouldStop = true
                    return@synchronized
                }
                tunInterface = pfd
                startIoThreadLocked(pfd)
                startRouteRefreshLocked()
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to start tunnel", t)
                serviceRunning = false
                ioRunning = false
                shouldStop = true
            }
        }
        // stopSelf() must be called OUTSIDE the synchronized block —
        // onDestroy() also acquires tunnelLock, calling it inside would deadlock.
        if (shouldStop) stopSelf()
    }

    /**
     * Builds a minimal VPN interface with just the two DNS resolver /32 routes.
     * This establishes the tunnel immediately without any blocking network I/O.
     * Blocked-IP sink routes are added later by the route-refresh thread.
     */
    private fun buildMinimalTunnel(): ParcelFileDescriptor? {
        val dnsA = PrismSettings.getPrimaryDns(this)
        val dnsB = PrismSettings.getSecondaryDns(this)

        return Builder()
            .setSession("Prism Private DNS")
            .setMtu(1500)
            .addAddress(VPN_ADDRESS, 30)
            .addDnsServer(dnsA)
            .addDnsServer(dnsB)
            .addRoute(dnsA, 32)
            .addRoute(dnsB, 32)
            .establish()
    }

    private fun rebuildTunnelWithFreshRoutes() {
        synchronized(tunnelLock) {
            if (!serviceRunning) return
            try {
                val comp = BlockedIpv4RouteTable.compute(this@PrivateDnsVpnService, blocklist)
                if (comp.packedIpv4 == sinkPackedIpv4) {
                    return
                }
                Log.i(TAG, "Refreshing VPN routes: ${comp.packedIpv4.size} blocked IPv4 sinks")
                ioRunning = false
                ioThread?.interrupt()
                try {
                    ioThread?.join(2000)
                } catch (_: Throwable) {
                }
                ioThread = null
                try {
                    tunInterface?.close()
                } catch (_: Throwable) {
                }
                tunInterface = null

                sinkPackedIpv4 = comp.packedIpv4
                val pfd = establishLocked(comp) ?: run {
                    Log.e(TAG, "re-establish() failed after route refresh; stopping VPN I/O")
                    serviceRunning = false
                    ioRunning = false
                    return
                }
                tunInterface = pfd
                ioRunning = true
                startIoThreadLocked(pfd)
            } catch (t: Throwable) {
                Log.e(TAG, "Route refresh failed", t)
            }
        }
    }

    private fun establishLocked(comp: RouteComputation): ParcelFileDescriptor? {
        val builder = Builder()
            .setSession("Prism private DNS + IP sink")
            .setMtu(1500)
            .addAddress(VPN_ADDRESS, 30)
            .addDnsServer(DNS_A)
            .addDnsServer(DNS_B)
            .addRoute(DNS_A, 32)
            .addRoute(DNS_B, 32)

        for (ip in comp.ipv4DottedForRoutes) {
            try {
                builder.addRoute(ip, 32)
            } catch (t: Throwable) {
                Log.w(TAG, "addRoute failed for $ip", t)
            }
        }

        return builder.establish()
    }

    private fun startIoThreadLocked(pfd: ParcelFileDescriptor) {
        ioThread = thread(name = "prism-vpn-io") {
            val input = FileInputStream(pfd.fileDescriptor)
            val output = FileOutputStream(pfd.fileDescriptor)
            val buffer = ByteArray(32767)
            while (ioRunning) {
                val n = try {
                    input.read(buffer)
                } catch (_: Throwable) {
                    break
                }
                if (n <= 0) continue
                val packet = buffer.copyOf(n)
                val reply = handlePacket(packet) ?: continue
                try {
                    output.write(reply)
                } catch (_: Throwable) {
                    break
                }
            }
        }
    }

    private fun startRouteRefreshLocked() {
        routeRefreshThread = thread(name = "prism-vpn-route-refresh") {
            // First pass runs immediately — installs blocked-IP sink routes without
            // waiting 5 minutes. sinkPackedIpv4 starts empty so any resolved IPs
            // will trigger a rebuild on first call.
            if (serviceRunning) rebuildTunnelWithFreshRoutes()
            while (serviceRunning) {
                try {
                    Thread.sleep(ROUTE_REFRESH_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    break
                }
                if (!serviceRunning) break
                rebuildTunnelWithFreshRoutes()
            }
        }
    }

    private fun shutdownTunnelAndThreads() {
        synchronized(tunnelLock) {
            shutdownTunnelLocked()
        }
    }

    private fun shutdownTunnelLocked() {
        serviceRunning = false
        ioRunning = false
        try {
            routeRefreshThread?.interrupt()
        } catch (_: Throwable) {
        }
        try {
            routeRefreshThread?.join(1500)
        } catch (_: Throwable) {
        }
        routeRefreshThread = null

        try {
            ioThread?.interrupt()
        } catch (_: Throwable) {
        }
        try {
            ioThread?.join(1500)
        } catch (_: Throwable) {
        }
        ioThread = null

        try {
            tunInterface?.close()
        } catch (_: Throwable) {
        }
        // Explicitly nullify to ensure any re-start attempt sees it as gone
        tunInterface = null
    }

    private fun handlePacket(packet: ByteArray): ByteArray? {
        if (packet.isEmpty() || (packet[0].toInt() ushr 4) != 4) return null
        val ihlWords = packet[0].toInt() and 0x0f
        val ipHeaderLen = ihlWords * 4
        if (ipHeaderLen < 20 || packet.size < ipHeaderLen) return null
        val totalLen = u16(packet, 2)
        if (totalLen > packet.size) return null
        val protocol = packet[9].toInt() and 0xff

        if (protocol == OsConstants.IPPROTO_UDP) {
            val udpOff = ipHeaderLen
            if (udpOff + 8 <= totalLen) {
                val dstPort = u16(packet, udpOff + 2)
                if (dstPort == 53) {
                    return handleDnsPacket(packet, ipHeaderLen, totalLen, udpOff)
                }
            }
        }

        val dstPacked = BlockedIpv4RouteTable.destinationPackedIpv4(packet) ?: return null
        if (dstPacked in sinkPackedIpv4) {
            Log.v(TAG, "Dropped IPv4 packet to sink (proto=$protocol len=$totalLen)")
            return null
        }

        return null
    }

    private fun handleDnsPacket(
        packet: ByteArray,
        ipHeaderLen: Int,
        totalLen: Int,
        udpOff: Int,
    ): ByteArray? {
        val srcIp = packet.copyOfRange(12, 16)
        val dstIp = packet.copyOfRange(16, 20)
        val srcPort = u16(packet, udpOff)

        val dnsPayload = packet.copyOfRange(udpOff + 8, totalLen)
        val qname = parseDnsQueryName(dnsPayload) ?: return null

        val hosts = blocklist.snapshotBlockedHosts()
        val suffixes = blocklist.snapshotSuffixes()
        val blocked = HostBlocklist.shouldBlock(qname, hosts, suffixes)
        val dnsAnswer = if (blocked) {
            buildNxDomainResponse(dnsPayload)
        } else {
            forwardDnsUdp(dnsPayload) ?: return null
        }
        return buildIpv4UdpResponse(
            srcIp = dstIp,
            dstIp = srcIp,
            srcPort = 53,
            dstPort = srcPort,
            payload = dnsAnswer,
        )
    }

    private fun forwardDnsUdp(query: ByteArray): ByteArray? {
        return try {
            val socket = DatagramSocket()
            protect(socket)
            val target = InetAddress.getByName(DNS_A)
            socket.send(DatagramPacket(query, query.size, target, 53))
            val buf = ByteArray(4096)
            val resp = DatagramPacket(buf, buf.size)
            socket.receive(resp)
            socket.close()
            buf.copyOf(resp.length)
        } catch (t: Throwable) {
            Log.w(TAG, "DNS forward failed", t)
            null
        }
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.vpn_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
        val pi = PendingIntent.getActivity(
            this,
            0,
            Intent(this, LauncherActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(getString(R.string.vpn_notification_title))
            .setContentText(getString(R.string.vpn_notification_text))
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "PrivateDnsVpnService"
        const val ACTION_STOP = "com.prism.launcher.vpn.STOP"

        private const val CHANNEL_ID = "prism_vpn"
        private const val NOTIFICATION_ID = 7101

        private const val VPN_ADDRESS = "10.7.0.2"
        private const val DNS_A = "1.1.1.1"
        private const val DNS_B = "1.0.0.1"

        private const val ROUTE_REFRESH_INTERVAL_MS = 5L * 60L * 1000L

        fun start(context: Context) {
            val intent = Intent(context, PrivateDnsVpnService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                @Suppress("DEPRECATION")
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(Intent(context, PrivateDnsVpnService::class.java).setAction(ACTION_STOP))
        }
    }
}

private fun u16(b: ByteArray, o: Int): Int =
    ((b[o].toInt() and 0xff) shl 8) or (b[o + 1].toInt() and 0xff)

private fun parseDnsQueryName(query: ByteArray): String? {
    if (query.size < 13) return null
    var o = 12
    val labels = ArrayList<String>()
    while (o < query.size) {
        val len = query[o].toInt() and 0xff
        if (len == 0) break
        if (len >= 64) return null // compression not handled
        o++
        if (o + len > query.size) return null
        labels.add(String(query, o, len, Charsets.US_ASCII))
        o += len
    }
    if (labels.isEmpty()) return null
    return labels.joinToString(".")
}

private fun buildNxDomainResponse(query: ByteArray): ByteArray {
    if (query.size < 12) return query
    val question = extractQuestionSection(query) ?: return query
    val resp = ByteArray(12 + question.size)
    resp[0] = query[0]
    resp[1] = query[1]
    resp[2] = 0x81.toByte()
    resp[3] = 0x83.toByte() // NXDOMAIN
    resp[4] = query[4]
    resp[5] = query[5]
    resp[6] = 0
    resp[7] = 0
    resp[8] = 0
    resp[9] = 0
    resp[10] = 0
    resp[11] = 0
    question.copyInto(resp, destinationOffset = 12)
    return resp
}

private fun extractQuestionSection(query: ByteArray): ByteArray? {
    if (query.size < 13) return null
    var o = 12
    while (o < query.size) {
        val len = query[o].toInt() and 0xff
        if (len == 0) {
            o++
            break
        }
        if (len >= 64) return null
        o += 1 + len
    }
    if (o + 4 > query.size) return null
    return query.copyOfRange(12, o + 4)
}

private fun buildIpv4UdpResponse(
    srcIp: ByteArray,
    dstIp: ByteArray,
    srcPort: Int,
    dstPort: Int,
    payload: ByteArray,
): ByteArray {
    val udpLen = 8 + payload.size
    val totalLen = 20 + udpLen
    val packet = ByteArray(totalLen)
    packet[0] = 0x45
    packet[1] = 0
    writeU16(packet, 2, totalLen)
    packet[4] = 0
    packet[5] = 0
    packet[6] = 0x40 // don't fragment
    packet[7] = 0
    packet[8] = 64 // ttl
    packet[9] = OsConstants.IPPROTO_UDP.toByte()
    srcIp.copyInto(packet, 12)
    dstIp.copyInto(packet, 16)
    writeU16(packet, 20, srcPort)
    writeU16(packet, 22, dstPort)
    writeU16(packet, 24, udpLen)
    writeU16(packet, 26, 0) // udp checksum 0
    payload.copyInto(packet, 28)
    val csum = ipv4HeaderChecksum(packet, 20)
    writeU16(packet, 10, csum)
    return packet
}

private fun writeU16(b: ByteArray, offset: Int, value: Int) {
    b[offset] = ((value shr 8) and 0xff).toByte()
    b[offset + 1] = (value and 0xff).toByte()
}

private fun ipv4HeaderChecksum(header: ByteArray, length: Int): Int {
    var sum = 0
    var i = 0
    while (i < length) {
        if (i == 10) {
            i += 2
            continue
        }
        val word = ((header[i].toInt() and 0xff) shl 8) or (header[i + 1].toInt() and 0xff)
        sum += word
        i += 2
    }
    while (sum ushr 16 != 0) {
        sum = (sum and 0xffff) + (sum shr 16)
    }
    return sum.inv() and 0xffff
}
