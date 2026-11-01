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
    private val drawerGroupStore = DrawerGroupStore(context)

    fun createBackupJson(): String {
        val root = JSONObject()
        root.put("version", 2)
        root.put("app", "RomasterLauncher")
        root.put("createdAt", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date()))
        root.put("settings", prefs.exportSettingsJson())
        root.put("iconPack", iconPackManager.getCurrentPackPackage() ?: "")

        // Desktop / dock items including folders and widgets
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
                put("isFolder", item.isFolder)
                put("folderName", item.folderName)
                put("isWidget", item.isWidget)
                put("appWidgetId", item.appWidgetId)
                val members = JSONArray()
                for (m in item.folderMembers) {
                    members.put(JSONObject().apply {
                        put("packageName", m.packageName)
                        put("activityName", m.activityName)
                        put("label", m.label)
                    })
                }
                put("folderMembers", members)
            })
        }
        root.put("homeItems", arr)

        // Drawer groups (cajón)
        val groups = drawerGroupStore.load()
        val gArr = JSONArray()
        for (g in groups) {
            gArr.put(JSONObject().apply {
                put("id", g.id)
                put("name", g.name)
                put("enabled", g.enabled)
                val members = JSONArray()
                for (m in g.members) {
                    members.put(JSONObject().apply {
                        put("packageName", m.packageName)
                        put("activityName", m.activityName)
                        put("label", m.label)
                    })
                }
                put("members", members)
            })
        }
        root.put("drawerGroups", gArr)

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
                val members = mutableListOf<FolderMember>()
                val memArr = obj.optJSONArray("folderMembers")
                if (memArr != null) {
                    for (j in 0 until memArr.length()) {
                        val m = memArr.getJSONObject(j)
                        members.add(
                            FolderMember(
                                packageName = m.getString("packageName"),
                                activityName = m.getString("activityName"),
                                label = m.optString("label", "")
                            )
                        )
                    }
                }
                list.add(
                    HomeItem(
                        id = obj.optString("id"),
                        packageName = obj.optString("packageName", ""),
                        activityName = obj.optString("activityName", ""),
                        label = obj.optString("label", ""),
                        screen = obj.optInt("screen", 0),
                        cellX = obj.optInt("cellX", 0),
                        cellY = obj.optInt("cellY", 0),
                        spanX = obj.optInt("spanX", 1),
                        spanY = obj.optInt("spanY", 1),
                        isDock = obj.optBoolean("isDock", false),
                        isFolder = obj.optBoolean("isFolder", false),
                        folderName = obj.optString("folderName", "Carpeta"),
                        folderMembers = members,
                        isWidget = obj.optBoolean("isWidget", false),
                        appWidgetId = obj.optInt("appWidgetId", -1)
                    )
                )
            }
            homeRepo.saveItems(list)
        }
        if (root.has("drawerGroups")) {
            val gArr = root.getJSONArray("drawerGroups")
            val groups = mutableListOf<DrawerGroup>()
            for (i in 0 until gArr.length()) {
                val o = gArr.getJSONObject(i)
                val members = mutableListOf<FolderMember>()
                val mArr = o.optJSONArray("members") ?: JSONArray()
                for (j in 0 until mArr.length()) {
                    val m = mArr.getJSONObject(j)
                    members.add(
                        FolderMember(
                            packageName = m.getString("packageName"),
                            activityName = m.getString("activityName"),
                            label = m.optString("label", m.getString("packageName"))
                        )
                    )
                }
                groups.add(
                    DrawerGroup(
                        id = o.optString("id", java.util.UUID.randomUUID().toString()),
                        name = o.optString("name", "Grupo"),
                        members = members,
                        enabled = o.optBoolean("enabled", true)
                    )
                )
            }
            drawerGroupStore.save(groups)
        }
        // HomeActivity soft-resume would keep stale in-memory lists — force full reload
        prefs.pendingHomeReload = true
    }
}
