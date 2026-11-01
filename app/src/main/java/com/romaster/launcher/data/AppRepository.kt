package com.romaster.launcher.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

class AppRepository(private val context: Context) {

    private val pm: PackageManager = context.packageManager
    private val iconPackManager: IconPackManager
        get() = (context.applicationContext as com.romaster.launcher.RomasterApp).iconPackManager

    private val _apps = MutableStateFlow<List<AppInfo>>(emptyList())
    val apps: StateFlow<List<AppInfo>> = _apps.asStateFlow()

    private val isLoading = AtomicBoolean(false)

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            forceRefresh()
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(packageReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(packageReceiver, filter)
        }
    }

    suspend fun loadApps(force: Boolean = false): List<AppInfo> {
        if (!force && _apps.value.isNotEmpty()) {
            return _apps.value
        }
        return refreshApps()
    }

    fun forceRefresh() {
        _apps.value = emptyList()
    }

    fun getCachedApps(): List<AppInfo> = _apps.value

    private suspend fun refreshApps(): List<AppInfo> = withContext(Dispatchers.IO) {
        if (!isLoading.compareAndSet(false, true)) {
            return@withContext _apps.value
        }
        try {
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val resolveInfos: List<ResolveInfo> = pm.queryIntentActivities(mainIntent, 0)
            val apps = ArrayList<AppInfo>(resolveInfos.size)

            for (ri in resolveInfos) {
                val activityInfo = ri.activityInfo ?: continue
                val packageName = activityInfo.packageName
                val activityName = activityInfo.name
                if (packageName == context.packageName) continue

                val label = ri.loadLabel(pm)?.toString() ?: packageName
                val defaultIcon = try { ri.loadIcon(pm) } catch (e: Exception) { null }
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
            apps.sortBy { it.label.lowercase() }
            _apps.value = apps
            apps
        } finally {
            isLoading.set(false)
        }
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
