package com.romaster.launcher.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class LauncherPrefs(context: Context) {

    private val prefs = context.getSharedPreferences("romaster_prefs", Context.MODE_PRIVATE)

    var desktopColumns: Int
        get() = prefs.getInt("desktop_columns", 5)
        set(value) {
            prefs.edit().putInt("desktop_columns", value.coerceIn(3, 8)).apply()
        }

    var desktopRows: Int
        get() = prefs.getInt("desktop_rows", 6)
        set(value) {
            prefs.edit().putInt("desktop_rows", value.coerceIn(4, 10)).apply()
        }

    var gridPaddingDp: Int
        get() = prefs.getInt("grid_padding_dp", 8)
        set(value) {
            prefs.edit().putInt("grid_padding_dp", value.coerceIn(0, 32)).apply()
        }

    var desktopIconGapDp: Int
        get() = prefs.getInt("desktop_icon_gap", 4)
        set(value) {
            prefs.edit().putInt("desktop_icon_gap", value.coerceIn(0, 24)).apply()
        }

    var desktopDockGapDp: Int
        get() = prefs.getInt("desktop_dock_gap", 8)
        set(value) {
            prefs.edit().putInt("desktop_dock_gap", value.coerceIn(0, 48)).apply()
        }

    var dockIconGapDp: Int
        get() = prefs.getInt("dock_icon_gap", 10)
        set(value) {
            prefs.edit().putInt("dock_icon_gap", value.coerceIn(0, 32)).apply()
        }

    var desktopIconSizePercent: Int
        get() = prefs.getInt("desktop_icon_size", 100)
        set(value) {
            prefs.edit().putInt("desktop_icon_size", value.coerceIn(70, 160)).apply()
        }

    var dockIconSizePercent: Int
        get() = prefs.getInt("dock_icon_size", 100)
        set(value) {
            prefs.edit().putInt("dock_icon_size", value.coerceIn(70, 160)).apply()
        }

    var drawerIconSizePercent: Int
        get() = prefs.getInt("drawer_icon_size", 100)
        set(value) {
            prefs.edit().putInt("drawer_icon_size", value.coerceIn(70, 160)).apply()
        }

    var pageCount: Int
        get() = prefs.getInt("page_count", 2).coerceIn(1, 5)
        set(value) {
            prefs.edit().putInt("page_count", value.coerceIn(1, 5)).apply()
        }

    var defaultPage: Int
        get() = prefs.getInt("default_page", 0)
        set(value) {
            prefs.edit().putInt("default_page", value.coerceAtLeast(0)).apply()
        }

    var showDrawerButton: Boolean
        get() = prefs.getBoolean("show_drawer_button", true)
        set(value) {
            prefs.edit().putBoolean("show_drawer_button", value).apply()
        }

    var showDock: Boolean
        get() = prefs.getBoolean("show_dock", false)
        set(value) {
            prefs.edit().putBoolean("show_dock", value).apply()
        }

    var dockAboveDrawerButton: Boolean
        get() = prefs.getBoolean("dock_above_button", true)
        set(value) {
            prefs.edit().putBoolean("dock_above_button", value).apply()
        }

    var dockGlassBorder: Boolean
        get() = prefs.getBoolean("dock_glass", true)
        set(value) {
            prefs.edit().putBoolean("dock_glass", value).apply()
        }

    var drawerAlphabetical: Boolean
        get() = prefs.getBoolean("drawer_alpha", false)
        set(value) {
            prefs.edit().putBoolean("drawer_alpha", value).apply()
        }

    var pageTransition: String
        get() = prefs.getString("page_transition", "simple") ?: "simple"
        set(value) {
            prefs.edit().putString("page_transition", value).apply()
        }

    /** off | on | inverse — wallpaper parallax when changing desktop pages */
    var wallpaperScroll: String
        get() = prefs.getString("wallpaper_scroll", "on") ?: "on"
        set(value) {
            prefs.edit().putString("wallpaper_scroll", value).apply()
        }

    var folderScrollMode: String
        get() = prefs.getString("folder_scroll", "vertical") ?: "vertical"
        set(value) {
            prefs.edit().putString("folder_scroll", value).apply()
        }

    var folderColumns: Int
        get() = prefs.getInt("folder_columns", 4)
        set(value) {
            prefs.edit().putInt("folder_columns", value.coerceIn(2, 6)).apply()
        }

    var folderRows: Int
        get() = prefs.getInt("folder_rows", 4)
        set(value) {
            prefs.edit().putInt("folder_rows", value.coerceIn(2, 6)).apply()
        }

    fun getHiddenPackages(): Set<String> {
        return prefs.getStringSet("hidden_packages", emptySet()) ?: emptySet()
    }

    fun setHiddenPackages(set: Set<String>) {
        prefs.edit().putStringSet("hidden_packages", set).apply()
    }

    fun isHidden(packageName: String): Boolean {
        return getHiddenPackages().contains(packageName)
    }

    fun setHidden(packageName: String, hidden: Boolean) {
        val s = getHiddenPackages().toMutableSet()
        if (hidden) s.add(packageName) else s.remove(packageName)
        setHiddenPackages(s)
    }

    fun exportSettingsJson(): JSONObject {
        return JSONObject().apply {
            put("desktopColumns", desktopColumns)
            put("desktopRows", desktopRows)
            put("gridPaddingDp", gridPaddingDp)
            put("desktopIconGapDp", desktopIconGapDp)
            put("desktopDockGapDp", desktopDockGapDp)
            put("dockIconGapDp", dockIconGapDp)
            put("desktopIconSizePercent", desktopIconSizePercent)
            put("dockIconSizePercent", dockIconSizePercent)
            put("drawerIconSizePercent", drawerIconSizePercent)
            put("pageCount", pageCount)
            put("defaultPage", defaultPage)
            put("showDrawerButton", showDrawerButton)
            put("showDock", showDock)
            put("dockAboveDrawerButton", dockAboveDrawerButton)
            put("dockGlassBorder", dockGlassBorder)
            put("drawerAlphabetical", drawerAlphabetical)
            put("pageTransition", pageTransition)
            put("folderScrollMode", folderScrollMode)
            put("folderColumns", folderColumns)
            put("folderRows", folderRows)
            put("hiddenPackages", JSONArray(getHiddenPackages().toList()))
        }
    }

    fun importSettingsJson(obj: JSONObject) {
        if (obj.has("desktopColumns")) desktopColumns = obj.getInt("desktopColumns")
        if (obj.has("desktopRows")) desktopRows = obj.getInt("desktopRows")
        if (obj.has("gridPaddingDp")) gridPaddingDp = obj.getInt("gridPaddingDp")
        if (obj.has("desktopIconGapDp")) desktopIconGapDp = obj.getInt("desktopIconGapDp")
        if (obj.has("desktopDockGapDp")) desktopDockGapDp = obj.getInt("desktopDockGapDp")
        if (obj.has("dockIconGapDp")) dockIconGapDp = obj.getInt("dockIconGapDp")
        if (obj.has("desktopIconSizePercent")) desktopIconSizePercent = obj.getInt("desktopIconSizePercent")
        if (obj.has("dockIconSizePercent")) dockIconSizePercent = obj.getInt("dockIconSizePercent")
        if (obj.has("drawerIconSizePercent")) drawerIconSizePercent = obj.getInt("drawerIconSizePercent")
        if (obj.has("pageCount")) pageCount = obj.getInt("pageCount")
        if (obj.has("defaultPage")) defaultPage = obj.getInt("defaultPage")
        if (obj.has("showDrawerButton")) showDrawerButton = obj.getBoolean("showDrawerButton")
        if (obj.has("showDock")) showDock = obj.getBoolean("showDock")
        if (obj.has("dockAboveDrawerButton")) dockAboveDrawerButton = obj.getBoolean("dockAboveDrawerButton")
        if (obj.has("dockGlassBorder")) dockGlassBorder = obj.getBoolean("dockGlassBorder")
        if (obj.has("drawerAlphabetical")) drawerAlphabetical = obj.getBoolean("drawerAlphabetical")
        if (obj.has("pageTransition")) pageTransition = obj.getString("pageTransition")
        if (obj.has("folderScrollMode")) folderScrollMode = obj.getString("folderScrollMode")
        if (obj.has("folderColumns")) folderColumns = obj.getInt("folderColumns")
        if (obj.has("folderRows")) folderRows = obj.getInt("folderRows")
        if (obj.has("hiddenPackages")) {
            val arr = obj.getJSONArray("hiddenPackages")
            val set = mutableSetOf<String>()
            for (i in 0 until arr.length()) {
                set.add(arr.getString(i))
            }
            setHiddenPackages(set)
        }
    }
}
