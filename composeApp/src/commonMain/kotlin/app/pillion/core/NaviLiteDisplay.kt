package app.pillion.core

import app.pillion.protocol.Auth

/** Native pixel size of a NaviLite dash's navigation image. */
data class DashSize(val width: Int, val height: Int)

/**
 * The dash renders each JPEG into a fixed-size navigation viewport and rejects a frame whose
 * dimensions don't match — the CCU throws "Connection Error" the moment it enters navigation. The
 * size is never advertised on the wire, but the CCU announces its **part number** in
 * AUTH_REQUEST_SEC_DATA ([Auth.partNumber]), which identifies the dash model closely enough to pick
 * the right size.
 *
 * Sizes are observed from real dashes over Bluetooth; add models here as captures confirm them.
 */
object NaviLiteDisplay {
    /** The standard Yamaha 480x240 TFT (MT-07 · MT-09 · XSR900 · R9 · Tracer) — the default. */
    val DEFAULT = DashSize(480, 240)

    /**
     * Resolve the dash image size from the CCU part number the handshake de-obfuscates. Unknown
     * part numbers fall back to [DEFAULT], so a new bike still connects at the common size.
     */
    fun forCcuPartNumber(partNumber: String): DashSize = when {
        // XMAX / NMAX scooter CCU (006-B3952-xx) uses a 480x234 nav viewport, not 480x240; sending
        // 480x240 makes the dash show "Connection Error" on entering navigation (GitHub issue #7).
        partNumber.startsWith("006-B3952") -> DashSize(480, 234)
        else -> DEFAULT
    }
}
