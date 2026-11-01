package com.romaster.launcher.ui

import android.app.Activity
import java.util.concurrent.Executors
import android.app.AlertDialog
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.service.wallpaper.WallpaperService
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.romaster.launcher.R
import com.romaster.launcher.data.BuiltInWallpapers
import com.romaster.launcher.data.LauncherPrefs
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.max

/**
 * Nova-style wallpaper picker.
 */
class WallpaperPickerActivity : Activity() {

    private lateinit var root: FrameLayout
    private lateinit var preview: ImageView
    private lateinit var guideLine: View
    private lateinit var topBar: LinearLayout
    private lateinit var bottomChrome: LinearLayout
    private lateinit var bottomContent: FrameLayout
    private var stripScroll: HorizontalScrollView? = null
    private var folderScroll: HorizontalScrollView? = null

    private var rootEntries: List<BuiltInWallpapers.Entry> = emptyList()
    private var folders: List<BuiltInWallpapers.Folder> = emptyList()
    private var folderEntries: List<BuiltInWallpapers.Entry> = emptyList()
    private var openFolder: BuiltInWallpapers.Folder? = null

    private var recentPaths: MutableList<String> = mutableListOf()

    /** 0=system wallpaper, 1=recent, 2=builtin/folder */
    private var selectedKind = 0
    private var selectedIndex = -1
    /** Stable ids so the checkmark does not jump across folders by index alone. */
    private var selectedRecentPath: String? = null
    private var selectedAssetPath: String? = null
    private var selectedBitmap: Bitmap? = null

    private var panX = 0f
    private var minPanX = 0f
    private var maxPanX = 0f
    private var scaledImgW = 0f
    private var scaledImgH = 0f
    private var scale = 1f
    private var scaleX = 1f
    private var scaleY = 1f
    /** 0f = left, 0.5f = center, 1f = right — independent of preview size */
    private var panFraction = 0.5f

    private var cropAlign = 1
    private var showCenterGuide = true
    private var stripMode = 0
    private var stripHidden = false
    private var savedStripScrollX = 0
    private var savedFolderScrollX = 0

    /** Multi-select recent images (long-press). */
    private var selectionMode = false
    private val selectedRecent = linkedSetOf<String>()

    private var dragStartX = 0f
    private var dragStartPan = 0f
    private var dragging = false

    private val pickGallery = 4401
    private val prefs by lazy { LauncherPrefs(this) }
    private val bgExecutor = Executors.newSingleThreadExecutor()
    @Volatile private var applyingWallpaper = false

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun thumbSize(): Int = dp(88)
    private val MATCH get() = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP get() = ViewGroup.LayoutParams.WRAP_CONTENT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = 0x66000000
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        rootEntries = BuiltInWallpapers.list(this)
        folders = BuiltInWallpapers.listFolders(this)
        recentPaths = prefs.recentWallpaperPaths.filter { File(it).isFile }.toMutableList()
        if (recentPaths != prefs.recentWallpaperPaths) {
            prefs.recentWallpaperPaths = recentPaths
        }

