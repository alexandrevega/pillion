package app.pillion.core.headunit

/**
 * First-party (built-in) head-unit profiles. These register through the same [HeadUnitRegistry] API a
 * plugin would, so the extension surface is dogfooded. Add a bike = add a profile here (or, later, a
 * downloadable ProfileSpec) — nothing else changes (OCP).
 */

/** Yamaha NaviLite — Bluetooth slideshow (Path A, the original Pillion path). */
object NaviLiteProfile : HeadUnitProfile {
    override val id = "yamaha-navilite"
    override val displayName = "StreetCross-compatible (Bluetooth)"
    override val vendor = "Yamaha"
    override val linkKind = LinkKind.BluetoothRfcomm   // iOS resolves this to MFi in its SessionFactory
    override val requiresUsb = false
    override val videoPreference = VideoPreference.JpegSlideshow(quality = 40)
    override val identity: AppIdentity? = null
    override val compatibleApp = "StreetCross"
    override val exampleBikes = "MT-07 · MT-09 · XSR900 · R9 · Tracer — any bike that uses the StreetCross app"
}

/** Yamaha SDL / Motorize-compatible — USB full-motion H.264 (Path B). Proven on a real Tracer. */
object SdlProfile : HeadUnitProfile {
    override val id = "yamaha-sdl"
    override val displayName = "Motorize-compatible (USB)"
    override val vendor = "Yamaha"
    override val linkKind = LinkKind.UsbAoa            // iOS resolves this to iAP2/EAP in its SessionFactory
    override val requiresUsb = true
    override val videoPreference = VideoPreference.H264(maxBitrateBps = null) // resolved from the dash's caps
    override val compatibleApp = "Motorize"
    override val exampleBikes = "Tracer 9 GT+ and other bikes with a USB Garmin dash (Motorize app)"
    // The identity the Tracer accepts (proven). For DISTRIBUTION this should be user-supplied
    // (bring-your-own-identity, see docs/ARCHITECTURE_HEADUNITS.md) rather than baked in; kept here so
    // the launch build connects out of the box for testing.
    override val identity = AppIdentity(
        appName = "Motorize",
        fullAppId = "7a5f3f25-8b82-4e0f-a173-80aefee79897",
        iosEapProtocols = (0..29).map { "com.smartdevicelink.prot$it" } + "com.smartdevicelink.multisession",
    )
}

/** Register the built-in profiles. Call once at app startup (before the bike-selection UI reads them). */
fun registerBuiltInHeadUnits() {
    HeadUnitRegistry.register(NaviLiteProfile)
    HeadUnitRegistry.register(SdlProfile)
}
