package app.pillion.android.sdl

import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * Finds the dash's SDL TCP endpoint on the current Wi-Fi network without manual IP entry.
 * The bike acts as a hotspot; its address is fixed (usually the gateway, .1). We scan the /24
 * around our own Wi-Fi address for an open [port] — SDL listens there when wireless streaming
 * is enabled. Gateway first, then parallel probes of the rest of the subnet.
 */
object TcpDiscovery {

    /** @return dash IP if [port] answers on the current Wi-Fi subnet, else null. */
    fun discover(port: Int = 12345): String? {
        val ownIp = wifiIpv4() ?: return null
        val b1 = (ownIp shr 24) and 0xFF
        val b2 = (ownIp shr 16) and 0xFF
        val b3 = (ownIp shr 8) and 0xFF
        val ownLast = ownIp and 0xFF

        // Gateway first — the dash is almost certainly the hotspot's .1.
        val candidates = ArrayList<String>()
        candidates.add("$b1.$b2.$b3.1")
        // 2..254: .255/.256 aren't usable unicast (and .256 would fall through to a DNS lookup);
        // .1 is already queued above as the gateway probe, so don't dial it twice.
        for (i in 2..254) {
            if (i != ownLast) candidates.add("$b1.$b2.$b3.$i")
        }

        val found = AtomicReference<String?>(null)
        val pool = Executors.newFixedThreadPool(8)
        try {
            for (host in candidates) {
                pool.submit { if (probe(host, port)) found.compareAndSet(null, host) }
            }
            // Wait up to ~4s for a hit; the gateway probe usually resolves instantly.
            val deadline = System.currentTimeMillis() + 4000L
            while (found.get() == null && System.currentTimeMillis() < deadline) Thread.sleep(50)
        } finally {
            pool.shutdownNow()
        }
        return found.get()
    }

    private fun probe(host: String, port: Int): Boolean {
        val s = Socket()
        try {
            // Bounded connect: a dead host can't outlive shutdownNow() by the ~20s OS TCP timeout.
            s.connect(InetSocketAddress(host, port), 800)
            return true
        } catch (e: Exception) {
            return false
        } finally {
            try {
                s.close()
            } catch (_: Exception) {}
        }
    }

    /** Our IPv4 on the Wi-Fi interface as an Int, or null if not on Wi-Fi. */
    private fun wifiIpv4(): Int? {
        val ifaces = try {
            java.util.Collections.list(NetworkInterface.getNetworkInterfaces())
        } catch (e: Exception) {
            return null
        } ?: return null
        for (iface in ifaces) {
            if (!iface.name.startsWith("wlan")) continue
            val addrs = java.util.Collections.list(iface.inetAddresses)
            for (addr in addrs) {
                val b = addr.address
                if (b.size == 4) {
                    return ((b[0].toInt() and 0xFF) shl 24 or
                        (b[1].toInt() and 0xFF) shl 16 or
                        (b[2].toInt() and 0xFF) shl 8 or
                        (b[3].toInt() and 0xFF))
                }
            }
        }
        return null
    }
}
