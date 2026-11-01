package com.romaster.launcher.wallpaper

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import com.romaster.launcher.data.BuiltInWallpapers
import com.romaster.launcher.data.LauncherPrefs
import kotlin.math.max

/**
 * Live Wallpaper service that draws bundled images from assets/wallpapers/.
 * Appears in the system wallpaper / live wallpaper picker.
 * Selected image is stored in [LauncherPrefs.liveWallpaperAsset].
 */
class RomasterWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = RomasterEngine()

    private inner class RomasterEngine : Engine(), SharedPreferences.OnSharedPreferenceChangeListener {

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val matrix = Matrix()
        private var bitmap: Bitmap? = null
        private var offsetX = 0.5f
        private var offsetY = 0.5f
        private var prefs: SharedPreferences? = null

        override fun onCreate(surfaceHolder: SurfaceHolder?) {
            super.onCreate(surfaceHolder)
            prefs = applicationContext.getSharedPreferences("romaster_prefs", MODE_PRIVATE)
            prefs?.registerOnSharedPreferenceChangeListener(this)
            loadBitmap()
        }

        override fun onDestroy() {
            prefs?.unregisterOnSharedPreferenceChangeListener(this)
            bitmap?.recycle()
            bitmap = null
            super.onDestroy()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            if (visible) drawFrame()
        }

        override fun onSurfaceChanged(holder: SurfaceHolder?, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            drawFrame()
        }

        override fun onSurfaceRedrawNeeded(holder: SurfaceHolder?) {
            drawFrame()
        }

        override fun onOffsetsChanged(
            xOffset: Float,
            yOffset: Float,
            xOffsetStep: Float,
            yOffsetStep: Float,
            xPixelOffset: Int,
            yPixelOffset: Int
        ) {
            offsetX = xOffset
            offsetY = yOffset
            drawFrame()
        }

        override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
            if (key == "live_wallpaper_asset") {
                loadBitmap()
                drawFrame()
            }
        }

        private fun loadBitmap() {
            val old = bitmap
            bitmap = null
            try {
                val lp = LauncherPrefs(applicationContext)
                var path = lp.liveWallpaperAsset
                if (path.isBlank()) {
                    path = BuiltInWallpapers.defaultEntry(applicationContext)?.assetPath.orEmpty()
                }
                if (path.isBlank()) {
                    path = BuiltInWallpapers.list(applicationContext).firstOrNull()?.assetPath.orEmpty()
                }
                if (path.isNotBlank()) {
                    applicationContext.assets.open(path).use { input ->
                        val opts = BitmapFactory.Options().apply {
                            inPreferredConfig = Bitmap.Config.ARGB_8888
                        }
                        bitmap = BitmapFactory.decodeStream(input, null, opts)
                    }
                }
            } catch (_: Exception) {
                bitmap = null
            } finally {
                try {
                    old?.recycle()
                } catch (_: Exception) {
                }
            }
        }

        private fun drawFrame() {
            val holder = surfaceHolder ?: return
            var canvas: Canvas? = null
            try {
                canvas = holder.lockCanvas()
                if (canvas != null) {
                    draw(canvas)
                }
            } catch (_: Exception) {
            } finally {
                if (canvas != null) {
                    try {
                        holder.unlockCanvasAndPost(canvas)
                    } catch (_: Exception) {
                    }
                }
            }
        }

        private fun draw(canvas: Canvas) {
            val bmp = bitmap
            val cw = canvas.width.toFloat().coerceAtLeast(1f)
            val ch = canvas.height.toFloat().coerceAtLeast(1f)
            if (bmp == null || bmp.isRecycled) {
                canvas.drawColor(Color.rgb(30, 20, 50))
                return
            }
            val bw = bmp.width.toFloat().coerceAtLeast(1f)
            val bh = bmp.height.toFloat().coerceAtLeast(1f)
            val scale = max(cw / bw, ch / bh) * 1.08f
            val dw = bw * scale
            val dh = bh * scale
            val maxPanX = (dw - cw).coerceAtLeast(0f)
            val maxPanY = (dh - ch).coerceAtLeast(0f)
            val left = -maxPanX * offsetX
            val top = -maxPanY * offsetY
            matrix.reset()
            matrix.setScale(scale, scale)
            matrix.postTranslate(left, top)
            canvas.drawColor(Color.BLACK)
            canvas.drawBitmap(bmp, matrix, paint)
        }
    }
}
