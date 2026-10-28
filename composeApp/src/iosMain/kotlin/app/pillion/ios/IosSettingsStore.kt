package app.pillion.ios

import app.pillion.core.DashResolution
import app.pillion.core.SettingsStore
import app.pillion.core.ThemeMode
import platform.Foundation.NSUserDefaults

/** [SettingsStore] backed by NSUserDefaults. */
class IosSettingsStore : SettingsStore {
    private val defaults = NSUserDefaults.standardUserDefaults

    override fun themeMode(): ThemeMode =
        runCatching { ThemeMode.valueOf(defaults.stringForKey(THEME_KEY) ?: ThemeMode.SYSTEM.name) }
            .getOrDefault(ThemeMode.SYSTEM)

    override fun setThemeMode(mode: ThemeMode) {
        defaults.setObject(mode.name, forKey = THEME_KEY)
    }

    // Dedicated dash mode is Android-only (ADB / virtual display); iOS streams via the broadcast
    // extension, so the UI never surfaces these. They're persisted only for interface completeness.
    override fun dashEnabled(): Boolean = defaults.boolForKey(DASH_ENABLED_KEY)

    override fun setDashEnabled(enabled: Boolean) {
        defaults.setBool(enabled, forKey = DASH_ENABLED_KEY)
    }

    override fun dashResolution(): DashResolution =
        DashResolution.fromName(defaults.stringForKey(DASH_RES_KEY))

    override fun setDashResolution(resolution: DashResolution) {
        defaults.setObject(resolution.name, forKey = DASH_RES_KEY)
    }

    override fun selectedBikeId(): String? = defaults.stringForKey(BIKE_KEY)

    override fun setSelectedBikeId(id: String) {
        defaults.setObject(id, forKey = BIKE_KEY)
    }

    // Interface completeness; the USB-override policy is Android (SDL/AOA) only. Default ON.
    override fun usbOverride(): Boolean =
        if (defaults.objectForKey(USB_OVERRIDE_KEY) == null) true else defaults.boolForKey(USB_OVERRIDE_KEY)

    override fun setUsbOverride(enabled: Boolean) {
        defaults.setBool(enabled, forKey = USB_OVERRIDE_KEY)
    }

    // Interface completeness; Wi-Fi streaming is Android-side SDL for now.
    override fun tcpHost(): String = defaults.stringForKey(TCP_HOST_KEY) ?: ""

    override fun setTcpHost(host: String) {
        defaults.setObject(host, forKey = TCP_HOST_KEY)
    }

    override fun tcpPort(): Int =
        if (defaults.objectForKey(TCP_PORT_KEY) == null) 12345 else defaults.integerForKey(TCP_PORT_KEY)

    override fun setTcpPort(port: Int) {
        defaults.setInteger(port, forKey = TCP_PORT_KEY)
    }

    private companion object {
        const val THEME_KEY = "theme_mode"
        const val USB_OVERRIDE_KEY = "usb_override"
        const val DASH_ENABLED_KEY = "dash_enabled"
        const val DASH_RES_KEY = "dash_resolution"
        const val BIKE_KEY = "selected_bike_id"
        const val TCP_HOST_KEY = "tcp_host"
        const val TCP_PORT_KEY = "tcp_port"
    }
}
