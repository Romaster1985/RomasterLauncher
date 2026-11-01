package com.romaster.launcher

import android.app.Application
import com.romaster.launcher.data.AppRepository
import com.romaster.launcher.data.IconPackManager
import com.romaster.launcher.data.HomeLayoutRepository

class RomasterApp : Application() {

    lateinit var appRepository: AppRepository
        private set

    lateinit var iconPackManager: IconPackManager
        private set

    lateinit var homeLayoutRepository: HomeLayoutRepository
        private set

    override fun onCreate() {
        super.onCreate()
        appRepository = AppRepository(this)
        iconPackManager = IconPackManager(this)
        homeLayoutRepository = HomeLayoutRepository(this)
    }
}
