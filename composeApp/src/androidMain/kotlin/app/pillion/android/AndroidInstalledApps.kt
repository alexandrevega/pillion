package app.pillion.android

import android.content.Context
import android.content.Intent
import app.pillion.core.DashApp
import app.pillion.core.InstalledApps

/** [InstalledApps] backed by PackageManager — every app with a launcher entry, minus Pillion itself. */
class AndroidInstalledApps(private val context: Context) : InstalledApps {
    override fun launchable(): List<DashApp> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .mapNotNull { info ->
                val ai = info.activityInfo ?: return@mapNotNull null
                if (ai.packageName == context.packageName) return@mapNotNull null
                val component = "${ai.packageName}/${ai.name}"
                DashApp(
                    label = info.loadLabel(pm).toString(),
                    packageName = ai.packageName,
                    component = component,
                )
            }
            .distinctBy { it.component }
            .sortedBy { it.label.lowercase() }
    }
}
