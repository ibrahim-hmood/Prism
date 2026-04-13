package com.prism.launcher

import android.content.Context
import java.net.Inet4Address
import java.net.NetworkInterface

object MeshUtils {

    /**
     * Finds the primary IP address to use for P2P Mesh identification.
     * Priority:
     * 1. WireGuard interface (tun0 or similar mesh interface)
     * 2. LAN IPv4 (Wifi/Ethernet)
     * 3. Loopback (fallback)
     */
    fun getLocalMeshIp(context: Context): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            val interfaceList = interfaces.toList()

            // 1. Try to find a WireGuard/VPN interface first (Commonly tun0 or contains 'wg')
            val meshInterface = interfaceList.find { it.name.contains("tun") || it.name.contains("wg") }
            meshInterface?.inetAddresses?.toList()?.find { it is Inet4Address && !it.isLoopbackAddress }?.let {
                return it.hostAddress ?: ""
            }

            // 2. Fallback to LAN IP
            interfaceList.filter { !it.isLoopback && it.isUp }.forEach { intf ->
                intf.inetAddresses.toList().find { it is Inet4Address && !it.isLoopbackAddress }?.let {
                    return it.hostAddress ?: ""
                }
            }
        } catch (ex: Exception) {
            ex.printStackTrace()
        }
        return "127.0.0.1"
    }

    /**
     * Finds a port that Android allows and that is currently free.
     * Randomly generates a port between 0 and 65535.
     * Recursively calls itself if the port is taken or reserved.
     */
    fun findAvailablePort(): Int {
        val reservedPorts = listOf(8080, 8081, 51820, 500, 4500, 1701)
        
        // 1. Generate a random port in the full valid range
        val randomPort = (0..65535).random()
        
        // 2. Check against our Prism internal blacklist + privileged ports (< 1024)
        // Note: Ports < 1024 usually require root on Android.
        if (reservedPorts.contains(randomPort) || randomPort < 1024) {
            return findAvailablePort() // Recurse
        }
        
        // 3. Try to bind to see if the OS allows it
        return try {
            java.net.ServerSocket(randomPort).use {
                randomPort
            }
        } catch (e: Exception) {
            // Collision or restricted port, try again
            findAvailablePort() // Recurse
        }
    }
}
