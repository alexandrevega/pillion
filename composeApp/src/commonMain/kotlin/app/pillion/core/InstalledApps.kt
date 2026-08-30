package app.pillion.core

/** A launchable app the user can pin to the dash. [component] is the flattened launcher component. */
data class DashApp(val label: String, val packageName: String, val component: String)

/**
 * Lists the phone's launchable apps for the dash app-picker. The UI depends on this abstraction
 * (DIP); the Android platform provides it (PackageManager). Null store → empty list (e.g. previews).
 */
interface InstalledApps {
    fun launchable(): List<DashApp>
}
