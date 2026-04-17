package com.prism.launcher.vpn

import android.content.Context
import java.io.ByteArrayInputStream
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import android.util.Base64
import com.prism.launcher.PrismLogger
import java.security.SecureRandom

/**
 * Handles the decentralized SSL/TLS layer for the Prism Mesh.
 * This ensures that mesh connections are encrypted even if the underlying
 * P2P transport is plain text.
 */
object PrismSslManager {
    private const val PASSWORD = "prism_mesh_encryption"
    private val contextMap = mutableMapOf<String, SSLContext>()
    private val lock = Any()

    /**
     * Generates a unique SSLContext for a specific domain using a dynamically generated certificate.
     */
    fun getSslContextForDomain(context: Context, domain: String): SSLContext {
        synchronized(lock) {
            contextMap[domain]?.let { return it }
            
            PrismCertificateManager.init(context)
            val leaf = PrismCertificateManager.generateLeafCertificate(domain) ?: return SSLContext.getDefault()

            try {
                // We create an in-memory KeyStore for this specific session
                val ks = KeyStore.getInstance("PKCS12")
                ks.load(null, null)
                
                val chain = arrayOf(leaf.certificate, leaf.issuer)
                ks.setKeyEntry("prism_leaf", leaf.keyPair.private, PASSWORD.toCharArray(), chain)

                val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
                kmf.init(ks, PASSWORD.toCharArray())

                val sslContext = SSLContext.getInstance("TLS")
                sslContext.init(kmf.keyManagers, arrayOf(PrismTrustManager), SecureRandom())
                
                contextMap[domain] = sslContext
                return sslContext
            } catch (e: Exception) {
                PrismLogger.logError("PrismSslManager", "Failed to create SSLContext for $domain", e)
                return SSLContext.getDefault()
            }
        }
    }

    /**
     * Legacy/Generic context for the proxy listener.
     */
    fun getSslContext(context: Context): SSLContext {
        return getSslContextForDomain(context, "localhost")
    }

    object PrismTrustManager : X509TrustManager {
        override fun checkClientTrusted(p0: Array<out java.security.cert.X509Certificate>?, p1: String?) {}
        override fun checkServerTrusted(p0: Array<out java.security.cert.X509Certificate>?, p1: String?) {}
        override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = emptyArray()
    }
}
