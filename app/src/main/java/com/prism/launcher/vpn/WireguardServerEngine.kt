package com.prism.launcher.vpn

import android.content.Context
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * Userspace WireGuard Server Engine.
 * Handles handshake initiations and decapsulates incoming UDP/51820 traffic.
 * 
 * Note: Key exchange (Noise Protocol) is simplified for this implementation.
 */
class WireguardServerEngine(private val context: Context) {

    private val sessions = ConcurrentHashMap<String, PeerSession>()
    
    data class PeerSession(
        val publicKey: String,
        val endpoint: InetAddress,
        val port: Int,
        val virtualIp: String,
        var lastSeen: Long = System.currentTimeMillis()
    )

    fun handlePacket(data: ByteArray, address: InetAddress, port: Int, socket: DatagramSocket) {
        if (data.size < 4) return
        
        val msgType = data[0].toInt()
        
        when (msgType) {
            1 -> handleHandshakeInitiation(data, address, port, socket)
            4 -> handleTransportPacket(data, address, port)
            else -> Log.d("PrismWG", "Unsupported WG message type: $msgType")
        }
    }

    private fun handleHandshakeInitiation(data: ByteArray, address: InetAddress, port: Int, socket: DatagramSocket) {
        Log.i("PrismWG", "Handshake Initiation from $address:$port")
        
        // 1. In a real WG server, we would verify the MAC and do Curve25519 DH.
        // For Prism, we'll respond with a Handshake Response to "Open the Tunnel".
        
        val response = ByteArray(92)
        response[0] = 2 // Handshake Response msg type
        // ... Fill in sender/receiver indices, random unencrypted nonce ...
        
        val packet = DatagramPacket(response, response.size, address, port)
        try {
            socket.send(packet)
            Log.d("PrismWG", "Handshake Response sent to $address")
            
            // Register session
            // Register session (default virtual IP 10.8.0.2 for first peer)
            sessions["10.8.0.2"] = PeerSession("CLIENT_PUB_KEY", address, port, "10.8.0.2")
        } catch (e: Exception) {
            Log.e("PrismWG", "Fail to send handshake resp", e)
        }
    }

    private fun handleTransportPacket(data: ByteArray, address: InetAddress, port: Int) {
        // Update session
        sessions.values.find { it.endpoint == address && it.port == port }?.lastSeen = System.currentTimeMillis()
        
        // 1. Decapsulate: In standard WG, msg type 4 starts with 16 bytes of header.
        // [1b Type=4] [3b Reserved] [4b Receiver Index] [8b Counter]
        if (data.size <= 16) return
        val payload = data.copyOfRange(16, data.size)
        
        Log.v("PrismWG", "Decapsulated packet from $address (${payload.size} bytes)")
        
        // 2. Inject into PrivateDnsVpnService for DNS filtering and routing
        (context as? com.prism.launcher.browser.PrivateDnsVpnService)?.injectPacket(payload)
    }

    fun encapsulateAndSend(packet: ByteArray, peerIp: String) {
        val session = sessions[peerIp] ?: return
        
        // msgType 4: Transport data
        // For this minimal userspace engine, we wrap the IP packet with a WG Transport Header.
        val header = ByteArray(16)
        header[0] = 4 // Type
        // ... (Receiver Index and Counter would be here) ...
        
        val wgPacket = header + packet
        val udpPacket = DatagramPacket(wgPacket, wgPacket.size, session.endpoint, session.port)
        
        // Send via a temporary socket or pass a socket reference.
        // For simplicity, we create a ephemeral socket protected by VPN.
        thread {
            try {
                // Ensure ephemeral sockets also utilize reuse if possible
                val socket = java.net.DatagramSocket(null)
                socket.reuseAddress = true
                socket.bind(java.net.InetSocketAddress(0))
                
                (context as? com.prism.launcher.browser.PrivateDnsVpnService)?.protect(socket)
                socket.send(udpPacket)
                socket.close()
            } catch (e: Exception) {
                Log.e("PrismWG", "Failed to send encapsulated packet to $peerIp", e)
            }
        }
    }
}
