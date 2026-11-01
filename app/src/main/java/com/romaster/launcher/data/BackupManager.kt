package com.romaster.launcher.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BackupManager(
    private val context: Context,
    private val prefs: LauncherPrefs,
    private val homeRepo: HomeLayoutRepository,
    private val iconPackManager: IconPackManager
) {

    fun createBackupJson(): String {
        val root = JSONObject()
        root.put("version", 1)
        root.put("app", "RomasterLauncher")
        root.put("createdAt", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date()))
        root.put("settings", prefs.exportSettingsJson())
        root.put("iconPack", iconPackManager.getCurrentPackPackage() ?: "")

        val items = homeRepo.loadItems()
        val arr = JSONArray()
        for (item in items) {
            arr.put(JSONObject().apply {
                put("id", item.id)
                put("packageName", item.packageName)
                put("activityName", item.activityName)
                put("label", item.label)
                put("screen", item.screen)
                put("cellX", item.cellX)
                put("cellY", item.cellY)
                put("spanX", item.spanX)
                put("spanY", item.spanY)
                put("isDock", item.isDock)
            })
        }
        root.put("homeItems", arr)
        return root.toString(2)
    }

    fun writeBackupToFile(): File {
        val dir = File(context.getExternalFilesDir(null), "backups").apply { mkdirs() }
        val name = "romaster_backup_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.json"
        val file = File(dir, name)
        file.writeText(createBackupJson())
        return file
    }

    fun restoreFromJson(json: String) {
        val root = JSONObject(json)
        if (root.has("settings")) {
            prefs.importSettingsJson(root.getJSONObject("settings"))
        }
        if (root.has("iconPack")) {
            val pack = root.getString("iconPack")
            if (pack.isBlank()) iconPackManager.setIconPack(null)
            else iconPackManager.setIconPack(pack)
        }
        if (root.has("homeItems")) {
            val arr = root.getJSONArray("homeItems")
            val list = mutableListOf<HomeItem>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    HomeItem(
                        id = obj.optString("id"),
                        packageName = obj.getString("packageName"),
                        activityName = obj.getString("activityName"),
                        label = obj.optString("label"),
                        screen = obj.optInt("screen", 0),
                        cellX = obj.optInt("cellX", 0),
                        cellY = obj.optInt("cellY", 0),
                        spanX = obj.optInt("spanX", 1),
                        spanY = obj.optInt("spanY", 1),
                        isDock = obj.optBoolean("isDock", false)
                    )
                )
            }
            homeRepo.saveItems(list)
        }
    }
}
