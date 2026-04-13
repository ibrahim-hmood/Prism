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
import com.prism.launcher.PrismApp
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

    private val STATUS_NOTIFICATION_ID = 8102
    private var vpnStatusText = "Initializing..."
    private var sinkPackedIpv4: Set<Int> = emptySet()
    private var isTunnelEstablished = false

    private val blocklist by lazy { PrismBlocklist.get(applicationContext) }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 1. ALWAYS satisfy the OS contract immediately.
        // On Android 14+, if startForegroundService() was used, we MUST call startForeground()
        // before ANY return path or stopSelf(), otherwise the OS kills the app instantly.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }

        if (intent?.action == ACTION_STOP) {
            shutdownTunnelAndThreads()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        // Pre-flight check: Should we actually STAY in the foreground?
        val tunnelRequested = intent?.getBooleanExtra("EXTRA_ESTABLISH_TUNNEL", false) ?: false
        val alwaysOn = PrismSettings.getVpnServerAlwaysOn(this)
        
        if (!tunnelRequested && !alwaysOn) {
            // No tunnel requested and always-on is disabled. 
            // We already satisfied the contract above, so now we can stop safely.
            shutdownTunnelAndThreads()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        bootstrapTunnel(tunnelRequested)
        return START_STICKY
    }

    private fun updateStatusNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        
        val mode = PrismSettings.getPrismVpnRole(this)
        val port = PrismSettings.getPrismVpnPort(this)
        
        val isTunneling = (this as? PrivateDnsVpnService)?.let { true } ?: false // simplistic check
        val title = if (isTunneling) "Prism Privacy Tunnel" else "Prism Mesh Backbone"
        
        val content = if (mode == PrismSettings.PRISM_ROLE_SERVER) {
            "P2P Host: Active @ ${getLocalIpAddress()}:$port"
        } else {
            val servers = PrismSettings.getPrismServers(this)
            val active = servers.find { it.isActive }
            if (active != null) {
                "Client: Mesh Bridge -> ${active.name}"
            } else {
                "Mesh Connectivity Active"
            }
        }

        val pi = PendingIntent.getActivity(
            this, 1, Intent(this, LauncherActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText(content)
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        nm.notify(NOTIFICATION_ID, notification) 
    }

    private fun getLocalIpAddress(): String {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                val addrs = intf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        return addr.hostAddress ?: "Unknown"
                    }
                }
            }
        } catch (ex: Exception) {
            Log.e(TAG, "Error getting IP", ex)
        }
        return "Not Connected"
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

    private fun bootstrapTunnel(forceTunnel: Boolean) {
        val alwaysOn = PrismSettings.getVpnServerAlwaysOn(this)
        val shouldTunnel = forceTunnel || alwaysOn

        synchronized(tunnelLock) {
            // If already running, we might just need to establish or shutdown TUN
            if (serviceRunning) {
                if (shouldTunnel && tunInterface == null) {
                    establishTunnelLocked()
                } else if (!shouldTunnel && tunInterface != null) {
                    shutdownTunOnlyLocked()
                }
                updateStatusNotification()
                return
            }

            shutdownTunnelLocked()
            serviceRunning = true
            ioRunning = true
            
            // Start/Refresh the Mesh Engine singleton
            PrismApp.instance.tunnelEngine.start()
            
            if (shouldTunnel) {
                establishTunnelLocked()
            }
            
            updateStatusNotification()
        }
    }

    private fun establishTunnelLocked() {
        try {
            val pfd = buildMinimalTunnel() ?: return
            tunInterface = pfd
            startIoThreadLocked(pfd)
            startRouteRefreshLocked()
            isTunnelEstablished = true
        } catch (e: Exception) {
            Log.e(TAG, "failed to establish tunnel", e)
        }
    }

    private fun shutdownTunOnlyLocked() {
        ioRunning = false
        ioThread?.interrupt()
        ioThread = null
        routeRefreshThread?.interrupt()
        routeRefreshThread = null
        tunInterface?.close()
        tunInterface = null
        isTunnelEstablished = false
    }

    /**
     * Builds a minimal VPN interface with just the two DNS resolver /32 routes.
     * This establishes the tunnel immediately without any blocking network I/O.
     * Blocked-IP sink routes are added later by the route-refresh thread.
     */
    private fun buildMinimalTunnel(): ParcelFileDescriptor? {
        val dnsA = PrismSettings.getPrimaryDns(this)
        val dnsB = PrismSettings.getSecondaryDns(this)

        return this.Builder()
            .setSession("Prism Private DNS")
            .setMtu(1500)
            .addAddress(VPN_ADDRESS, 30)
            .addDnsServer(dnsA)
            .addDnsServer(dnsB)
            .addRoute(dnsA, 32)
            .addRoute(dnsB, 32)
            .addRoute("10.8.0.0", 24) // WireGuard Peer Subnet
            .establish()
    }

    private fun rebuildTunnelWithFreshRoutes() {
        if (!serviceRunning) return
        
        // 1. Perform heavy computation OUTSIDE of the lock to prevent UI thread hangs
        val comp = BlockedIpv4RouteTable.compute(this@PrivateDnsVpnService, blocklist)
        
        synchronized(tunnelLock) {
            if (!serviceRunning) return
            try {
                if (comp.packedIpv4 == sinkPackedIpv4) {
                    return
                }
                Log.i(TAG, "Refreshing VPN routes: ${comp.packedIpv4.size} blocked IPv4 sinks")
                ioRunning = false
                ioThread?.interrupt()
                try {
                    ioThread?.join(500)
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
                    Log.e(TAG, "re-establish() failed after route refresh")
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
        val builder = this.Builder()
            .setSession("Prism private DNS + IP sink")
            .setMtu(1500)
            .addAddress(VPN_ADDRESS, 30)
            .addDnsServer(DNS_A)
            .addDnsServer(DNS_B)
            .addRoute(DNS_A, 32)
            .addRoute(DNS_B, 32)
            .addRoute("10.8.0.0", 24) // WireGuard Peer Subnet

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

    fun injectPacket(packet: ByteArray) {
        synchronized(tunnelLock) {
            if (!ioRunning || tunInterface == null) return
            try {
                FileOutputStream(tunInterface?.fileDescriptor).write(packet)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to inject packet into TUN", e)
            }
        }
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
            // Un-comment for local debugging if desired, but kept quiet by default
            // Log.v(TAG, "Dropped IPv4 packet to sink (proto=$protocol len=$totalLen)")
            return null
        }

        // Pipe valid, non-sink traffic to our Tunnel Engine backbone.
        // The Engine handles routing outbound into P2P sockets or returning wrapped payloads.
        return PrismApp.instance.tunnelEngine.routeOutboundPacket(packet)
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
        val qtype = if (dnsPayload.size >= 16) u16(dnsPayload, 14) else 1
        val qname = parseDnsQueryName(dnsPayload) ?: return null

        // 1. Check P2P DNS Shadow Index
        val p2pIp = P2pDnsManager.resolve(qname)
        if (p2pIp != null) {
            Log.d("PrismDNS", "P2P Resolved $qname (type $qtype) -> $p2pIp")
            
            val p2pAnswer = if (qtype == 28) {
                // AAAA query for P2P: Return "No Data" response to prevent hangs
                buildNoDataResponse(dnsPayload)
            } else {
                buildAStaticResponse(dnsPayload, p2pIp)
            }

            return buildIpv4UdpResponse(
                srcIp = dstIp,
                dstIp = srcIp,
                srcPort = 53,
                dstPort = srcPort,
                payload = p2pAnswer,
            )
        }

        val hosts = blocklist.snapshotBlockedHosts()
        val suffixes = blocklist.snapshotSuffixes()
        val blocked = HostBlocklist.shouldBlock(qname, hosts, suffixes)
        val dnsAnswer = if (blocked) {
            buildNxDomainResponse(dnsPayload)
        } else {
            val answer = forwardDnsUdp(dnsPayload)
            if (answer != null) {
                // Auto-populate P2P DNS from global fallback
                extractIpv4FromDnsResponse(answer)?.let { ip ->
                    P2pDnsManager.seedFromGlobal(this@PrivateDnsVpnService, qname, ip)
                }
            }
            answer ?: return null
        }
        return buildIpv4UdpResponse(
            srcIp = dstIp,
            dstIp = srcIp,
            srcPort = 53,
            dstPort = srcPort,
            payload = dnsAnswer,
        )
    }

    private var dnsSocket: DatagramSocket? = null
    private fun getDnsSocket(): DatagramSocket? {
        if (dnsSocket == null || dnsSocket?.isClosed == true) {
            try {
                dnsSocket = DatagramSocket().apply {
                    protect(this)
                    soTimeout = 5000
                }
            } catch (e: Exception) {
                Log.e(TAG, "failed to create DNS socket", e)
            }
        }
        return dnsSocket
    }

    private fun forwardDnsUdp(query: ByteArray): ByteArray? {
        val socket = getDnsSocket() ?: return null
        return try {
            val target = InetAddress.getByName(DNS_A)
            socket.send(DatagramPacket(query, query.size, target, 53))
            val buf = ByteArray(4096)
            val resp = DatagramPacket(buf, buf.size)
            socket.receive(resp)
            buf.copyOf(resp.length)
        } catch (t: Throwable) {
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

    private fun buildAStaticResponse(query: ByteArray, ip: String): ByteArray {
        if (query.size < 12) return query
        val question = extractQuestionSection(query) ?: return query
        val resp = ByteArray(12 + question.size + 16) // Header + Question + Answer
        query.copyInto(resp, 0, 0, 12)
        resp[2] = 0x81.toByte()
        resp[3] = 0x80.toByte() // Standard Response, No Error
        resp[7] = 1 // 1 Answer
        question.copyInto(resp, 12)
        
        val offset = 12 + question.size
        resp[offset] = 0xc0.toByte() // Pointer to name
        resp[offset + 1] = 0x0c.toByte()
        resp[offset + 3] = 1 // Type A
        resp[offset + 5] = 1 // Class IN
        resp[offset + 9] = 300.toByte() // TTL 300
        resp[offset + 11] = 4 // Data length 4
        
        val ipParts = ip.split(".").map { it.toInt().toByte() }
        if (ipParts.size == 4) {
            resp[offset + 12] = ipParts[0]
            resp[offset + 13] = ipParts[1]
            resp[offset + 14] = ipParts[2]
            resp[offset + 15] = ipParts[3]
        }
        
        return resp
    }

    private fun extractIpv4FromDnsResponse(resp: ByteArray): String? {
        // Very minimal DNS response parser to extract the first A record result
        try {
            if (resp.size < 12) return null
            val ancount = ((resp[6].toInt() and 0xff) shl 8) or (resp[7].toInt() and 0xff)
            if (ancount == 0) return null
            
            // Skip header and question section
            var o = 12
            val qcount = ((resp[4].toInt() and 0xff) shl 8) or (resp[5].toInt() and 0xff)
            for (i in 0 until qcount) {
                while (o < resp.size && resp[o] != 0.toByte()) {
                    val len = resp[o].toInt() and 0xff
                    o += 1 + len
                }
                o += 5 // Null byte + Type (2) + Class (2)
            }
            
            // At first answer
            if (o + 12 > resp.size) return null
            // Skip Name pointer (2), Type (2), Class (2), TTL (4)
            val rdlen = ((resp[o + 10].toInt() and 0xff) shl 8) or (resp[o + 11].toInt() and 0xff)
            if (rdlen == 4 && o + 12 + 4 <= resp.size) {
                val a = resp[o + 12].toInt() and 0xff
                val b = resp[o + 13].toInt() and 0xff
                val c = resp[o + 14].toInt() and 0xff
                val d = resp[o + 15].toInt() and 0xff
                return "$a.$b.$c.$d"
            }
        } catch (e: Exception) {}
        return null
    }

    companion object {
        private var instance: PrivateDnsVpnService? = null
        fun protectSocket(socket: java.net.Socket) {
            instance?.protect(socket)
        }

        fun protectSocket(socket: java.net.DatagramSocket) {
            instance?.protect(socket)
        }

        fun getTunnelEngine(): PrismTunnelEngine? = PrismApp.instance.tunnelEngine

        const val ACTION_STOP = "com.prism.launcher.vpn.STOP"
        private const val CHANNEL_ID = "prism_vpn"
        private const val NOTIFICATION_ID = 7101
        private const val TAG = "PrivateDnsVpnService"
        private const val VPN_ADDRESS = "10.7.0.2"
        private const val DNS_A = "1.1.1.1"
        private const val DNS_B = "1.0.0.1"
        private const val ROUTE_REFRESH_INTERVAL_MS = 5L * 60L * 1000L
        
        fun buildNoDataResponse(query: ByteArray): ByteArray {
            if (query.size < 12) return query
            val question = extractQuestionSection(query) ?: return query
            val resp = ByteArray(12 + question.size)
            query.copyInto(resp, 0, 0, 12)
            resp[2] = 0x81.toByte()
            resp[3] = 0x80.toByte() // Standard Response, No Error
            resp[7] = 0 // 0 Answers
            question.copyInto(resp, 12)
            return resp
        }
        
        fun start(context: Context, establishTunnel: Boolean = false) {
            val intent = Intent(context, PrivateDnsVpnService::class.java)
            intent.putExtra("EXTRA_ESTABLISH_TUNNEL", establishTunnel)
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

private fun buildAStaticResponse(query: ByteArray, ip: String): ByteArray {
    if (query.size < 12) return query
    val question = extractQuestionSection(query) ?: return query
    val resp = ByteArray(12 + question.size + 16) // Header + Question + Answer
    query.copyInto(resp, 0, 0, 12)
    resp[2] = 0x81.toByte()
    resp[3] = 0x80.toByte() // Standard Response, No Error
    resp[7] = 1 // 1 Answer
    question.copyInto(resp, 12)
    
    val offset = 12 + question.size
    resp[offset] = 0xc0.toByte() // Pointer to name
    resp[offset + 1] = 0x0c.toByte()
    resp[offset + 3] = 1 // Type A
    resp[offset + 5] = 1 // Class IN
    resp[offset + 9] = 300.toByte() // TTL 300
    resp[offset + 11] = 4 // Data length 4
    
    val ipParts = ip.split(".").map { it.toInt().toByte() }
    if (ipParts.size == 4) {
        resp[offset + 12] = ipParts[0]
        resp[offset + 13] = ipParts[1]
        resp[offset + 14] = ipParts[2]
        resp[offset + 15] = ipParts[3]
    }
    
    return resp
}

private fun extractIpv4FromDnsResponse(resp: ByteArray): String? {
    // Very minimal DNS response parser to extract the first A record result
    try {
        if (resp.size < 12) return null
        val ancount = ((resp[6].toInt() and 0xff) shl 8) or (resp[7].toInt() and 0xff)
        if (ancount == 0) return null
        
        // Skip header and question section
        var o = 12
        val qcount = ((resp[4].toInt() and 0xff) shl 8) or (resp[5].toInt() and 0xff)
        for (i in 0 until qcount) {
            while (o < resp.size && resp[o] != 0.toByte()) {
                val len = resp[o].toInt() and 0xff
                o += 1 + len
            }
            o += 5 // Null byte + Type (2) + Class (2)
        }
        
        // At first answer
        if (o + 12 > resp.size) return null
        // Skip Name pointer (2), Type (2), Class (2), TTL (4)
        val rdlen = ((resp[o + 10].toInt() and 0xff) shl 8) or (resp[o + 11].toInt() and 0xff)
        if (rdlen == 4 && o + 12 + 4 <= resp.size) {
            val a = resp[o + 12].toInt() and 0xff
            val b = resp[o + 13].toInt() and 0xff
            val c = resp[o + 14].toInt() and 0xff
            val d = resp[o + 15].toInt() and 0xff
            return "$a.$b.$c.$d"
        }
    } catch (e: Exception) {}
    return null
}
