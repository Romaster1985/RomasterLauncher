package com.romaster.launcher

import android.app.Application
import com.romaster.launcher.data.AppRepository
import com.romaster.launcher.data.HomeLayoutRepository
import com.romaster.launcher.data.IconPackManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class RomasterApp : Application() {

    lateinit var appRepository: AppRepository
        private set

    lateinit var iconPackManager: IconPackManager
        private set

    lateinit var homeLayoutRepository: HomeLayoutRepository
        private set

    /** Process-scoped scope — survives Activity recreation while the process lives. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        appRepository = AppRepository(this)
        iconPackManager = IconPackManager(this)
        homeLayoutRepository = HomeLayoutRepository(this)
        // Warm the app list + icons as soon as the process starts so HomeActivity
        // can paint without waiting on PackageManager on first (or cold) resume.
        appScope.launch {
            try {
                appRepository.loadApps(force = false)
            } catch (_: Exception) {
            }
        }
    }
}
