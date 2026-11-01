package com.romaster.launcher.ui

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.PopupMenu
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
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

    // Track if a long-press happened on an icon so we don't also show the home menu
    private var longPressedOnIcon = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupDesktop()
        setupDock()
        setupListeners()
        setupReorder()

        loadData()
    }

    private fun setupDesktop() {
        desktopAdapter = AppIconAdapter(
            onClick = { app -> launchApp(app) },
            onLongClick = { app, view ->
                longPressedOnIcon = true
                showAppOptions(app, view)
            }
        )
        binding.desktopIcons.layoutManager = GridLayoutManager(this, 4)
        binding.desktopIcons.adapter = desktopAdapter

        // Make RecyclerView not consume long-presses on empty space
        binding.desktopIcons.isNestedScrollingEnabled = false
        binding.desktopIcons.setHasFixedSize(true)
    }

    private fun setupDock() {
        dockAdapter = AppIconAdapter(
            onClick = { app -> launchApp(app) },
            onLongClick = { app, view ->
                longPressedOnIcon = true
                showAppOptions(app, view)
            }
        )
        binding.dockIcons.layoutManager = GridLayoutManager(this, 5)
        binding.dockIcons.adapter = dockAdapter
    }

    private fun setupListeners() {
        // Open app drawer
        binding.btnOpenDrawer.setOnClickListener {
            startActivity(Intent(this, AppDrawerActivity::class.java))
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }

        // Long-press on the root (empty areas of the home screen)
        binding.homeRoot.setOnLongClickListener {
            if (!longPressedOnIcon) {
                showHomeOptions(it)
            }
            longPressedOnIcon = false
            true
        }

        // Also capture long-press that bubbles from the RecyclerView empty space
        binding.desktopIcons.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            private var downX = 0f
            private var downY = 0f
            private var longPressTriggered = false

            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = e.x
                        downY = e.y
                        longPressTriggered = false
                        longPressedOnIcon = false
                        // Schedule a long-press check
                        rv.postDelayed({
                            if (!longPressTriggered && !longPressedOnIcon) {
                                // Check if the touch is still roughly in the same place
                                // and no child was pressed
                                val child = rv.findChildViewUnder(downX, downY)
                                if (child == null) {
                                    longPressTriggered = true
                                    showHomeOptions(rv)
                                }
                            }
                        }, 500) // 500ms long-press
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        longPressTriggered = true // cancel pending
                    }
                }
                return false // never steal the event
            }
        })
    }

    /** Enable drag-to-reorder on desktop and dock */
    private fun setupReorder() {
        val desktopCallback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT,
            0
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false

                // Reorder the underlying homeItems that are NOT dock
                val desktopItems = homeItems.filter { !it.isDock }.toMutableList()
                if (from < desktopItems.size && to < desktopItems.size) {
                    val item = desktopItems.removeAt(from)
                    desktopItems.add(to, item)

                    // Rebuild homeItems keeping dock items
                    val dockItems = homeItems.filter { it.isDock }
                    homeItems.clear()
                    homeItems.addAll(desktopItems)
                    homeItems.addAll(dockItems)
                    homeRepo.saveItems(homeItems)
                    refreshUi()
                }
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}
            override fun isLongPressDragEnabled() = true
        }
        ItemTouchHelper(desktopCallback).attachToRecyclerView(binding.desktopIcons)

        val dockCallback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT,
            0
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false

                val dockItems = homeItems.filter { it.isDock }.toMutableList()
                if (from < dockItems.size && to < dockItems.size) {
                    val item = dockItems.removeAt(from)
                    dockItems.add(to, item)

                    val desktopItems = homeItems.filter { !it.isDock }
                    homeItems.clear()
                    homeItems.addAll(desktopItems)
                    homeItems.addAll(dockItems)
                    homeRepo.saveItems(homeItems)
                    refreshUi()
                }
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}
            override fun isLongPressDragEnabled() = true
        }
        ItemTouchHelper(dockCallback).attachToRecyclerView(binding.dockIcons)
    }

    private fun loadData() {
        lifecycleScope.launch {
            // Uses cache → should be fast after first load
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
                    Toast.makeText(
                        this,
                        "Selector de widgets (próximamente más completo)",
                        Toast.LENGTH_SHORT
                    ).show()
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
        Toast.makeText(this, "Eliminado del escritorio", Toast.LENGTH_SHORT).show()
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
            Toast.makeText(this, "No se pudo abrir el selector de fondos", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        // Only refresh the UI from cache – do NOT force a full PackageManager scan every time
        lifecycleScope.launch {
            allApps = appRepo.loadApps(force = false)
            homeItems = homeRepo.loadItems().toMutableList()
            refreshUi()
        }
    }
}
