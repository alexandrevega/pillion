package app.pillion.core

/**
 * Persists user preferences across launches. The UI depends on this abstraction (DIP); platforms
 * provide it (Android: SharedPreferences). A null store simply means "use defaults" (e.g. previews).
 */
interface SettingsStore {
    fun themeMode(): ThemeMode
    fun setThemeMode(mode: ThemeMode)

    /** Whether the user has opted into "dedicated dash display" mode (completed onboarding). */
    fun dashEnabled(): Boolean
    fun setDashEnabled(enabled: Boolean)

    /** Virtual display resolution for the dedicated dash helper. */
    fun dashResolution(): DashResolution
    fun setDashResolution(resolution: DashResolution)

    /** The head-unit the user picked at onboarding ([app.pillion.core.headunit.HeadUnitProfile.id]),
     *  or null if they haven't chosen yet (→ show the bike-selection screen). */
    fun selectedBikeId(): String?
    fun setSelectedBikeId(id: String)

    /** When true (default), a USB dash connection wins over a live/possible Wi-Fi (TCP) session:
     *  plugging in switches a running TCP session to USB, and Start with the cable in uses USB even
     *  if an IP is typed. When false, a HEALTHY TCP session ignores the plug-in (charging/faulty-cable
     *  safety); a dead session still rebuilds over USB. */
    fun usbOverride(): Boolean
    fun setUsbOverride(enabled: Boolean)

    /** The bike's Wi-Fi (TCP) host typed in settings, or "" for USB-only mode. Persisted. */
    fun tcpHost(): String
    fun setTcpHost(host: String)

    /** TCP port for Wi-Fi streaming (default 12345). Persisted. */
    fun tcpPort(): Int
    fun setTcpPort(port: Int)
}
