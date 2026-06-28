package app.pillion.core.headunit

import app.pillion.core.MirrorController
import app.pillion.core.MirrorSettings

/**
 * Builds a live session ([MirrorController]) for a chosen [HeadUnitProfile]. Implemented per platform
 * (Android wires RFCOMM / USB-AOA + MediaProjection; iOS wires EAAccessory / iAP2 + ReplayKit), so the
 * Compose UI selects a profile (common data) and gets a controller without knowing the wiring (DIP).
 */
interface SessionFactory {
    /** Whether this factory can build the given profile on the current platform. */
    fun supports(profile: HeadUnitProfile): Boolean

    fun create(profile: HeadUnitProfile, settings: MirrorSettings): MirrorController
}
