package app.pillion.android

import android.content.Context
import android.os.Build
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.AdbStream
import java.io.File
import java.io.IOException
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

/**
 * In-app ADB client for the dedicated-dash bootstrap. Pillion pairs + connects to the phone's *own*
 * `adbd` over Wireless debugging (no PC, no second app), which gives it shell-uid privilege — the
 * same privilege scrcpy uses to create a trusted virtual display and launch a real app onto it so
 * the dash keeps rendering with the screen off.
 *
 * Backed by libadb-android, which implements the Android 11+ SPAKE2 pairing flow. The RSA keypair +
 * self-signed certificate are generated once and persisted, so the device only has to authorise us
 * once (re-pairing is needed only if the user revokes debugging authorisations).
 *
 * Single responsibility: own the ADB identity + connection; callers ask it to [pairDevice],
 * [connectDevice]/[autoConnectDevice], and [runShell].
 */
class PillionAdb private constructor(
    private val privateKey: PrivateKey,
    private val certificate: X509Certificate,
) : AbsAdbConnectionManager() {

    init {
        setApi(Build.VERSION.SDK_INT)
    }

    override fun getPrivateKey(): PrivateKey = privateKey
    override fun getCertificate(): Certificate = certificate
    override fun getDeviceName(): String = "Pillion"

    /** Pair once using the code + port from Settings → Wireless debugging → "Pair device with code". */
    fun pairDevice(host: String, pairingPort: Int, pairingCode: String) {
        pair(host, pairingPort, pairingCode)
    }

    /** Connect using the host + port shown on the main Wireless debugging screen. */
    fun connectDevice(host: String, port: Int): Boolean = connect(host, port)

    /** Discover the adbd endpoint via mDNS and connect (used for reconnect after a reboot). */
    fun autoConnectDevice(context: Context, timeoutMs: Long = 10_000): Boolean =
        autoConnect(context, timeoutMs)

    /** Run a one-shot shell command and return its combined stdout/stderr. */
    fun runShell(command: String): String {
        val stream: AdbStream = openStream("shell:$command")
        try {
            val reader = stream.openInputStream().bufferedReader()
            val output = StringBuilder()
            val buffer = CharArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = try {
                    reader.read(buffer)
                } catch (e: IOException) {
                    if (e.message?.contains("Stream closed", ignoreCase = true) == true) break else throw e
                }
                if (read < 0) break
                output.append(buffer, 0, read)
            }
            return output.toString()
        } finally {
            stream.close()
        }
    }

    /** Grant shell-backed special access needed by dedicated dash mode. */
    fun prepareDashPrivileges(context: Context) {
        runShell("cmd appops set ${context.packageName} GET_USAGE_STATS allow")
    }

    /**
     * Open a long-lived shell stream (e.g. to spawn the [app.pillion.server.DashServer] helper). The
     * caller owns the returned stream and must close it to terminate the remote process.
     */
    fun openShellStream(command: String): AdbStream = openStream("shell:$command")

    /**
     * Open a raw `exec:` stream (no PTY, so binary isn't mangled) — used to spawn the
     * [app.pillion.server.DashServer] helper and read its JPEG frame stream off stdout.
     */
    fun openExecStream(command: String): AdbStream = openStream("exec:$command")

    /**
     * Switch adbd to classic TCP mode on [port] (the same request `adb tcpip` sends). adbd then also
     * listens on **`127.0.0.1`**, a privileged channel that stays up with **no Wi-Fi** — unlike the
     * mDNS Wireless-debugging port, which dies when Wi-Fi drops. adbd restarts, so this connection is
     * gone afterwards; the caller reconnects with `connectDevice("127.0.0.1", port)`. That gives the
     * dash a channel it can use to **respawn the helper offline** if it ever gets killed.
     */
    fun enableTcpip(port: Int = TCPIP_PORT) {
        runCatching {
            val stream = openStream("tcpip:$port")
            runCatching { stream.openInputStream().readBytes() } // adbd acks, then restarts
            runCatching { stream.close() }
        }
        runCatching { close() } // the old (wireless) connection is dead now
    }

    companion object {
        /** Loopback adb (always up, no Wi-Fi) once [enableTcpip] has run. */
        const val LOOPBACK_HOST = "127.0.0.1"
        const val TCPIP_PORT = 5555

        @Volatile private var instance: PillionAdb? = null

        fun getInstance(context: Context): PillionAdb =
            instance ?: synchronized(this) {
                instance ?: build(context.applicationContext).also { instance = it }
            }

        private fun build(context: Context): PillionAdb {
            val keyFile = File(context.filesDir, "adb_key.pk8")
            val certFile = File(context.filesDir, "adb_cert.der")
            return if (keyFile.exists() && certFile.exists()) {
                val key = KeyFactory.getInstance("RSA")
                    .generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
                val cert = CertificateFactory.getInstance("X.509")
                    .generateCertificate(certFile.inputStream().buffered()) as X509Certificate
                PillionAdb(key, cert)
            } else {
                val (key, cert) = generateKeyPairAndCert()
                keyFile.writeBytes(PKCS8EncodedKeySpec(key.encoded).encoded)
                certFile.writeBytes(cert.encoded)
                PillionAdb(key, cert)
            }
        }

        /** Generate an RSA keypair + self-signed X509 cert in the shape adbd's TLS handshake expects. */
        private fun generateKeyPairAndCert(): Pair<PrivateKey, X509Certificate> {
            val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val now = System.currentTimeMillis()
            val notBefore = Date(now)
            val notAfter = Date(now + 30L * 365 * 24 * 60 * 60 * 1000) // ~30y
            val subject = X500Name("CN=Pillion")
            // A local provider instance — not registered globally, so it can't clash with Android's
            // relocated BouncyCastle.
            val provider = BouncyCastleProvider()

            val builder = JcaX509v3CertificateBuilder(
                subject, BigInteger.valueOf(now), notBefore, notAfter, subject, keyPair.public,
            ).addExtension(
                Extension.subjectKeyIdentifier, false,
                JcaX509ExtensionUtils().createSubjectKeyIdentifier(keyPair.public),
            )
            val signer = JcaContentSignerBuilder("SHA512withRSA").setProvider(provider).build(keyPair.private)
            val cert = JcaX509CertificateConverter().setProvider(provider).getCertificate(builder.build(signer))
            return keyPair.private to cert
        }
    }
}
