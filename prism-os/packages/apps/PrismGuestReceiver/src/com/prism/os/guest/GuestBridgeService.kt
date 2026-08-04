package com.prism.os.guest

import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.os.IBinder
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket

/**
 * Runs inside PrismOS (the VM guest).
 *
 * Listens on TCP port 5554 for newline-delimited commands sent by the host
 * via ADB-over-vsock or ADB-over-TCP.  Supported commands:
 *
 *   LAUNCH <flatComponentName>   — starts the requested app
 *   PING                         — responds PONG (health check)
 *
 * The host side ([VmController.sendViaAdb]) writes these commands using
 * an ADB shell → am start -n <component> pipeline.  This service is an
 * alternative direct bridge that avoids the full ADB stack overhead.
 */
class GuestBridgeService : Service() {

    companion object {
        private const val TAG = "PrismGuestBridge"
        private const val PORT = 5554
    }

    private var serverThread: Thread? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startBridgeServer()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startBridgeServer() {
        serverThread = Thread({
            try {
                ServerSocket(PORT).use { server ->
                    Log.i(TAG, "PrismOS guest bridge listening on :$PORT")
                    while (!Thread.currentThread().isInterrupted) {
                        val client = server.accept()
                        Thread({
                            try {
                                BufferedReader(InputStreamReader(client.getInputStream())).use { reader ->
                                    val line = reader.readLine() ?: return@Thread
                                    handleCommand(line.trim())
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "Client error: ${e.message}")
                            } finally {
                                runCatching { client.close() }
                            }
                        }, "GuestClient").start()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Bridge server failed: ${e.message}")
            }
        }, "GuestBridgeServer")
        serverThread?.start()
    }

    private fun handleCommand(cmd: String) {
        Log.d(TAG, "Command: $cmd")
        when {
            cmd.startsWith("LAUNCH ") -> {
                val flat = cmd.removePrefix("LAUNCH ").trim()
                launchComponent(flat)
            }
            cmd == "PING" -> Log.d(TAG, "PONG")
            else -> Log.w(TAG, "Unknown command: $cmd")
        }
    }

    private fun launchComponent(flatComponentName: String) {
        try {
            val cn = ComponentName.unflattenFromString(flatComponentName)
                ?: throw IllegalArgumentException("Bad component: $flatComponentName")
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                component = cn
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            }
            startActivity(intent)
            Log.i(TAG, "Launched: $flatComponentName")
        } catch (e: Exception) {
            Log.e(TAG, "Launch failed for $flatComponentName: ${e.message}")
        }
    }

    override fun onDestroy() {
        serverThread?.interrupt()
        super.onDestroy()
    }
}
