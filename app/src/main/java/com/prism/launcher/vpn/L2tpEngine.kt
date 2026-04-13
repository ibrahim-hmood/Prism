package com.prism.launcher.vpn

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Minimal L2TP responder for identifying and starting L2TP control tunnels.
 */
class L2tpEngine(
    private val psk: String,
    private val user: String,
    private val pass: String
) {

    fun handlePacket(data: ByteArray, address: InetAddress, port: Int, socket: DatagramSocket) {
        if (data.size < 12) return // Minimum L2TP header
        
        val isControl = (data[0].toInt() and 0x80) != 0
        if (isControl) {
            handleControlMessage(data, address, port, socket)
        }
    }

    private fun handleControlMessage(data: ByteArray, address: InetAddress, port: Int, socket: DatagramSocket) {
        // L2TP Control Message Header (12 bytes)
        // Length offset 2. Tunnel ID offset 6. Call ID offset 8.
        val tunnelId = ((data[6].toInt() and 0xFF) shl 8) or (data[7].toInt() and 0xFF)
        
        // Find Message Type AVP (Attribute Value Pair)
        // AVP starts at index 12. Message Type is usually the first AVP.
        // AVP Format: [6 bits Flags][10 bits Length][2 bytes Vendor][2 bytes Type][Value]
        if (data.size < 20) return
        val avpType = ((data[18].toInt() and 0xFF) shl 8) or (data[19].toInt() and 0xFF)
        val msgType = if (avpType == 0) data[21].toInt() else -1 // Type 0 = Message Type
        
        if (msgType == 1) { // SCCRQ (Start-Control-Connection-Request)
            sendSccrp(address, port, socket, tunnelId)
        }
    }

    private fun sendSccrp(address: InetAddress, port: Int, socket: DatagramSocket, remoteTunnelId: Int) {
        android.util.Log.d("PrismL2tp", "Responding SCCRP to Windows tunnel ID $remoteTunnelId")
        
        // Basic SCCRP packet (minimal AVPs: Message Type=2, Protocol Ver=1.0, HostName=Prism)
        val packetData = ByteArray(40)
        packetData[0] = 0xC8.toByte() // T, L, ver (0x80 | 0x40 | 0x02)
        packetData[1] = 0x02.toByte() 
        // Length 40
        packetData[2] = 0x00
        packetData[3] = 40
        // Tunnel ID (Remote)
        packetData[6] = (remoteTunnelId shr 8).toByte()
        packetData[7] = (remoteTunnelId and 0xFF).toByte()
        // Call ID (0 for control)
        packetData[8] = 0
        packetData[9] = 0
        
        // AVP: Message Type = 2 (SCCRP)
        packetData[12] = 0x80.toByte() // Mandatory
        packetData[13] = 0x08 // Length 8
        packetData[14] = 0
        packetData[15] = 0
        packetData[16] = 0 // Type 0 (Message Type)
        packetData[17] = 0 
        packetData[18] = 0 
        packetData[19] = 2 // Value 2 (SCCRP)
        
        val packet = DatagramPacket(packetData, packetData.size, address, port)
        try {
            socket.send(packet)
            android.util.Log.d("PrismL2tp", "SCCRP Sent.")
        } catch (e: Exception) {
            android.util.Log.e("PrismL2tp", "Failed to send L2TP response", e)
        }
    }
}
