package com.romaster.launcher.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.util.Log
import org.xmlpull.v1.XmlPullParser

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
            "ginlemon.smartlauncher.THEMES",
            "com.anddoes.launcher.THEME",
            "com.tsf.shell.themes"
        )
    }

    init {
        currentPackPackage = prefs.getString(PREF_CURRENT_PACK, null)
        if (!currentPackPackage.isNullOrBlank()) {
            loadPack(currentPackPackage!!)
        }
    }

    fun getAvailableIconPacks(): List<IconPackInfo> {
        val packs = mutableListOf<IconPackInfo>()
        val seen = mutableSetOf<String>()

        for (action in THEME_ACTIONS) {
            val intent = Intent(action)
            val resolveInfos: List<ResolveInfo> = try {
                pm.queryIntentActivities(intent, PackageManager.GET_META_DATA)
            } catch (e: Exception) {
                emptyList()
            }
            for (ri in resolveInfos) {
                val pkg = ri.activityInfo?.packageName ?: continue
                if (seen.add(pkg)) {
                    val label = ri.loadLabel(pm)?.toString() ?: pkg
                    val icon = try { ri.loadIcon(pm) } catch (e: Exception) { null }
                    packs.add(IconPackInfo(pkg, label, icon))
                }
            }
        }
        // Also try packages that declare appfilter in their resources (common pattern)
        return packs.sortedBy { it.name.lowercase() }
    }

    fun setIconPack(packageName: String?) {
        currentPackPackage = packageName
        prefs.edit().putString(PREF_CURRENT_PACK, packageName).apply()
        componentToDrawable.clear()
        packResources = null
        if (!packageName.isNullOrBlank()) {
            loadPack(packageName)
        }
        Log.i(TAG, "Icon pack set to: $packageName, mappings=${componentToDrawable.size}")
    }

    fun getCurrentPackPackage(): String? = currentPackPackage

    fun getIcon(packageName: String, activityName: String, defaultIcon: Drawable?): Drawable? {
        if (currentPackPackage.isNullOrBlank() || packResources == null) {
            return defaultIcon
        }

        val key = "$packageName/$activityName"
        val drawableName = componentToDrawable[key]
            ?: componentToDrawable["ComponentInfo{$packageName/$activityName}"]
            ?: componentToDrawable[packageName]

        if (drawableName != null) {
            try {
                val resId = packResources!!.getIdentifier(drawableName, "drawable", currentPackPackage)
                if (resId != 0) {
                    return packResources!!.getDrawable(resId, null)
                }
                // Some packs use drawable-nodpi or other
                val resId2 = packResources!!.getIdentifier(drawableName, "drawable-nodpi", currentPackPackage)
                if (resId2 != 0) {
                    return packResources!!.getDrawable(resId2, null)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load $drawableName from $currentPackPackage", e)
            }
        }
        return defaultIcon
    }

    private fun loadPack(packPackage: String) {
        try {
            packResources = pm.getResourcesForApplication(packPackage)
            val possibleFilters = listOf("appfilter", "app_filter", "theme_appfilter", "icon_pack")
            for (name in possibleFilters) {
                val id = packResources!!.getIdentifier(name, "xml", packPackage)
                if (id != 0) {
                    parseAppFilter(packResources!!.getXml(id))
                    Log.i(TAG, "Loaded icon pack $packPackage with ${componentToDrawable.size} mappings from $name")
                    return
                }
            }
            // Try assets
            try {
                val assetManager = packResources!!.assets
                val files = assetManager.list("") ?: emptyArray()
                for (f in files) {
                    if (f.contains("appfilter", ignoreCase = true) && f.endsWith(".xml")) {
                        assetManager.open(f).use { stream ->
                            val parser = android.util.Xml.newPullParser()
                            parser.setInput(stream, "UTF-8")
                            parseAppFilter(parser)
                        }
                        Log.i(TAG, "Loaded icon pack $packPackage from assets/$f with ${componentToDrawable.size} mappings")
                        return
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "No appfilter in assets of $packPackage")
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
                if (event == XmlPullParser.START_TAG) {
                    val tag = parser.name
                    if (tag == "item" || tag == "icon") {
                        val component = parser.getAttributeValue(null, "component")
                            ?: parser.getAttributeValue(null, "componentInfo")
                        val drawable = parser.getAttributeValue(null, "drawable")
                            ?: parser.getAttributeValue(null, "icon")
                        if (component != null && drawable != null) {
                            val cleaned = component
                                .removePrefix("ComponentInfo{")
                                .removeSuffix("}")
                                .trim()
                            componentToDrawable[cleaned] = drawable
                            // Also store by package only as fallback
                            val pkg = cleaned.substringBefore("/")
                            if (pkg.isNotBlank() && !componentToDrawable.containsKey(pkg)) {
                                componentToDrawable[pkg] = drawable
                            }
                        }
                    }
                }
                event = parser.next()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing appfilter", e)
        }
    }
}
