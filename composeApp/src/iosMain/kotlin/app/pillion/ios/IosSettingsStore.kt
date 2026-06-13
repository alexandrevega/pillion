package app.pillion.ios

import app.pillion.core.SettingsStore
import app.pillion.core.ThemeMode
import platform.Foundation.NSUserDefaults

/** [SettingsStore] backed by NSUserDefaults. */
class IosSettingsStore : SettingsStore {
    private val defaults = NSUserDefaults.standardUserDefaults

    override fun themeMode(): ThemeMode =
        runCatching { ThemeMode.valueOf(defaults.stringForKey(KEY) ?: ThemeMode.SYSTEM.name) }
            .getOrDefault(ThemeMode.SYSTEM)

    override fun setThemeMode(mode: ThemeMode) {
        defaults.setObject(mode.name, forKey = KEY)
    }

    private companion object {
        const val KEY = "theme_mode"
    }
}
