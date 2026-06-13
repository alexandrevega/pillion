package app.pillion.ios

import app.pillion.core.ByteChannel
import app.pillion.core.Logger
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.ExternalAccessory.EAAccessory
import platform.ExternalAccessory.EAAccessoryManager
import platform.ExternalAccessory.EASession
import platform.Foundation.NSDefaultRunLoopMode
import platform.Foundation.NSInputStream
import platform.Foundation.NSOutputStream
import platform.Foundation.NSRunLoop
import platform.posix.uint8_tVar
import platform.posix.usleep

/**
 * The **production** iOS transport: talks to the real dash as an MFi External Accessory.
 *
 * Unlike [NetworkByteChannel] (which targets the emulator over TCP), this can only run
 * against the actual bike — MFi accessories require Apple's hardware auth co-processor, so
 * no PC or Raspberry Pi can stand in for it. Everything *above* the [ByteChannel] seam is
 * identical between the two, which is why we can validate the whole stack against the
 * emulator and leave only this thin adapter for final on-bike testing.
 *
 * @param protocolString the accessory's EA protocol, from the dash's MFi declaration.
 *        On a connected bike, discover it via `EAAccessory.protocolStrings`.
 *
 * NOTE: pending validation on real hardware — the read/write polling below assumes the
 * stream's run loop is being serviced on this thread.
 */
@OptIn(ExperimentalForeignApi::class)
class ExternalAccessoryByteChannel(
    private val protocolString: String,
) : ByteChannel {
    private var session: EASession? = null
    private var input: NSInputStream? = null
    private var output: NSOutputStream? = null

    override fun open() {
        val manager = EAAccessoryManager.sharedAccessoryManager()
        val accessory = manager.connectedAccessories
            .filterIsInstance<EAAccessory>()
            .firstOrNull { it.protocolStrings.contains(protocolString) }
            ?: error("no MFi dash advertising '$protocolString' is connected")
        val s = EASession(accessory = accessory, forProtocol = protocolString)
        val inp = s.inputStream ?: error("EA session has no input stream")
        val out = s.outputStream ?: error("EA session has no output stream")
        val loop = NSRunLoop.currentRunLoop
        inp.scheduleInRunLoop(loop, NSDefaultRunLoopMode)
        out.scheduleInRunLoop(loop, NSDefaultRunLoopMode)
        inp.open()
        out.open()
        session = s
        input = inp
        output = out
        Logger.d("ea: session open to ${accessory.name}")
    }

    override fun write(bytes: ByteArray) {
        val out = output ?: error("channel not open")
        if (bytes.isEmpty()) return
        bytes.usePinned { pinned ->
            var off = 0
            while (off < bytes.size) {
                while (!out.hasSpaceAvailable) usleep(1000u)
                val n = out.write(pinned.addressOf(off).reinterpret<uint8_tVar>(), (bytes.size - off).convert())
                check(n > 0) { "EA write failed" }
                off += n.toInt()
            }
        }
    }

    override fun read(buffer: ByteArray): Int {
        val inp = input ?: error("channel not open")
        if (buffer.isEmpty()) return 0
        while (!inp.hasBytesAvailable) usleep(1000u)
        return buffer.usePinned { pinned ->
            inp.read(pinned.addressOf(0).reinterpret<uint8_tVar>(), buffer.size.convert()).toInt()
        }
    }

    override fun close() {
        input?.close()
        output?.close()
        session = null
        input = null
        output = null
    }
}
