package app.pillion.core.headunit

/**
 * The companion app a profile presents itself as to the head unit (e.g. "Motorize"). This is DATA —
 * a compatibility key the head unit checks — not bundled vendor code. Intended to be supplied by the
 * user at first run (bring-your-own-identity), so a third-party appId need not ship in the binary.
 */
data class AppIdentity(
    val appName: String,
    val fullAppId: String,
    /** iOS External Accessory protocol strings (e.g. SDL's com.smartdevicelink.prot0 .. prot29). */
    val iosEapProtocols: List<String> = emptyList(),
)
