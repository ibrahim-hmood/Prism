package com.prism.launcher.browser

import android.content.Context
import android.util.Log
import com.prism.launcher.PrismSettings
import com.prism.launcher.vpn.AppUsageMonitor
import com.prism.launcher.vpn.PrismProxyClient
import com.prism.launcher.vpn.PrismProxyServer
import com.prism.launcher.vpn.PrismSocket
import com.prism.launcher.vpn.VpnMultiplexer
import com.prism.launcher.vpn.WireguardController
import com.prism.launcher.MeshUtils
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.SocketFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate

/**
 * High-level router that acts as the backbone for VPN tunneling when enabled.
 * Takes the raw traffic captured by the VpnService (that isn't blocked by DNS/sink routes)
 * and pipes it into the corresponding P2P or External VPN protocol.
 * 
 * Note: Protocol-level execution (WireGuard crypto, WebRTC relay) is stubbed.
 */
class PrismTunnelEngine(private val context: Context) {

    private var routingActive: Boolean = false
    private var currentMode: String? = null
    private var currentRole: String? = null
    
    private var proxyServer: PrismProxyServer? = null
    private var hostingServer: PrismProxyServer? = null
    private var vpnMultiplexer: VpnMultiplexer? = null

    // Persistent mesh fetcher: custom DNS for P2P resolution + universal SSL for any TLD
    private val httpClient: OkHttpClient by lazy {
        // Trust all certs — the user is explicitly routing through the mesh for any domain.
        // Hostname verification is also bypassed so .p2p, .local, .gov, .com all work identically.
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })

        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, trustAllCerts, java.security.SecureRandom())

        // Custom SSLSocketFactory that suppresses the SNI extension.
        //
        // Why: When OkHttp opens an HTTPS connection, it passes the domain name (e.g. "hi.p2p",
        // or any custom TLD) to the server as the SNI hint. If the server doesn't have a cert for
        // that name, it may silently drop the TCP connection instead of sending a TLS alert.
        // That silence causes OkHttp to wait for the full connect timeout → the browser "hangs".
        //
        // Fix: clear serverNames on the SSLSocket BEFORE the handshake, causing the server to
        // serve its default cert. Our permissive TrustManager and HostnameVerifier accept it
        // unconditionally, regardless of TLD (.p2p, .com, .gov, .local, anything).
        val noSniSslFactory = object : SSLSocketFactory() {
            private val delegate: SSLSocketFactory = sslContext.socketFactory

            override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
            override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

            // OkHttp calls this overload to wrap the already-connected TCP socket with TLS.
            // 'host' here is what OkHttp would use as the SNI hint — we suppress it.
            override fun createSocket(s: Socket, host: String, port: Int, autoClose: Boolean): Socket {
                return (delegate.createSocket(s, host, port, autoClose) as SSLSocket).apply {
                    suppressSni()
                }
            }

            override fun createSocket(host: String?, port: Int): Socket =
                (delegate.createSocket(host, port) as SSLSocket).apply { suppressSni() }

            override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
                (delegate.createSocket(host, port, localHost, localPort) as SSLSocket).apply { suppressSni() }

            override fun createSocket(host: InetAddress?, port: Int): Socket =
                (delegate.createSocket(host, port) as SSLSocket).apply { suppressSni() }

            override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
                (delegate.createSocket(address, port, localAddress, localPort) as SSLSocket).apply { suppressSni() }

            private fun SSLSocket.suppressSni() {
                try {
                    sslParameters = sslParameters.also { it.serverNames = emptyList() }
                } catch (_: Throwable) { /* best-effort on older APIs */ }
            }
        }

        OkHttpClient.Builder()
            .proxy(null) // Routing is handled by P2pDnsManager + SocketFactory
            .socketFactory(com.prism.launcher.vpn.PrismSocketFactory())
            .protocols(listOf(okhttp3.Protocol.HTTP_2, okhttp3.Protocol.HTTP_1_1))
            .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    // 1. Priority: Decentralized Mesh Lookup
                    val p2pIp = P2pDnsManager.resolve(hostname)
                    if (p2pIp != null) {
                        return listOf(InetAddress.getByName(p2pIp))
                    }

                    // 2. Fallback: Standard Global DNS
                    // We use Dns.SYSTEM which respects the device resolver settings.
                    return try {
                        val systemResult = Dns.SYSTEM.lookup(hostname)
                        
                        // 3. Crowdsourcing: Seed the Mesh with successful global results
                        if (systemResult.isNotEmpty()) {
                            val ip = systemResult[0].hostAddress
                            if (ip != null) {
                                com.prism.launcher.PrismLogger.logInfo("PrismTunnel", "Global Fallback Success: $hostname -> $ip. Seeding Mesh.")
                                P2pDnsManager.seedFromGlobal(context, hostname, ip)
                            }
                        }
                        systemResult
                    } catch (e: Exception) {
                        com.prism.launcher.PrismLogger.logWarning("PrismTunnel", "Global Fallback Failed for $hostname")
                        throw e
                    }
                }
            })
            .sslSocketFactory(noSniSslFactory, trustAllCerts[0] as X509TrustManager)
            // Universal hostname verifier — works for .com, .gov, .p2p, anything.
            .hostnameVerifier { _, _ -> true }
            // Handshaking SocketFactory: performs PRISM_CONNECT handshake for .p2p domains
            .socketFactory(object : SocketFactory() {
                private fun meshSocket(): PrismSocket = PrismSocket().also {
                    PrivateDnsVpnService.protectSocket(it)
                }
                override fun createSocket(): Socket = meshSocket()
                
                override fun createSocket(host: String, port: Int): Socket {
                    return meshSocket().apply {
                        connect(InetSocketAddress(host, port))
                    }
                }

                override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket {
                    return meshSocket().apply {
                        bind(InetSocketAddress(localHost, localPort))
                        connect(InetSocketAddress(host, port))
                    }
                }

                override fun createSocket(host: InetAddress, port: Int): Socket {
                    return meshSocket().apply {
                        connect(InetSocketAddress(host, port))
                    }
                }

                override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket {
                    return meshSocket().apply {
                        bind(InetSocketAddress(localAddress, localPort))
                        connect(InetSocketAddress(address, port))
                    }
                }
            })
            .build()
    }

    fun start() {
        if (routingActive) return
        routingActive = true
        
        if (!PrismSettings.getVpnTunnelingEnabled(context)) {
            Log.d("PrismTunnel", "Tunneling disabled in settings; Backbone idling.")
            return
        }
        
        currentMode = PrismSettings.getVpnMode(context)
        currentRole = PrismSettings.getPrismVpnRole(context)
        
        com.prism.launcher.PrismLogger.logInfo("PrismTunnel", "Starting Mesh Backbone (Mode: $currentMode, Role: $currentRole)")
        
        when (currentMode) {
            PrismSettings.VPN_MODE_PRISM -> {
                if (currentRole == PrismSettings.PRISM_ROLE_SERVER) {
                    startP2pServerMode()
                } else {
                    startP2pClientMode()
                }
            }
            PrismSettings.VPN_MODE_EXTERNAL -> {
                startExternalVpnTunnel()
            }
        }
        
        // Start usage monitor exclusively when tunneling is fundamentally requested and permissions are presumably granted
        AppUsageMonitor.start(context, this)
    }

    fun stop() {
        routingActive = false
        AppUsageMonitor.stop()
        hostingServer?.stop()
        hostingServer = null
        proxyServer?.stop()
        proxyServer = null
        vpnMultiplexer?.stop()
        vpnMultiplexer = null
        PrismProxyClient.stop()
        WireguardController.stop()
    }

    fun pauseTunnel() {
        routingActive = false
        hostingServer?.stop()
        hostingServer = null
        proxyServer?.stop()
        proxyServer = null
        vpnMultiplexer?.stop()
        vpnMultiplexer = null
        PrismProxyClient.stop()
        WireguardController.stop()
    }

    fun resumeTunnel() {
        if (!PrismSettings.getVpnTunnelingEnabled(context)) return
        routingActive = true
        when (currentMode) {
            PrismSettings.VPN_MODE_PRISM -> {
                if (currentRole == PrismSettings.PRISM_ROLE_SERVER) startP2pServerMode() else startP2pClientMode()
            }
            PrismSettings.VPN_MODE_EXTERNAL -> startExternalVpnTunnel()
        }
    }

    /**
     * Intercepts a raw IP packet from the TUN interface.
     * @return an optional response packet to inject back into the TUN, or null if dropped/forwarded asynchronously.
     */
    fun routeOutboundPacket(packet: ByteArray): ByteArray? {
        if (!routingActive) return null
        
        // 1. Check if this is a return packet for a WireGuard peer
        // IP header: version at [0], protocol at [9], Src at [12-15], Dst at [16-19]
        if (packet.size >= 20 && (packet[0].toInt() ushr 4) == 4) {
            val d1 = packet[16].toInt() and 0xFF
            val d2 = packet[17].toInt() and 0xFF
            val d3 = packet[18].toInt() and 0xFF
            
            // WireGuard Subnet: 10.8.0.x
            if (d1 == 10 && d2 == 8 && d3 == 0) {
                val lastOctet = packet[19].toInt() and 0xFF
                val peerIp = "10.8.0.$lastOctet"
                vpnMultiplexer?.wgServerEngine?.encapsulateAndSend(packet, peerIp)
                return null // Packet consumed by WG engine
            }
        }
        
        return null
    }

    private fun startP2pServerMode() {
        // 0. Ensure clean state (STOP old servers first to avoid EADDRINUSE)
        hostingServer?.stop()
        hostingServer = null
        proxyServer?.stop()
        proxyServer = null
        vpnMultiplexer?.stop()
        vpnMultiplexer = null
        
        // 0.1 Settling Delay: Give the kernel 200ms to clear socket states (TIME_WAIT)
        try { Thread.sleep(200) } catch(e: InterruptedException) { }

        var vpnPortStr = PrismSettings.getPrismVpnPort(context)
        // LAST MINUTE PORT GUARD: If VPN port is accidentally 8080/8081, force a re-discover
        if (vpnPortStr == "8080" || vpnPortStr == "8081" || vpnPortStr == "") {
            val newPort = MeshUtils.findAvailablePort().toString()
            PrismSettings.setPrismVpnPort(context, newPort)
            vpnPortStr = newPort
        }
        
        val port = vpnPortStr.toIntOrNull() ?: MeshUtils.findAvailablePort()
        val user = PrismSettings.getPrismVpnUsername(context)
        val pass = PrismSettings.getPrismVpnPassword(context)
        val protoMode = PrismSettings.getVpnProtocolMode(context)
        
        // 1. Start dedicated P2P Hosting Server (Always 8080)
        hostingServer = PrismProxyServer(8080, "PrismHost", isProxyMode = false)
        hostingServer?.start()

        // 2. Start VPN Proxy Server (Configurable, defaults to 8888)
        if (protoMode == PrismSettings.VPN_PROTOCOL_AUTO || protoMode == PrismSettings.VPN_PROTOCOL_PROXY) {
            proxyServer = PrismProxyServer(port, "PrismVPN", isProxyMode = true)
            proxyServer?.setCredentials(user, pass)
            proxyServer?.start()
        }

        // 3. Start UDP Multiplexer (IKEv2, L2TP, IPsec)
        if (protoMode != PrismSettings.VPN_PROTOCOL_PROXY) {
            // Signaling port defaults to 8081 now
            val udpPort = try { (PrismSettings.getMeshBootstrapPort(context)).toInt() } catch(e: Exception) { 8081 }
            vpnMultiplexer = com.prism.launcher.vpn.VpnMultiplexer(context, udpPort, pass, user, pass)
            vpnMultiplexer?.start()
        }
    }

    private fun startP2pClientMode() {
        PrismProxyClient.start(context)
    }

    private fun startExternalVpnTunnel() {
        WireguardController.init(context)
        val confData = PrismSettings.getExternalVpnProfile(context)
        if (confData.isNotEmpty()) {
            WireguardController.loadProfile(ByteArrayInputStream(confData.toByteArray()))
            WireguardController.start()
        } else {
            Log.w("PrismTunnel", "No WireGuard profile loaded to start.")
        }
    }

    /**
     * Resolves and fetches content from a peer in the mesh without needing a system VPN tunnel.
     * Works for any domain + any TLD in both public and private tabs.
     *
     * HTTPS: OkHttp builds a TLS session using [noSniSslFactory] (SNI suppressed) so the server
     * serves its default cert regardless of the domain name. The permissive trust manager and
     * hostname verifier accept it unconditionally, completing the handshake cleanly.
     */
    fun fetchMeshContent(url: String): Response? {
        val uri = android.net.Uri.parse(url)
        val host = uri.host ?: return null

        val request = Request.Builder()
            .url(url)
            .header("Host", host) // Preserve original hostname for virtual-host routing on the node
            .build()

        return try {
            httpClient.newCall(request).execute()
        } catch (e: Exception) {
            com.prism.launcher.PrismLogger.logError("PrismTunnel", "Mesh fetch failed for $url", e)
            null
        }
    }
}
