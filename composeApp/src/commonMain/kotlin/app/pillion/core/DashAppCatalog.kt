package app.pillion.core

/** Launchable applications available as fixed virtual-display targets. */
data class DashApp(val packageName: String, val label: String)

fun interface DashAppCatalog {
    fun availableApps(): List<DashApp>
}
