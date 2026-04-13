package com.prism.launcher.vpn

import android.util.Log
import com.prism.launcher.PrismApp
import com.prism.launcher.PrismLogger
import com.prism.launcher.browser.P2pDnsManager
import com.prism.launcher.browser.PrivateDnsVpnService
import com.prism.launcher.MeshUtils
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress

/**
 * A Mesh-aware Socket subclass that automatically handles port redirection (to 8080)
 * and the PRISM_CONNECT handshake for all P2P Mesh traffic.
 */
class PrismSocket : Socket() {

    private var targetHost: String? = null

    /**
     * Set the domain name hint for the handshake if known.
     */
    fun setHostHint(host: String) {
        this.targetHost = host
    }

    override fun connect(endpoint: SocketAddress?) {
        connect(endpoint, 0)
    }

    override fun connect(endpoint: SocketAddress?, timeout: Int) {
        if (endpoint !is InetSocketAddress) {
            super.connect(endpoint, timeout)
            return
        }

        val originalHost = endpoint.hostName ?: endpoint.address?.hostAddress ?: "unknown"
        val originalPort = endpoint.port
        
        // 1. Resolve domain for mesh status
        val p2pIp = P2pDnsManager.resolve(originalHost)
        val ipAddr = endpoint.address?.hostAddress ?: ""
        
        // Dynamic Mesh Detection:
        // - In P2P DNS list
        // - In VPN Subnet (10.8.0.x)
        // - Already on mesh port (8080)
        // - Matches peer naming convention (contains "-s-")
        // - Domain ends in ".p2p"
        val isMeshTarget = p2pIp != null || 
                           ipAddr.startsWith("10.8.0.") || 
                           ipAddr.startsWith("192.0.2.") || 
                           originalPort == 8080 || 
                           originalHost.contains("-s-") || 
                           originalHost.endsWith(".p2p")
        
        if (!isMeshTarget) {
            super.connect(endpoint, timeout)
            return
        }

        // 2. Perform Redirection & Handshake
        try {
            val meshIp = p2pIp ?: ipAddr
            val finalHost = if (meshIp.startsWith("10.8.0.")) meshIp else originalHost
            val finalPort = 8080
            
            PrismLogger.logInfo("PrismSocket", "Mesh Redirection: $originalHost:$originalPort -> $finalHost:$finalPort")
            
            // Handle local loop: if target is self, connect to local IP (Reliable loopback)
            // 192.0.2.1 is treated as the synthetic local loopback for Mesh identities
            val localIp = MeshUtils.getLocalMeshIp(PrismApp.instance)
            val isLocal = meshIp == localIp || meshIp == "192.0.2.1" || meshIp == "127.0.0.1"
            val connectAddr = if (isLocal) localIp else meshIp

            PrismLogger.logInfo("PrismSocket", "Redirection: $originalHost -> $connectAddr:$finalPort (Local: $isLocal)")
            super.connect(InetSocketAddress(connectAddr, finalPort), timeout)
            
            // 3. Perform Handshake
            performHandshake(originalHost)
            
            PrismLogger.logSuccess("PrismSocket", "Handshake successful for $originalHost")
            
        } catch (e: Exception) {
            PrismLogger.logError("PrismSocket", "Connection/Handshake failed for $originalHost", e)
            throw e
        }
    }

    private fun performHandshake(hostName: String) {
        val out = getOutputStream()
        val inp = getInputStream()
        
        val handshake = "PRISM_CONNECT $hostName\n"
        out.write(handshake.toByteArray())
        out.flush()
        
        val sb = StringBuilder()
        while (true) {
            val c = inp.read()
            if (c == -1 || c == '\n'.code) break
            if (c != '\r'.code) sb.append(c.toChar())
        }
        
        val ack = sb.toString().trim()
        if (ack != "PRISM_ACK") {
            throw java.io.IOException("Handshake mismatch: Expected PRISM_ACK, got '$ack'")
        }
    }
}

/**
 * SocketFactory that provides mesh-aware PrismSocket instances for internal engines.
 */
class PrismSocketFactory : javax.net.SocketFactory() {
    override fun createSocket(): Socket = PrismSocket()

    override fun createSocket(host: String?, port: Int): Socket =
        PrismSocket().apply { connect(java.net.InetSocketAddress(host, port)) }

    override fun createSocket(host: String?, port: Int, localHost: java.net.InetAddress?, localPort: Int): Socket =
        PrismSocket().apply { connect(java.net.InetSocketAddress(host, port)) }

    override fun createSocket(host: java.net.InetAddress?, port: Int): Socket =
        PrismSocket().apply { connect(java.net.InetSocketAddress(host, port)) }

    override fun createSocket(address: java.net.InetAddress?, port: Int, localAddress: java.net.InetAddress?, localPort: Int): Socket =
        PrismSocket().apply { connect(java.net.InetSocketAddress(address, port)) }
}
