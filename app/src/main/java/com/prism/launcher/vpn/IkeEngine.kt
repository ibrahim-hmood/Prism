package com.prism.launcher.vpn

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Minimal IKEv2/IKEv1 responder for responding to Windows VPN handshakes.
 */
class IkeEngine(
    private val psk: String,
    private val user: String,
    private val pass: String
) {

    fun handlePacket(data: ByteArray, address: InetAddress, port: Int, socket: DatagramSocket, receivingPort: Int, hasMarker: Boolean) {
        if (data.size < 28) return // Minimum IKE header size
        
        val version = data[17].toInt()
        val exchangeType = data[18].toInt()
        
        if (version == 0x20) { // IKEv2
            if (exchangeType == 34) { // IKE_SA_INIT
                handleIkeV2SaInit(data, address, port, socket, receivingPort, hasMarker)
            } else {
                android.util.Log.d("PrismIke", "Support for IKEv2 Exchange $exchangeType is coming soon.")
            }
        }
    }

    private fun handleIkeV2SaInit(data: ByteArray, address: InetAddress, port: Int, socket: DatagramSocket, receivingPort: Int, hasMarker: Boolean) {
        android.util.Log.d("PrismIke", "Responding to full IKE_SA_INIT from $address:$port (via $receivingPort)")
        
        val initiatorSpi = data.copyOfRange(0, 8)
        val responderSpi = java.security.SecureRandom.getSeed(8) 
        
        // 1. SA Payload (Proposal: AES-256-CBC, SHA256, DH Group 14)
        val saPayload = byteArrayOf(
            34, 0, 0, 44, // Next: KE(34), Plen: 44
            0, 0, 0, 0,   // Reserved
            0, 0, 0, 36,  // Proposal #1, ID:1(IKE), Plen: 36, #Transforms: 4
            3, 0, 0, 8,   // Transform 1: ENCR, ID: 12 (AES-CBC), KeyLen: 256
            1, 0, 1, 0,
            3, 0, 0, 8,   // Transform 2: PRF, ID: 5 (HMAC-SHA2-256)
            2, 0, 0, 5,
            3, 0, 0, 8,   // Transform 3: INTEG, ID: 12 (HMAC-SHA2-256-128)
            3, 0, 0, 12,
            0, 0, 0, 8,   // Transform 4: DH, ID: 14 (MODP 2048)
            4, 0, 0, 14
        )
        
        // 2. KE Payload (DH Group 14 - 256 bytes)
        val kePayloadHead = byteArrayOf(40, 0, 1, 4, 0, 14, 0, 0)
        val myPubKey = java.security.SecureRandom.getSeed(256)
        
        // 3. Ni Payload (Nonce - 32 bytes)
        val niPayloadHead = byteArrayOf(0, 0, 0, 36)
        val myNonce = java.security.SecureRandom.getSeed(32)
        
        val totalIkeLen = 32 + saPayload.size + kePayloadHead.size + myPubKey.size + niPayloadHead.size + myNonce.size
        val hasNatMarker = receivingPort == 4500 || hasMarker
        val response = ByteArray(if (hasNatMarker) totalIkeLen + 4 else totalIkeLen)
        
        val ikOffset = if (hasNatMarker) 4 else 0
        if (hasNatMarker) {
            // Non-ESP Marker (4 null bytes)
            response[0] = 0; response[1] = 0; response[2] = 0; response[3] = 0
        }

        System.arraycopy(initiatorSpi, 0, response, ikOffset, 8)
        System.arraycopy(responderSpi, 0, response, ikOffset + 8, 8)
        response[ikOffset + 16] = 33 // Next: SA
        response[ikOffset + 17] = 0x20
        response[ikOffset + 18] = 34 // IKE_SA_INIT
        response[ikOffset + 19] = 0x20 // Response
        writeU32(response, ikOffset + 28, totalIkeLen)
        
        var offset = ikOffset + 32
        System.arraycopy(saPayload, 0, response, offset, saPayload.size); offset += saPayload.size
        System.arraycopy(kePayloadHead, 0, response, offset, kePayloadHead.size); offset += kePayloadHead.size
        System.arraycopy(myPubKey, 0, response, offset, myPubKey.size); offset += myPubKey.size
        System.arraycopy(niPayloadHead, 0, response, offset, niPayloadHead.size); offset += niPayloadHead.size
        System.arraycopy(myNonce, 0, response, offset, myNonce.size); offset += myNonce.size

        val packet = DatagramPacket(response, response.size, address, port)
        try {
            socket.send(packet)
            android.util.Log.d("PrismIke", "Full IKE_SA_INIT Response sent ($totalIkeLen bytes)")
        } catch (e: Exception) {
            android.util.Log.e("PrismIke", "Failed to send IKE response", e)
        }
    }

    private fun extractNonce(data: ByteArray): ByteArray? = null // Minimal for now

    private fun writeU32(b: ByteArray, o: Int, v: Int) {
        b[o] = (v shr 24).toByte()
        b[o+1] = (v shr 16).toByte()
        b[o+2] = (v shr 8).toByte()
        b[o+3] = v.toByte()
    }
}
