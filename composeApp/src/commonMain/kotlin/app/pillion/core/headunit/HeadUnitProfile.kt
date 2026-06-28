package app.pillion.core.headunit

/**
 * A motorcycle / head-unit described as data = one complete projection strategy. THE extension point:
 * adding a bike means adding a profile (and only the genuinely-new transport/protocol/codec), never
 * editing existing code (OCP). Session creation is platform-specific and lives in [SessionFactory],
 * so this stays pure-common metadata that the onboarding selector and registry can reason about.
 */
interface HeadUnitProfile {
    /** Stable id, e.g. "yamaha-navilite", "yamaha-sdl". */
    val id: String
    /** Shown in the bike-selection UI, framed by bike/connection (e.g. "Yamaha · USB full-motion"). */
    val displayName: String
    val vendor: String
    val linkKind: LinkKind
    val requiresUsb: Boolean
    val videoPreference: VideoPreference
    /** null until the user supplies one (bring-your-own-identity); non-impersonating profiles stay null. */
    val identity: AppIdentity?

    /**
     * The Garmin companion dash app this head unit speaks to — "StreetCross" (Bluetooth) or "Motorize"
     * (USB). Used only to describe compatibility ("StreetCross-compatible"), never as our own brand.
     */
    val compatibleApp: String get() = ""

    /** Human hint of known / likely bike models, shown during onboarding (e.g. "MT-07 · MT-09 · …"). */
    val exampleBikes: String get() = ""
}
