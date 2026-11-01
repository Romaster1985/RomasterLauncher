package com.romaster.launcher.ui

import android.app.WallpaperManager
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.PopupMenu
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.romaster.launcher.R
import com.romaster.launcher.RomasterApp
import com.romaster.launcher.data.AppInfo
import com.romaster.launcher.data.HomeItem
import com.romaster.launcher.databinding.ActivityHomeBinding
import kotlinx.coroutines.launch
import java.util.UUID

class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding
    private lateinit var desktopAdapter: AppIconAdapter
    private lateinit var dockAdapter: AppIconAdapter

    private val appRepo by lazy { (application as RomasterApp).appRepository }
    private val homeRepo by lazy { (application as RomasterApp).homeLayoutRepository }

    private var allApps: List<AppInfo> = emptyList()
    private var homeItems: MutableList<HomeItem> = mutableListOf()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupDesktop()
        setupDock()
        setupListeners()

        loadData()
    }

    private fun setupDesktop() {
        desktopAdapter = AppIconAdapter(
            onClick = { app -> launchApp(app) },
            onLongClick = { app, view -> showAppOptions(app, view) }
        )
        binding.desktopIcons.layoutManager = GridLayoutManager(this, 4)
        binding.desktopIcons.adapter = desktopAdapter
    }

    private fun setupDock() {
        dockAdapter = AppIconAdapter(
            onClick = { app -> launchApp(app) },
            onLongClick = { app, view -> showAppOptions(app, view) }
        )
        binding.dockIcons.layoutManager = GridLayoutManager(this, 5)
        binding.dockIcons.adapter = dockAdapter
    }

    private fun setupListeners() {
        binding.btnOpenDrawer.setOnClickListener {
            startActivity(Intent(this, AppDrawerActivity::class.java))
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }

        binding.homeRoot.setOnLongClickListener {
            showHomeOptions(it)
            true
        }

        binding.desktopIcons.setOnLongClickListener {
            showHomeOptions(it)
            true
        }
    }

    private fun loadData() {
        lifecycleScope.launch {
            allApps = appRepo.loadApps()
            homeItems = homeRepo.loadItems().toMutableList()

            if (homeItems.isEmpty()) {
                seedDefaultDock()
            }

            refreshUi()
        }
    }

    private fun seedDefaultDock() {
        val candidates = allApps.take(4)
        candidates.forEachIndexed { index, app ->
            homeItems.add(
                HomeItem(
                    id = UUID.randomUUID().toString(),
                    packageName = app.packageName,
                    activityName = app.activityName,
                    label = app.label,
                    isDock = true,
                    cellX = index
                )
            )
        }
        homeRepo.saveItems(homeItems)
    }

    private fun refreshUi() {
        val desktopApps = homeItems.filter { !it.isDock }.mapNotNull { item ->
            allApps.find { it.packageName == item.packageName && it.activityName == item.activityName }
        }
        val dockApps = homeItems.filter { it.isDock }.mapNotNull { item ->
            allApps.find { it.packageName == item.packageName && it.activityName == item.activityName }
        }

        desktopAdapter.submitList(desktopApps)
        dockAdapter.submitList(dockApps)
    }

    private fun launchApp(app: AppInfo) {
        try {
            appRepo.launchApp(app)
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudo abrir la aplicación", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showHomeOptions(anchor: View) {
        val popup = PopupMenu(this, anchor, Gravity.CENTER)
        popup.menu.add(0, 1, 0, getString(R.string.change_wallpaper))
        popup.menu.add(0, 2, 1, getString(R.string.add_widget))
        popup.menu.add(0, 3, 2, getString(R.string.settings))

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> openWallpaperPicker()
                2 -> {
                    Toast.makeText(this, "Selector de widgets (próximamente más completo)", Toast.LENGTH_SHORT).show()
                }
                3 -> startActivity(Intent(this, SettingsActivity::class.java))
            }
            true
        }
        popup.show()
    }

    private fun showAppOptions(app: AppInfo, anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, getString(R.string.remove_from_home))
        popup.menu.add(0, 2, 1, getString(R.string.app_info))
        popup.menu.add(0, 3, 2, getString(R.string.uninstall))

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> removeFromHome(app)
                2 -> openAppInfo(app)
                3 -> uninstallApp(app)
            }
            true
        }
        popup.show()
    }

    private fun removeFromHome(app: AppInfo) {
        homeItems.removeAll {
            it.packageName == app.packageName && it.activityName == app.activityName
        }
        homeRepo.saveItems(homeItems)
        refreshUi()
    }

    private fun openAppInfo(app: AppInfo) {
        val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = android.net.Uri.parse("package:${app.packageName}")
        }
        startActivity(intent)
    }

    private fun uninstallApp(app: AppInfo) {
        val intent = Intent(Intent.ACTION_DELETE).apply {
            data = android.net.Uri.parse("package:${app.packageName}")
        }
        startActivity(intent)
    }

    private fun openWallpaperPicker() {
        try {
            val intent = Intent(Intent.ACTION_SET_WALLPAPER)
            startActivity(Intent.createChooser(intent, getString(R.string.change_wallpaper)))
        } catch (e: Exception) {
            try {
                startActivity(Intent("android.intent.action.SET_WALLPAPER"))
            } catch (ex: Exception) {
                Toast.makeText(this, "No se pudo abrir el selector de fondos", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun addAppToHome(app: AppInfo, toDock: Boolean = false) {
        if (homeItems.any { it.packageName == app.packageName && it.activityName == app.activityName }) {
            Toast.makeText(this, "Ya está en el escritorio", Toast.LENGTH_SHORT).show()
            return
        }

        val item = HomeItem(
            id = UUID.randomUUID().toString(),
            packageName = app.packageName,
            activityName = app.activityName,
            label = app.label,
            isDock = toDock
        )
        homeItems.add(item)
        homeRepo.saveItems(homeItems)
        refreshUi()
        Toast.makeText(this, "Añadido al escritorio", Toast.LENGTH_SHORT).show()
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            allApps = appRepo.loadApps()
            refreshUi()
        }
    }
}
