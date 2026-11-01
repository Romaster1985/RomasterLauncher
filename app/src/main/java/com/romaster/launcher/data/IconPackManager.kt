package com.romaster.launcher.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.util.Log
import org.xmlpull.v1.XmlPullParser

/**
 * How the icon system works in Romaster Launcher:
 *
 * 1. Default icons:
 *    - Taken directly from PackageManager (ResolveInfo.loadIcon / ApplicationInfo.loadIcon).
 *
 * 2. Icon Packs (third-party):
 *    Most popular icon packs work by:
 *    a) Declaring an activity with a specific intent-filter so launchers can discover them.
 *       Common actions: "com.novalauncher.THEME", "org.adw.launcher.THEMES", etc.
 *    b) Providing an appfilter.xml that maps component names → drawable resource names.
 *    c) Providing the actual drawable resources.
 *
 * This manager discovers packs, loads the selected one, and resolves icons.
 */
class IconPackManager(private val context: Context) {

    private val prefs = context.getSharedPreferences("romaster_icons", Context.MODE_PRIVATE)
    private val pm = context.packageManager

    private var currentPackPackage: String? = null
    private var packResources: Resources? = null
    private val componentToDrawable = mutableMapOf<String, String>()

    companion object {
        private const val TAG = "IconPackManager"
        private const val PREF_CURRENT_PACK = "current_icon_pack"

        private val THEME_ACTIONS = listOf(
            "com.novalauncher.THEME",
            "org.adw.launcher.THEMES",
            "com.gau.go.launcherex.theme",
            "com.dlto.atom.launcher.THEME",
            "ginlemon.smartlauncher.THEMES"
        )
    }

    init {
        currentPackPackage = prefs.getString(PREF_CURRENT_PACK, null)
        if (currentPackPackage != null) {
            loadPack(currentPackPackage!!)
        }
    }

    fun getAvailableIconPacks(): List<IconPackInfo> {
        val packs = mutableListOf<IconPackInfo>()
        val seen = mutableSetOf<String>()

        for (action in THEME_ACTIONS) {
            val intent = Intent(action)
            val resolveInfos: List<ResolveInfo> = pm.queryIntentActivities(intent, PackageManager.GET_META_DATA)
            for (ri in resolveInfos) {
                val pkg = ri.activityInfo.packageName
                if (seen.add(pkg)) {
                    val label = ri.loadLabel(pm)?.toString() ?: pkg
                    val icon = ri.loadIcon(pm)
                    packs.add(IconPackInfo(pkg, label, icon))
                }
            }
        }
        return packs.sortedBy { it.name.lowercase() }
    }

    fun setIconPack(packageName: String?) {
        currentPackPackage = packageName
        prefs.edit().putString(PREF_CURRENT_PACK, packageName).apply()
        componentToDrawable.clear()
        packResources = null

        if (packageName != null) {
            loadPack(packageName)
        }
    }

    fun getCurrentPackPackage(): String? = currentPackPackage

    fun getIcon(packageName: String, activityName: String, defaultIcon: Drawable?): Drawable? {
        if (currentPackPackage == null || packResources == null) {
            return defaultIcon
        }

        val key = "$packageName/$activityName"
        val drawableName = componentToDrawable[key]
            ?: componentToDrawable[packageName]

        if (drawableName != null) {
            try {
                val resId = packResources!!.getIdentifier(drawableName, "drawable", currentPackPackage)
                if (resId != 0) {
                    return packResources!!.getDrawable(resId, null)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load drawable $drawableName from pack", e)
            }
        }
        return defaultIcon
    }

    private fun loadPack(packPackage: String) {
        try {
            packResources = pm.getResourcesForApplication(packPackage)
            val possibleFilters = listOf("appfilter", "app_filter", "theme_appfilter")
            for (name in possibleFilters) {
                val id = packResources!!.getIdentifier(name, "xml", packPackage)
                if (id != 0) {
                    parseAppFilter(packResources!!.getXml(id))
                    Log.i(TAG, "Loaded icon pack $packPackage with ${componentToDrawable.size} mappings")
                    return
                }
            }
            Log.w(TAG, "No appfilter.xml found in pack $packPackage")
        } catch (e: Exception) {
            Log.e(TAG, "Error loading icon pack $packPackage", e)
            currentPackPackage = null
            packResources = null
        }
    }

    private fun parseAppFilter(parser: XmlPullParser) {
        try {
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && parser.name == "item") {
                    val component = parser.getAttributeValue(null, "component")
                    val drawable = parser.getAttributeValue(null, "drawable")
                    if (component != null && drawable != null) {
                        val cleaned = component
                            .removePrefix("ComponentInfo{")
                            .removeSuffix("}")
                        componentToDrawable[cleaned] = drawable
                    }
                }
                event = parser.next()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing appfilter", e)
        }
    }
}
