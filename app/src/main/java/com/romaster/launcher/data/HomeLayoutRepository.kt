package com.romaster.launcher.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class HomeLayoutRepository(context: Context) {

    private val prefs = context.getSharedPreferences("romaster_home", Context.MODE_PRIVATE)

    fun saveItems(items: List<HomeItem>) {
        val array = JSONArray()
        for (item in items) {
            val obj = JSONObject().apply {
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
                val members = JSONArray()
                for (m in item.folderMembers) {
                    members.put(JSONObject().apply {
                        put("packageName", m.packageName)
                        put("activityName", m.activityName)
                        put("label", m.label)
                    })
                }
                put("folderMembers", members)
            }
            array.put(obj)
        }
        prefs.edit().putString("home_items", array.toString()).apply()
    }

    fun loadItems(): List<HomeItem> {
        val json = prefs.getString("home_items", null) ?: return emptyList()
        return try {
            val array = JSONArray(json)
            val list = mutableListOf<HomeItem>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
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
                        id = obj.getString("id"),
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
                        folderMembers = members
                    )
                )
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }
}
