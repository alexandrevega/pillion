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

    private companion object {
        const val THEME_KEY = "theme_mode"
        const val DASH_ENABLED_KEY = "dash_enabled"
        const val DASH_RES_KEY = "dash_resolution"
        const val BIKE_KEY = "selected_bike_id"
    }
}
