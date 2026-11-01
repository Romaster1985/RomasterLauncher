package com.romaster.launcher.data

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AppRepository(private val context: Context) {

    private val pm: PackageManager = context.packageManager
    private val iconPackManager: IconPackManager
        get() = (context.applicationContext as com.romaster.launcher.RomasterApp).iconPackManager

    suspend fun loadApps(): List<AppInfo> = withContext(Dispatchers.IO) {
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }

        val resolveInfos: List<ResolveInfo> = pm.queryIntentActivities(mainIntent, 0)
        val apps = mutableListOf<AppInfo>()

        for (ri in resolveInfos) {
            val activityInfo = ri.activityInfo ?: continue
            val packageName = activityInfo.packageName
            val activityName = activityInfo.name
            val label = ri.loadLabel(pm)?.toString() ?: packageName

            if (packageName == context.packageName) continue

            val defaultIcon = ri.loadIcon(pm)
            val icon = iconPackManager.getIcon(packageName, activityName, defaultIcon)

            val isSystem = (activityInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0

            apps.add(
                AppInfo(
                    packageName = packageName,
                    activityName = activityName,
                    label = label,
                    icon = icon,
                    isSystemApp = isSystem
                )
            )
        }

        apps.sortedBy { it.label.lowercase() }
    }

    fun launchApp(app: AppInfo) {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            setClassName(app.packageName, app.activityName)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        }
        context.startActivity(intent)
    }
}
