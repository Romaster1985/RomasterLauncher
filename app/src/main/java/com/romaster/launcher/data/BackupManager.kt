package com.romaster.launcher.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Full launcher backup: settings + home layout + drawer groups.
 * Plain JSON when there are no GIF/image widget files.
 * ZIP (JSON + media/) when at least one GIF/image file exists.
 */
class BackupManager(
    private val context: Context,
    private val prefs: LauncherPrefs,
    private val homeRepo: HomeLayoutRepository,
    private val iconPackManager: IconPackManager
) {
    private val drawerGroupStore = DrawerGroupStore(context)

    data class MediaEntry(val zipPath: String, val file: File)

    fun hasMediaFiles(): Boolean = collectMedia().isNotEmpty()

    fun collectMedia(): List<MediaEntry> {
        val out = mutableListOf<MediaEntry>()
        for (item in homeRepo.loadItems()) {
            if (!item.isGifWidget) continue
            val path = item.gifPath
            if (path.isBlank()) continue
            val f = File(path)
            if (!f.isFile || f.length() <= 0L) continue
            val name = f.name.ifBlank { "${item.id}.img" }
            out.add(MediaEntry("media/$name", f))
        }
        return out
    }

    /**
     * Build backup JSON.
     * @param relativeMedia if true, gifPath becomes media/filename for ZIP packaging.
     */
    fun createBackupJson(relativeMedia: Boolean = false): String {
        val mediaByAbs = if (relativeMedia) {
            collectMedia().associate { it.file.absolutePath to it.zipPath }
        } else {
            emptyMap()
        }

        val root = JSONObject()
        root.put("version", 2)
        root.put(
            "exportedAt",
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())
        )
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
                put("isFolder", item.isFolder)
                put("folderName", item.folderName)
                put("isWidget", item.isWidget)
                put("appWidgetId", item.appWidgetId)
                put("isWidgetStack", item.isWidgetStack)
                put("stackSwipeVertical", item.stackSwipeVertical)
                put("stackCurrentPage", item.stackCurrentPage)
                put("isGifWidget", item.isGifWidget)
                val gp = item.gifPath
                put("gifPath", if (relativeMedia) (mediaByAbs[gp] ?: gp) else gp)
                put("gifAspectMode", item.gifAspectMode)
                put("zOrder", item.zOrder)
                put("freePlacement", item.freePlacement)
                put("freeX", item.freeX.toDouble())
                put("freeY", item.freeY.toDouble())
                put("freeW", item.freeW)
                put("freeH", item.freeH)
                put("reactionsJson", item.reactionsJson)
                put("animTx", item.animTx.toDouble())
                put("animTy", item.animTy.toDouble())
                put("animRot", item.animRot.toDouble())
                put("animScale", item.animScale.toDouble())
                put("animAlpha", item.animAlpha.toDouble())
                put("aboveIcons", item.aboveIcons)
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
            })
        }
        root.put("homeItems", arr)

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

    /**
     * Write backup to [os].
     * ZIP with romaster_backup.json + media when media exists; plain JSON otherwise.
     * @return true if a ZIP was written, false if plain JSON
     */
    fun writeBackup(os: OutputStream): Boolean {
        val media = collectMedia()
        if (media.isEmpty()) {
            os.write(createBackupJson(relativeMedia = false).toByteArray(Charsets.UTF_8))
            return false
        }
        val jsonBytes = createBackupJson(relativeMedia = true).toByteArray(Charsets.UTF_8)
        ZipOutputStream(BufferedOutputStream(os)).use { zos ->
            zos.putNextEntry(ZipEntry("romaster_backup.json"))
            zos.write(jsonBytes)
            zos.closeEntry()
            for (m in media) {
                zos.putNextEntry(ZipEntry(m.zipPath))
                FileInputStream(m.file).use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
        return true
    }

    fun writeBackupToFile(): File {
        val dir = File(context.getExternalFilesDir(null), "backups").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return if (hasMediaFiles()) {
            val f = File(dir, "romaster_backup_$stamp.zip")
            FileOutputStream(f).use { writeBackup(it) }
            f
        } else {
            val f = File(dir, "romaster_backup_$stamp.json")
            FileOutputStream(f).use { writeBackup(it) }
            f
        }
    }

    fun restoreFromJson(json: String) {
        restoreFromJsonObject(JSONObject(json), mediaFiles = emptyMap())
    }

    /**
     * Restore from a ZIP stream (romaster_backup.json + media) or a raw JSON stream.
     */
    fun restoreFromStream(input: InputStream) {
        val buf = BufferedInputStream(input)
        buf.mark(64 * 1024)
        val head = ByteArray(8)
        val n = buf.read(head)
        buf.reset()
        val isZip = n >= 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()
        if (!isZip) {
            val json = buf.bufferedReader(Charsets.UTF_8).readText()
            restoreFromJson(json)
            return
        }
        val mediaTmp = linkedMapOf<String, File>()
        var jsonText: String? = null
        val tmpDir = File(context.cacheDir, "backup_restore_${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            ZipInputStream(buf).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val name = entry.name.trimStart('/')
                    if (!entry.isDirectory) {
                        when {
                            name == "romaster_backup.json" ||
                                (name.endsWith(".json") && jsonText == null) -> {
                                val baos = ByteArrayOutputStream()
                                zis.copyTo(baos)
                                jsonText = baos.toString(Charsets.UTF_8.name())
                            }
                            name.startsWith("media/") -> {
                                val dest = File(tmpDir, name.substringAfterLast('/'))
                                FileOutputStream(dest).use { zis.copyTo(it) }
                                mediaTmp[name] = dest
                                mediaTmp[name.substringAfterLast('/')] = dest
                            }
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            val json = jsonText ?: throw IllegalArgumentException("ZIP sin romaster_backup.json")
            restoreFromJsonObject(JSONObject(json), mediaFiles = mediaTmp)
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    private fun restoreFromJsonObject(root: JSONObject, mediaFiles: Map<String, File>) {
        if (root.has("settings")) {
            prefs.importSettingsJson(root.getJSONObject("settings"))
        }
        if (root.has("iconPack")) {
            val pack = root.optString("iconPack", "")
            if (pack.isNotBlank()) {
                try {
                    iconPackManager.setIconPack(pack)
                } catch (_: Exception) {
                }
            }
        }
        if (root.has("homeItems")) {
            val arr = root.getJSONArray("homeItems")
            val list = mutableListOf<HomeItem>()
            val gifsDir = File(context.filesDir, "gifs").apply { if (!exists()) mkdirs() }
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val members = mutableListOf<FolderMember>()
                val mArr = obj.optJSONArray("folderMembers") ?: JSONArray()
                for (j in 0 until mArr.length()) {
                    val m = mArr.getJSONObject(j)
                    members.add(
                        FolderMember(
                            packageName = m.getString("packageName"),
                            activityName = m.getString("activityName"),
                            label = m.optString("label", "")
                        )
                    )
                }
                val stackMembers = mutableListOf<StackWidgetMember>()
                val sArr = obj.optJSONArray("stackMembers") ?: JSONArray()
                for (j in 0 until sArr.length()) {
                    val s = sArr.getJSONObject(j)
                    stackMembers.add(
                        StackWidgetMember(
                            packageName = s.getString("packageName"),
                            className = s.getString("className"),
                            appWidgetId = s.optInt("appWidgetId", -1)
                        )
                    )
                }
                val id = obj.optString("id")
                var gifPath = obj.optString("gifPath", "")
                if (obj.optBoolean("isGifWidget", false) && gifPath.isNotBlank()) {
                    val baseName = gifPath.substringAfterLast('/')
                    val key = when {
                        mediaFiles.containsKey(gifPath) -> gifPath
                        mediaFiles.containsKey("media/$baseName") -> "media/$baseName"
                        mediaFiles.containsKey(baseName) -> baseName
                        else -> null
                    }
                    if (key != null) {
                        val src = mediaFiles[key]
                        if (src != null && src.isFile) {
                            val ext = src.extension.ifBlank {
                                gifPath.substringAfterLast('.', "img")
                            }
                            val dest = File(gifsDir, "$id.$ext")
                            try {
                                src.copyTo(dest, overwrite = true)
                                gifPath = dest.absolutePath
                            } catch (_: Exception) {
                            }
                        }
                    } else if (gifPath.startsWith("media/")) {
                        gifPath = ""
                    } else if (!File(gifPath).isFile) {
                        val local = File(gifsDir, File(gifPath).name)
                        gifPath = if (local.isFile) local.absolutePath else ""
                    }
                }
                list.add(
                    HomeItem(
                        id = id,
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
                        gifPath = gifPath,
                        gifAspectMode = obj.optString("gifAspectMode", "fit"),
                        zOrder = obj.optInt("zOrder", 0),
                        freePlacement = obj.optBoolean("freePlacement", false),
                        freeX = obj.optDouble("freeX", 0.0).toFloat(),
                        freeY = obj.optDouble("freeY", 0.0).toFloat(),
                        freeW = obj.optInt("freeW", 0),
                        freeH = obj.optInt("freeH", 0),
                        reactionsJson = obj.optString("reactionsJson", ""),
                        animTx = obj.optDouble("animTx", 0.0).toFloat(),
                        animTy = obj.optDouble("animTy", 0.0).toFloat(),
                        animRot = obj.optDouble("animRot", 0.0).toFloat(),
                        animScale = obj.optDouble("animScale", 1.0).toFloat(),
                        animAlpha = obj.optDouble("animAlpha", 1.0).toFloat(),
                        aboveIcons = obj.optBoolean("aboveIcons", true)
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
        prefs.pendingHomeReload = true
    }
}
