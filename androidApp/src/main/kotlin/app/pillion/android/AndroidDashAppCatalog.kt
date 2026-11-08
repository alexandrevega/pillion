package app.pillion.android

import android.content.Context
import android.content.Intent
import app.pillion.core.DashApp
import app.pillion.core.DashAppCatalog

class AndroidDashAppCatalog(context: Context) : DashAppCatalog {
    private val pm = context.applicationContext.packageManager
    private val selfPackage = context.packageName

    override fun availableApps(): List<DashApp> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .asSequence()
            .filter { it.activityInfo.packageName != selfPackage }
            .map { DashApp(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
            .distinctBy { it.packageName }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
            .toList()
    }
}
