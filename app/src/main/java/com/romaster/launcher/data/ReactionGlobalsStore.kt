package com.romaster.launcher.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Launcher-wide global bool variables shared by all IMG/GIF reaction recipes.
 * Values persist in SharedPreferences and only emit change events on real transitions.
 */
object ReactionGlobalsStore {

    private const val PREFS = "romaster_reaction_globals"
    private const val KEY_BOOLS = "bool_globals_json"

    data class GlobalBool(val name: String, var value: Boolean)

    fun interface OnBoolChanged {
        /** Called only when [value] is different from the previous stored value. */
        fun onChanged(name: String, value: Boolean)
    }

    private val listeners = mutableListOf<OnBoolChanged>()

    @Volatile private var memoryCache: MutableMap<String, Boolean>? = null

    fun addListener(l: OnBoolChanged) {
        synchronized(listeners) {
            if (!listeners.contains(l)) listeners.add(l)
        }
    }

    fun removeListener(l: OnBoolChanged) {
        synchronized(listeners) { listeners.remove(l) }
    }

    private fun notifyChanged(name: String, value: Boolean) {
        val snap: List<OnBoolChanged>
        synchronized(listeners) { snap = listeners.toList() }
        snap.forEach {
            try { it.onChanged(name, value) } catch (_: Exception) {}
        }
    }

    private fun ensureCache(context: Context): MutableMap<String, Boolean> {
        memoryCache?.let { return it }
        val map = mutableMapOf<String, Boolean>()
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_BOOLS, null)
        if (!raw.isNullOrBlank()) {
            try {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val name = o.optString("name", "").trim()
                    if (name.isNotEmpty()) map[name] = o.optBoolean("value", false)
                }
            } catch (_: Exception) {}
        }
        memoryCache = map
        return map
    }

    private fun persist(context: Context, map: Map<String, Boolean>) {
        val arr = JSONArray()
        map.forEach { (name, value) ->
            arr.put(JSONObject().apply {
                put("name", name)
                put("value", value)
            })
        }
        // commit() so value is on disk before process is killed on lock
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_BOOLS, arr.toString())
            .commit()
    }

    fun listBools(context: Context): MutableList<GlobalBool> {
        return ensureCache(context).map { GlobalBool(it.key, it.value) }
            .sortedBy { it.name.lowercase() }
            .toMutableList()
    }

    fun getBool(context: Context, name: String, default: Boolean = false): Boolean {
        return ensureCache(context)[name] ?: default
    }

    /**
     * Updates a global bool. Notifies listeners only if the value actually changed.
     * @return true if the value changed
     */
    fun setBool(context: Context, name: String, value: Boolean): Boolean {
        val n = name.trim()
        if (n.isEmpty()) return false
        val map = ensureCache(context)
        val prev = map[n]
        if (prev != null && prev == value) {
            return false // no change — do NOT notify (prevents unlock / redraw re-fires)
        }
        map[n] = value
        persist(context, map)
        notifyChanged(n, value)
        return true
    }

    fun addBool(context: Context, name: String, initial: Boolean): Boolean {
        val n = name.trim()
        if (n.isEmpty()) return false
        val map = ensureCache(context)
        if (map.keys.any { it.equals(n, ignoreCase = true) }) return false
        map[n] = initial
        persist(context, map)
        // Creating a var does not fire triggers (only true transitions of existing flow)
        return true
    }

    fun deleteBool(context: Context, name: String) {
        val map = ensureCache(context)
        if (map.remove(name) != null) {
            persist(context, map)
        }
    }

    /** Reload cache from disk (e.g. after process restore). Does not notify. */
    fun reloadFromDisk(context: Context) {
        memoryCache = null
        ensureCache(context)
    }
}
