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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * App list + icons.
 *
 * Cold start is two-phase (Nova/Lawnchair style):
 *  1) Resolve launcher activities + **default** system icons (fast) → publish
 *  2) Resolve icon-pack icons **in parallel** on IO → publish again
 *
 * UI should paint after phase 1 and refresh when phase 2 completes.
 */
class AppRepository(private val context: Context) {

    private val pm: PackageManager = context.packageManager
    private val iconPackManager: IconPackManager
        get() = (context.applicationContext as com.romaster.launcher.RomasterApp).iconPackManager

    private val _apps = MutableStateFlow<List<AppInfo>>(emptyList())
    val apps: StateFlow<List<AppInfo>> = _apps.asStateFlow()

    private val loadMutex = Mutex()
    @Volatile private var needsRefresh = false

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // Do NOT wipe _apps to empty — that causes gray icons on soft resume.
            // Mark dirty; next loadApps(force=true) will replace the list atomically.
            needsRefresh = true
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

    fun forceRefresh() {
        // Keep showing the previous list until loadApps finishes (no gray-icon flash)
        needsRefresh = true
    }

    fun getCachedApps(): List<AppInfo> = _apps.value

    /**
     * @param force rebuild even if cache is warm
     * @param onPartial invoked on Main-caller coroutine context after phase 1 (default icons)
     *                  and again after phase 2 (icon pack). Safe to paint UI from here.
     */
    suspend fun loadApps(
        force: Boolean = false,
        onPartial: (suspend (List<AppInfo>) -> Unit)? = null
    ): List<AppInfo> {
        val must = force || needsRefresh
        if (!must && _apps.value.isNotEmpty()) {
            val cached = _apps.value
            onPartial?.invoke(cached)
            return cached
        }
        needsRefresh = false
        return refreshApps(must, onPartial)
    }

    private suspend fun refreshApps(
        force: Boolean,
        onPartial: (suspend (List<AppInfo>) -> Unit)?
    ): List<AppInfo> {
        // Fast path without holding work for long
        if (!force && _apps.value.isNotEmpty()) {
            val cached = _apps.value
            onPartial?.invoke(cached)
            return cached
        }

        loadMutex.lock()
        val fast: List<AppInfo>
        try {
            if (!force && _apps.value.isNotEmpty()) {
                fast = _apps.value
            } else {
                // ── Phase 1: package list + default icons only ──
                fast = withContext(Dispatchers.IO) {
                    val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                        addCategory(Intent.CATEGORY_LAUNCHER)
                    }
                    val resolveInfos: List<ResolveInfo> = try {
                        pm.queryIntentActivities(mainIntent, 0)
                    } catch (_: Exception) {
                        emptyList()
                    }
                    val apps = ArrayList<AppInfo>(resolveInfos.size)
                    for (ri in resolveInfos) {
                        val activityInfo = ri.activityInfo ?: continue
                        val packageName = activityInfo.packageName
                        val activityName = activityInfo.name
                        if (packageName == context.packageName) continue
                        val label = try {
                            ri.loadLabel(pm)?.toString() ?: packageName
                        } catch (_: Exception) {
                            packageName
                        }
                        val defaultIcon = try {
                            ri.loadIcon(pm)
                        } catch (_: Exception) {
                            null
                        }
                        val isSystem = try {
                            (activityInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                        } catch (_: Exception) {
                            false
                        }
                        apps.add(
                            AppInfo(
                                packageName = packageName,
                                activityName = activityName,
                                label = label,
                                icon = defaultIcon,
                                isSystemApp = isSystem
                            )
                        )
                    }
                    apps.sortBy { it.label.lowercase() }
                    apps
                }
                _apps.value = fast
            }
        } finally {
            loadMutex.unlock()
        }
        onPartial?.invoke(fast)

        // ── Phase 2: icon-pack in parallel (outside lock so UI can run) ──
        val enriched = withContext(Dispatchers.IO) {
            coroutineScope {
                val chunks = fast.chunked(24)
                val result = ArrayList<AppInfo>(fast.size)
                for (chunk in chunks) {
                    val part = chunk.map { app ->
                        async(Dispatchers.IO) {
                            try {
                                val packed = iconPackManager.getIcon(
                                    app.packageName,
                                    app.activityName,
                                    app.icon
                                )
                                if (packed != null && packed !== app.icon) {
                                    app.copy(icon = packed)
                                } else {
                                    app
                                }
                            } catch (_: Exception) {
                                app
                            }
                        }
                    }.awaitAll()
                    result.addAll(part)
                }
                result
            }
        }
        _apps.value = enriched
        onPartial?.invoke(enriched)
        return enriched
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
