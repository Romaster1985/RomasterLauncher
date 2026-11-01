package com.romaster.launcher.ui

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.graphics.drawable.Drawable
import android.os.Build
import android.text.Editable
import android.text.TextWatcher
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.hypot

/**
 * Nova-style expandable widget browser grouped by app.
 * Supports tap-to-place and long-press-to-drag.
 */
class WidgetPickerSheet(
    private val context: Context,
    private val columns: Int,
    private val onPick: (AppWidgetProviderInfo, spanX: Int, spanY: Int) -> Unit,
    private val onLongPressDrag: ((AppWidgetProviderInfo, spanX: Int, spanY: Int, event: MotionEvent) -> Unit)? = null,
    private val showSearchBar: Boolean = true,
    /** Desktop cell size in px — must match HomeActivity grid for correct span labels. */
    private val cellWidthPx: Int = 0,
    private val cellHeightPx: Int = 0,
    private val maxRows: Int = 8
) {
    private data class Entry(
        val info: AppWidgetProviderInfo,
        val label: String,
        val preview: Drawable?,
        val spanX: Int,
        val spanY: Int
    )

    private data class Group(
        val packageName: String,
        val appLabel: String,
        val appIcon: Drawable?,
        val entries: List<Entry>
    )

    /** ScrollView of the list — used by HomeActivity to know if at top for swipe-down close. */
    var listScrollView: ScrollView? = null
        private set

    /** Set after attachTo — filter the list from an external search field. */
    var filterFunction: ((String) -> Unit)? = null
        private set

    fun attachTo(parent: LinearLayout) {
        val density = context.resources.displayMetrics.density
        val mgr = AppWidgetManager.getInstance(context)
        val pm = context.packageManager
        val dm = context.resources.displayMetrics
        val groups = buildGroups(mgr, pm, dm)

        parent.removeAllViews()
        parent.orientation = LinearLayout.VERTICAL

        val search = EditText(context).apply {
            hint = "Buscar Widgets"
            setHintTextColor(0xFF888888.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setPadding((16 * density).toInt(), (12 * density).toInt(), (16 * density).toInt(), (12 * density).toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 24f * density
                setColor(0xFF2C2C2E.toInt())
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.setMargins((16 * density).toInt(), (4 * density).toInt(), (16 * density).toInt(), (12 * density).toInt())
            layoutParams = lp
        }
        if (showSearchBar) {
            parent.addView(search)
        }

        val scroll = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        listScrollView = scroll
        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((12 * density).toInt(), 0, (12 * density).toInt(), (32 * density).toInt())
        }
        scroll.addView(list)
        parent.addView(scroll)

        fun rebuild(filter: String) {
            list.removeAllViews()
            val q = filter.trim().lowercase()
            for (g in groups) {
                val filtered = if (q.isEmpty()) g.entries else g.entries.filter {
                    it.label.lowercase().contains(q) || g.appLabel.lowercase().contains(q)
                }
                if (filtered.isEmpty()) continue

                var expanded = q.isNotEmpty()
                val header = makeHeader(g, filtered.size, density)
                val body = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    visibility = if (expanded) View.VISIBLE else View.GONE
                    setPadding((8 * density).toInt(), 0, (8 * density).toInt(), (12 * density).toInt())
                }
                header.setOnClickListener {
                    expanded = !expanded
                    body.visibility = if (expanded) View.VISIBLE else View.GONE
                    header.findViewWithTag<TextView>("arrow")?.text = if (expanded) "▲" else "▼"
                }
                var row: LinearLayout? = null
                filtered.forEachIndexed { index, e ->
                    if (index % 2 == 0) {
                        row = LinearLayout(context).apply {
                            orientation = LinearLayout.HORIZONTAL
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT
                            )
                        }
                        body.addView(row)
                    }
                    val cell = makeWidgetRow(e, density)
                    cell.layoutParams = LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                    )
                    row!!.addView(cell)
                }
                list.addView(header)
                list.addView(body)
            }
        }

        if (showSearchBar) {
            search.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) { rebuild(s?.toString().orEmpty()) }
            })
        }
        // External filter hook for drawer search bar
        filterFunction = { q -> rebuild(q) }
        rebuild("")
    }

    private fun makeHeader(g: Group, count: Int, density: Float): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((14 * density).toInt(), (12 * density).toInt(), (14 * density).toInt(), (12 * density).toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 14f * density
                setColor(0xFF2C2C2E.toInt())
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = (8 * density).toInt()
            layoutParams = lp

            val icon = ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams((36 * density).toInt(), (36 * density).toInt()).apply {
                    marginEnd = (12 * density).toInt()
                }
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageDrawable(g.appIcon)
            }
            addView(icon)

            val texts = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            texts.addView(TextView(context).apply {
                text = g.appLabel
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 15f
            })
            texts.addView(TextView(context).apply {
                text = if (count == 1) "1 widget" else "$count widgets"
                setTextColor(0xFFAAAAAA.toInt())
                textSize = 12f
            })
            addView(texts)

            addView(TextView(context).apply {
                tag = "arrow"
                text = "▼"
                setTextColor(0xFFAAAAAA.toInt())
                textSize = 14f
            })
        }
    }

    private fun makeWidgetRow(e: Entry, density: Float): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding((8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt())
            isClickable = true
            isFocusable = true
        }
        val preview = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (96 * density).toInt()
            )
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            setImageDrawable(e.preview)
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 12f * density
                setColor(0xFF3A3A3C.toInt())
            }
            setPadding((6 * density).toInt(), (6 * density).toInt(), (6 * density).toInt(), (6 * density).toInt())
        }
        root.addView(preview)
        root.addView(TextView(context).apply {
            text = e.label
            setTextColor(0xFFE0E0E0.toInt())
            textSize = 12f
            gravity = Gravity.CENTER
            maxLines = 2
            setPadding(0, (6 * density).toInt(), 0, 0)
        })
        root.addView(TextView(context).apply {
            text = "${e.spanX} × ${e.spanY}"
            setTextColor(0xFF888888.toInt())
            textSize = 11f
            gravity = Gravity.CENTER
        })

        root.setOnClickListener { onPick(e.info, e.spanX, e.spanY) }

        // Long-press → drag onto desktop
        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        val longTimeout = ViewConfiguration.getLongPressTimeout().toLong()
        var downX = 0f
        var downY = 0f
        var armed = false
        var started = false
        val arm = Runnable {
            armed = true
            try {
                root.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            } catch (_: Exception) {}
            root.animate().scaleX(1.05f).scaleY(1.05f).setDuration(80).start()
        }
        root.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX
                    downY = ev.rawY
                    armed = false
                    started = false
                    v.postDelayed(arm, longTimeout)
                    false // allow click
                }
                MotionEvent.ACTION_MOVE -> {
                    val dist = hypot((ev.rawX - downX).toDouble(), (ev.rawY - downY).toDouble()).toFloat()
                    if (!armed && dist > touchSlop) {
                        v.removeCallbacks(arm)
                    }
                    if (armed && !started && dist > touchSlop) {
                        started = true
                        v.removeCallbacks(arm)
                        onLongPressDrag?.invoke(e.info, e.spanX, e.spanY, ev)
                        return@setOnTouchListener true
                    }
                    started
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.removeCallbacks(arm)
                    v.animate().scaleX(1f).scaleY(1f).setDuration(80).start()
                    started
                }
                else -> false
            }
        }
        return root
    }

    private fun buildGroups(
        mgr: AppWidgetManager,
        pm: android.content.pm.PackageManager,
        dm: DisplayMetrics
    ): List<Group> {
        val providers = try { mgr.installedProviders } catch (_: Exception) { emptyList() }
        val byPkg = linkedMapOf<String, MutableList<Entry>>()
        val appLabels = hashMapOf<String, String>()
        val appIcons = hashMapOf<String, Drawable?>()
        for (info in providers) {
            val pkg = info.provider.packageName
            val label = try { info.loadLabel(pm).toString() } catch (_: Exception) { pkg }
            val preview = loadPreview(info, dm)
            val (sx, sy) = spansFor(info, dm)
            byPkg.getOrPut(pkg) { mutableListOf() }.add(Entry(info, label, preview, sx, sy))
            if (pkg !in appLabels) {
                try {
                    val ai = pm.getApplicationInfo(pkg, 0)
                    appLabels[pkg] = pm.getApplicationLabel(ai).toString()
                    appIcons[pkg] = pm.getApplicationIcon(ai)
                } catch (_: Exception) {
                    appLabels[pkg] = pkg
                    appIcons[pkg] = null
                }
            }
        }
        return byPkg.map { (pkg, entries) ->
            Group(pkg, appLabels[pkg] ?: pkg, appIcons[pkg], entries.sortedBy { it.label.lowercase() })
        }.sortedBy { it.appLabel.lowercase() }
    }

    private fun spansFor(info: AppWidgetProviderInfo, dm: DisplayMetrics): Pair<Int, Int> {
        val maxC = columns.coerceAtLeast(1)
        val maxR = maxRows.coerceAtLeast(1)
        val cw = when {
            cellWidthPx > 1 -> cellWidthPx
            else -> (dm.widthPixels / maxC).coerceAtLeast(1)
        }
        val ch = when {
            cellHeightPx > 1 -> cellHeightPx
            // Fallback: cells are taller than wide on phones; do NOT reuse width.
            else -> (cw * 1.3f).toInt().coerceAtLeast(1)
        }
        // Prefer provider-declared target cells (API 31+), then grow so the
        // reserved cells actually cover minWidth/minHeight. Google News Glance
        // often declares targetCell=1×1 while minWidth≈368px → stays on "Cargando…".
        var sx = 1
        var sy = 1
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            try {
                val tx = info.targetCellWidth
                val ty = info.targetCellHeight
                if (tx > 0 && ty > 0) {
                    sx = tx.coerceIn(1, maxC)
                    sy = ty.coerceIn(1, maxR)
                }
            } catch (_: Exception) {}
        }
        if (sx == 1 && sy == 1 && (info.minWidth > 0 || info.minHeight > 0)) {
            sx = kotlin.math.ceil(info.minWidth.toDouble() / cw).toInt().coerceIn(1, maxC)
            sy = kotlin.math.ceil(info.minHeight.toDouble() / ch).toInt().coerceIn(1, maxR)
        }
        // Always grow to satisfy min px (safe: only expands, never full-grid)
        while (sx < maxC && sx * cw < info.minWidth) sx++
        while (sy < maxR && sy * ch < info.minHeight) sy++
        return sx to sy
    }

    private fun loadPreview(info: AppWidgetProviderInfo, dm: DisplayMetrics): Drawable? {
        return try {
            @Suppress("DEPRECATION")
            val preview = info.loadPreviewImage(context, dm.densityDpi)
            preview ?: info.loadIcon(context, dm.densityDpi)
        } catch (_: Exception) {
            try { info.loadIcon(context, dm.densityDpi) } catch (_: Exception) { null }
        }
    }
}
