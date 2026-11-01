package com.romaster.launcher.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class DrawerGroup(
    val id: String,
    var name: String,
    val members: MutableList<FolderMember> = mutableListOf(),
    var enabled: Boolean = true
)

class DrawerGroupStore(context: Context) {
    private val prefs = context.getSharedPreferences("romaster_drawer_groups", Context.MODE_PRIVATE)

    fun load(): MutableList<DrawerGroup> {
        val raw = prefs.getString("groups_json", null) ?: return mutableListOf()
        return try {
            val arr = JSONArray(raw)
            val out = mutableListOf<DrawerGroup>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
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
                out.add(
                    DrawerGroup(
                        id = o.getString("id"),
                        name = o.optString("name", "Grupo"),
                        members = members,
                        enabled = o.optBoolean("enabled", true)
                    )
                )
            }
            out
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    fun save(groups: List<DrawerGroup>) {
        val arr = JSONArray()
        for (g in groups) {
            arr.put(JSONObject().apply {
                put("id", g.id)
                put("name", g.name)
                put("enabled", g.enabled)
                put("members", JSONArray().apply {
                    for (m in g.members) {
                        put(JSONObject().apply {
                            put("packageName", m.packageName)
                            put("activityName", m.activityName)
                            put("label", m.label)
                        })
                    }
                })
            })
        }
        prefs.edit().putString("groups_json", arr.toString()).apply()
    }

    fun newGroup(name: String = "Nuevo grupo"): DrawerGroup {
        return DrawerGroup(id = UUID.randomUUID().toString(), name = name)
    }
}
