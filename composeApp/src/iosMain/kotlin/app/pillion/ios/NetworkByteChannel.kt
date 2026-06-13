package app.pillion.ios

import app.pillion.core.ByteChannel
import app.pillion.core.Logger
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.posix.AF_INET
import platform.posix.SOCK_STREAM
import platform.posix.connect
import platform.posix.recv
import platform.posix.sockaddr
import platform.posix.sockaddr_in
import platform.posix.socket

/**
 * A plain TCP [ByteChannel] for talking to the NaviLite dash **emulator**
 * (`receiver.py` running in its default TCP mode) over WiFi or localhost.
 *
 * This is the development transport. Because [app.pillion.core.MirrorEngine] depends
 * only on [ByteChannel], pointing it at this channel exercises the *entire* iOS stack —
 * the NaviLite codec, handshake, echo-auth, ReplayKit capture and the streaming loop —
 * against the emulator, on the Simulator or a real device.
 *
 * The bike itself speaks MFi / External Accessory (see [ExternalAccessoryByteChannel]),
 * never TCP, so that is the production path. Everything above the [ByteChannel] seam is
 * identical between the two, which is the whole point of testing against the emulator.
 */
@OptIn(ExperimentalForeignApi::class)
class NetworkByteChannel(
    private val host: String,
    private val port: Int = 7220,
) : ByteChannel {
    private var fd: Int = -1

    override fun open() {
        // Parse the dotted-quad ourselves (arpa/inet helpers aren't bridged on iOS) into a
        // network-order (big-endian) in_addr. On a little-endian host the in-memory byte order
        // a.b.c.d is exactly octet[0] | octet[1]<<8 | octet[2]<<16 | octet[3]<<24.
        val octets = host.split(".").mapNotNull { it.toIntOrNull() }
        require(octets.size == 4 && octets.all { it in 0..255 }) {
            "invalid dash host '$host' (expected an IPv4 like 192.168.1.183)"
        }
        val s = socket(AF_INET, SOCK_STREAM, 0)
        check(s >= 0) { "socket() failed" }
        memScoped {
            val addr = alloc<sockaddr_in>()
            addr.sin_family = AF_INET.convert()
            addr.sin_port = (((port and 0xff) shl 8) or ((port shr 8) and 0xff)).toUShort()
            addr.sin_addr.s_addr =
                (octets[0] or (octets[1] shl 8) or (octets[2] shl 16) or (octets[3] shl 24)).toUInt()
            if (connect(s, addr.ptr.reinterpret<sockaddr>(), sockaddr_in.size.convert()) != 0) {
                platform.posix.close(s)
                error("connect($host:$port) failed — is the emulator running in TCP mode?")
            }
        }
        fd = s
        Logger.d("network: connected to $host:$port")
    }

    override fun write(bytes: ByteArray) {
        val s = fd
        check(s >= 0) { "channel not open" }
        if (bytes.isEmpty()) return
        bytes.usePinned { pinned ->
            var off = 0
            while (off < bytes.size) {
                val n = platform.posix.send(s, pinned.addressOf(off), (bytes.size - off).convert(), 0)
                check(n > 0) { "send() failed" }
                off += n.toInt()
            }
        }
    }

    override fun read(buffer: ByteArray): Int {
        val s = fd
        check(s >= 0) { "channel not open" }
        if (buffer.isEmpty()) return 0
        return buffer.usePinned { pinned ->
            recv(s, pinned.addressOf(0), buffer.size.convert(), 0).toInt()
        }
    }

    override fun close() {
        if (fd >= 0) {
            platform.posix.close(fd)
            fd = -1
        }
    }
}
