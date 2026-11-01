package com.romaster.launcher.data

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import java.io.InputStream

/**
 * Bundled static wallpapers under assets/wallpapers/.
 *
 * - Root image files appear as thumbnails.
 * - Subfolders (e.g. wallpapers/Nature/) appear as category folders;
 *   tapping a folder shows its images with a Back control.
 *
 * Prefer naming the first-install default: default.png / default.jpg / default.webp
 */
object BuiltInWallpapers {

    private const val ASSET_DIR = "wallpapers"
    private val IMAGE_EXT = setOf("png", "jpg", "jpeg", "webp", "bmp")

    data class Entry(
        /** Path relative to assets root, e.g. wallpapers/default.png or wallpapers/Nature/a.png */
        val assetPath: String,
        val fileName: String,
        val displayName: String
    )

    data class Folder(
        val name: String,
        /** e.g. wallpapers/Nature */
        val assetDir: String,
        /** Up to 4 image asset paths for collage preview */
        val previewPaths: List<String>
    )

    private fun isImageName(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in IMAGE_EXT
    }

    private fun isDirectory(context: Context, relativePath: String): Boolean {
        return try {
            val children = context.assets.list(relativePath)
            // AssetManager returns empty array for empty dirs, null if not a dir / missing
            children != null && !isImageName(relativePath.substringAfterLast('/'))
        } catch (_: Exception) {
            false
        }
    }

    /** Root-level image files only (not inside subfolders). */
    fun list(context: Context): List<Entry> = listInDir(context, ASSET_DIR)

    /** Image files directly inside [dir] (e.g. wallpapers or wallpapers/Nature). */
    fun listInDir(context: Context, dir: String): List<Entry> {
        val am = context.assets
        val names = try {
            am.list(dir)?.toList().orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
        return names
            .filter { isImageName(it) }
            .sortedWith(
                compareBy<String> { !it.lowercase().startsWith("default.") }
                    .thenBy { it.lowercase() }
            )
            .map { name ->
                Entry(
                    assetPath = "$dir/$name",
                    fileName = name,
                    displayName = name.substringBeforeLast('.')
                        .replace('_', ' ')
                        .replace('-', ' ')
                )
            }
    }

    /** Subfolders under assets/wallpapers/ with at least one image (recursive one level). */
    fun listFolders(context: Context): List<Folder> {
        val am = context.assets
        val names = try {
            am.list(ASSET_DIR)?.toList().orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
        return names
            .filter { !isImageName(it) && isDirectory(context, "$ASSET_DIR/$it") }
            .sortedBy { it.lowercase() }
            .mapNotNull { folderName ->
                val dir = "$ASSET_DIR/$folderName"
                val images = listInDir(context, dir)
                if (images.isEmpty()) return@mapNotNull null
                Folder(
                    name = folderName.replace('_', ' ').replace('-', ' '),
                    assetDir = dir,
                    previewPaths = images.take(4).map { it.assetPath }
                )
            }
    }

    fun defaultEntry(context: Context): Entry? {
        val all = list(context)
        return all.firstOrNull { it.fileName.lowercase().startsWith("default.") }
            ?: all.firstOrNull()
            ?: listFolders(context).firstOrNull()?.let { listInDir(context, it.assetDir).firstOrNull() }
    }

    fun openStream(context: Context, entry: Entry): InputStream? {
        return try {
            context.assets.open(entry.assetPath)
        } catch (_: Exception) {
            null
        }
    }

    fun openStream(context: Context, assetPath: String): InputStream? {
        return try {
            context.assets.open(assetPath)
        } catch (_: Exception) {
            null
        }
    }

    fun decodeSampled(context: Context, entry: Entry, maxSide: Int = 512): Bitmap? {
        return decodeSampledPath(context, entry.assetPath, maxSide)
    }

    fun decodeSampledPath(context: Context, assetPath: String, maxSide: Int = 512): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.assets.open(assetPath).use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (bounds.outWidth / sample > maxSide || bounds.outHeight / sample > maxSide) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample.coerceAtLeast(1) }
            context.assets.open(assetPath).use { BitmapFactory.decodeStream(it, null, opts) }
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeForScreen(context: Context, entry: Entry): Bitmap? {
        val dm = context.resources.displayMetrics
        val maxSide = maxOf(dm.widthPixels, dm.heightPixels) * 2
        return decodeSampled(context, entry, maxSide)
    }

    fun applyAsWallpaper(context: Context, entry: Entry): Boolean {
        try {
            LauncherPrefs(context).liveWallpaperAsset = entry.assetPath
        } catch (_: Exception) {
        }

        val wm = WallpaperManager.getInstance(context)
        val bmp = decodeForScreen(context, entry) ?: return false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    wm.setBitmap(bmp, null, true, WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK)
                } catch (_: Exception) {
                    wm.setBitmap(bmp, null, true, WallpaperManager.FLAG_SYSTEM)
                }
            } else {
                @Suppress("DEPRECATION")
                wm.setBitmap(bmp)
            }
            true
        } catch (_: Exception) {
            false
        } finally {
            // don't recycle — may still be referenced
        }
    }

    fun applyDefaultIfNeeded(context: Context) {
        val prefs = LauncherPrefs(context)
        if (prefs.defaultWallpaperApplied) return
        val entry = defaultEntry(context) ?: run {
            prefs.defaultWallpaperApplied = true
            return
        }
        try {
            applyAsWallpaper(context, entry)
        } catch (_: Exception) {
        }
        prefs.defaultWallpaperApplied = true
    }
}
