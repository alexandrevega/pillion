package app.pillion.android

import android.content.Context
import app.pillion.core.DashResolution
import app.pillion.core.SettingsStore
import app.pillion.core.ThemeMode

/** [SettingsStore] backed by SharedPreferences. Single responsibility: persist preferences. */
class AndroidSettingsStore(context: Context) : SettingsStore {
    private val prefs = context.getSharedPreferences("pillion.settings", Context.MODE_PRIVATE)

    override fun themeMode(): ThemeMode =
        runCatching { ThemeMode.valueOf(prefs.getString(KEY_THEME, null) ?: ThemeMode.SYSTEM.name) }
            .getOrDefault(ThemeMode.SYSTEM)

    override fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME, mode.name).apply()
    }

    override fun dashEnabled(): Boolean = prefs.getBoolean(KEY_DASH_ENABLED, false)

    override fun setDashEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DASH_ENABLED, enabled).apply()
    }

    override fun dashResolution(): DashResolution =
        DashResolution.fromName(prefs.getString(KEY_DASH_RESOLUTION, null))

    override fun setDashResolution(resolution: DashResolution) {
        prefs.edit().putString(KEY_DASH_RESOLUTION, resolution.name).apply()
    }

    override fun selectedBikeId(): String? = prefs.getString(KEY_BIKE, null)

    override fun setSelectedBikeId(id: String) {
        prefs.edit().putString(KEY_BIKE, id).apply()
    }

    override fun usbOverride(): Boolean = prefs.getBoolean(KEY_USB_OVERRIDE, true)

    override fun setUsbOverride(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_USB_OVERRIDE, enabled).apply()
    }

    override fun tcpHost(): String = prefs.getString(KEY_TCP_HOST, "") ?: ""

    override fun setTcpHost(host: String) {
        prefs.edit().putString(KEY_TCP_HOST, host).apply()
    }

    override fun tcpPort(): Int = prefs.getInt(KEY_TCP_PORT, 12345)

    override fun setTcpPort(port: Int) {
        prefs.edit().putInt(KEY_TCP_PORT, port).apply()
    }

    /** File name + key mirrored by SdlService (it reads this store to decide attach behaviour). */
    companion object {
        const val PREFS_FILE = "pillion.settings"
        const val KEY_USB_OVERRIDE = "usb_override"

        private const val KEY_THEME = "theme_mode"
        private const val KEY_DASH_ENABLED = "dash_enabled"
        private const val KEY_DASH_RESOLUTION = "dash_resolution"
        private const val KEY_BIKE = "selected_bike_id"
        private const val KEY_TCP_HOST = "tcp_host"
        private const val KEY_TCP_PORT = "tcp_port"
    }
}