        root = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
        }

        preview = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
            scaleType = ImageView.ScaleType.MATRIX
            visibility = View.INVISIBLE
            isClickable = true
        }
        root.addView(preview)

        guideLine = object : View(this) {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xCCFFFFFF.toInt()
                strokeWidth = dp(2).toFloat()
                style = Paint.Style.STROKE
                pathEffect = DashPathEffect(floatArrayOf(dp(6).toFloat(), dp(6).toFloat()), 0f)
            }
            override fun onDraw(canvas: Canvas) {
                canvas.drawLine(width / 2f, 0f, width / 2f, height.toFloat(), paint)
            }
        }.apply {
            layoutParams = FrameLayout.LayoutParams(dp(4), MATCH, Gravity.CENTER_HORIZONTAL)
            visibility = View.VISIBLE
            isClickable = false
        }
        root.addView(guideLine)

        preview.setOnTouchListener { _, ev ->
            if (selectionMode) return@setOnTouchListener false
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragging = false
                    dragStartX = ev.rawX
                    dragStartPan = panX
                    hideStrip(true)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - dragStartX
                    if (!dragging && abs(dx) > dp(6)) dragging = true
                    if (dragging && selectedBitmap != null && cropAlign != 3 && maxPanX > minPanX) {
                        panX = (dragStartPan + dx).coerceIn(minPanX, maxPanX)
                        updatePanFractionFromPan()
                        applyMatrix()
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!dragging) hideStrip(!stripHidden) else hideStrip(false)
                    dragging = false
                    true
                }
                else -> false
            }
        }

        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(8), dp(12))
            setBackgroundColor(0x66000000)
            layoutParams = FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP)
        }
        root.addView(topBar)
        rebuildTopBar()

        bottomChrome = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM)
        }
        bottomContent = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, thumbSize() + dp(16))
            setBackgroundColor(0x55000000)
        }
        bottomChrome.addView(bottomContent)
        root.addView(bottomChrome)

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            topBar.setPadding(dp(12), bars.top + dp(6), dp(8), dp(10))
            bottomChrome.setPadding(0, 0, 0, bars.bottom)
            insets
        }

        setContentView(root)
        savedStripScrollX = 0
        savedFolderScrollX = 0
        rebuildBottomContent()
        selectedKind = 0
        selectedIndex = -1
        selectedRecentPath = null
        selectedAssetPath = null
        selectedBitmap = null
        preview.visibility = View.INVISIBLE
    }

    private fun rebuildTopBar() {
        topBar.removeAllViews()
        if (selectionMode) {
            topBar.setBackgroundColor(0xEE222222.toInt())
            topBar.addView(TextView(this).apply {
                text = "←"
                setTextColor(Color.WHITE)
                textSize = 22f
                setPadding(dp(4), 0, dp(12), 0)
                isClickable = true
                setOnClickListener { exitSelectionMode() }
            })
            topBar.addView(TextView(this).apply {
                text = "${selectedRecent.size} selected"
                setTextColor(Color.WHITE)
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            })
            // Nova order: Select all → Share → Delete
            fun barIcon(res: Int, onClick: () -> Unit) = ImageView(this).apply {
                setImageResource(res)
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply {
                    marginStart = dp(4)
                    marginEnd = dp(4)
                }
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(dp(8), dp(8), dp(8), dp(8))
                isClickable = true
                setOnClickListener { onClick() }
            }
            topBar.addView(barIcon(R.drawable.select_all_icon) {
                if (selectedRecent.size == recentPaths.size) {
                    selectedRecent.clear()
                } else {
                    selectedRecent.clear()
                    selectedRecent.addAll(recentPaths)
                }
                rebuildTopBar()
                rebuildBottomContent()
            })
            topBar.addView(barIcon(R.drawable.share_icon) {
                shareSelectedRecents()
            })
            topBar.addView(barIcon(R.drawable.bin_icon) {
                deleteSelectedRecents()
            })
        } else {
            topBar.setBackgroundColor(0x66000000)
            val applyBtn = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                setOnClickListener { applyCurrent() }
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            }
            applyBtn.addView(ImageView(this).apply {
                setImageResource(R.drawable.check_icon)
                layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(10) }
                scaleType = ImageView.ScaleType.FIT_CENTER
            })
            applyBtn.addView(TextView(this).apply {
                text = "Establecer como fondo de pantalla"
                setTextColor(Color.WHITE)
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
            })
            topBar.addView(applyBtn)
            topBar.addView(ImageView(this).apply {
                setImageResource(R.drawable.dashed_line_bt)
                layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply {
                    marginStart = dp(4); marginEnd = dp(4)
                }
                scaleType = ImageView.ScaleType.FIT_CENTER
                isClickable = true
                setOnClickListener {
                    showCenterGuide = !showCenterGuide
                    guideLine.visibility = if (showCenterGuide) View.VISIBLE else View.GONE
                    alpha = if (showCenterGuide) 1f else 0.45f
                }
            })
            topBar.addView(ImageView(this).apply {
                setImageResource(R.drawable.wall_pages_icon)
                layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply {
                    marginStart = dp(2); marginEnd = dp(4)
                }
                scaleType = ImageView.ScaleType.FIT_CENTER
                isClickable = true
                setOnClickListener { v -> showAlignMenu(v) }
            })
        }
    }

    private fun exitSelectionMode() {
        selectionMode = false
        selectedRecent.clear()
        rebuildTopBar()
        rebuildBottomContent()
    }

    private fun shareSelectedRecents() {
        if (selectedRecent.isEmpty()) {
            Toast.makeText(this, "Nada seleccionado", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val uris = ArrayList<Uri>()
            for (path in selectedRecent) {
                val f = File(path)
                if (!f.isFile) continue
                val uri = try {
                    androidx.core.content.FileProvider.getUriForFile(
                        this,
                        "$packageName.fileprovider",
                        f
                    )
                } catch (_: Exception) {
                    Uri.fromFile(f)
                }
                uris.add(uri)
            }
            if (uris.isEmpty()) {
                Toast.makeText(this, "No hay archivos para compartir", Toast.LENGTH_SHORT).show()
                return
            }
            val intent = if (uris.size == 1) {
                Intent(Intent.ACTION_SEND).apply {
                    type = "image/*"
                    putExtra(Intent.EXTRA_STREAM, uris[0])
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "image/*"
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
            startActivity(Intent.createChooser(intent, "Compartir fondo"))
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudo compartir: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun deleteSelectedRecents() {
        if (selectedRecent.isEmpty()) {
            Toast.makeText(this, "Nada seleccionado", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Eliminar imágenes")
            .setMessage("¿Quitar ${selectedRecent.size} imagen(es) de recientes?")
            .setPositiveButton("Eliminar") { _, _ ->
                val toRemove = selectedRecent.toList()
                for (path in toRemove) {
                    try { File(path).delete() } catch (_: Exception) {}
                    recentPaths.remove(path)
                }
                prefs.recentWallpaperPaths = recentPaths.toList()
                // If current selection was a deleted recent, clear overlay
                if (selectedKind == 1 && selectedIndex in recentPaths.indices) {
                    // ok
                } else if (selectedKind == 1) {
                    selectedKind = 0
                    selectedIndex = -1
                    selectedRecentPath = null
                    selectedAssetPath = null
                    selectedBitmap = null
                    preview.visibility = View.INVISIBLE
                }
                exitSelectionMode()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun hideStrip(hide: Boolean) {
        if (selectionMode) return
        if (stripHidden == hide) return
        stripHidden = hide
        val target = if (hide) 0f else 1f
        bottomChrome.animate()
            .alpha(target)
            .setDuration(180)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withStartAction { if (!hide) bottomChrome.visibility = View.VISIBLE }
            .withEndAction { if (hide) bottomChrome.visibility = View.INVISIBLE }
            .start()
    }

    private fun showAlignMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, 0, 0, "Left").setCheckable(true).isChecked = cropAlign == 0
            menu.add(0, 1, 1, "Center").setCheckable(true).isChecked = cropAlign == 1
            menu.add(0, 2, 2, "Right").setCheckable(true).isChecked = cropAlign == 2
            menu.add(0, 3, 3, "Stretch").setCheckable(true).isChecked = cropAlign == 3
            menu.setGroupCheckable(0, true, true)
            setOnMenuItemClickListener { item ->
                cropAlign = item.itemId
                panFraction = when (cropAlign) {
                    0 -> 0f
                    2 -> 1f
                    else -> 0.5f
                }
                snapPanToAlign()
                true
            }
            show()
        }
    }

    private fun updatePanFractionFromPan() {
        val range = maxPanX - minPanX
        panFraction = if (range < -0.5f || range > 0.5f) {
            // maxPanX=0, minPanX=negative → panX from 0 (left) to min (right)
            ((maxPanX - panX) / (maxPanX - minPanX)).coerceIn(0f, 1f)
        } else {
            0.5f
        }
        cropAlign = when {
            panFraction < 0.25f -> 0
            panFraction > 0.75f -> 2
            else -> 1
        }
    }

    private fun snapPanToAlign() {
        if (selectedBitmap == null) return
        recomputeScaleAndPan()
        if (cropAlign == 3) {
            panX = 0f
            panFraction = 0.5f
            applyMatrix()
            return
        }
        val vw = preview.width.toFloat().coerceAtLeast(1f)
        panX = when (cropAlign) {
            0 -> 0f
            2 -> vw - scaledImgW
            else -> (vw - scaledImgW) / 2f
        }.coerceIn(minPanX, maxPanX)
        panFraction = when (cropAlign) {
            0 -> 0f
            2 -> 1f
            else -> 0.5f
        }
        applyMatrix()
    }

    private fun recomputeScaleAndPan() {
        val bmp = selectedBitmap ?: return
        val vw = preview.width.toFloat().coerceAtLeast(1f)
        val vh = preview.height.toFloat().coerceAtLeast(1f)
        if (cropAlign == 3) {
            scaleX = vw / bmp.width.toFloat()
            scaleY = vh / bmp.height.toFloat()
            scale = scaleY
            scaledImgW = vw
            scaledImgH = vh
            minPanX = 0f
            maxPanX = 0f
            panX = 0f
            return
        }
        val sFill = vh / bmp.height.toFloat()
        scaleX = sFill
        scaleY = sFill
        scale = scaleY
        scaledImgW = bmp.width * scale
        scaledImgH = bmp.height * scale
        if (scaledImgW <= vw) {
            minPanX = (vw - scaledImgW) / 2f
            maxPanX = minPanX
            panX = minPanX
        } else {
            minPanX = vw - scaledImgW
            maxPanX = 0f
            panX = (maxPanX - panFraction * (maxPanX - minPanX)).coerceIn(minPanX, maxPanX)
        }
    }

    private fun applyMatrix() {
        val bmp = selectedBitmap ?: return
        val m = Matrix()
        m.setScale(scaleX, scaleY)
        m.postTranslate(if (cropAlign == 3) 0f else panX, 0f)
        preview.imageMatrix = m
        preview.setImageBitmap(bmp)
        preview.visibility = View.VISIBLE
    }

    private fun rememberScroll() {
        if (openFolder != null) {
            folderScroll?.let { savedFolderScrollX = it.scrollX }
        } else {
            stripScroll?.let { savedStripScrollX = it.scrollX }
        }
    }

    private fun restoreScroll() {
        if (openFolder != null) {
            val x = savedFolderScrollX
            folderScroll?.post { folderScroll?.scrollTo(x, 0) }
        } else {
            val x = savedStripScrollX
            stripScroll?.post { stripScroll?.scrollTo(x, 0) }
        }
    }

    private fun rebuildBottomContent() {
        rememberScroll()
        bottomContent.removeAllViews()
        when {
            stripMode == 1 -> bottomContent.addView(buildAppStrip(staticWallpaperApps(), false))
            stripMode == 2 -> bottomContent.addView(buildAppStrip(liveWallpaperApps(), true))
            openFolder != null -> bottomContent.addView(buildFolderStrip(openFolder!!))
            else -> bottomContent.addView(buildImageStrip())
        }
        if (stripMode == 0) restoreScroll()
    }

    private fun buildImageStrip(): View {
        val sz = thumbSize()
        val scroll = HorizontalScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
            isHorizontalScrollBarEnabled = false
            setPadding(0, dp(8), 0, dp(8))
        }
        stripScroll = scroll
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        row.addView(chipButton(R.drawable.wall_img_icon, "Elegir\nimagen", sz) {
            if (selectionMode) exitSelectionMode()
            try {
                startActivityForResult(
                    Intent.createChooser(
                        Intent(Intent.ACTION_GET_CONTENT).apply {
                            type = "image/*"
                            addCategory(Intent.CATEGORY_OPENABLE)
                        },
                        "Elegir imagen"
                    ),
                    pickGallery
                )
            } catch (_: Exception) {
                Toast.makeText(this@WallpaperPickerActivity, "No se pudo abrir galería", Toast.LENGTH_SHORT).show()
            }
        })

        // User recent first
        recentPaths.forEachIndexed { index, path ->
            val selected = selectedKind == 1 && path == selectedRecentPath
            val inMulti = selectionMode && path in selectedRecent
            row.addView(thumbView(decodeFileSampled(path, 256), selected || inMulti, sz,
                onClick = {
                    if (selectionMode) {
                        if (path in selectedRecent) selectedRecent.remove(path) else selectedRecent.add(path)
                        rebuildTopBar()
                        rebuildBottomContent()
                    } else {
                        selectRecent(index)
                    }
                },
                onLongClick = {
                    if (!selectionMode) {
                        selectionMode = true
                        selectedRecent.clear()
                        selectedRecent.add(path)
                        rebuildTopBar()
                        rebuildBottomContent()
                    }
                    true
                }
            ))
        }

        // Loose root images BEFORE folders
        rootEntries.forEachIndexed { index, entry ->
            val bmp = BuiltInWallpapers.decodeSampled(this, entry, 256)
            val selected = selectedKind == 2 && selectedAssetPath != null && entry.assetPath == selectedAssetPath
            row.addView(thumbView(bmp, selected, sz, onClick = {
                if (selectionMode) exitSelectionMode()
                selectBuiltin(entry, index)
            }))
        }

        // Folders after loose images
        folders.forEach { folder ->
            row.addView(folderThumb(folder, sz) {
                if (selectionMode) exitSelectionMode()
                rememberScroll()
                openFolder = folder
                folderEntries = BuiltInWallpapers.listInDir(this@WallpaperPickerActivity, folder.assetDir)
                savedFolderScrollX = 0
                rebuildBottomContent()
            })
        }

        row.addView(chipButton(R.drawable.themes_apps_wall_icon, "Apps\nestáticos", sz, 0xFFF5A623.toInt()) {
            if (selectionMode) exitSelectionMode()
            stripMode = 1
            rebuildBottomContent()
        })
        row.addView(chipButton(R.drawable.livewall_icon, "Live\nWallpapers", sz, 0xFF9C27B0.toInt()) {
            if (selectionMode) exitSelectionMode()
            stripMode = 2
            rebuildBottomContent()
        })

        scroll.addView(row)
        return scroll
    }

    private fun buildFolderStrip(folder: BuiltInWallpapers.Folder): View {
        val sz = thumbSize()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
            setPadding(dp(4), dp(8), 0, dp(8))
        }
        container.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(48), sz)
            isClickable = true
            setOnClickListener {
                rememberScroll()
                openFolder = null
                folderEntries = emptyList()
                rebuildBottomContent()
            }
            addView(TextView(this@WallpaperPickerActivity).apply {
                text = "←"
                setTextColor(Color.WHITE)
                textSize = 28f
                gravity = Gravity.CENTER
            })
        })
        val scroll = HorizontalScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, MATCH, 1f)
            isHorizontalScrollBarEnabled = false
        }
        folderScroll = scroll
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        folderEntries.forEachIndexed { index, entry ->
            val bmp = BuiltInWallpapers.decodeSampled(this, entry, 256)
            val selected = selectedKind == 2 && selectedAssetPath != null && entry.assetPath == selectedAssetPath
            row.addView(thumbView(bmp, selected, sz, onClick = {
                selectBuiltin(entry, index)
            }))
        }
        scroll.addView(row)
        container.addView(scroll)
        return container
    }

    private fun folderThumb(folder: BuiltInWallpapers.Folder, sz: Int, onClick: () -> Unit): View {
        return FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(sz, sz)
            setBackgroundColor(0xFF2A2A2A.toInt())
            clipToOutline = true
            isClickable = true
            setOnClickListener { onClick() }
            val paths = folder.previewPaths
            if (paths.size <= 1) {
                addView(ImageView(this@WallpaperPickerActivity).apply {
                    layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    if (paths.isNotEmpty()) {
                        BuiltInWallpapers.decodeSampledPath(
                            this@WallpaperPickerActivity, paths[0], 256
                        )?.let { setImageBitmap(it) }
                    }
                })
            } else {
                val grid = LinearLayout(this@WallpaperPickerActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
                }
                val half = sz / 2
                for (rowI in 0..1) {
                    val r = LinearLayout(this@WallpaperPickerActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        layoutParams = LinearLayout.LayoutParams(MATCH, half)
                    }
                    for (colI in 0..1) {
                        val idx = rowI * 2 + colI
                        r.addView(ImageView(this@WallpaperPickerActivity).apply {
                            layoutParams = LinearLayout.LayoutParams(half, half)
                            scaleType = ImageView.ScaleType.CENTER_CROP
                            if (idx < paths.size) {
                                BuiltInWallpapers.decodeSampledPath(
                                    this@WallpaperPickerActivity, paths[idx], 128
                                )?.let { setImageBitmap(it) }
                            } else setBackgroundColor(0xFF1A1A1A.toInt())
                        })
                    }
                    grid.addView(r)
                }
                addView(grid)
            }
            addView(TextView(this@WallpaperPickerActivity).apply {
                text = folder.name
                setTextColor(Color.WHITE)
                textSize = 10f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setBackgroundColor(0x88000000.toInt())
                layoutParams = FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM)
                setPadding(dp(2), dp(2), dp(2), dp(2))
                maxLines = 1
            })
        }
    }

    private fun chipButton(
        iconRes: Int,
        label: String,
        sz: Int,
        bgColor: Int? = null,
        onClick: () -> Unit
    ): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(sz, sz)
            setBackgroundColor(bgColor ?: 0x33000000)
            isClickable = true
            setOnClickListener { onClick() }
            addView(ImageView(this@WallpaperPickerActivity).apply {
                setImageResource(iconRes)
                layoutParams = LinearLayout.LayoutParams(dp(30), dp(30))
                scaleType = ImageView.ScaleType.FIT_CENTER
            })
            addView(TextView(this@WallpaperPickerActivity).apply {
                text = label
                setTextColor(Color.WHITE)
                textSize = 9f
                gravity = Gravity.CENTER
                setPadding(0, dp(2), 0, 0)
            })
        }
    }

    private fun thumbView(
        bmp: Bitmap?,
        selected: Boolean,
        sz: Int,
        onClick: () -> Unit,
        onLongClick: (() -> Boolean)? = null
    ): View {
        return FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(sz, sz)
            setBackgroundColor(0xFF222222.toInt())
            clipToOutline = true
            isClickable = true
            setOnClickListener { onClick() }
            if (onLongClick != null) {
                setOnLongClickListener { onLongClick() }
            }
            addView(ImageView(this@WallpaperPickerActivity).apply {
                layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
                scaleType = ImageView.ScaleType.CENTER_CROP
                if (bmp != null) setImageBitmap(bmp)
            })
            if (selected) {
                addView(ImageView(this@WallpaperPickerActivity).apply {
                    setImageResource(R.drawable.check_icon)
                    layoutParams = FrameLayout.LayoutParams(dp(36), dp(36), Gravity.CENTER)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                })
            }
        }
    }

    private fun buildAppStrip(apps: List<ResolveInfo>, isLive: Boolean): View {
        val sz = thumbSize()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
            setPadding(dp(4), dp(8), 0, dp(8))
        }
        container.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(48), sz)
            isClickable = true
            setOnClickListener {
                stripMode = 0
                rebuildBottomContent()
            }
            addView(TextView(this@WallpaperPickerActivity).apply {
                text = "←"
                setTextColor(Color.WHITE)
                textSize = 28f
                gravity = Gravity.CENTER
            })
        })
        val scroll = HorizontalScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, MATCH, 1f)
            isHorizontalScrollBarEnabled = false
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        if (apps.isEmpty()) {
            row.addView(TextView(this).apply {
                text = if (isLive) "  No hay Live Wallpapers instalados"
                else "  No hay otras apps de fondo estático"
                setTextColor(0xFFAAAAAA.toInt())
                textSize = 13f
                setPadding(dp(12), 0, 0, 0)
            })
        } else {
            val pm = packageManager
            for (ri in apps) {
                val label = try { ri.loadLabel(pm)?.toString() ?: "?" } catch (_: Exception) { "?" }
                val icon = try { ri.loadIcon(pm) } catch (_: Exception) { null }
                val cell = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(sz, sz)
                    isClickable = true
                    setOnClickListener { launchResolve(ri) }
                }
                cell.addView(ImageView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    if (icon != null) setImageDrawable(icon)
                })
                cell.addView(TextView(this).apply {
                    text = label
                    setTextColor(Color.WHITE)
                    textSize = 9f
                    gravity = Gravity.CENTER
                    maxLines = 2
                    setPadding(dp(2), dp(2), dp(2), 0)
                })
                row.addView(cell)
            }
        }
        scroll.addView(row)
        container.addView(scroll)
        return container
    }

    private fun staticWallpaperApps(): List<ResolveInfo> {
        val flags = if (Build.VERSION.SDK_INT >= 23) PackageManager.MATCH_ALL else 0
        val list = try {
            packageManager.queryIntentActivities(Intent(Intent.ACTION_SET_WALLPAPER), flags)
        } catch (_: Exception) {
            emptyList()
        }
        return list
            .filter { it.activityInfo?.packageName != null && it.activityInfo.packageName != packageName }
            .distinctBy { it.activityInfo.packageName + "/" + it.activityInfo.name }
            .sortedBy { it.loadLabel(packageManager).toString().lowercase() }
    }

    private fun liveWallpaperApps(): List<ResolveInfo> {
        val list = try {
            packageManager.queryIntentServices(
                Intent(WallpaperService.SERVICE_INTERFACE),
                PackageManager.GET_META_DATA
            )
        } catch (_: Exception) {
            emptyList()
        }
        return list
            .filter { it.serviceInfo?.packageName != null && it.serviceInfo.packageName != packageName }
            .distinctBy { it.serviceInfo.packageName + "/" + it.serviceInfo.name }
            .sortedBy { it.loadLabel(packageManager).toString().lowercase() }
    }

    private fun launchResolve(ri: ResolveInfo) {
        try {
            val pkg = ri.activityInfo?.packageName ?: ri.serviceInfo?.packageName ?: return
            val cls = ri.activityInfo?.name ?: ri.serviceInfo?.name ?: return
            if (ri.serviceInfo != null) {
                try {
                    startActivity(
                        Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
                            putExtra(
                                WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                                ComponentName(pkg, cls)
                            )
                        }
                    )
                    return
                } catch (_: Exception) {
                }
                try {
                    startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
                    return
                } catch (_: Exception) {
                }
            }
            startActivity(Intent(Intent.ACTION_SET_WALLPAPER).apply {
                component = ComponentName(pkg, cls)
            })
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudo abrir: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun selectRecent(index: Int) {
        if (index !in recentPaths.indices) return
        selectedKind = 1
        selectedIndex = index
        selectedRecentPath = recentPaths[index]
        selectedAssetPath = null
        loadBitmapFromFile(recentPaths[index])
        if (stripMode == 0) rebuildBottomContent()
    }

    private fun selectBuiltin(entry: BuiltInWallpapers.Entry, index: Int) {
        selectedKind = 2
        selectedIndex = index
        selectedAssetPath = entry.assetPath
        selectedRecentPath = null
        try {
            selectedBitmap?.recycle()
        } catch (_: Exception) {
        }
        selectedBitmap = decodeAssetFull(entry)
        try {
            prefs.liveWallpaperAsset = entry.assetPath
        } catch (_: Exception) {
        }
        if (cropAlign != 3) {
            panFraction = when (cropAlign) {
                0 -> 0f
                2 -> 1f
                else -> 0.5f
            }
        }
        preview.post {
            recomputeScaleAndPan()
            snapPanToAlign()
        }
        if (stripMode == 0) rebuildBottomContent()
    }

    private fun loadBitmapFromFile(path: String) {
        try {
            selectedBitmap?.recycle()
        } catch (_: Exception) {
        }
        selectedBitmap = decodeFileSampled(
            path,
            maxSide = maxOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels) * 2
        )
        if (cropAlign != 3) {
            panFraction = when (cropAlign) {
                0 -> 0f
                2 -> 1f
                else -> 0.5f
            }
        }
        preview.post {
            recomputeScaleAndPan()
            snapPanToAlign()
        }
    }

    private fun decodeAssetFull(entry: BuiltInWallpapers.Entry): Bitmap? {
        return try {
            val maxSide = maxOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels) * 2
            BuiltInWallpapers.decodeSampled(this, entry, maxSide)
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeFileSampled(path: String, maxSide: Int): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            var sample = 1
            while (bounds.outWidth / sample > maxSide || bounds.outHeight / sample > maxSide) sample *= 2
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample.coerceAtLeast(1)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeFile(path, opts)
        } catch (_: Exception) {
            null
        }
    }

    private fun applyCurrent() {
        val bmp = selectedBitmap
        if (bmp == null) {
            Toast.makeText(this, "Elegí una imagen primero", Toast.LENGTH_SHORT).show()
            return
        }
        if (applyingWallpaper) return
        val items = arrayOf(
            "Pantalla de inicio",
            "Pantalla de bloqueo",
            "Pantalla de inicio y de bloqueo"
        )
        AlertDialog.Builder(this)
            .setTitle("Establecer como fondo de pantalla")
            .setItems(items) { _, which ->
                if (applyingWallpaper) return@setItems
                val flags = when (which) {
                    0 -> WallpaperManager.FLAG_SYSTEM
                    1 -> WallpaperManager.FLAG_LOCK
                    else -> WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
                }
                applyingWallpaper = true
                Toast.makeText(this, "Aplicando fondo…", Toast.LENGTH_SHORT).show()
                // Heavy scale + WallpaperManager.setBitmap off the UI thread
                bgExecutor.execute {
                    val ok = try {
                        applyBitmapAsWallpaper(bmp, flags)
                    } catch (_: Exception) {
                        false
                    }
                    runOnUiThread {
                        applyingWallpaper = false
                        Toast.makeText(
                            this,
                            if (ok) "Fondo aplicado" else "No se pudo aplicar",
                            Toast.LENGTH_SHORT
                        ).show()
                        if (ok) {
                            savedStripScrollX = 0
                            savedFolderScrollX = 0
                            try {
                                preview.setImageBitmap(null)
                                selectedBitmap = null
                            } catch (_: Exception) {}
                            window.decorView.postDelayed({
                                try { System.gc() } catch (_: Exception) {}
                                finish()
                            }, 120L)
                        }
                    }
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /**
     * FILL-height using screen metrics (not preview view size) so apply matches what user saw.
     * Horizontal framing uses [panFraction] (0=left … 1=right), not raw preview pixels.
     * Scroll ON  → wide bitmap so system can pan with pages.
     * Scroll OFF → single screen crop at chosen fraction.
     */
    private fun applyBitmapAsWallpaper(src: Bitmap, flags: Int): Boolean {
        return try {
            val dm = resources.displayMetrics
            val screenW = dm.widthPixels
            val screenH = dm.heightPixels
            val wm = WallpaperManager.getInstance(this)

            val scrollOn = prefs.wallpaperScroll.equals("on", ignoreCase = true) ||
                prefs.wallpaperScroll.equals("true", ignoreCase = true) ||
                prefs.wallpaperScroll == "1"

            // FILL height against the real screen — same formula as preview
            val s = screenH.toFloat() / src.height.toFloat()
            val scaledW = src.width * s

            // Stretch: force exact screen size, ignore page-scroll surface
            if (cropAlign == 3) {
                try { wm.suggestDesiredDimensions(screenW, screenH) } catch (_: Exception) {}
                val out = Bitmap.createBitmap(screenW, screenH, Bitmap.Config.RGB_565)
                val m = Matrix()
                m.setScale(screenW.toFloat() / src.width.toFloat(), screenH.toFloat() / src.height.toFloat())
                Canvas(out).drawBitmap(src, m, null)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    wm.setBitmap(out, android.graphics.Rect(0, 0, out.width, out.height), true, flags)
                } else {
                    @Suppress("DEPRECATION")
                    wm.setBitmap(out)
                }
                try { if (!out.isRecycled) out.recycle() } catch (_: Exception) {}
                return true
            }

            /**
             * Android often keeps a wallpaper surface larger than the screen (for page
             * parallax). If we hand it a screen-sized bitmap while desired size is wider,
             * the system scales it up → the slight zoom you see in Nova and here.
             *
             * Scroll OFF: force desired size to the screen, bake a screen-sized bitmap
             * with the exact crop from [panFraction].
             * Scroll ON: bake a full-width FILL-height bitmap and set desired size to match.
             */
            val out: Bitmap
            if (scrollOn && scaledW > screenW + 1f) {
                val fullW = kotlin.math.round(scaledW).toInt().coerceAtLeast(screenW)
                out = Bitmap.createBitmap(fullW, screenH, Bitmap.Config.RGB_565)
                Canvas(out).drawBitmap(src, Matrix().apply { setScale(s, s) }, null)
                try {
                    wm.suggestDesiredDimensions(fullW, screenH)
                } catch (_: Exception) {
                }
            } else {
                try {
                    // Critical: tell the system the wallpaper is exactly one screen
                    wm.suggestDesiredDimensions(screenW, screenH)
                } catch (_: Exception) {
                }

                // If the OEM still reports a larger desired size, paint into that canvas
                // so the *centered* screen window matches our crop (true center, like Nova).
                var outW = screenW
                var outH = screenH
                try {
                    val dw = wm.desiredMinimumWidth
                    val dh = wm.desiredMinimumHeight
                    // Only expand if OEM insists; content still framed for the screen center
                    if (dw > screenW) outW = dw
                    if (dh > screenH) outH = dh
                } catch (_: Exception) {
                }

                out = Bitmap.createBitmap(outW, outH, Bitmap.Config.RGB_565)
                val c = Canvas(out)
                c.drawColor(Color.BLACK)

                // Screen-aligned crop rect inside the (possibly larger) wallpaper surface.
                // Non-scrolling wallpapers are shown from the center of the surface.
                val originX = ((outW - screenW) / 2f)
                val originY = ((outH - screenH) / 2f)

                val txScreen = if (scaledW <= screenW) {
                    (screenW - scaledW) / 2f
                } else {
                    -panFraction * (scaledW - screenW)
                }
                // Pixel-align to avoid half-pixel drift to one side
                val tx = kotlin.math.round(originX + txScreen).toFloat()
                val ty = kotlin.math.round(originY).toFloat()

                val m = Matrix()
                m.setScale(s, s)
                m.postTranslate(tx, ty)
                c.drawBitmap(src, m, null)

                try {
                    wm.suggestDesiredDimensions(outW, outH)
                } catch (_: Exception) {
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                // Explicit full-bitmap crop hint — discourages extra system zoom/crop
                val crop = android.graphics.Rect(0, 0, out.width, out.height)
                wm.setBitmap(out, crop, true, flags)
            } else {
                @Suppress("DEPRECATION")
                wm.setBitmap(out)
            }
            // Drop the temporary canvas ASAP so HomeActivity is less likely to be
            // killed for memory while we are in the background.
            if (out !== src && !out.isRecycled) {
                try { out.recycle() } catch (_: Exception) {}
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun persistPickedImage(uri: Uri): String? {
        return try {
            val dir = File(filesDir, "recent_wallpapers").apply { mkdirs() }
            val outFile = File(dir, "recent_${System.currentTimeMillis()}.jpg")
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(outFile).use { output -> input.copyTo(output) }
            } ?: return null
            val check = BitmapFactory.decodeFile(outFile.absolutePath)
            if (check == null) {
                outFile.delete()
                return null
            }
            check.recycle()
            prefs.addRecentWallpaperPath(outFile.absolutePath)
            recentPaths = prefs.recentWallpaperPaths.filter { File(it).isFile }.toMutableList()
            dir.listFiles()?.forEach { f ->
                if (f.absolutePath !in recentPaths) {
                    try { f.delete() } catch (_: Exception) {}
                }
            }
            outFile.absolutePath
        } catch (_: Exception) {
            null
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != pickGallery || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val path = persistPickedImage(uri)
        if (path == null) {
            Toast.makeText(this, "No se pudo cargar la imagen", Toast.LENGTH_SHORT).show()
            return
        }
        stripMode = 0
        openFolder = null
        selectRecent(0)
        rebuildBottomContent()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            selectionMode -> exitSelectionMode()
            openFolder != null -> {
                openFolder = null
                folderEntries = emptyList()
                rebuildBottomContent()
            }
            stripMode != 0 -> {
                stripMode = 0
                rebuildBottomContent()
            }
            else -> super.onBackPressed()
        }
    }
}
