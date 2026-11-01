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
                put("isWidget", item.isWidget)
                put("appWidgetId", item.appWidgetId)
                put("isWidgetStack", item.isWidgetStack)
                put("stackSwipeVertical", item.stackSwipeVertical)
                put("stackCurrentPage", item.stackCurrentPage)
                put("isGifWidget", item.isGifWidget)
                put("gifPath", item.gifPath)
                put("gifAspectMode", item.gifAspectMode)
                put("zOrder", item.zOrder)
                put("freePlacement", item.freePlacement)
                put("freeX", item.freeX.toDouble())
                put("freeY", item.freeY.toDouble())
                put("freeW", item.freeW)
                put("freeH", item.freeH)
                val members = JSONArray()
                for (m in item.folderMembers) {
                    members.put(JSONObject().apply {
                        put("packageName", m.packageName)
                        put("activityName", m.activityName)
                        put("label", m.label)
                    })
                }
                put("folderMembers", members)
                val stack = JSONArray()
                for (s in item.stackMembers) {
                    stack.put(JSONObject().apply {
                        put("packageName", s.packageName)
                        put("className", s.className)
                        put("appWidgetId", s.appWidgetId)
                    })
                }
                put("stackMembers", stack)
            }
            array.put(obj)
        }
        prefs.edit().putString("home_items", array.toString()).commit()
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
                val stackMembers = mutableListOf<StackWidgetMember>()
                val stackArr = obj.optJSONArray("stackMembers")
                if (stackArr != null) {
                    for (j in 0 until stackArr.length()) {
                        val s = stackArr.getJSONObject(j)
                        stackMembers.add(
                            StackWidgetMember(
                                packageName = s.getString("packageName"),
                                className = s.getString("className"),
                                appWidgetId = s.optInt("appWidgetId", -1)
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
                        folderMembers = members,
                        isWidget = obj.optBoolean("isWidget", false),
                        appWidgetId = obj.optInt("appWidgetId", -1),
                        isWidgetStack = obj.optBoolean("isWidgetStack", false),
                        stackMembers = stackMembers,
                        stackSwipeVertical = obj.optBoolean("stackSwipeVertical", true),
                        stackCurrentPage = obj.optInt("stackCurrentPage", 0),
                        isGifWidget = obj.optBoolean("isGifWidget", false),
                        gifPath = obj.optString("gifPath", ""),
                        gifAspectMode = obj.optString("gifAspectMode", "fit"),
                        zOrder = obj.optInt("zOrder", 0),
                        freePlacement = obj.optBoolean("freePlacement", false),
                        freeX = obj.optDouble("freeX", 0.0).toFloat(),
                        freeY = obj.optDouble("freeY", 0.0).toFloat(),
                        freeW = obj.optInt("freeW", 0),
                        freeH = obj.optInt("freeH", 0)
                    )
                )
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }
}
