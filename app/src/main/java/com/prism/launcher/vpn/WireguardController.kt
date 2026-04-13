package com.prism.launcher.vpn

import android.content.Context
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import java.io.InputStream

object WireguardController {

    private var backend: GoBackend? = null
    private val tunnel = PrismTunnel()
    private var config: Config? = null

    fun init(context: Context) {
        if (backend == null) {
            backend = GoBackend(context)
        }
    }

    fun loadProfile(inputStream: InputStream) {
        config = Config.parse(inputStream)
    }

    fun start() {
        config?.let { c ->
            backend?.setState(tunnel, Tunnel.State.UP, c)
        }
    }

    fun stop() {
        try {
            backend?.setState(tunnel, Tunnel.State.DOWN, config)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private class PrismTunnel : Tunnel {
        override fun getName(): String = "PrismWG"
        override fun onStateChange(newState: Tunnel.State) {}
    }
}
