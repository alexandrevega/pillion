package app.pillion.android

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import app.pillion.core.Logger
import app.pillion.core.WifiLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentSkipListSet
import java.util.concurrent.atomic.AtomicInteger

/**
 * Android [WifiLink]. The scan finds the bike on the Wi-Fi network the phone is joined to: every TCP
 * port on the gateway (with a short banner grab), mDNS service types and SSDP replies. Everything is
 * logged for the diagnostic zip, and the most likely SDL endpoint is remembered as the TCP target that
 * [target] hands to the SDL session when Wi-Fi mode is on.
 */
class AndroidWifiLink(
    context: Context,
    private val requestPermissions: () -> Unit = {},
) : WifiLink {
    private val context = context.applicationContext
    private val prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val cm = this.context.getSystemService(ConnectivityManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private val _status = MutableStateFlow(prefs.getString(KEY_STATUS, null) ?: "Not scanned yet")
    override val status: StateFlow<String> = _status.asStateFlow()
    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, false))
    override val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    override fun setEnabled(enabled: Boolean) {
        _enabled.value = enabled
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
        Logger.d("wifi: Wi-Fi mode ${if (enabled) "ON" else "OFF"} — target ${describe(context)}")
    }

    override fun scan() {
        if (job?.isActive == true) return
        requestPermissions()
        job = scope.launch {
            runCatching { runScan() }.onFailure {
                Logger.e("wifi: scan failed", it)
                publish("Scan failed: ${it.message}")
            }
        }
    }

    /** The Wi-Fi network + SDL endpoint to use, or null when Wi-Fi mode is off or no Wi-Fi is joined. */
    fun target(): Target? {
        if (!_enabled.value) return null
        val network = wifiNetwork() ?: return null
        val host = prefs.getString(KEY_HOST, null) ?: gatewayOf(network)?.hostAddress
        if (host == null) {
            Logger.d("wifi: no usable gateway on ${cm.getLinkProperties(network)} — falling back to USB")
            return null
        }
        Logger.d("wifi: target $host via ${cm.getLinkProperties(network)?.interfaceName} caps=${cm.getNetworkCapabilities(network)}")
        return Target(network, host, prefs.getInt(KEY_PORT, DEFAULT_SDL_PORT))
    }

    data class Target(val network: Network, val host: String, val port: Int)

    private suspend fun runScan() {
        publish("Scanning…")
        val network = wifiNetwork()
        if (network == null) {
            publish("No Wi-Fi network — join the bike's Wi-Fi first, then scan again")
            return
        }
        logNetwork(network)
        logNearbyNetworks()
        val gateway = gatewayOf(network)
        Logger.d("wifi: gateway=${gateway?.hostAddress}")

        mdnsQuery(network)
        ssdpQuery(network)

        if (gateway == null) {
            publish("Joined Wi-Fi but found no gateway — see logs")
            return
        }
        val open = scanPorts(network, gateway)
        Logger.d("wifi: open TCP ports on ${gateway.hostAddress}: $open")

        // SDL Core listens on 12345 by default; otherwise prefer an unprivileged port nothing else claims.
        val port = when {
            DEFAULT_SDL_PORT in open -> DEFAULT_SDL_PORT
            else -> open.firstOrNull { it >= 1024 && it !in COMMON_NON_SDL_PORTS } ?: DEFAULT_SDL_PORT
        }
        prefs.edit().putString(KEY_HOST, gateway.hostAddress).putInt(KEY_PORT, port).apply()
        val portsText = if (open.isEmpty()) "none" else open.joinToString()
        publish("${gateway.hostAddress} · open ports: $portsText · SDL target :$port")
    }

    private fun logNetwork(network: Network) {
        val lp = cm.getLinkProperties(network)
        val caps = cm.getNetworkCapabilities(network)
        Logger.d("wifi: iface=${lp?.interfaceName} addrs=${lp?.linkAddresses} dns=${lp?.dnsServers} routes=${lp?.routes}")
        Logger.d("wifi: internet=${caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)} validated=${caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)}")
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) caps?.transportInfo as? WifiInfo else null
        @Suppress("DEPRECATION")
        val legacy = runCatching { context.getSystemService(WifiManager::class.java).connectionInfo }.getOrNull()
        val wifi = info ?: legacy
        Logger.d("wifi: ssid=${wifi?.ssid} bssid=${wifi?.bssid} freq=${wifi?.frequency}MHz rssi=${wifi?.rssi}")
    }

    private fun logNearbyNetworks() {
        runCatching {
            val results = context.getSystemService(WifiManager::class.java).scanResults
            Logger.d("wifi: ${results.size} nearby networks (cached scan)")
            results.sortedByDescending { it.level }.take(30).forEach {
                @Suppress("DEPRECATION")
                Logger.d("wifi:   ssid='${it.SSID}' bssid=${it.BSSID} freq=${it.frequency} level=${it.level} caps=${it.capabilities}")
            }
        }.onFailure { Logger.d("wifi: nearby networks unavailable (${it.javaClass.simpleName}: ${it.message})") }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun scanPorts(network: Network, host: InetAddress): List<Int> = withContext(Dispatchers.IO.limitedParallelism(PARALLEL)) {
        val open = ConcurrentSkipListSet<Int>()
        val done = AtomicInteger()
        val next = AtomicInteger(1)
        coroutineScope {
            repeat(PARALLEL) {
                launch {
                    while (true) {
                        val port = next.getAndIncrement()
                        if (port > 65535) break
                        if (probePort(network, host, port)) open += port
                        val n = done.incrementAndGet()
                        if (n % 2048 == 0) _status.value = "Scanning ports… ${n * 100 / 65535}% (open: ${open.joinToString()})"
                    }
                }
            }
        }
        open.toList()
    }

    private fun probePort(network: Network, host: InetAddress, port: Int): Boolean {
        val socket = runCatching { network.socketFactory.createSocket() }.getOrNull() ?: return false
        return try {
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            socket.soTimeout = BANNER_TIMEOUT_MS
            val buf = ByteArray(128)
            val n = try { socket.getInputStream().read(buf) } catch (_: SocketTimeoutException) { 0 }
            Logger.d("wifi: OPEN ${host.hostAddress}:$port banner(${maxOf(n, 0)}b)=${printable(buf, maxOf(n, 0))} hex=${hex(buf, maxOf(n, 0))}")
            true
        } catch (_: Exception) {
            false
        } finally {
            runCatching { socket.close() }
        }
    }

    /** Legacy-unicast mDNS queries (replies come straight back to our ephemeral port). */
    private fun mdnsQuery(network: Network) {
        val lock = runCatching {
            context.getSystemService(WifiManager::class.java).createMulticastLock("pillion-probe").apply { acquire() }
        }.getOrNull()
        try {
            DatagramSocket().use { socket ->
                network.bindSocket(socket)
                socket.soTimeout = 400
                val group = InetAddress.getByName("224.0.0.251")
                MDNS_QUERIES.forEachIndexed { i, name ->
                    val q = dnsQuery(i + 1, name, type = 12) // PTR
                    socket.send(DatagramPacket(q, q.size, group, 5353))
                }
                collect(socket, "mdns")
            }
        } catch (t: Throwable) {
            Logger.e("wifi: mDNS query failed", t)
        } finally {
            runCatching { lock?.release() }
        }
    }

    private fun ssdpQuery(network: Network) {
        try {
            DatagramSocket().use { socket ->
                network.bindSocket(socket)
                socket.soTimeout = 400
                val msg = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\n" +
                    "MX: 2\r\nST: ssdp:all\r\n\r\n").toByteArray()
                socket.send(DatagramPacket(msg, msg.size, InetAddress.getByName("239.255.255.250"), 1900))
                collect(socket, "ssdp")
            }
        } catch (t: Throwable) {
            Logger.e("wifi: SSDP query failed", t)
        }
    }

    private fun collect(socket: DatagramSocket, tag: String) {
        val deadline = System.currentTimeMillis() + LISTEN_MS
        var count = 0
        while (System.currentTimeMillis() < deadline) {
            val buf = ByteArray(4096)
            val packet = DatagramPacket(buf, buf.size)
            try {
                socket.receive(packet)
            } catch (_: SocketTimeoutException) {
                continue
            }
            count++
            Logger.d("wifi: $tag reply from ${packet.address.hostAddress}:${packet.port} (${packet.length}b) ${printable(buf, packet.length)}")
        }
        Logger.d("wifi: $tag done — $count replies")
    }

    private fun dnsQuery(id: Int, name: String, type: Int): ByteArray {
        val out = ByteArrayOutputStream()
        fun u16(v: Int) { out.write(v shr 8 and 0xFF); out.write(v and 0xFF) }
        u16(id); u16(0); u16(1); u16(0); u16(0); u16(0)
        name.split('.').forEach { label ->
            out.write(label.length)
            out.write(label.toByteArray())
        }
        out.write(0)
        u16(type)
        u16(0x8001) // class IN with the "unicast response" bit
        return out.toByteArray()
    }

    private fun wifiNetwork(): Network? =
        @Suppress("DEPRECATION")
        cm.allNetworks.firstOrNull { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }

    private fun gatewayOf(network: Network): InetAddress? {
        val lp = cm.getLinkProperties(network) ?: return null
        // A default route via 0.0.0.0 is on-link, not a gateway; using it would dial the phone itself.
        return lp.routes.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address && !it.gateway!!.isAnyLocalAddress }?.gateway
            ?: (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) lp.dhcpServerAddress else null)
                ?.takeUnless { it.isAnyLocalAddress }
    }

    private fun publish(text: String) {
        _status.value = text
        prefs.edit().putString(KEY_STATUS, text).apply()
        Logger.d("wifi: $text")
    }

    private fun printable(buf: ByteArray, len: Int): String =
        String(CharArray(len) { val c = buf[it].toInt() and 0xFF; if (c in 32..126) c.toChar() else '.' })

    private fun hex(buf: ByteArray, len: Int): String = (0 until minOf(len, 32)).joinToString("") { "%02x".format(buf[it]) }

    companion object {
        private const val PREFS = "pillion.wifi"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_STATUS = "status"
        private const val KEY_HOST = "host"
        private const val KEY_PORT = "port"
        private const val DEFAULT_SDL_PORT = 12345
        private const val PARALLEL = 128
        private const val CONNECT_TIMEOUT_MS = 400
        private const val BANNER_TIMEOUT_MS = 500
        private const val LISTEN_MS = 3000L
        private val COMMON_NON_SDL_PORTS = setOf(1900, 5353, 8080, 8443)
        private val MDNS_QUERIES = listOf(
            "_services._dns-sd._udp.local",
            "_sdl._tcp.local",
            "_garmin._tcp.local",
            "_http._tcp.local",
        )

        /** Summary line for the diagnostic zip. */
        fun describe(context: Context): String {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return "enabled=${p.getBoolean(KEY_ENABLED, false)} target=${p.getString(KEY_HOST, "(gateway)")}:" +
                "${p.getInt(KEY_PORT, DEFAULT_SDL_PORT)} lastScan='${p.getString(KEY_STATUS, "never")}'"
        }
    }
}
