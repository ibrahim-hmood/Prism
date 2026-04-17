package com.prism.launcher.vpn

import android.content.Context
import com.prism.launcher.PrismLogger
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.io.FileOutputStream
import java.math.BigInteger
import java.security.*
import java.security.cert.X509Certificate
import java.util.*
import org.bouncycastle.jce.ECNamedCurveTable
import org.bouncycastle.jce.spec.ECParameterSpec

/**
 * Handles on-device generation of SSL/TLS certificates for the Prism Mesh.
 * Uses BouncyCastle to create a unique Root CA and dynamic leaf certs for .p2p domains.
 */
object PrismCertificateManager {

    private const val TAG = "PrismCertManager"
    private const val CA_KEY_FILE = "prism_ca_key.der"
    private const val CA_CERT_FILE = "prism_ca_cert.der"

    init {
        Security.removeProvider("BC")
        Security.addProvider(BouncyCastleProvider())
    }

    private var rootKey: PrivateKey? = null
    private var rootCert: X509Certificate? = null

    /**
     * Initializes or loads the device-unique Root CA.
     */
    fun init(context: Context) {
        val keyFile = File(context.filesDir, CA_KEY_FILE)
        val certFile = File(context.filesDir, CA_CERT_FILE)

        if (keyFile.exists() && certFile.exists()) {
            try {
                loadCA(keyFile, certFile)
                PrismLogger.logInfo(TAG, "Root CA loaded successfully.")
                return
            } catch (e: Exception) {
                PrismLogger.logError(TAG, "Failed to load stored CA. Regenerating...", e)
            }
        }

        generateNewCA(context, keyFile, certFile)
    }

    private fun loadCA(keyFile: File, certFile: File) {
        val keyBytes = keyFile.readBytes()
        val kf = KeyFactory.getInstance("EC", "BC")
        rootKey = kf.generatePrivate(java.security.spec.PKCS8EncodedKeySpec(keyBytes))

        val cf = java.security.cert.CertificateFactory.getInstance("X.509", "BC")
        rootCert = cf.generateCertificate(certFile.inputStream()) as X509Certificate
    }

    private fun generateNewCA(context: Context, keyFile: File, certFile: File) {
        PrismLogger.logInfo(TAG, "Generating unique Root CA for this device...")
        try {
            val keyGen = KeyPairGenerator.getInstance("EC", "BC")
            val params = ECNamedCurveTable.getParameterSpec("prime256v1")
            keyGen.initialize(params)
            val pair = keyGen.generateKeyPair()
            rootKey = pair.private

            val subject = X500Name("CN=Prism Mesh Root CA, O=Prism Launcher, OU=Decentralized Intelligence")
            val serial = BigInteger.valueOf(System.currentTimeMillis())
            val from = Date()
            val to = Date(from.time + 3650L * 24 * 60 * 60 * 1000) // 10 years

            val builder = JcaX509v3CertificateBuilder(subject, serial, from, to, subject, pair.public)
            builder.addExtension(Extension.basicConstraints, true, BasicConstraints(true))

            val signer = JcaContentSignerBuilder("SHA256withECDSA").setProvider("BC").build(pair.private)
            rootCert = JcaX509CertificateConverter().setProvider("BC").getCertificate(builder.build(signer))

            // Save to disk
            keyFile.writeBytes(rootKey!!.encoded)
            certFile.writeBytes(rootCert!!.encoded)
            
            PrismLogger.logSuccess(TAG, "New Root CA generated and persisted.")
        } catch (e: Exception) {
            PrismLogger.logError(TAG, "FATAL: Failed to generate CA", e)
        }
    }

    /**
     * Generates a leaf certificate for a specific mesh domain (e.g. site.p2p)
     */
    fun generateLeafCertificate(domain: String): KeyPairWithCert? {
        val caKey = rootKey ?: return null
        val caCert = rootCert ?: return null

        try {
            val keyGen = KeyPairGenerator.getInstance("EC", "BC")
            val params = ECNamedCurveTable.getParameterSpec("prime256v1")
            keyGen.initialize(params)
            val pair = keyGen.generateKeyPair()

            val subject = X500Name("CN=$domain, O=Prism Mesh Leaf, OU=On-Device Generated")
            val serial = BigInteger.valueOf(System.currentTimeMillis())
            val from = Date(System.currentTimeMillis() - 1000L * 60 * 60) // 1 hr ago for clock skew
            val to = Date(from.time + 30L * 24 * 60 * 60 * 1000) // 30 days

            val builder = JcaX509v3CertificateBuilder(X500Name.getInstance(caCert.subjectX500Principal.encoded), serial, from, to, subject, pair.public)
            
            // SAN (Subject Alternative Name) is mandatory for modern browsers
            val san = GeneralNames(arrayOf(GeneralName(GeneralName.dNSName, domain)))
            builder.addExtension(Extension.subjectAlternativeName, false, san)
            builder.addExtension(Extension.basicConstraints, true, BasicConstraints(false))

            val signer = JcaContentSignerBuilder("SHA256withECDSA").setProvider("BC").build(caKey)
            val cert = JcaX509CertificateConverter().setProvider("BC").getCertificate(builder.build(signer))

            return KeyPairWithCert(pair, cert, caCert)
        } catch (e: Exception) {
            PrismLogger.logError(TAG, "Failed to generate leaf for $domain", e)
            return null
        }
    }

    /**
     * Exports the Root CA as a .crt file to the public Downloads folder for user installation.
     */
    fun exportRootCA(context: Context): String? {
        val cert = rootCert ?: return null
        try {
            val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
            if (!downloadsDir.exists()) downloadsDir.mkdirs()
            
            val file = File(downloadsDir, "Prism_Mesh_Root_CA.crt")
            
            // Write in PEM format (human readable)
            val output = FileOutputStream(file)
            output.write("-----BEGIN CERTIFICATE-----\n".toByteArray())
            output.write(android.util.Base64.encode(cert.encoded, android.util.Base64.DEFAULT))
            output.write("-----END CERTIFICATE-----\n".toByteArray())
            output.close()
            
            return file.absolutePath
        } catch (e: Exception) {
            PrismLogger.logError(TAG, "Export failed", e)
            return null
        }
    }

    data class KeyPairWithCert(val keyPair: KeyPair, val certificate: X509Certificate, val issuer: X509Certificate)
}
