package com.romaster.launcher.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Point
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.doOnLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.snackbar.Snackbar
import com.romaster.launcher.R
import com.romaster.launcher.RomasterApp
import com.romaster.launcher.data.AppInfo
import com.romaster.launcher.data.HomeItem
import com.romaster.launcher.data.FolderMember
import com.romaster.launcher.data.LauncherPrefs
import com.romaster.launcher.data.DrawerGroupStore
import com.romaster.launcher.databinding.ActivityHomeBinding
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding
    private lateinit var prefs: LauncherPrefs
    private lateinit var drawerGroupStore: DrawerGroupStore
    private lateinit var drawerAdapter: AppIconAdapter

    private val appRepo by lazy { (application as RomasterApp).appRepository }
    private val homeRepo by lazy { (application as RomasterApp).homeLayoutRepository }

    private var allApps: List<AppInfo> = emptyList()
    private var homeItems: MutableList<HomeItem> = mutableListOf()
    private var dockItems: MutableList<HomeItem> = mutableListOf()
    private var dockPageIndex = 0
    private var dockSwipeActive = false
    private var dockTouchStartX = 0f
    private var dockTouchStartY = 0f
    private var pendingDockRestore: HomeItem? = null

    private var columns = 5
    private var rows = 6
    private var cellWidth = 0f
    private var cellHeight = 0f
    private var desktopIconPx = 0
    private var drawerIconPx = 0

    private var dragShadow: View? = null
    private var draggingApp: AppInfo? = null
    private var draggingHomeItem: HomeItem? = null
    private var isDrawerOpen = false
    private var currentPopup: PopupMenu? = null
    private var dropOverlay: GridDropOverlay? = null
    private var lastDropCell: Point? = null
    private var lastRemovedItem: HomeItem? = null

    // Drawer gesture with velocity
    private var drawerGestureActive = false
    private var drawerVelocityTracker: VelocityTracker? = null
    private var drawerTouchStartY = 0f
    private var drawerStartTy = 0f
    private var drawerOpeningFromClosed = false

    // Horizontal page swipe
    private var pageSwipeActive = false
    private var suppressIconClick = false
    private var touchStartedOnDock = false
    private var dockPageSwipeActive = false
    /** Dual-page transition layers (origin + destination coexist) */
    private var outgoingPageLayer: FrameLayout? = null
    private var incomingPageLayer: FrameLayout? = null
    private var dualTransitionDir = 0 // -1 next (finger left), +1 prev (finger right)
    private var dualTransitionActive = false
    private var pageTouchStartX = 0f
    private var pageTouchStartY = 0f
    private var pageStartTranslation = 0f
    private var edgePageFlipRunnable: Runnable? = null
    private var pendingEdgeDir: Int = 0 // -1 left, +1 right
    private var homeButtonView: View? = null
    private var overviewOverlay: FrameLayout? = null
    private var addPageButton: TextView? = null

    private var isOverviewMode = false
    private var currentPage = 0

    // Suppress empty long-press while gesturing
    private var suppressEmptyLongPress = false
    /** Blocks desktop/dock icon long-press & click while drawer gesture runs / settles */
    private var suppressIconInteractions = false
    /** True while an icon long-press menu is open or arming drag — blocks page swipes */
    private var iconLongPressActive = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Edge-to-edge: draw under status bar & gesture nav
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        ViewCompat.setOnApplyWindowInsetsListener(binding.homeRoot) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            // Search / settings sit below status bar inside the drawer
            binding.searchApps.setPadding(
                binding.searchApps.paddingLeft,
                0,
                binding.searchApps.paddingRight,
                binding.searchApps.paddingBottom
            )
            val searchLp = binding.searchApps.layoutParams
            if (searchLp is androidx.constraintlayout.widget.ConstraintLayout.LayoutParams) {
                searchLp.topMargin = bars.top + (12 * resources.displayMetrics.density).toInt()
                binding.searchApps.layoutParams = searchLp
            }
            // Bottom bar above gesture nav
            binding.bottomBar.setPadding(
                binding.bottomBar.paddingLeft,
                binding.bottomBar.paddingTop,
                binding.bottomBar.paddingRight,
                bars.bottom + (4 * resources.displayMetrics.density).toInt()
            )
            // Desktop can use full area; slight top inset so icons aren't under clock
            binding.desktopContainer.setPadding(
                binding.desktopContainer.paddingLeft,
                bars.top + (8 * resources.displayMetrics.density).toInt(),
                binding.desktopContainer.paddingRight,
                binding.desktopContainer.paddingBottom
            )
            insets
        }

        prefs = LauncherPrefs(this)
        drawerGroupStore = DrawerGroupStore(this)
        columns = prefs.desktopColumns
        rows = prefs.desktopRows
        currentPage = prefs.defaultPage.coerceAtMost((prefs.pageCount - 1).coerceAtLeast(0))

        setupDesktop()
        setupDrawer()
        setupGestures()
        loadData()
    }

    private fun setupDesktop() {
        applyDrawerButtonVisibility()
        binding.btnOpenDrawer.setOnClickListener {
            if (!isDrawerOpen) openDrawerAnimated()
        }
        // Long-press empty space → edit mode ONLY (never during page swipe / drawer / drag)
        binding.desktopContainer.isLongClickable = true
        binding.desktopContainer.setOnLongClickListener {
            // Absolute guards — swipe / drawer / drag must never open edit mode
            if (
                suppressEmptyLongPress || drawerGestureActive || pageSwipeActive ||
                dualTransitionActive || dockPageSwipeActive ||
                draggingApp != null || isDrawerOpen
            ) {
                return@setOnLongClickListener true
            }
            if (isOverviewMode) {
                showPageOptionsPopup(binding.desktopContainer)
                return@setOnLongClickListener true
            }
            enterOverviewMode()
            true
        }
    }

    private fun loadData() {
        lifecycleScope.launch {
            allApps = appRepo.loadApps(force = false)
            val allItems = homeRepo.loadItems()
            homeItems = allItems.filter { !it.isDock }.toMutableList()
            dockItems = allItems.filter { it.isDock }.toMutableList()
            pruneAllFolders()
            saveAllItems()
            binding.desktopContainer.doOnLayout {
                calculateMetrics()
                renderDesktop()
                renderDock()
            }
            submitDrawerApps(allApps)
        }
    }

    /** Never share drawable bounds across ImageViews / folder previews */
    private fun safeIcon(src: android.graphics.drawable.Drawable?): android.graphics.drawable.Drawable? {
        if (src == null) return null
        return try {
            (src.constantState?.newDrawable(resources) ?: src.mutate()).mutate()
        } catch (_: Exception) {
            try { src.mutate() } catch (_: Exception) { src }
        }
    }

    private fun calculateMetrics() {
        val container = binding.desktopContainer
        // When drawer button is hidden and dock off, reclaim bottom space
        val density = resources.displayMetrics.density
        // Dock lives in bottomBar OUTSIDE desktopContainer — only gap padding needed
        var bottomPad = when {
            prefs.showDock || prefs.showDrawerButton ->
                (prefs.desktopDockGapDp * density).toInt().coerceAtLeast((2 * density).toInt())
            else -> (8 * density).toInt()
        }
        // In overview, leave room for wallpaper/settings chrome under the glass frame
        if (isOverviewMode) {
            bottomPad += (88 * density).toInt()
        }
        if (container.paddingBottom != bottomPad) {
            container.setPadding(container.paddingLeft, container.paddingTop, container.paddingRight, bottomPad)
        }
        val w = (container.width - container.paddingLeft - container.paddingRight).toFloat()
        val h = (container.height - container.paddingTop - container.paddingBottom).toFloat()
        if (w <= 0 || h <= 0) return
        columns = prefs.desktopColumns
        rows = prefs.desktopRows
        cellWidth = w / columns
        cellHeight = h / rows

        val baseIcon = 56 * resources.displayMetrics.density
        desktopIconPx = (baseIcon * prefs.desktopIconSizePercent / 100f).roundToInt()
        drawerIconPx = (baseIcon * prefs.drawerIconSizePercent / 100f).roundToInt()
    }

    private fun renderDesktop() {
        val container = binding.desktopContainer
        if (cellWidth <= 1f || cellHeight <= 1f || container.width > 0) {
            calculateMetrics()
        }
        val wasDragging = dropOverlay != null && draggingApp != null
        container.removeAllViews()
        dropOverlay = null
        if (!(isOverviewMode && currentPage >= prefs.pageCount)) {
            container.alpha = 1f
        }

        // Frame inside the page → zooms & slides with icons
        if (isOverviewMode && currentPage < prefs.pageCount) {
            container.addView(
                createPageFrame(),
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }

        val pageItems = homeItems.filter { it.screen == currentPage && !it.isDock }
        for (item in pageItems) {
            if (draggingHomeItem?.id == item.id) continue
            if (item.isFolder) {
                val iconView = createFolderIconView(item)
                positionIcon(iconView, item.cellX, item.cellY)
                container.addView(iconView)
            } else {
                val app = allApps.find {
                    it.packageName == item.packageName && it.activityName == item.activityName
                } ?: continue
                val iconView = createDesktopIconView(app, item)
                positionIcon(iconView, item.cellX, item.cellY)
                container.addView(iconView)
            }
        }

        if (wasDragging) {
            ensureDropOverlay()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createDesktopIconView(app: AppInfo, item: HomeItem): View {
        val density = resources.displayMetrics.density
        val iconSize = if (desktopIconPx > 0) desktopIconPx else (56 * density).toInt()
        val labelHeight = (28 * density).toInt()
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            tag = item.id
            isClickable = true
            isFocusable = true
        }

        val image = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageDrawable(safeIcon(app.icon))
            elevation = 2 * density
        }
        val label = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, labelHeight)
            text = app.label
            textSize = 11f
            setTextColor(resources.getColor(R.color.romaster_text_primary, theme))
            gravity = Gravity.CENTER
            maxLines = 2
            setShadowLayer(2f, 0f, 1f, 0x80000000.toInt())
        }
        root.addView(image)
        root.addView(label)

        var downX = 0f
        var downY = 0f
        var moved = false
        var longPressArmed = false
        var dragStarted = false

        val longPressRunnable = Runnable {
            if (!moved && draggingApp == null
                && !isDrawerOpen && !drawerGestureActive
                && !suppressIconInteractions && !suppressEmptyLongPress
            ) {
                longPressArmed = true
                iconLongPressActive = true
                elevateView(root)
                root.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                showDesktopAppOptions(app, item, root)
            }
        }

        root.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (suppressIconInteractions || isDrawerOpen || drawerGestureActive) {
                        return@setOnTouchListener false
                    }
                    downX = event.rawX
                    downY = event.rawY
                    moved = false
                    longPressArmed = false
                    dragStarted = false
                    view.postDelayed(longPressRunnable, longPressTimeout)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dist = hypot((event.rawX - downX).toDouble(), (event.rawY - downY).toDouble()).toFloat()
                    if (dist > touchSlop) {
                        moved = true
                        if (!longPressArmed) {
                            view.removeCallbacks(longPressRunnable)
                        } else if (!dragStarted && draggingApp == null) {
                            dragStarted = true
                            dismissCurrentPopup()
                            startDesktopDrag(app, item, view, event)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.removeCallbacks(longPressRunnable)
                    when {
                        draggingHomeItem?.id == item.id -> { /* endDrag via dispatch */ }
                        longPressArmed && !dragStarted -> {
                            view.animate().scaleX(1.06f).scaleY(1.06f).translationZ(10f).setDuration(90).start()
                        }
                        !moved && !longPressArmed -> {
                            resetElevation(view)
                            if (isOverviewMode) {
                                exitOverviewMode()
                            } else if (!suppressIconClick && !pageSwipeActive) {
                                launchApp(app)
                            }
                        }
                        else -> resetElevation(view)
                    }
                    true
                }
                else -> false
            }
        }
        return root
    }

    private fun elevateView(v: View) {
        v.animate().scaleX(1.14f).scaleY(1.14f).translationZ(22f)
            .setDuration(140).setInterpolator(OvershootInterpolator(1.6f)).start()
    }

    private fun resetElevation(v: View) {
        v.animate().scaleX(1f).scaleY(1f).translationZ(0f).setDuration(120).start()
    }

    /** Center icon inside its grid cell (same math as overlay) */
    private fun positionIcon(view: View, cellX: Int, cellY: Int) {
        val maxW = cellWidth.toInt().coerceAtLeast(1)
        val maxH = cellHeight.toInt().coerceAtLeast(1)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(maxW, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST)
        )
        val vw = view.measuredWidth.coerceAtLeast(1)
        val vh = view.measuredHeight.coerceAtLeast(1)
        view.layoutParams = FrameLayout.LayoutParams(vw, vh)
        view.x = cellX * cellWidth + (cellWidth - vw) / 2f
        view.y = cellY * cellHeight + (cellHeight - vh) / 2f
    }

    // ───────────────────────────── Drawer UI ─────────────────────────────

    private fun setupDrawer() {
        val density = resources.displayMetrics.density
        val size = if (drawerIconPx > 0) drawerIconPx else (52 * density).toInt()
        drawerAdapter = AppIconAdapter(
            onClick = { app ->
                launchApp(app)
                closeDrawerAnimated()
            },
            onLongPressArmed = { app, view -> showDrawerAppOptions(app, view) },
            onStartDrag = { app, view ->
                dismissCurrentPopup()
                startDrawerDrag(app, view)
            },
            onCancelElevate = { v ->
                v.animate().scaleX(1f).scaleY(1f).translationZ(0f).setDuration(100).start()
            },
            onHideApp = { app ->
                prefs.setHidden(app.packageName, true)
                submitDrawerApps(allApps)
                Toast.makeText(this, "Oculta: ${app.label}", Toast.LENGTH_SHORT).show()
            },
            onGroupClick = { groupId -> openDrawerGroup(groupId) },
            iconSizePx = size,
            columns = 4
        )
        val glm = GridLayoutManager(this, 4)
        glm.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                return if (drawerAdapter.getItemViewType(position) == 1) 4 else 1
            }
        }
        binding.appsGrid.layoutManager = glm
        binding.appsGrid.adapter = drawerAdapter
        binding.appsGrid.isVerticalScrollBarEnabled = false
        binding.appsGrid.overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        setupDrawerScrollIndicator()

        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.searchApps.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString().orEmpty()
                if (q.isBlank()) submitDrawerApps(allApps)
                else {
                    val hidden = prefs.getHiddenPackages()
                    val filtered = allApps.filter {
                        it.packageName !in hidden &&
                            (it.label.contains(q, true) || it.packageName.contains(q, true))
                    }
                    drawerAdapter.submitApps(filtered, alphabetical = prefs.drawerAlphabetical)
                }
            }
        })
    }

    /** Panel height in px; falls back to root height if not laid out yet */
    private fun cancelPendingIconLongPresses() {
        suppressIconInteractions = true
        iconLongPressActive = false
        dismissCurrentPopup()
        // Long-press runnables check suppressIconInteractions / drawerGestureActive before firing
    }

    private fun releaseIconInteractionBlock(delayMs: Long = 0L) {
        val run = Runnable {
            suppressIconInteractions = false
            iconLongPressActive = false
            suppressEmptyLongPress = false
            drawerGestureActive = false
        }
        if (delayMs <= 0L) {
            binding.homeRoot.post(run)
        } else {
            binding.homeRoot.postDelayed(run, delayMs)
        }
    }

    private fun applyWallpaperOffset() {
        try {
            val mode = prefs.wallpaperScroll
            if (mode == "off") return
            val pages = prefs.pageCount.coerceAtLeast(1)
            val steps = if (pages <= 1) 0f else 1f / (pages - 1).toFloat()
            val wm = android.app.WallpaperManager.getInstance(this)
            wm.setWallpaperOffsetSteps(steps, 0f)
            val raw = if (pages <= 1) 0.5f else currentPage.toFloat() / (pages - 1).toFloat()
            val x = when (mode) {
                "inverse" -> 1f - raw
                else -> raw // "on"
            }.coerceIn(0f, 1f)
            val token = window.decorView.windowToken ?: return
            wm.setWallpaperOffsets(token, x, 0.5f)
        } catch (_: Exception) {}
    }

    private fun drawerHeight(): Float {
        val h = binding.drawerPanel.height.toFloat()
        if (h > 1f) return h
        return binding.homeRoot.height.toFloat().coerceAtLeast(1f)
    }

    /** Open amount 0..1 from translationY (0 = fully open) */
    private fun drawerOpenAmount(ty: Float = binding.drawerPanel.translationY): Float {
        val h = drawerHeight()
        return (1f - (ty / h)).coerceIn(0f, 1f)
    }

    private fun applyDrawerOpenVisual(ty: Float) {
        binding.drawerPanel.translationY = ty
        binding.drawerPanel.alpha = drawerOpenAmount(ty)
    }

    private fun openDrawerAnimated() {
        val density = resources.displayMetrics.density
        val size = if (drawerIconPx > 0) drawerIconPx else (52 * density).toInt()
        if (::drawerAdapter.isInitialized) {
            drawerAdapter.updateIconSize(size)
        }
        if (isDrawerOpen && binding.drawerPanel.translationY == 0f && binding.drawerPanel.alpha >= 0.99f) return
        isDrawerOpen = true
        suppressEmptyLongPress = true
        binding.drawerPanel.visibility = View.VISIBLE
        val h = drawerHeight()
        if (binding.drawerPanel.translationY <= 0f || binding.drawerPanel.translationY >= h) {
            applyDrawerOpenVisual(h) // start closed: ty=h, alpha=0
        } else {
            applyDrawerOpenVisual(binding.drawerPanel.translationY)
        }
        binding.btnOpenDrawer.visibility = View.INVISIBLE
        binding.drawerPanel.animate().cancel()
        binding.drawerPanel.animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(300)
            .setInterpolator(DecelerateInterpolator(1.5f))
            .withEndAction {
                applyDrawerOpenVisual(0f)
                drawerGestureActive = false
                releaseIconInteractionBlock(0)
                updateDrawerScrollThumb()
                binding.appsGrid.post { updateDrawerScrollThumb() }
            }
            .start()
        binding.appsGrid.post { updateDrawerScrollThumb() }
    }

    private fun closeDrawerAnimated() {
        isDrawerOpen = false
        suppressEmptyLongPress = true
        val h = drawerHeight()
        binding.drawerPanel.animate().cancel()
        binding.drawerPanel.animate()
            .translationY(h)
            .alpha(0f)
            .setDuration(260)
            .setInterpolator(DecelerateInterpolator(1.4f))
            .withEndAction {
                binding.drawerPanel.visibility = View.GONE
                applyDrawerOpenVisual(h)
                applyDrawerButtonVisibility()
                drawerGestureActive = false
                releaseIconInteractionBlock(0)
            }
            .start()
    }

    private fun flingDrawer(velocityY: Float) {
        val h = drawerHeight()
        val ty = binding.drawerPanel.translationY
        // velocityY > 0 = finger moving down = closing
        val openThreshold = h * 0.35f
        val minFling = 1200f

        val shouldOpen = when {
            abs(velocityY) > minFling -> velocityY < 0 // fling up → open
            else -> ty < openThreshold
        }

        if (shouldOpen) {
            isDrawerOpen = true
            binding.drawerPanel.animate().cancel()
            val distance = ty
            val duration = (220 + (distance / h) * 120).toLong().coerceIn(160, 320)
            binding.drawerPanel.animate()
                .translationY(0f)
                .alpha(1f)
                .setDuration(duration)
                .setInterpolator(DecelerateInterpolator(1.6f))
                .withEndAction {
                    applyDrawerOpenVisual(0f)
                    drawerGestureActive = false
                    releaseIconInteractionBlock(0)
                }
                .start()
            binding.btnOpenDrawer.visibility = View.INVISIBLE
        } else {
            isDrawerOpen = false
            binding.drawerPanel.animate().cancel()
            val distance = h - ty
            val duration = (220 + (distance / h) * 120).toLong().coerceIn(160, 320)
            binding.drawerPanel.animate()
                .translationY(h)
                .alpha(0f)
                .setDuration(duration)
                .setInterpolator(DecelerateInterpolator(1.5f))
                .withEndAction {
                    binding.drawerPanel.visibility = View.GONE
                    applyDrawerOpenVisual(h)
                    applyDrawerButtonVisibility()
                    drawerGestureActive = false
                    releaseIconInteractionBlock(0)
                }
                .start()
        }
    }

    private fun bounceDrawerBack() {
        val h = drawerHeight()
        binding.drawerPanel.animate().cancel()
        if (isDrawerOpen || drawerOpeningFromClosed && binding.drawerPanel.translationY < h * 0.5f) {
            binding.drawerPanel.animate()
                .translationY(0f)
                .alpha(1f)
                .setDuration(180)
                .setInterpolator(OvershootInterpolator(0.9f))
                .withEndAction {
                    isDrawerOpen = true
                    applyDrawerOpenVisual(0f)
                    drawerGestureActive = false
                    releaseIconInteractionBlock(0)
                }
                .start()
        } else {
            binding.drawerPanel.animate()
                .translationY(h)
                .alpha(0f)
                .setDuration(180)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction {
                    binding.drawerPanel.visibility = View.GONE
                    applyDrawerOpenVisual(h)
                    applyDrawerButtonVisibility()
                    isDrawerOpen = false
                    drawerGestureActive = false
                    releaseIconInteractionBlock(0)
                }
                .start()
        }
    }

    // ───────────────────────────── Gestures ─────────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestures() {
        // Gestures handled mainly in dispatchTouchEvent for reliability
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // ── Drag shadow tracking ──
        if (draggingApp != null && dragShadow != null) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    moveDragShadow(ev.rawX, ev.rawY)
                    updateDropHighlight(ev.rawX, ev.rawY)
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    endDrag(ev.rawX, ev.rawY)
                    return true
                }
            }
        }

        // ── Icon context menu open: block page/drawer/dock swipes ──
        if (currentPopup != null && draggingApp == null) {
            // Still deliver to popup / children; do not start desktop gestures
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    // Allow move-to-drag on the armed icon; handled by icon listeners
                }
            }
        }

        // ── Folder open: never steal gestures for desktop/drawer/notifications ──
        if (folderOverlay != null && draggingApp == null) {
            return super.dispatchTouchEvent(ev)
        }

        // ── Gestures (drawer / desktop pages / dock pages) ──
        if (draggingApp == null) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    drawerTouchStartY = ev.rawY
                    pageTouchStartX = ev.rawX
                    pageTouchStartY = ev.rawY
                    drawerGestureActive = false
                    pageSwipeActive = false
                    dockPageSwipeActive = false
                    touchStartedOnDock = isTouchOnDock(ev.rawX, ev.rawY)
                    if (touchStartedOnDock) {
                        // Never let desktop page swipe or empty long-press start
                        suppressEmptyLongPress = true
                        binding.desktopContainer.cancelLongPress()
                    }
                    drawerVelocityTracker?.recycle()
                    drawerVelocityTracker = VelocityTracker.obtain()
                    drawerVelocityTracker?.addMovement(ev)
                }
                MotionEvent.ACTION_MOVE -> {
                    drawerVelocityTracker?.addMovement(ev)
                    val dy = ev.rawY - drawerTouchStartY
                    val dx = ev.rawX - pageTouchStartX
                    val touchSlop = ViewConfiguration.get(this).scaledTouchSlop

                    // ── DOCK page swipe (only if touch began on dock) ──
                    // Never steal when icon menu/long-press is active — that would race with drag
                    if (touchStartedOnDock && prefs.showDock && !isDrawerOpen
                        && currentPopup == null && !iconLongPressActive && draggingApp == null
                    ) {
                        if (!dockPageSwipeActive && abs(dx) > touchSlop && abs(dx) > abs(dy)) {
                            dockPageSwipeActive = true
                            suppressEmptyLongPress = true
                            binding.desktopContainer.cancelLongPress()
                            // Cancel any dock-icon long-press callbacks
                            binding.dockPager.isPressed = false
                            for (i in 0 until binding.dockPager.childCount) {
                                val page = binding.dockPager.getChildAt(i)
                                page.isPressed = false
                                if (page is android.view.ViewGroup) {
                                    for (j in 0 until page.childCount) {
                                        page.getChildAt(j).isPressed = false
                                        page.getChildAt(j).cancelLongPress()
                                        page.getChildAt(j).animate().cancel()
                                        page.getChildAt(j).scaleX = 1f
                                        page.getChildAt(j).scaleY = 1f
                                        page.getChildAt(j).translationZ = 0f
                                    }
                                }
                            }
                        }
                        if (dockPageSwipeActive) {
                            applyDockSwipeVisual(dx)
                            return true // consume — never reaches desktop page swipe
                        }
                        // Still on dock, not horizontal enough: consume vertical too so desktop doesn't move
                        if (abs(dy) > touchSlop) return true
                        return super.dispatchTouchEvent(ev)
                    }
                    // Long-press / menu on dock icon: do not intercept — let icon listener drive drag
                    if (touchStartedOnDock && (currentPopup != null || iconLongPressActive) && draggingApp == null) {
                        return super.dispatchTouchEvent(ev)
                    }

                    // ── DESKTOP page swipe (never if touch began on dock) ──
                    if (currentPopup != null) {
                        // keep popup interaction only
                    } else if (!touchStartedOnDock && !isDrawerOpen && !drawerGestureActive && !pageSwipeActive &&
                        abs(dx) > touchSlop * 2 && abs(dx) > abs(dy) * 1.2f
                    ) {
                        pageSwipeActive = true
                        suppressEmptyLongPress = true
                        binding.desktopContainer.cancelLongPress()
                        overviewOverlay?.cancelLongPress()
                        homeButtonView?.cancelLongPress()
                        binding.homeRoot.cancelLongPress()
                        // Reset transforms before interactive effect
                        resetDesktopTransforms()
                    }
                    if (pageSwipeActive) {
                        applyInteractivePageEffect(dx)
                        return true
                    }

                    // ── Drawer open/close + notifications (not on dock) ──
                    if (currentPopup == null && !touchStartedOnDock && !isOverviewMode && !pageSwipeActive) {
                        if (!drawerGestureActive) {
                            if (!isDrawerOpen && dy > touchSlop * 4 && abs(dy) > abs(dx) * 1.5f) {
                                openNotificationShade()
                                drawerTouchStartY = ev.rawY
                            } else if (!isDrawerOpen && dy < -touchSlop * 2 && abs(dy) > abs(dx)) {
                                startDrawerGesture(opening = true)
                            } else if (isDrawerOpen && dy > touchSlop && abs(dy) > abs(dx)) {
                                // Close only when apps scrolled to top
                                if (!binding.appsGrid.canScrollVertically(-1)) {
                                    startDrawerGesture(opening = false)
                                }
                            }
                        }
                        if (drawerGestureActive) {
                            updateDrawerGesture(ev.rawY)
                            return true
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    drawerVelocityTracker?.addMovement(ev)
                    if (dockPageSwipeActive) {
                        dockPageSwipeActive = false
                        suppressEmptyLongPress = false
                        finishDockSwipe(ev.rawX - pageTouchStartX)
                        drawerVelocityTracker?.recycle()
                        drawerVelocityTracker = null
                        return true
                    }
                    if (pageSwipeActive) {
                        pageSwipeActive = false
                        suppressEmptyLongPress = false
                        suppressIconClick = true
                        finishPageSwipe(ev.rawX - pageTouchStartX)
                        binding.homeRoot.postDelayed({ suppressIconClick = false }, 300)
                        drawerVelocityTracker?.recycle()
                        drawerVelocityTracker = null
                        return true
                    }
                    if (drawerGestureActive) {
                        finishDrawerGesture()
                        drawerVelocityTracker?.recycle()
                        drawerVelocityTracker = null
                        return true
                    }
                    drawerVelocityTracker?.recycle()
                    drawerVelocityTracker = null
                    if (touchStartedOnDock) {
                        suppressEmptyLongPress = false
                    }
                    touchStartedOnDock = false
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun resetDesktopTransforms() {
        val c = binding.desktopContainer
        c.animate().cancel()
        c.translationX = 0f
        c.translationY = 0f
        c.rotationY = 0f
        c.rotationX = 0f
        c.scaleX = if (isOverviewMode) 0.82f else 1f
        c.scaleY = if (isOverviewMode) 0.82f else 1f
        c.alpha = 1f
        val w = c.width.toFloat().coerceAtLeast(1f)
        c.pivotX = w / 2f
        c.pivotY = c.height / 2f
        c.cameraDistance = 16 * resources.displayMetrics.density * w
        // Also reset any dual layers
        outgoingPageLayer?.let {
            it.animate().cancel()
            it.translationX = 0f
            it.rotationY = 0f
            it.rotationX = 0f
            it.scaleX = 1f
            it.scaleY = 1f
            it.alpha = 1f
        }
        incomingPageLayer?.let {
            it.animate().cancel()
            it.translationX = 0f
            it.rotationY = 0f
            it.rotationX = 0f
            it.scaleX = 1f
            it.scaleY = 1f
            it.alpha = 1f
        }
    }

    private fun clearDualLayers(keepIncoming: Boolean) {
        val container = binding.desktopContainer
        val out = outgoingPageLayer
        val inc = incomingPageLayer
        outgoingPageLayer = null
        incomingPageLayer = null
        dualTransitionActive = false
        dualTransitionDir = 0

        if (keepIncoming && inc != null) {
            // Flatten incoming children into container
            val children = mutableListOf<View>()
            for (i in 0 until inc.childCount) children.add(inc.getChildAt(i))
            container.removeAllViews()
            for (ch in children) {
                inc.removeView(ch)
                container.addView(ch)
            }
        } else if (!keepIncoming && out != null) {
            // Restore outgoing
            val children = mutableListOf<View>()
            for (i in 0 until out.childCount) children.add(out.getChildAt(i))
            container.removeAllViews()
            for (ch in children) {
                out.removeView(ch)
                container.addView(ch)
            }
        } else {
            container.removeAllViews()
            renderDesktop()
        }
        resetDesktopTransforms()
    }

    /** Build a page layer with icons for a given screen index */
    private fun buildPageLayer(screen: Int): FrameLayout {
        val layer = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        if (isOverviewMode && screen < prefs.pageCount) {
            layer.addView(
                createPageFrame(),
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }
        val pageItems = homeItems.filter { it.screen == screen && !it.isDock }
        for (item in pageItems) {
            if (draggingHomeItem?.id == item.id) continue
            if (item.isFolder) {
                val iconView = createFolderIconView(item)
                positionIcon(iconView, item.cellX, item.cellY)
                layer.addView(iconView)
            } else {
                val app = allApps.find {
                    it.packageName == item.packageName && it.activityName == item.activityName
                } ?: continue
                val iconView = createDesktopIconView(app, item)
                positionIcon(iconView, item.cellX, item.cellY)
                layer.addView(iconView)
            }
        }
        return layer
    }

    /**
     * Start dual-page transition: pack current icons as outgoing, build target as incoming.
     * dir: -1 = going to next page (swipe left), +1 = previous (swipe right)
     */
    private fun ensureDualTransition(dir: Int) {
        if (dualTransitionActive && dualTransitionDir == dir) return
        // If direction flipped mid-gesture, rebuild
        if (dualTransitionActive) {
            clearDualLayers(keepIncoming = false)
        }
        val container = binding.desktopContainer
        val targetPage = currentPage - dir // dir +1 means finger right → previous page
        // Wait: swipe left (dx < 0) → next page → currentPage+1
        // dir convention: dualTransitionDir = sign of intended page delta = if fromRight then +1 for next
        // Let's use: pageDelta = if swipe left then +1 else -1
        // Actually parameter `dir` here = pageDelta (+1 next, -1 prev)
        
        val dest = (currentPage + dir).coerceIn(0, if (isOverviewMode) prefs.pageCount else prefs.pageCount - 1)
        if (dest == currentPage && !isOverviewMode) return
        // Allow overview virtual page

        // Pack existing children into outgoing
        val outgoing = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        val existing = mutableListOf<View>()
        for (i in 0 until container.childCount) existing.add(container.getChildAt(i))
        for (v in existing) {
            container.removeView(v)
            if (v !== dropOverlay) outgoing.addView(v)
        }
        container.addView(outgoing)

        val incoming = buildPageLayer(dest)
        // If overview add-page screen
        if (isOverviewMode && dest >= prefs.pageCount) {
            // empty incoming is fine
        }
        container.addView(incoming)

        // Camera setup
        val w = container.width.toFloat().coerceAtLeast(1f)
        val cam = 16 * resources.displayMetrics.density * w
        outgoing.cameraDistance = cam
        incoming.cameraDistance = cam
        outgoing.pivotX = w / 2f
        outgoing.pivotY = container.height / 2f
        incoming.pivotX = w / 2f
        incoming.pivotY = container.height / 2f

        // Initial incoming state (hidden to the side / rotated)
        val effect = if (isOverviewMode) "simple" else prefs.pageTransition
        setLayerProgress(outgoing, incoming, progress = 0f, pageDelta = dir, effect = effect)

        outgoingPageLayer = outgoing
        incomingPageLayer = incoming
        dualTransitionDir = dir
        dualTransitionActive = true
    }

    /**
     * progress 0 = fully showing origin, 1 = fully showing destination
     * pageDelta +1 = to next page (content comes from right), -1 = to previous
     */
    private fun setLayerProgress(
        outgoing: View,
        incoming: View,
        progress: Float,
        pageDelta: Int,
        effect: String
    ) {
        val p = progress.coerceIn(0f, 1f)
        val w = binding.desktopContainer.width.toFloat().coerceAtLeast(1f)
        val h = binding.desktopContainer.height.toFloat().coerceAtLeast(1f)
        val baseScale = if (isOverviewMode) 0.82f else 1f
        // sign: next page (pageDelta=+1) → outgoing rotates toward left (negative Y)
        val sign = if (pageDelta > 0) -1f else 1f

        outgoing.pivotX = w / 2f
        outgoing.pivotY = h / 2f
        incoming.pivotX = w / 2f
        incoming.pivotY = h / 2f
        val cam = 16 * resources.displayMetrics.density * w
        outgoing.cameraDistance = cam
        incoming.cameraDistance = cam

        when (effect) {
            "cube" -> {
                // True cube: both faces visible, rotate around vertical axis with slight X shift
                // Outgoing face turns away; incoming comes from the side
                val angle = 90f * p
                outgoing.rotationY = sign * angle
                incoming.rotationY = sign * (angle - 90f)
                // Small X displacement so faces don't feel glued to center (stay on-screen)
                val shift = w * 0.35f * p
                outgoing.translationX = sign * shift
                incoming.translationX = sign * (shift - w * 0.35f)
                outgoing.alpha = (1f - p * 0.35f).coerceIn(0.4f, 1f)
                incoming.alpha = (0.4f + p * 0.6f).coerceIn(0.4f, 1f)
                outgoing.scaleX = baseScale
                outgoing.scaleY = baseScale
                incoming.scaleX = baseScale
                incoming.scaleY = baseScale
                outgoing.rotationX = 0f
                incoming.rotationX = 0f
            }
            "flip" -> {
                outgoing.rotationY = sign * 180f * p
                incoming.rotationY = sign * (180f * p - 180f)
                outgoing.translationX = 0f
                incoming.translationX = 0f
                outgoing.alpha = if (p < 0.5f) 1f - p * 1.2f else 0f
                incoming.alpha = if (p > 0.5f) (p - 0.5f) * 2f else 0f
                outgoing.scaleX = baseScale
                outgoing.scaleY = baseScale
                incoming.scaleX = baseScale
                incoming.scaleY = baseScale
            }
            "flip_vertical" -> {
                outgoing.rotationX = -sign * 180f * p
                incoming.rotationX = -sign * (180f * p - 180f)
                outgoing.translationX = 0f
                incoming.translationX = 0f
                outgoing.rotationY = 0f
                incoming.rotationY = 0f
                outgoing.alpha = if (p < 0.5f) 1f - p * 1.2f else 0f
                incoming.alpha = if (p > 0.5f) (p - 0.5f) * 2f else 0f
            }
            "accordion" -> {
                outgoing.pivotX = if (pageDelta > 0) 0f else w
                incoming.pivotX = if (pageDelta > 0) w else 0f
                outgoing.scaleX = (baseScale * (1f - p)).coerceAtLeast(0.05f)
                incoming.scaleX = (baseScale * p).coerceAtLeast(0.05f)
                outgoing.scaleY = baseScale
                incoming.scaleY = baseScale
                outgoing.translationX = 0f
                incoming.translationX = 0f
                outgoing.rotationY = 0f
                incoming.rotationY = 0f
                outgoing.alpha = 1f - p * 0.5f
                incoming.alpha = 0.5f + p * 0.5f
            }
            "zoom_fade" -> {
                outgoing.scaleX = baseScale * (1f - p * 0.3f)
                outgoing.scaleY = baseScale * (1f - p * 0.3f)
                incoming.scaleX = baseScale * (0.7f + p * 0.3f)
                incoming.scaleY = baseScale * (0.7f + p * 0.3f)
                outgoing.alpha = 1f - p
                incoming.alpha = p
                outgoing.translationX = 0f
                incoming.translationX = 0f
                outgoing.rotationY = 0f
                incoming.rotationY = 0f
            }
            "crystal" -> {
                outgoing.scaleX = baseScale * (1f - p * 0.06f)
                outgoing.scaleY = baseScale * (1f - p * 0.06f)
                incoming.scaleX = baseScale * (0.96f + p * 0.04f)
                incoming.scaleY = baseScale * (0.96f + p * 0.04f)
                outgoing.alpha = 1f - p
                incoming.alpha = p
                outgoing.translationX = sign * w * 0.08f * p
                incoming.translationX = sign * w * 0.08f * (p - 1f)
                outgoing.rotationY = 0f
                incoming.rotationY = 0f
            }
            "none" -> {
                outgoing.alpha = if (p < 0.5f) 1f else 0f
                incoming.alpha = if (p >= 0.5f) 1f else 0f
                outgoing.translationX = 0f
                incoming.translationX = 0f
            }
            else -> {
                // simple slide: both pages visible sliding
                outgoing.translationX = -sign * w * p
                incoming.translationX = sign * w * (1f - p)
                // opposite sign for incoming start: if next (sign=-1 for outgoing going left),
                // incoming starts from right: translationX = +w at p=0
                // sign for pageDelta>+1: sign=-1, outgoing.translationX = -(-1)*w*p = w*p? 
                // Fix simple properly:
                // pageDelta +1 (next): outgoing moves left (negative X), incoming from right (+w → 0)
                val outX = if (pageDelta > 0) -w * p else w * p
                val inX = if (pageDelta > 0) w * (1f - p) else -w * (1f - p)
                outgoing.translationX = outX
                incoming.translationX = inX
                outgoing.alpha = 1f - p * 0.4f
                incoming.alpha = 0.6f + p * 0.4f
                outgoing.rotationY = 0f
                incoming.rotationY = 0f
                outgoing.scaleX = baseScale
                outgoing.scaleY = baseScale
                incoming.scaleX = baseScale
                incoming.scaleY = baseScale
            }
        }
    }

    private fun applyInteractivePageEffect(dx: Float) {
        val w = binding.desktopContainer.width.toFloat().coerceAtLeast(1f)
        val effect = if (isOverviewMode) "simple" else prefs.pageTransition
        // pageDelta from finger: swipe left (dx<0) → next (+1)
        val pageDelta = if (dx < 0) 1 else -1
        val progress = (abs(dx) / w).coerceIn(0f, 1f)

        // Don't start dual if can't go that direction
        val maxPage = if (isOverviewMode) prefs.pageCount else prefs.pageCount - 1
        val dest = currentPage + pageDelta
        if (dest < 0 || dest > maxPage) {
            // rubber band on single layer
            val c = binding.desktopContainer
            c.translationX = dx * 0.25f
            c.alpha = 0.85f
            return
        }

        if (effect == "none") {
            // no live dual
            return
        }

        ensureDualTransition(pageDelta)
        val out = outgoingPageLayer ?: return
        val inc = incomingPageLayer ?: return
        setLayerProgress(out, inc, progress, pageDelta, effect)
    }

    private fun finishPageSwipe(dx: Float) {
        val width = binding.desktopContainer.width.toFloat().coerceAtLeast(1f)
        val threshold = width * 0.22f
        val maxPage = if (isOverviewMode) prefs.pageCount else prefs.pageCount - 1
        val pageDelta = when {
            dx < -threshold && currentPage < maxPage -> 1
            dx > threshold && currentPage > 0 -> -1
            else -> 0
        }

        if (pageDelta == 0) {
            // Cancel dual transition
            if (dualTransitionActive) {
                val out = outgoingPageLayer
                val inc = incomingPageLayer
                if (out != null && inc != null) {
                    // Animate back to progress 0
                    val effect = if (isOverviewMode) "simple" else prefs.pageTransition
                    val startP = (abs(dx) / width).coerceIn(0f, 1f)
                    val anim = android.animation.ValueAnimator.ofFloat(startP, 0f)
                    anim.duration = 180
                    anim.interpolator = DecelerateInterpolator()
                    anim.addUpdateListener { va ->
                        setLayerProgress(out, inc, va.animatedValue as Float, dualTransitionDir, effect)
                    }
                    anim.addListener(object : android.animation.AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: android.animation.Animator) {
                            clearDualLayers(keepIncoming = false)
                        }
                    })
                    anim.start()
                } else {
                    clearDualLayers(keepIncoming = false)
                }
            } else {
                binding.desktopContainer.animate()
                    .translationX(0f).alpha(1f)
                    .setDuration(160).setInterpolator(DecelerateInterpolator()).start()
            }
            if (isOverviewMode) refreshOverviewChrome()
            return
        }

        // Complete transition to target page
        val effect = if (isOverviewMode) "simple" else prefs.pageTransition
        ensureDualTransition(pageDelta)
        val out = outgoingPageLayer
        val inc = incomingPageLayer
        if (out == null || inc == null) {
            currentPage = (currentPage + pageDelta).coerceIn(0, maxPage)
            applyWallpaperOffset()
            renderDesktop()
            if (isOverviewMode) refreshOverviewChrome()
            return
        }
        val startP = (abs(dx) / width).coerceIn(0f, 1f)
        val anim = android.animation.ValueAnimator.ofFloat(startP, 1f)
        anim.duration = 240
        anim.interpolator = DecelerateInterpolator(1.3f)
        anim.addUpdateListener { va ->
            setLayerProgress(out, inc, va.animatedValue as Float, pageDelta, effect)
        }
        anim.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                currentPage = (currentPage + pageDelta).coerceIn(0, maxPage)
            applyWallpaperOffset()
                clearDualLayers(keepIncoming = true)
                // Re-render to attach proper listeners/state
                renderDesktop()
                if (isOverviewMode) refreshOverviewChrome()
            }
        })
        anim.start()
    }

    private fun animatePageChange(fromRight: Boolean) {
        // currentPage already points to the DESTINATION page
        val pageDelta = if (fromRight) 1 else -1
        val effect = if (isOverviewMode) "simple" else prefs.pageTransition
        if (effect == "none") {
            renderDesktop()
            if (isOverviewMode) refreshOverviewChrome()
            return
        }
        val dest = currentPage
        val origin = (currentPage - pageDelta).coerceAtLeast(0)
        // Render origin, then dual-transition into dest
        currentPage = origin
        renderDesktop()
        ensureDualTransition(pageDelta)
        currentPage = dest
        applyWallpaperOffset()
        val out = outgoingPageLayer
        val inc = incomingPageLayer
        if (out == null || inc == null) {
            renderDesktop()
            if (isOverviewMode) refreshOverviewChrome()
            return
        }
        val anim = android.animation.ValueAnimator.ofFloat(0f, 1f)
        anim.duration = 300
        anim.interpolator = DecelerateInterpolator(1.35f)
        anim.addUpdateListener { va ->
            setLayerProgress(out, inc, va.animatedValue as Float, pageDelta, effect)
        }
        anim.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                clearDualLayers(keepIncoming = true)
                renderDesktop()
                if (isOverviewMode) refreshOverviewChrome()
            }
        })
        anim.start()
    }

    // ── Dock independent swipe ──
    private fun applyDockSwipeVisual(dx: Float) {
        val pager = binding.dockPager
        val page = pager.getChildAt(0) ?: return
        val w = pager.width.toFloat().coerceAtLeast(1f)
        page.translationX = dx * 0.65f
        page.alpha = (1f - abs(dx) / (w * 1.3f)).coerceIn(0.35f, 1f)
    }

    private fun finishDockSwipe(dx: Float) {
        val pager = binding.dockPager
        val w = pager.width.toFloat().coerceAtLeast(1f)
        val threshold = w * 0.15f
        val pages = dockPageCount()
        when {
            pages > 1 && dx < -threshold -> {
                val next = if (dockPageIndex < pages - 1) dockPageIndex + 1 else 0
                animateDockPageChange(next, fromRight = true)
            }
            pages > 1 && dx > threshold -> {
                val prev = if (dockPageIndex > 0) dockPageIndex - 1 else pages - 1
                animateDockPageChange(prev, fromRight = false)
            }
            else -> {
                pager.getChildAt(0)?.animate()
                    ?.translationX(0f)?.alpha(1f)
                    ?.setDuration(150)
                    ?.setInterpolator(DecelerateInterpolator())
                    ?.start()
            }
        }
    }

    /** Brief glass border flash when switching pages while dragging an icon */
    private fun showBriefGlassFrame() {
        val density = resources.displayMetrics.density
        val glass = View(this).apply {
            tag = "glass_flash"
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 24f * density
                setColor(0x18FFFFFF)
                setStroke((1.5f * density).toInt(), 0x99FFFFFF.toInt())
            }
            alpha = 0f
        }
        val margin = (18 * density).toInt()
        val lp = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams(0, 0).apply {
            topToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            bottomToBottom = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            setMargins(margin, (40 * density).toInt(), margin, (80 * density).toInt())
        }
        binding.homeRoot.addView(glass, lp)
        glass.animate().alpha(1f).setDuration(120).withEndAction {
            glass.animate().alpha(0f).setDuration(280).withEndAction {
                binding.homeRoot.removeView(glass)
            }.start()
        }.start()
    }

    /** Switch page while an icon is being dragged (edge dwell) with slide + glass flash. */
    private fun flipPageWhileDragging(dir: Int) {
        val target = currentPage + dir
        if (target < 0 || target >= prefs.pageCount) return
        val container = binding.desktopContainer
        val width = container.width.toFloat().coerceAtLeast(1f)
        val outX = dir * -width * 0.35f

        showBriefGlassFrame()
        container.animate()
            .translationX(outX)
            .alpha(0.55f)
            .setDuration(140)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                currentPage = target
                applyWallpaperOffset()
                // Keep drag state; re-render icons + folders for the new page (shadow stays)
                val overlay = dropOverlay
                val toRemove = mutableListOf<View>()
                for (i in 0 until container.childCount) {
                    val c = container.getChildAt(i)
                    if (c !== overlay) toRemove.add(c)
                }
                toRemove.forEach { container.removeView(it) }
                val pageItems = homeItems.filter { it.screen == currentPage && !it.isDock }
                for (item in pageItems) {
                    if (draggingHomeItem?.id == item.id) continue
                    if (item.isFolder) {
                        val iconView = createFolderIconView(item)
                        positionIcon(iconView, item.cellX, item.cellY)
                        container.addView(iconView)
                    } else {
                        val app = allApps.find {
                            it.packageName == item.packageName && it.activityName == item.activityName
                        } ?: continue
                        val iconView = createDesktopIconView(app, item)
                        positionIcon(iconView, item.cellX, item.cellY)
                        container.addView(iconView)
                    }
                }
                if (overlay != null) container.bringChildToFront(overlay)

                container.translationX = -outX
                container.animate()
                    .translationX(0f)
                    .alpha(1f)
                    .setDuration(160)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
            .start()
    }

    private fun scheduleEdgePageFlip(rawX: Float) {
        val edge = 48 * resources.displayMetrics.density
        val screenW = resources.displayMetrics.widthPixels.toFloat()
        val dir = when {
            rawX < edge -> -1
            rawX > screenW - edge -> 1
            else -> 0
        }
        if (dir == 0 || dir == pendingEdgeDir) {
            if (dir == 0) {
                cancelEdgePageFlip()
            }
            return
        }
        cancelEdgePageFlip()
        pendingEdgeDir = dir
        val r = Runnable {
            if (draggingApp != null) {
                flipPageWhileDragging(dir)
            }
            pendingEdgeDir = 0
            edgePageFlipRunnable = null
        }
        edgePageFlipRunnable = r
        binding.homeRoot.postDelayed(r, 450)
    }

    private fun cancelEdgePageFlip() {
        edgePageFlipRunnable?.let { binding.homeRoot.removeCallbacks(it) }
        edgePageFlipRunnable = null
        pendingEdgeDir = 0
    }

    private fun startDrawerGesture(opening: Boolean) {
        drawerGestureActive = true
        suppressEmptyLongPress = true
        cancelPendingIconLongPresses()
        drawerOpeningFromClosed = opening
        val h = drawerHeight()

        if (opening) {
            binding.drawerPanel.visibility = View.VISIBLE
            applyDrawerOpenVisual(h) // ty=h → alpha 0
            binding.btnOpenDrawer.visibility = View.INVISIBLE
            drawerStartTy = h
        } else {
            drawerStartTy = binding.drawerPanel.translationY
            applyDrawerOpenVisual(drawerStartTy)
        }
        binding.drawerPanel.animate().cancel()
    }

    private fun updateDrawerGesture(rawY: Float) {
        val h = drawerHeight()
        val dy = rawY - drawerTouchStartY
        val ty = if (drawerOpeningFromClosed) {
            (h + dy).coerceIn(0f, h)
        } else {
            (drawerStartTy + dy).coerceIn(0f, h)
        }
        // alpha follows open amount: 0% open → 0 alpha, 100% open → 1 alpha
        applyDrawerOpenVisual(ty)
    }

    private fun finishDrawerGesture() {
        drawerGestureActive = false
        drawerVelocityTracker?.computeCurrentVelocity(1000)
        val vy = drawerVelocityTracker?.yVelocity ?: 0f
        if (abs(vy) > 900f) {
            flingDrawer(vy)
        } else {
            val h = drawerHeight()
            val ty = binding.drawerPanel.translationY
            if (ty < h * 0.35f) {
                flingDrawer(-2000f) // force open
            } else if (ty > h * 0.55f) {
                flingDrawer(2000f) // force close
            } else {
                bounceDrawerBack()
            }
        }
    }

    // ───────────────────────────── Drag & Drop ─────────────────────────────

    private fun startDrawerDrag(app: AppInfo, sourceView: View) {
        if (draggingApp != null) return
        draggingApp = app
        draggingHomeItem = null
        dragShadow = createDragShadow(app)
        (binding.homeRoot as ViewGroup).addView(dragShadow)

        val loc = IntArray(2)
        sourceView.getLocationOnScreen(loc)
        val rootLoc = IntArray(2)
        binding.homeRoot.getLocationOnScreen(rootLoc)
        dragShadow?.x = (loc[0] - rootLoc[0]).toFloat()
        dragShadow?.y = (loc[1] - rootLoc[1]).toFloat()
        dragShadow?.scaleX = 1.14f
        dragShadow?.scaleY = 1.14f

        ensureDropOverlay()
        closeDrawerAnimated()
    }

    private fun startDesktopDrag(app: AppInfo, item: HomeItem, sourceView: View, event: MotionEvent) {
        if (draggingApp != null) return
        iconLongPressActive = true
        dismissCurrentPopup()
        draggingApp = app
        draggingHomeItem = item
        sourceView.visibility = View.INVISIBLE

        dragShadow = createDragShadow(app)
        (binding.homeRoot as ViewGroup).addView(dragShadow)
        dragShadow?.scaleX = 1.14f
        dragShadow?.scaleY = 1.14f
        moveDragShadow(event.rawX, event.rawY)
        ensureDropOverlay()
        updateDropHighlight(event.rawX, event.rawY)
    }

    private fun createDragShadow(app: AppInfo): View {
        val size = if (desktopIconPx > 0) desktopIconPx else (56 * resources.displayMetrics.density).toInt()
        return ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(size, size)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageDrawable(safeIcon(app.icon))
            scaleX = 1f
            scaleY = 1f
            elevation = 28f
            alpha = 0.95f
        }
    }

    private fun moveDragShadow(rawX: Float, rawY: Float) {
        val shadow = dragShadow ?: return
        val rootLoc = IntArray(2)
        binding.homeRoot.getLocationOnScreen(rootLoc)
        shadow.x = rawX - rootLoc[0] - shadow.width / 2f
        shadow.y = rawY - rootLoc[1] - shadow.height / 2f
        // Live dock reorder preview when dragging over dock
        if (prefs.showDock && binding.dockArea.visibility == View.VISIBLE && pendingDockRestore != null) {
            val dockLoc = IntArray(2)
            binding.dockArea.getLocationOnScreen(dockLoc)
            if (rawY >= dockLoc[1] - 20 && rawY <= dockLoc[1] + binding.dockArea.height + 30) {
                val target = dockDropIndex(rawX).coerceIn(0, dockItems.size)
                // Visual only: shift translation of icons toward gap — full reorder on drop
            }
        }
    }

    private fun ensureDropOverlay() {
        calculateMetrics()
        val existing = dropOverlay
        if (existing != null) {
            existing.configure(columns, rows)
            return
        }
        val overlay = GridDropOverlay(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            configure(columns, rows)
        }
        binding.desktopContainer.addView(overlay)
        dropOverlay = overlay
    }

    private fun updateDropHighlight(rawX: Float, rawY: Float) {
        val overlay = dropOverlay ?: return
        // Keep overlay grid in sync with current prefs/metrics
        calculateMetrics()
        overlay.configure(columns, rows)
        if (cellWidth <= 0f || cellHeight <= 0f) return

        // Coordinates relative to overlay (= content area of desktopContainer)
        val loc = IntArray(2)
        overlay.getLocationOnScreen(loc)
        val localX = rawX - loc[0]
        val localY = rawY - loc[1]

        // Prefer overlay measured size for hit-testing (matches onDraw)
        val ow = if (overlay.width > 0) overlay.width.toFloat() else (cellWidth * columns)
        val oh = if (overlay.height > 0) overlay.height.toFloat() else (cellHeight * rows)
        val cw = ow / columns
        val ch = oh / rows

        val fx = (localX / cw).coerceIn(0f, columns - 0.001f)
        val fy = (localY / ch).coerceIn(0f, rows - 0.001f)
        val cellX = fx.toInt().coerceIn(0, columns - 1)
        val cellY = fy.toInt().coerceIn(0, rows - 1)
        lastDropCell = Point(cellX, cellY)

        val dist = hypot((fx - cellX - 0.5f).toDouble(), (fy - cellY - 0.5f).toDouble()).toFloat()
        val strength = (1f - dist * 1.8f).coerceIn(0.4f, 1f)

        overlay.setFocus(cellX, cellY, strength)
        // Auto-flip page when dwelling on left/right edge while dragging
        scheduleEdgePageFlip(rawX)
    }

    private fun endDrag(rawX: Float, rawY: Float) {
        cancelEdgePageFlip()
        val app = draggingApp ?: return
        // Drop on dock?
        if (prefs.showDock && binding.dockArea.visibility == View.VISIBLE) {
            val dockLoc = IntArray(2)
            binding.dockArea.getLocationOnScreen(dockLoc)
            val inDock = rawY >= dockLoc[1] - 40 &&
                rawY <= dockLoc[1] + binding.dockArea.height + 40
            if (inDock) {
                draggingHomeItem?.let { item ->
                    homeItems.removeAll { it.id == item.id }
                }
                pendingDockRestore = null
                val insertAt = dockDropIndex(rawX)
                addToDock(app, insertAt)
                cleanupDrag()
                renderDesktop()
                return
            }
        }
        val cell = lastDropCell ?: Point(0, 0)
        var cellX = cell.x
        var cellY = cell.y
        val existingItem = draggingHomeItem

        if (existingItem != null && existingItem.isFolder) {
            // Moving a folder — just reposition
            if (isCellOccupied(cellX, cellY, excludeId = existingItem.id)) {
                val free = findNearestFreeCell(cellX, cellY, excludeId = existingItem.id)
                if (free != null) { cellX = free.x; cellY = free.y }
                else { cellX = existingItem.cellX; cellY = existingItem.cellY }
            }
            existingItem.cellX = cellX
            existingItem.cellY = cellY
            existingItem.screen = currentPage
            saveAllItems()
        } else if (existingItem != null) {
            val occupant = homeItems.find {
                it.screen == currentPage && it.cellX == cellX && it.cellY == cellY && it.id != existingItem.id
            }
            if (occupant != null) {
                mergeIntoFolder(occupant, existingItem, app)
            } else {
                existingItem.cellX = cellX
                existingItem.cellY = cellY
                existingItem.screen = currentPage
                saveAllItems()
            }
        } else {
            // New shortcut from drawer / dock
            val occupant = homeItems.find {
                it.screen == currentPage && it.cellX == cellX && it.cellY == cellY
            }
            if (occupant != null) {
                pendingDockRestore = null
                val tempItem = HomeItem(
                    id = UUID.randomUUID().toString(),
                    packageName = app.packageName,
                    activityName = app.activityName,
                    label = app.label,
                    cellX = cellX, cellY = cellY, screen = currentPage, isDock = false
                )
                mergeIntoFolder(occupant, tempItem, app)
            } else {
                pendingDockRestore = null
                homeItems.add(
                    HomeItem(
                        id = UUID.randomUUID().toString(),
                        packageName = app.packageName,
                        activityName = app.activityName,
                        label = app.label,
                        cellX = cellX,
                        cellY = cellY,
                        screen = currentPage,
                        isDock = false
                    )
                )
                saveAllItems()
            }
        }

        // Animate drop into cell center
        val shadow = dragShadow
        if (shadow != null && cellWidth > 0) {
            val overlay = dropOverlay
            val targetCenterX: Float
            val targetCenterY: Float
            if (overlay != null) {
                val ol = IntArray(2)
                overlay.getLocationOnScreen(ol)
                val rl = IntArray(2)
                binding.homeRoot.getLocationOnScreen(rl)
                targetCenterX = (ol[0] - rl[0]) + cellX * cellWidth + cellWidth / 2f - shadow.width / 2f
                targetCenterY = (ol[1] - rl[1]) + cellY * cellHeight + cellHeight / 2f - shadow.height / 2f
            } else {
                targetCenterX = shadow.x
                targetCenterY = shadow.y
            }
            shadow.animate()
                .x(targetCenterX)
                .y(targetCenterY)
                .scaleX(1f)
                .scaleY(1f)
                .alpha(0.2f)
                .setDuration(160)
                .setInterpolator(OvershootInterpolator(0.9f))
                .withEndAction {
                    cleanupDrag()
                    closeFolderOverlay()
                    renderDesktop()
                }
                .start()
        } else {
            cleanupDrag()
            closeFolderOverlay()
            renderDesktop()
        }
    }

    private fun cleanupDrag() {
        try { cancelEdgePageFlip() } catch (_: Exception) {}
        try {
            folderDragCatcher?.let { c ->
                (c.parent as? ViewGroup)?.removeView(c)
                folderDragCatcher = null
            }
        } catch (_: Exception) {}
        try {
            pendingDockRestore?.let { item ->
                if (dockItems.none { it.id == item.id }) {
                    dockItems.add(item)
                    dockItems.forEachIndexed { idx, hi -> hi.cellX = idx }
                    saveAllItems()
                    renderDock()
                }
                pendingDockRestore = null
            }
        } catch (_: Exception) {}
        try {
            dragShadow?.let { s ->
                (s.parent as? ViewGroup)?.removeView(s)
            }
        } catch (_: Exception) {}
        dragShadow = null
        try {
            dropOverlay?.let { o ->
                (o.parent as? ViewGroup)?.removeView(o)
            }
        } catch (_: Exception) {}
        dropOverlay = null
        lastDropCell = null
        draggingApp = null
        draggingHomeItem = null
        folderDragInProgress = false
        suppressEmptyLongPress = false
        iconLongPressActive = false
        // Close hidden folder overlay after a folder drag
        if (folderOverlay != null && folderOverlay?.alpha == 0f) {
            closeFolderOverlay()
        }
    }

    private fun submitDrawerApps(apps: List<AppInfo>) {
        val hidden = prefs.getHiddenPackages()
        val visible = apps.filter { it.packageName !in hidden }
        val groups = try {
            if (!::drawerGroupStore.isInitialized) emptyList()
            else drawerGroupStore.load().filter { it.enabled && it.members.isNotEmpty() }
        } catch (_: Exception) { emptyList() }
        val groupRows = groups.map { g ->
            // Build tiny preview mosaic for the group
            val previewMembers = g.members.take(4)
            val size = if (drawerIconPx > 0) drawerIconPx else (52 * resources.displayMetrics.density).toInt()
            val fakeFolder = HomeItem(
                id = g.id,
                packageName = "",
                activityName = "",
                label = g.name,
                cellX = 0, cellY = 0, screen = 0,
                isFolder = true,
                folderName = g.name,
                folderMembers = previewMembers.toMutableList()
            )
            val icon = createFolderPreviewDrawable(fakeFolder, size)
            AppIconAdapter.Row.Group(g.id, g.name, icon)
        }
        drawerAdapter.submitAppsWithGroups(groupRows, visible, alphabetical = prefs.drawerAlphabetical)
    }

    private fun openDrawerGroup(groupId: String) {
        val group = try { drawerGroupStore.load().find { it.id == groupId } } catch (_: Exception) { null }
        if (group == null) return
        // Open folder overlay ON TOP of the drawer (do not close drawer)
        val folderItem = HomeItem(
            id = "drawer_group_" + group.id,
            packageName = "",
            activityName = "",
            label = group.name,
            cellX = 0,
            cellY = 0,
            screen = currentPage,
            isFolder = true,
            folderName = group.name,
            folderMembers = group.members.toMutableList()
        )
        openFolder(folderItem)
    }

    /** Persist drawer-group folder edits back into DrawerGroupStore + refresh drawer */
    private fun syncDrawerGroupFromFolder(item: HomeItem) {
        if (!item.id.startsWith("drawer_group_")) return
        val gid = item.id.removePrefix("drawer_group_")
        try {
            val groups = drawerGroupStore.load()
            val g = groups.find { it.id == gid } ?: return
            g.name = item.folderName
            g.members.clear()
            g.members.addAll(item.folderMembers.map {
                FolderMember(it.packageName, it.activityName, it.label)
            })
            drawerGroupStore.save(groups)
            // Refresh group tiles in the open drawer
            if (::drawerAdapter.isInitialized) {
                submitDrawerApps(allApps)
            }
        } catch (_: Exception) {}
    }

    private val DOCK_SLOTS = 5

    private fun dockPageCount(): Int {
        val n = dockItems.size
        return if (n <= DOCK_SLOTS) 1 else (n + DOCK_SLOTS - 1) / DOCK_SLOTS
    }

    private fun renderDock() {
        val area = binding.dockArea
        val pager = binding.dockPager
        val indicators = binding.dockIndicators
        pager.removeAllViews()
        indicators.removeAllViews()
        if (!prefs.showDock) {
            area.visibility = View.GONE
            applyBottomBarOrder()
            return
        }
        area.visibility = View.VISIBLE
        applyBottomBarOrder()
        val density = resources.displayMetrics.density
        val pages = dockPageCount().coerceAtLeast(1)
        dockPageIndex = dockPageIndex.coerceIn(0, pages - 1)

        // Glass background on the whole dock area
        if (prefs.dockGlassBorder) {
            area.background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 20f * density
                setColor(0x22FFFFFF)
                setStroke((1.2f * density).toInt(), 0x88FFFFFF.toInt())
            }
            area.setPadding((8 * density).toInt(), (4 * density).toInt(), (8 * density).toInt(), (4 * density).toInt())
        } else {
            area.background = null
            area.setPadding((4 * density).toInt(), 0, (4 * density).toInt(), 0)
        }

        val iconPx = (48 * density * prefs.dockIconSizePercent / 100f).toInt()
        // Build current page only (animated page changes rebuild)
        pager.clipChildren = true
        pager.clipToPadding = true
        val pageView = buildDockPage(dockPageIndex, iconPx, density)
        pager.addView(pageView)

        // Indicators
        if (pages > 1) {
            for (i in 0 until pages) {
                val dot = View(this).apply {
                    val size = (if (i == dockPageIndex) 7 else 5) * density
                    layoutParams = LinearLayout.LayoutParams(size.toInt(), size.toInt()).apply {
                        marginStart = (3 * density).toInt()
                        marginEnd = (3 * density).toInt()
                    }
                    background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(if (i == dockPageIndex) 0xFFFFFFFF.toInt() else 0x66FFFFFF)
                    }
                }
                indicators.addView(dot)
            }
        }

        // Dock swipe handled globally in dispatchTouchEvent (touchStartedOnDock)
    }

    private fun buildDockPage(page: Int, iconPx: Int, density: Float): LinearLayout {
        val ph = binding.dockPager.height.coerceAtLeast((72 * density).toInt())
        val pw = binding.dockPager.width.coerceAtLeast(1)
        val pageView = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            // Fixed pixel size — avoids expanding dockArea during transitions
            layoutParams = FrameLayout.LayoutParams(pw, ph)
        }
        val start = page * DOCK_SLOTS
        val end = minOf(start + DOCK_SLOTS, dockItems.size)
        val slice = dockItems.subList(start, end)
        for (item in slice) {
            val app = allApps.find {
                it.packageName == item.packageName && it.activityName == item.activityName
            } ?: continue
            val iv = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(iconPx, iconPx).apply {
                    val gap = (prefs.dockIconGapDp * density).toInt()
                    marginStart = gap
                    marginEnd = gap
                }
                minimumWidth = iconPx
                minimumHeight = iconPx
                scaleType = ImageView.ScaleType.FIT_CENTER
                setImageDrawable(safeIcon(app.icon))
                // Avoid residual scale from previous page animation
                scaleX = 1f
                scaleY = 1f
                alpha = 1f
                tag = item.id
                setOnClickListener {
                    if (draggingApp == null) launchApp(app)
                }
                // Long-press drag out of dock → desktop
                setOnTouchListener(dockIconTouchListener(app, item, this))
            }
            pageView.addView(iv)
        }
        return pageView
    }

    private fun dockIconTouchListener(app: AppInfo, item: HomeItem, iconView: View): View.OnTouchListener {
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        val longPressTimeout = (ViewConfiguration.getLongPressTimeout() * 1.2f).toLong()
        var downX = 0f
        var downY = 0f
        var armed = false
        var started = false
        var canceled = false
        val arm = Runnable {
            if (dockPageSwipeActive || pageSwipeActive || canceled || draggingApp != null) return@Runnable
            if (isDrawerOpen || drawerGestureActive || suppressIconInteractions || suppressEmptyLongPress) return@Runnable
            armed = true
            iconLongPressActive = true
            // Cancel any dock page swipe that might have started
            dockPageSwipeActive = false
            elevateView(iconView)
            iconView.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            dismissCurrentPopup()
            val popup = PopupMenu(this@HomeActivity, iconView, Gravity.TOP)
            popup.menu.add(0, 1, 0, "Quitar del dock")
            popup.menu.add(0, 2, 1, "Info de la app")
            popup.setOnMenuItemClickListener { mi ->
                when (mi.itemId) {
                    1 -> {
                        dockItems.removeAll { it.id == item.id }
                        dockItems.forEachIndexed { idx, hi -> hi.cellX = idx }
                        saveAllItems()
                        if (dockPageIndex >= dockPageCount()) {
                            dockPageIndex = (dockPageCount() - 1).coerceAtLeast(0)
                        }
                        renderDock()
                    }
                    2 -> openAppInfo(app)
                }
                true
            }
            popup.setOnDismissListener {
                if (draggingApp == null) {
                    resetElevation(iconView)
                    iconLongPressActive = false
                }
                if (currentPopup == popup) currentPopup = null
            }
            currentPopup = popup
            popup.show()
        }
        return View.OnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (suppressIconInteractions || isDrawerOpen || drawerGestureActive) return@OnTouchListener false
                    downX = ev.rawX
                    downY = ev.rawY
                    armed = false
                    started = false
                    canceled = false
                    v.handler?.postDelayed(arm, longPressTimeout)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downX
                    val dy = ev.rawY - downY
                    val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()
                    if (!armed && dist > touchSlop) {
                        canceled = true
                        v.handler?.removeCallbacks(arm)
                        return@OnTouchListener false
                    }
                    // Move after menu → drag mode
                    if (armed && !started && dist > touchSlop && draggingApp == null) {
                        started = true
                        v.handler?.removeCallbacks(arm)
                        dismissCurrentPopup()
                        startDockDrag(app, item, v)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.handler?.removeCallbacks(arm)
                    val dist = hypot((ev.rawX - downX).toDouble(), (ev.rawY - downY).toDouble()).toFloat()
                    when {
                        started -> { /* drop via dispatchTouchEvent */ }
                        armed && currentPopup != null -> {
                            // Keep menu open
                        }
                        // Simple tap → open app
                        ev.actionMasked == MotionEvent.ACTION_UP &&
                            !armed && !started && !canceled && dist < touchSlop * 2 &&
                            draggingApp == null -> {
                            resetElevation(v)
                            launchApp(app)
                        }
                        else -> resetElevation(v)
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun startDockDrag(app: AppInfo, item: HomeItem, source: View) {
        if (draggingApp != null) return
        iconLongPressActive = true
        dockPageSwipeActive = false
        dismissCurrentPopup()
        pendingDockRestore = item.copy()
        dockItems.removeAll { it.id == item.id }
        dockItems.forEachIndexed { idx, hi -> hi.cellX = idx }
        saveAllItems()
        renderDock()
        draggingApp = app
        draggingHomeItem = null
        suppressEmptyLongPress = true
        dragShadow = createDragShadow(app)
        dragShadow?.scaleX = 1.14f
        dragShadow?.scaleY = 1.14f
        (binding.homeRoot as ViewGroup).addView(dragShadow)
        val loc = IntArray(2)
        source.getLocationOnScreen(loc)
        val rootLoc = IntArray(2)
        binding.homeRoot.getLocationOnScreen(rootLoc)
        dragShadow?.x = (loc[0] - rootLoc[0]).toFloat()
        dragShadow?.y = (loc[1] - rootLoc[1]).toFloat()
        ensureDropOverlay()
        val midX = loc[0] + source.width / 2f
        val midY = loc[1] + source.height / 2f
        updateDropHighlight(midX, midY)
    }

    private fun setupDockSwipe(pager: FrameLayout) {
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        pager.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dockTouchStartX = ev.rawX
                    dockTouchStartY = ev.rawY
                    dockSwipeActive = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - dockTouchStartX
                    val dy = ev.rawY - dockTouchStartY
                    if (!dockSwipeActive && abs(dx) > touchSlop * 1.5f && abs(dx) > abs(dy) * 1.4f) {
                        dockSwipeActive = true
                    }
                    if (dockSwipeActive) {
                        // Follow finger lightly
                        val page = pager.getChildAt(0)
                        page?.translationX = dx * 0.55f
                        page?.alpha = 1f - (abs(dx) / (pager.width.coerceAtLeast(1) * 1.5f)).coerceIn(0f, 0.4f)
                    }
                    dockSwipeActive
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dockSwipeActive) {
                        val dx = ev.rawX - dockTouchStartX
                        val threshold = pager.width * 0.18f
                        val pages = dockPageCount()
                        when {
                            dx < -threshold && pages > 1 -> {
                                val next = if (dockPageIndex < pages - 1) dockPageIndex + 1 else 0
                                animateDockPageChange(next, fromRight = true)
                            }
                            dx > threshold && pages > 1 -> {
                                val prev = if (dockPageIndex > 0) dockPageIndex - 1 else pages - 1
                                animateDockPageChange(prev, fromRight = false)
                            }
                            else -> {
                                // bounce back
                                pager.getChildAt(0)?.animate()
                                    ?.translationX(0f)?.alpha(1f)
                                    ?.setDuration(160)
                                    ?.setInterpolator(DecelerateInterpolator())
                                    ?.start()
                            }
                        }
                        dockSwipeActive = false
                        true
                    } else {
                        false
                    }
                }
                else -> false
            }
        }
    }

    private fun animateDockPageChange(targetPage: Int, fromRight: Boolean) {
        val pager = binding.dockPager
        // Hard-clip so pages never expand the dock vertically
        pager.clipChildren = true
        pager.clipToPadding = true
        val width = pager.width.toFloat().coerceAtLeast(1f)
        val height = pager.height.coerceAtLeast(1)
        val density = resources.displayMetrics.density
        val iconPx = (48 * density * prefs.dockIconSizePercent / 100f).toInt()

        val outgoing = pager.getChildAt(0)
        val incoming = buildDockPage(targetPage, iconPx, density)
        // Force fixed size — never MATCH_PARENT height that could expand ancestors
        val lp = FrameLayout.LayoutParams(pager.width.coerceAtLeast(1), height)
        incoming.layoutParams = lp
        incoming.translationX = if (fromRight) width else -width
        incoming.alpha = 0.4f
        pager.addView(incoming)

        val outX = if (fromRight) -width else width
        outgoing?.animate()?.cancel()
        incoming.animate().cancel()

        outgoing?.animate()
            ?.translationX(outX)
            ?.alpha(0.2f)
            ?.setDuration(180)
            ?.setInterpolator(DecelerateInterpolator())
            ?.start()
        incoming.animate()
            .translationX(0f)
            .alpha(1f)
            .setDuration(200)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                dockPageIndex = targetPage
                // Rebuild clean single page (indicators etc.) without layout jump
                renderDock()
            }
            .start()
    }

    /** Soft highlight — MUST NOT use height=MATCH_PARENT inside vertical LinearLayout */
    private fun showBriefDockGlass() {
        val area = binding.dockArea
        val h = area.height.coerceAtLeast(1)
        val w = area.width.coerceAtLeast(1)
        if (h <= 1 || w <= 1) return
        val density = resources.displayMetrics.density
        val flash = View(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 20f * density
                setColor(0x33FFFFFF)
                setStroke((1.5f * density).toInt(), 0xCCFFFFFF.toInt())
            }
            alpha = 0f
        }
        // Explicit pixel size — prevents LinearLayout expansion flash
        area.addView(flash, LinearLayout.LayoutParams(w, h))
        flash.animate().alpha(1f).setDuration(70).withEndAction {
            flash.animate().alpha(0f).setDuration(160).withEndAction {
                area.removeView(flash)
            }.start()
        }.start()
    }

    private fun applyBottomBarOrder() {
        val bar = binding.bottomBar
        val dock = binding.dockArea
        val btn = binding.btnOpenDrawer
        bar.removeView(dock)
        bar.removeView(btn)
        if (prefs.dockAboveDrawerButton) {
            bar.addView(dock)
            bar.addView(btn)
        } else {
            bar.addView(btn)
            bar.addView(dock)
        }
        applyDrawerButtonVisibility()
    }

    private fun saveAllItems() {
        // Normalize dock slots
        dockItems.forEachIndexed { idx, item ->
            item.cellX = idx
            item.isDock = true
            item.screen = idx / DOCK_SLOTS
        }
        homeRepo.saveItems(homeItems + dockItems)
    }



    private fun applyDrawerButtonVisibility() {
        if (isDrawerOpen || isOverviewMode) {
            binding.btnOpenDrawer.visibility = View.INVISIBLE
        } else {
            binding.btnOpenDrawer.visibility =
                if (prefs.showDrawerButton) View.VISIBLE else View.GONE
        }
    }

    // ───────────────────────────── Overview ─────────────────────────────

    /** Reload prefs-driven UI without full process restart */
    private fun applySettingsFromPrefs() {
        columns = prefs.desktopColumns
        rows = prefs.desktopRows
        applyDrawerButtonVisibility()
        applyBottomBarOrder()
        binding.bottomBar.requestLayout()
        binding.desktopContainer.requestLayout()
        // Recalculate grid after dock / drawer-button visibility changes bottom space
        binding.desktopContainer.doOnLayout {
            calculateMetrics()
            renderDesktop()
            renderDock()
            applyWallpaperOffset()
        }
    }

    private fun createPageFrame(): View {
        val density = resources.displayMetrics.density
        return View(this).apply {
            tag = "page_frame"
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 18f * density
                setColor(0x10FFFFFF)
                setStroke((2.2f * density).toInt(), 0xD0FFFFFF.toInt())
            }
            isClickable = false
            isFocusable = false
        }
    }

    private fun enterOverviewMode() {
        if (isOverviewMode) return
        isOverviewMode = true
        binding.desktopContainer.animate().cancel()
        if (dualTransitionActive) clearDualLayers(keepIncoming = false)
        resetDesktopTransforms()
        // Hide dock + drawer button (blue zone in the screenshot)
        binding.bottomBar.visibility = View.GONE
        binding.btnOpenDrawer.visibility = View.INVISIBLE
        binding.homeRoot.requestLayout()
        binding.desktopContainer.doOnLayout {
            calculateMetrics()
            renderDesktop() // page frame matches full page area
            binding.desktopContainer.scaleX = 0.82f
            binding.desktopContainer.scaleY = 0.82f
        }
        binding.desktopContainer.animate()
            .scaleX(0.82f)
            .scaleY(0.82f)
            .alpha(1f)
            .translationX(0f)
            .rotationY(0f)
            .rotationX(0f)
            .setDuration(240)
            .setInterpolator(DecelerateInterpolator())
            .start()
        showOverviewChrome()
    }

    private fun exitOverviewMode() {
        if (!isOverviewMode) return
        isOverviewMode = false
        if (currentPage >= prefs.pageCount) {
            currentPage = (prefs.pageCount - 1).coerceAtLeast(0)
        }
        if (dualTransitionActive) clearDualLayers(keepIncoming = false)
        binding.desktopContainer.setOnClickListener(null)
        clearOverviewChrome()
        // Restore chrome first so metrics use normal bottom padding / dock area
        binding.bottomBar.visibility = View.VISIBLE
        applyDrawerButtonVisibility()
        applyBottomBarOrder()
        binding.desktopContainer.animate().cancel()
        binding.desktopContainer.scaleX = 1f
        binding.desktopContainer.scaleY = 1f
        binding.desktopContainer.translationX = 0f
        binding.desktopContainer.rotationY = 0f
        binding.desktopContainer.rotationX = 0f
        binding.desktopContainer.alpha = 1f
        resetDesktopTransforms()
        // Wait for layout after bottomBar is visible, then recompute grid & place icons
        binding.homeRoot.requestLayout()
        binding.desktopContainer.doOnLayout {
            calculateMetrics()
            renderDesktop()
            renderDock()
        }
        // Fallback if doOnLayout already fired with old size
        binding.desktopContainer.post {
            calculateMetrics()
            renderDesktop()
            renderDock()
        }
    }

    private fun clearOverviewChrome() {
        val toRemove = mutableListOf<View>()
        for (i in 0 until binding.homeRoot.childCount) {
            val c = binding.homeRoot.getChildAt(i)
            if (c.tag == "overview_chrome") toRemove.add(c)
        }
        toRemove.forEach { binding.homeRoot.removeView(it) }
        homeButtonView = null
        addPageButton = null
        overviewOverlay = null
    }

    private fun refreshOverviewChrome() {
        clearOverviewChrome()
        if (isOverviewMode) showOverviewChrome()
    }

    /**
     * Nova-style edit mode:
     * - Home icon TOP CENTER (filled = default page)
     * - Wallpaper + Settings BOTTOM CENTER
     * - Big "+" CENTER on virtual add-page
     * - Tap page exits edit mode
     * - Long-press → set default / delete page (no toast)
     *
     * Uses a full-screen FrameLayout overlay so Gravity works
     * (homeRoot is ConstraintLayout and ignores FrameLayout gravity).
     */
    private fun showOverviewChrome() {
        val density = resources.displayMetrics.density
        val root = binding.homeRoot
        val isAddPageScreen = currentPage >= prefs.pageCount

        // Full-screen FrameLayout overlay (ConstraintLayout child with 0dp + constraints)
        val overlay = FrameLayout(this).apply {
            tag = "overview_chrome"
            id = View.generateViewId()
        }
        val overlayLp = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams(
            0, 0
        ).apply {
            topToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            bottomToBottom = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
        }
        root.addView(overlay, overlayLp)

        // Page frame is drawn INSIDE desktopContainer (createPageFrame) so it
        // zooms and slides with the page — not a fixed overlay on homeRoot.

        // Tap empty area of overlay (not on buttons) exits edit mode
        overlay.setOnClickListener {
            if (!isAddPageScreen) exitOverviewMode()
        }
        overviewOverlay = overlay
        overlay.setOnLongClickListener {
            // Never open page menu while swiping between pages
            if (pageSwipeActive || suppressEmptyLongPress || dualTransitionActive || dockPageSwipeActive) {
                return@setOnLongClickListener true
            }
            if (!isAddPageScreen) showPageOptionsPopup(overlay)
            true
        }

        // Desktop: tap exits (icons don't launch in overview)
        binding.desktopContainer.setOnClickListener {
            if (isOverviewMode && !isAddPageScreen) exitOverviewMode()
        }

        // ── HOME button — TOP CENTER (below status bar, clear of page frame) ──
        if (!isAddPageScreen) {
            val isDefault = currentPage == prefs.defaultPage
            val homeBtn = ImageView(this).apply {
                tag = "overview_chrome"
                scaleType = ImageView.ScaleType.FIT_CENTER
                setImageResource(
                    if (isDefault) R.drawable.ic_overview_home_on
                    else R.drawable.ic_overview_home_off
                )
                contentDescription = "Página de inicio"
                setOnClickListener {
                    prefs.defaultPage = currentPage
                    refreshOverviewChrome()
                }
                setOnLongClickListener {
                    if (pageSwipeActive || suppressEmptyLongPress || dualTransitionActive) {
                        return@setOnLongClickListener true
                    }
                    showPageOptionsPopup(it)
                    true
                }
            }
            homeButtonView = homeBtn
            val size = (48 * density).toInt()
            val statusTop = ViewCompat.getRootWindowInsets(binding.homeRoot)
                ?.getInsets(WindowInsetsCompat.Type.statusBars())?.top ?: (28 * density).toInt()
            overlay.addView(homeBtn, FrameLayout.LayoutParams(size, size).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                // Sit clearly under the status bar and above the scaled page frame
                topMargin = statusTop + (12 * density).toInt()
            })
        }

        // ── ADD PAGE screen: big "+" in CENTER ──
        if (isAddPageScreen) {
            binding.desktopContainer.alpha = 0.2f
            val plus = TextView(this).apply {
                tag = "overview_chrome"
                text = "+"
                textSize = 56f
                gravity = Gravity.CENTER
                setTextColor(0xFF222222.toInt())
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(0xFFFFFFFF.toInt())
                }
                elevation = 14 * density
                setOnClickListener {
                    prefs.pageCount = prefs.pageCount + 1
                    currentPage = prefs.pageCount - 1
                    exitOverviewMode()
                    renderDesktop()
                }
            }
            addPageButton = plus
            val plusSize = (80 * density).toInt()
            overlay.addView(plus, FrameLayout.LayoutParams(plusSize, plusSize).apply {
                gravity = Gravity.CENTER
            })
        } else {
            binding.desktopContainer.alpha = 1f
        }

        // ── BOTTOM: Wallpaper + Settings (Nova style) ──
        val bottomBar = LinearLayout(this).apply {
            tag = "overview_chrome"
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            // Don't let bottom bar clicks bubble to overlay exit
            isClickable = true
        }
                fun bottomAction(iconRes: Int, label: String, action: () -> Unit): LinearLayout {
            return LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding((24 * density).toInt(), (8 * density).toInt(), (24 * density).toInt(), (8 * density).toInt())
                minimumWidth = (132 * density).toInt()
                addView(ImageView(this@HomeActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        (52 * density).toInt(), (52 * density).toInt()
                    ).apply {
                        gravity = Gravity.CENTER_HORIZONTAL
                        bottomMargin = (6 * density).toInt()
                    }
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    adjustViewBounds = true
                    setImageResource(iconRes)
                })
                addView(TextView(this@HomeActivity).apply {
                    text = label
                    textSize = 12.5f
                    gravity = Gravity.CENTER_HORIZONTAL
                    setTextColor(0xFFFFFFFF.toInt())
                    setShadowLayer(3f, 0f, 1f, 0x80000000.toInt())
                    maxLines = 2
                    isSingleLine = false
                    includeFontPadding = false
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { gravity = Gravity.CENTER_HORIZONTAL }
                })
                setOnClickListener { action() }
            }
        }
        bottomBar.addView(bottomAction(R.drawable.ic_overview_wallpaper, "Fondos de\npantalla") {
            exitOverviewMode()
            openWallpaperPicker()
        })
        bottomBar.addView(bottomAction(R.drawable.ic_overview_settings, "Ajustes") {
            exitOverviewMode()
            startActivity(Intent(this, SettingsActivity::class.java))
        })

        overlay.addView(
            bottomBar,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM
                bottomMargin = (36 * density).toInt()
            }
        )
    }

    private fun showPageOptionsPopup(anchor: View) {
        // Never show during page swipe / transition
        if (pageSwipeActive || suppressEmptyLongPress || dualTransitionActive) return
        if (currentPage >= prefs.pageCount) return
        dismissCurrentPopup()

        // Nova-style centered card (not a bottom-left PopupMenu)
        val density = resources.displayMetrics.density
        val dialog = android.app.Dialog(this, android.R.style.Theme_Translucent_NoTitleBar)
        dialog.setCancelable(true)
        dialog.setCanceledOnTouchOutside(true)

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 16f * density
                setColor(0xE61A2332.toInt()) // dark teal like Nova
            }
            elevation = 12 * density
            val padH = (20 * density).toInt()
            val padV = (8 * density).toInt()
            setPadding(padH, padV, padH, padV)
            minimumWidth = (180 * density).toInt()
        }

        fun menuRow(icon: String, label: String, onClick: () -> Unit): LinearLayout {
            return LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val pad = (14 * density).toInt()
                setPadding(pad, pad, pad, pad)
                isClickable = true
                isFocusable = true
                background = android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(0x33FFFFFF),
                    null,
                    android.graphics.drawable.ColorDrawable(0xFFFFFFFF.toInt())
                )
                addView(TextView(this@HomeActivity).apply {
                    text = icon
                    textSize = 18f
                    setPadding(0, 0, (14 * density).toInt(), 0)
                })
                addView(TextView(this@HomeActivity).apply {
                    text = label
                    textSize = 16f
                    setTextColor(0xFFFFFFFF.toInt())
                })
                setOnClickListener {
                    dialog.dismiss()
                    onClick()
                }
            }
        }

        card.addView(menuRow("⌂", "Página predeterminada") {
            prefs.defaultPage = currentPage
            refreshOverviewChrome()
        })

        if (prefs.pageCount > 1) {
            // Divider
            card.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (1 * density).toInt()
                ).apply {
                    topMargin = (2 * density).toInt()
                    bottomMargin = (2 * density).toInt()
                }
                setBackgroundColor(0x44FFFFFF)
            })
            card.addView(menuRow("✕", "Eliminar") {
                if (prefs.pageCount <= 1) return@menuRow
                val pageToDelete = currentPage
                homeItems.removeAll { it.screen == pageToDelete }
                homeItems.forEach { if (it.screen > pageToDelete) it.screen = it.screen - 1 }
                saveAllItems()
                prefs.pageCount = prefs.pageCount - 1
                if (prefs.defaultPage >= prefs.pageCount) {
                    prefs.defaultPage = (prefs.pageCount - 1).coerceAtLeast(0)
                }
                currentPage = currentPage.coerceIn(0, prefs.pageCount - 1)
                renderDesktop()
                refreshOverviewChrome()
            })
        }

        // WRAP_CONTENT centered card (not full-width)
        card.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        )
        dialog.setContentView(card)
        dialog.window?.apply {
            setLayout(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setGravity(Gravity.CENTER)
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0x00000000))
        }
        dialog.show()
    }


    // ───────────────────────────── Helpers ─────────────────────────────
    // ───────────────────────────── Helpers ─────────────────────────────
    // ───────────────────────────── Helpers ─────────────────────────────

    private fun dismissCurrentPopup() {
        try { currentPopup?.dismiss() } catch (_: Exception) {}
        currentPopup = null
        if (draggingApp == null) {
            // Don't clear mid-drag; cleanupDrag handles that
            // iconLongPressActive cleared on popup dismiss only if not dragging
        }
    }

    private fun isCellOccupied(x: Int, y: Int, excludeId: String? = null) =
        homeItems.any {
            it.screen == currentPage && it.cellX == x && it.cellY == y && it.id != excludeId
        }

    private fun findNearestFreeCell(fromX: Int, fromY: Int, excludeId: String? = null): Point? {
        for (radius in 0..maxOf(columns, rows)) {
            for (dy in -radius..radius) {
                for (dx in -radius..radius) {
                    if (abs(dx) != radius && abs(dy) != radius) continue
                    val nx = fromX + dx
                    val ny = fromY + dy
                    if (nx in 0 until columns && ny in 0 until rows && !isCellOccupied(nx, ny, excludeId)) {
                        return Point(nx, ny)
                    }
                }
            }
        }
        return null
    }

    private fun findFirstFreeCell(): Point? {
        for (y in 0 until rows) {
            for (x in 0 until columns) {
                if (!isCellOccupied(x, y)) return Point(x, y)
            }
        }
        return null
    }

    private fun isTouchOnDock(rawX: Float, rawY: Float): Boolean {
        if (!prefs.showDock || binding.dockArea.visibility != View.VISIBLE) return false
        // Whole bottom bar is exclusive dock gesture zone (prevents desktop page swipe)
        val barLoc = IntArray(2)
        binding.bottomBar.getLocationOnScreen(barLoc)
        if (rawY >= barLoc[1] - 12) return true
        val loc = IntArray(2)
        binding.dockArea.getLocationOnScreen(loc)
        return rawY >= loc[1] - 12 && rawY <= loc[1] + binding.dockArea.height + 16
    }

    private fun openNotificationShade() {
        try {
            val sb = getSystemService(android.content.Context.STATUS_BAR_SERVICE)
            val method = sb.javaClass.getMethod("expandNotificationsPanel")
            method.invoke(sb)
        } catch (_: Exception) {
            try {
                val sb = getSystemService(android.content.Context.STATUS_BAR_SERVICE)
                val method = sb.javaClass.getMethod("expandSettingsPanel")
                method.invoke(sb)
            } catch (_: Exception) { }
        }
    }

    private fun launchApp(app: AppInfo) {
        try {
            appRepo.launchApp(app)
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudo abrir la aplicación", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showDesktopAppOptions(app: AppInfo, item: HomeItem, anchor: View) {
        dismissCurrentPopup()
        val popup = PopupMenu(this, anchor, Gravity.END)
        popup.menu.add(0, 1, 0, "Quitar del escritorio")
        popup.menu.add(0, 2, 1, "Info de la app")
        popup.menu.add(0, 3, 2, "Desinstalar")
        popup.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                1 -> removeFromHomeWithUndo(item)
                2 -> openAppInfo(app)
                3 -> uninstallApp(app)
            }
            resetElevation(anchor)
            true
        }
        popup.setOnDismissListener {
            if (draggingApp == null) {
                resetElevation(anchor)
                iconLongPressActive = false
            }
            if (currentPopup == popup) currentPopup = null
        }
        currentPopup = popup
        popup.show()
    }

    private fun dockDropIndex(rawX: Float): Int {
        val loc = IntArray(2)
        binding.dockPager.getLocationOnScreen(loc)
        val localX = rawX - loc[0]
        val w = binding.dockPager.width.toFloat().coerceAtLeast(1f)
        val slots = DOCK_SLOTS
        val pageStart = dockPageIndex * DOCK_SLOTS
        val slot = ((localX / w) * slots).toInt().coerceIn(0, slots - 1)
        return (pageStart + slot).coerceIn(0, dockItems.size)
    }

    private fun addToDock(app: AppInfo, insertAt: Int = dockItems.size) {
        val item = HomeItem(
            id = UUID.randomUUID().toString(),
            packageName = app.packageName,
            activityName = app.activityName,
            label = app.label,
            cellX = insertAt,
            cellY = 0,
            screen = 0,
            isDock = true
        )
        val idx = insertAt.coerceIn(0, dockItems.size)
        dockItems.add(idx, item)
        dockItems.forEachIndexed { i, hi -> hi.cellX = i }
        saveAllItems()
        dockPageIndex = (idx / DOCK_SLOTS).coerceAtLeast(0)
        renderDock()
    }

    private fun mergeIntoFolder(occupant: HomeItem, incoming: HomeItem, app: AppInfo) {
        if (occupant.isFolder) {
            val already = occupant.folderMembers.any {
                it.packageName == app.packageName && it.activityName == app.activityName
            }
            if (!already) {
                occupant.folderMembers.add(
                    FolderMember(app.packageName, app.activityName, app.label)
                )
            }
            homeItems.removeAll { it.id == incoming.id }
        } else {
            val members = mutableListOf(
                FolderMember(occupant.packageName, occupant.activityName, occupant.label),
                FolderMember(app.packageName, app.activityName, app.label)
            )
            val folder = HomeItem(
                id = UUID.randomUUID().toString(),
                label = "Carpeta",
                screen = occupant.screen,
                cellX = occupant.cellX,
                cellY = occupant.cellY,
                isFolder = true,
                folderName = "Carpeta",
                folderMembers = members
            )
            homeItems.removeAll { it.id == occupant.id || it.id == incoming.id }
            homeItems.add(folder)
        }
        saveAllItems()
        renderDesktop()
    }

    private fun createFolderPreviewDrawable(item: HomeItem, size: Int): android.graphics.drawable.Drawable {
        val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        // Nova-like translucent plate
        val bg = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = size * 0.28f
            setColor(0xAA2A2A2A.toInt())
        }
        bg.setBounds(0, 0, size, size)
        bg.draw(canvas)
        val members = item.folderMembers.take(4)
        val pad = (size * 0.14f).toInt()
        val gap = (size * 0.05f).toInt()
        val cell = (size - pad * 2 - gap) / 2
        members.forEachIndexed { i, m ->
            val app = allApps.find { it.packageName == m.packageName && it.activityName == m.activityName }
            val icon = safeIcon(app?.icon) ?: return@forEachIndexed
            val col = i % 2
            val row = i / 2
            val left = pad + col * (cell + gap)
            val top = pad + row * (cell + gap)
            icon.setBounds(left, top, left + cell, top + cell)
            icon.draw(canvas)
        }
        return android.graphics.drawable.BitmapDrawable(resources, bmp)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createFolderIconView(item: HomeItem): View {
        val density = resources.displayMetrics.density
        val iconSize = if (desktopIconPx > 0) desktopIconPx else (56 * density).toInt()
        val labelHeight = (28 * density).toInt()
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            tag = item.id
            isClickable = true
            isFocusable = true
        }
        val image = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageDrawable(createFolderPreviewDrawable(item, iconSize))
            elevation = 2 * density
        }
        val label = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, labelHeight)
            text = item.folderName
            textSize = 11f
            setTextColor(resources.getColor(R.color.romaster_text_primary, theme))
            gravity = Gravity.CENTER
            maxLines = 2
            setShadowLayer(2f, 0f, 1f, 0x80000000.toInt())
        }
        root.addView(image)
        root.addView(label)

        var downX = 0f
        var downY = 0f
        var moved = false
        var longPressArmed = false
        var dragStarted = false
        val longPressRunnable = Runnable {
            if (!moved && draggingApp == null
                && !isDrawerOpen && !drawerGestureActive
                && !suppressIconInteractions && !suppressEmptyLongPress
            ) {
                longPressArmed = true
                elevateView(root)
                root.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            }
        }
        root.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (suppressIconInteractions || isDrawerOpen || drawerGestureActive) {
                        return@setOnTouchListener false
                    }
                    downX = event.rawX
                    downY = event.rawY
                    moved = false
                    longPressArmed = false
                    dragStarted = false
                    view.postDelayed(longPressRunnable, longPressTimeout)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dist = hypot((event.rawX - downX).toDouble(), (event.rawY - downY).toDouble()).toFloat()
                    if (dist > touchSlop) {
                        moved = true
                        if (!longPressArmed) {
                            view.removeCallbacks(longPressRunnable)
                        } else if (!dragStarted && draggingApp == null) {
                            dragStarted = true
                            val fake = AppInfo("", "", item.folderName, createFolderPreviewDrawable(item, iconSize))
                            draggingApp = fake
                            draggingHomeItem = item
                            view.visibility = View.INVISIBLE
                            dragShadow = ImageView(this).apply {
                                layoutParams = FrameLayout.LayoutParams(iconSize, iconSize)
                                setImageDrawable(createFolderPreviewDrawable(item, iconSize))
                            }
                            (binding.homeRoot as ViewGroup).addView(dragShadow)
                            moveDragShadow(event.rawX, event.rawY)
                            ensureDropOverlay()
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.removeCallbacks(longPressRunnable)
                    if (draggingHomeItem?.id == item.id) {
                        // endDrag via dispatch
                    } else if (!moved && !longPressArmed && !isOverviewMode) {
                        openFolder(item)
                    } else {
                        resetElevation(view)
                    }
                    true
                }
                else -> false
            }
        }
        return root
    }

    // Folder UI is an overlay on homeRoot (NOT a Dialog) so drag gestures are not cancelled.
    private var folderOverlay: FrameLayout? = null
    private var openFolderItem: HomeItem? = null
    private var folderPageIndex = 0
    private var folderDragCatcher: View? = null
    private var drawerScrollThumb: View? = null
    private var drawerScrollTrack: View? = null
    private var folderDragInProgress = false

    private fun setupDrawerScrollIndicator() {
        val parent = binding.appsGrid.parent as? FrameLayout
        if (parent == null) {
            // Fallback: still enable native bars
            binding.appsGrid.isVerticalScrollBarEnabled = true
            binding.appsGrid.isScrollbarFadingEnabled = false
            return
        }
        drawerScrollThumb?.let { (it.parent as? ViewGroup)?.removeView(it) }
        drawerScrollTrack?.let { (it.parent as? ViewGroup)?.removeView(it) }
        val density = resources.displayMetrics.density

        binding.appsGrid.isVerticalScrollBarEnabled = true
        binding.appsGrid.isScrollbarFadingEnabled = false
        binding.appsGrid.scrollBarStyle = View.SCROLLBARS_OUTSIDE_OVERLAY
        try { binding.appsGrid.setScrollbarFadingEnabled(false) } catch (_: Exception) {}

        val track = View(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 3f * density
                setColor(0x44FFFFFF)
            }
        }
        drawerScrollTrack = track
        val trackW = (4 * density).toInt()
        parent.addView(track, FrameLayout.LayoutParams(trackW, FrameLayout.LayoutParams.MATCH_PARENT).apply {
            gravity = Gravity.END
            topMargin = (8 * density).toInt()
            bottomMargin = (8 * density).toInt()
            marginEnd = (6 * density).toInt()
        })

        val thumb = View(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 3f * density
                setColor(0xFFFFFFFF.toInt())
                setStroke((1 * density).toInt(), 0xFF000000.toInt())
            }
            elevation = 12 * density
        }
        drawerScrollThumb = thumb
        parent.addView(thumb, FrameLayout.LayoutParams(trackW + 2, (64 * density).toInt()).apply {
            gravity = Gravity.END or Gravity.TOP
            topMargin = (8 * density).toInt()
            marginEnd = (5 * density).toInt()
        })
        track.bringToFront()
        thumb.bringToFront()

        binding.appsGrid.clearOnScrollListeners()
        binding.appsGrid.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: androidx.recyclerview.widget.RecyclerView, dx: Int, dy: Int) {
                updateDrawerScrollThumb()
            }
            override fun onScrollStateChanged(recyclerView: androidx.recyclerview.widget.RecyclerView, newState: Int) {
                updateDrawerScrollThumb()
            }
        })
        binding.appsGrid.viewTreeObserver.addOnGlobalLayoutListener {
            updateDrawerScrollThumb()
        }
        binding.appsGrid.post { updateDrawerScrollThumb() }
    }

    private fun updateDrawerScrollThumb() {
        val thumb = drawerScrollThumb ?: return
        val track = drawerScrollTrack
        val rv = binding.appsGrid
        if (rv.height <= 0) {
            rv.post { updateDrawerScrollThumb() }
            return
        }
        val range = rv.computeVerticalScrollRange()
        val extent = rv.computeVerticalScrollExtent()
        val offset = rv.computeVerticalScrollOffset()
        if (range <= extent + 4) {
            thumb.visibility = View.INVISIBLE
            track?.visibility = View.INVISIBLE
            return
        }
        thumb.visibility = View.VISIBLE
        track?.visibility = View.VISIBLE
        thumb.bringToFront()
        val density = resources.displayMetrics.density
        val visibleFrac = (extent.toFloat() / range.toFloat()).coerceIn(0.1f, 0.85f)
        val thumbH = (rv.height * visibleFrac).toInt().coerceIn((40 * density).toInt(), rv.height - 8)
        val lp = thumb.layoutParams
        if (lp.height != thumbH) {
            lp.height = thumbH
            thumb.layoutParams = lp
        }
        val trackH = (track?.height?.takeIf { it > 0 } ?: rv.height)
        val travel = (trackH - thumbH).coerceAtLeast(1)
        val ratio = if (range > extent) offset.toFloat() / (range - extent).toFloat() else 0f
        thumb.translationY = travel * ratio.coerceIn(0f, 1f)
    }

    private fun closeFolderOverlay() {
        folderOverlay?.let { ov ->
            try {
                ov.animate().cancel()
                ov.alpha = 1f
                ov.visibility = View.GONE
                (ov.parent as? ViewGroup)?.removeView(ov)
            } catch (_: Exception) {}
        }
        folderOverlay = null
        openFolderItem = null
        folderPageIndex = 0
        folderDragInProgress = false
        folderDragCatcher = null
    }

    private fun showFolderRenameDialog(item: HomeItem, titleView: TextView? = null) {
        val input = android.widget.EditText(this).apply {
            setText(item.folderName)
            setSelection(text.length)
            setSingleLine()
            hint = "Nombre de la carpeta"
            setPadding(48, 32, 48, 32)
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Editar nombre")
            .setView(input)
            .setPositiveButton("Guardar") { d, _ ->
                item.folderName = input.text.toString().ifBlank { "Carpeta" }
                titleView?.text = item.folderName
                if (item.id.startsWith("drawer_group_")) {
                    syncDrawerGroupFromFolder(item)
                } else {
                    saveAllItems()
                    renderDesktop()
                }
                d.dismiss()
            }
            .setNegativeButton("Cancelar", null)
            .show()
        input.post {
            input.requestFocus()
            val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun openFolder(item: HomeItem) {
        if (!item.isFolder) return
        // Drawer groups are virtual — never prune into desktop items
        if (!item.id.startsWith("drawer_group_")) {
            pruneFolder(item)
            if (item.folderMembers.isEmpty()) {
                renderDesktop()
                return
            }
        }
        closeFolderOverlay()
        // Remove any leftover folder overlays that might have lost their reference
        try {
            val hr = binding.homeRoot as ViewGroup
            for (i in hr.childCount - 1 downTo 0) {
                val c = hr.getChildAt(i)
                if (c.tag == "folder_overlay") {
                    hr.removeViewAt(i)
                }
            }
        } catch (_: Exception) {}
        openFolderItem = item
        folderPageIndex = 0

        val density = resources.displayMetrics.density
        val root = FrameLayout(this).apply {
            tag = "folder_overlay"
            setBackgroundColor(0x66000000.toInt())
            isClickable = true
            isFocusable = true
            // Dim tap closes folder; keep drawer open if it was open
            setOnClickListener {
                closeFolderOverlay()
                if (!isDrawerOpen) renderDesktop()
            }
        }
        folderOverlay = root

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // Nova-like glass: more translucent so wallpaper shows through
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 28f * density
                setColor(0xCC1C1C1E.toInt())
                setStroke((1f * density).toInt(), 0x33FFFFFF)
            }
            val pad = (16 * density).toInt()
            setPadding(pad, (18 * density).toInt(), pad, (12 * density).toInt())
            elevation = 24 * density
            isClickable = true
        }

        // Top bar: only the + button (Nova keeps name at bottom)
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        val addBtn = TextView(this).apply {
            text = "+"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(0x33FFFFFF)
                setStroke((1.5f * density).toInt(), 0x88FFFFFF.toInt())
            }
            val s = (34 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(s, s)
            setOnClickListener { showFolderAppPicker(item) }
        }
        topBar.addView(addBtn)
        panel.addView(topBar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = (4 * density).toInt() })

        val title = TextView(this).apply {
            text = item.folderName.ifBlank { "Carpeta" }
            setTextColor(0xCCFFFFFF.toInt())
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, (6 * density).toInt(), 0, (4 * density).toInt())
            setOnClickListener { showFolderRenameDialog(item, this) }
        }

        val usePages = prefs.folderScrollMode == "pages" || prefs.folderScrollMode == "horizontal"
        val cols = prefs.folderColumns.coerceIn(2, 6)
        val rows = prefs.folderRows.coerceIn(2, 6)
        val members = item.folderMembers.sortedBy { it.label.lowercase() }
        val pageSize = cols * rows
        val pages = if (usePages) ((members.size + pageSize - 1) / pageSize).coerceAtLeast(1) else 1

        val contentH = (minOf(resources.displayMetrics.heightPixels * 0.55f, 420 * density)).toInt()
        val panelW = (resources.displayMetrics.widthPixels * 0.92f).toInt()

        val iconSize = (((panelW - 28 * density) / cols) * 0.55f).toInt()
            .coerceIn((40 * density).toInt(), (64 * density).toInt())

        fun buildGridForMembers(slice: List<FolderMember>): View {
            val grid = android.widget.GridLayout(this).apply {
                columnCount = cols
                if (usePages) rowCount = rows
            }
            for (m in slice) {
                val app = allApps.find {
                    it.packageName == m.packageName && it.activityName == m.activityName
                } ?: continue
                val cell = makeFolderAppCell(app, item, density, iconSize)
                val lp = android.widget.GridLayout.LayoutParams().apply {
                    width = 0
                    height = android.widget.GridLayout.LayoutParams.WRAP_CONTENT
                    columnSpec = android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f)
                    setMargins((4 * density).toInt(), (4 * density).toInt(), (4 * density).toInt(), (4 * density).toInt())
                }
                grid.addView(cell, lp)
            }
            return grid
        }

        val indicators = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        if (usePages) {
            // Dock-style paging with intercept: swipe works even when started on an icon
            val pageW = (panelW - (28 * density).toInt()).coerceAtLeast(1)
            val touchSlopPager = ViewConfiguration.get(this).scaledTouchSlop

            // Forward-declared for intercept pager
            var folderPagerRef: FrameLayout? = null

            fun buildFolderPage(page: Int): View {
                val slice = members.drop(page * pageSize).take(pageSize)
                return FrameLayout(this@HomeActivity).apply {
                    layoutParams = FrameLayout.LayoutParams(pageW, contentH)
                    addView(
                        buildGridForMembers(slice),
                        FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT
                        )
                    )
                }
            }

            fun refreshDots(active: Int) {
                indicators.removeAllViews()
                if (pages <= 1) {
                    indicators.visibility = View.GONE
                    return
                }
                indicators.visibility = View.VISIBLE
                for (i in 0 until pages) {
                    val selected = i == active
                    val dot = View(this@HomeActivity).apply {
                        background = android.graphics.drawable.GradientDrawable().apply {
                            shape = android.graphics.drawable.GradientDrawable.OVAL
                            setColor(if (selected) 0xFFFFFFFF.toInt() else 0x66FFFFFF)
                        }
                    }
                    val ds = ((if (selected) 8 else 6) * density).toInt()
                    indicators.addView(dot, LinearLayout.LayoutParams(ds, ds).apply {
                        marginStart = (4 * density).toInt()
                        marginEnd = (4 * density).toInt()
                    })
                }
            }

            fun goToFolderPage(target: Int, fromRight: Boolean) {
                val pager = folderPagerRef ?: return
                if (target == folderPageIndex || target < 0 || target >= pages) return
                val width = pageW.toFloat()
                val outgoing = pager.getChildAt(0)
                val incoming = buildFolderPage(target)
                incoming.translationX = if (fromRight) width else -width
                incoming.alpha = 0.45f
                pager.addView(incoming)
                folderPageIndex = target
                refreshDots(target)
                val outX = if (fromRight) -width else width
                outgoing?.animate()?.cancel()
                incoming.animate().cancel()
                outgoing?.animate()
                    ?.translationX(outX)
                    ?.alpha(0.2f)
                    ?.setDuration(180)
                    ?.setInterpolator(DecelerateInterpolator())
                    ?.start()
                incoming.animate()
                    .translationX(0f)
                    .alpha(1f)
                    .setDuration(200)
                    .setInterpolator(DecelerateInterpolator())
                    .withEndAction {
                        val keep = incoming
                        val remove = mutableListOf<View>()
                        for (i in 0 until pager.childCount) {
                            val c = pager.getChildAt(i)
                            if (c !== keep) remove.add(c)
                        }
                        remove.forEach { pager.removeView(it) }
                        keep.translationX = 0f
                        keep.alpha = 1f
                        refreshDots(folderPageIndex)
                    }
                    .start()
            }

            val pager = object : FrameLayout(this@HomeActivity) {
                var downX = 0f
                var downY = 0f
                var intercepting = false
                var tracking = false

                init {
                    clipChildren = true
                    clipToPadding = true
                }

                override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
                    // Let long-press / drag of icons win
                    if (iconLongPressActive || currentPopup != null || draggingApp != null) {
                        intercepting = false
                        return false
                    }
                    when (ev.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            downX = ev.rawX
                            downY = ev.rawY
                            intercepting = false
                            tracking = true
                            // Don't intercept yet — children get the DOWN
                            return false
                        }
                        MotionEvent.ACTION_MOVE -> {
                            if (!tracking) return false
                            val dx = ev.rawX - downX
                            val dy = ev.rawY - downY
                            if (abs(dx) > touchSlopPager && abs(dx) > abs(dy) * 1.15f) {
                                intercepting = true
                                // Cancel child long-press arming
                                parent?.requestDisallowInterceptTouchEvent(true)
                                return true
                            }
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            tracking = false
                            intercepting = false
                        }
                    }
                    return intercepting
                }

                override fun onTouchEvent(ev: MotionEvent): Boolean {
                    if (iconLongPressActive || currentPopup != null || draggingApp != null) {
                        return super.onTouchEvent(ev)
                    }
                    when (ev.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            downX = ev.rawX
                            downY = ev.rawY
                            tracking = true
                            intercepting = false
                            return true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val dx = ev.rawX - downX
                            val dy = ev.rawY - downY
                            if (!intercepting && abs(dx) > touchSlopPager && abs(dx) > abs(dy) * 1.15f) {
                                intercepting = true
                            }
                            if (intercepting || tracking) {
                                val page = getChildAt(0)
                                val w = pageW.toFloat()
                                page?.translationX = dx * 0.7f
                                page?.alpha = (1f - abs(dx) / (w * 1.4f)).coerceIn(0.4f, 1f)
                                return true
                            }
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            if (!tracking && !intercepting) {
                                return super.onTouchEvent(ev)
                            }
                            val dx = ev.rawX - downX
                            val was = intercepting
                            tracking = false
                            intercepting = false
                            if (!was) {
                                // Tap fell through after intercept false — let click work
                                getChildAt(0)?.animate()?.translationX(0f)?.alpha(1f)
                                    ?.setDuration(100)?.start()
                                return false
                            }
                            val threshold = pageW * 0.12f
                            when {
                                pages > 1 && dx < -threshold && folderPageIndex < pages - 1 ->
                                    goToFolderPage(folderPageIndex + 1, fromRight = true)
                                pages > 1 && dx > threshold && folderPageIndex > 0 ->
                                    goToFolderPage(folderPageIndex - 1, fromRight = false)
                                else -> {
                                    getChildAt(0)?.animate()
                                        ?.translationX(0f)?.alpha(1f)
                                        ?.setDuration(150)
                                        ?.setInterpolator(DecelerateInterpolator())
                                        ?.start()
                                }
                            }
                            return true
                        }
                    }
                    return super.onTouchEvent(ev)
                }
            }
            folderPagerRef = pager

            folderPageIndex = 0
            pager.addView(buildFolderPage(0))
            refreshDots(0)

            panel.addView(pager, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, contentH
            ))
        } else {
            val scroll = android.widget.ScrollView(this).apply {
                isVerticalScrollBarEnabled = true
                isScrollbarFadingEnabled = false
            }
            scroll.addView(buildGridForMembers(members), ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            panel.addView(scroll, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, contentH
            ))
            indicators.visibility = View.GONE
        }

        panel.addView(indicators, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (8 * density).toInt() })

        // Nova: folder name editor at the bottom
        panel.addView(title, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (6 * density).toInt() })

        val panelLp = FrameLayout.LayoutParams(
            panelW,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        )
        root.addView(panel, panelLp)
        // Must sit ABOVE the drawer (drawer elevation is 24dp)
        val z = 64f * density
        root.elevation = z
        root.translationZ = z
        (binding.homeRoot as ViewGroup).addView(
            root,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        root.bringToFront()
        binding.homeRoot.invalidate()
        panel.scaleX = 0.85f
        panel.scaleY = 0.85f
        panel.alpha = 0f
        panel.animate().scaleX(1f).scaleY(1f).alpha(1f)
            .setDuration(200)
            .setInterpolator(OvershootInterpolator(1.05f))
            .start()
    }

    private fun pruneFolder(item: HomeItem) {
        if (!item.isFolder) return
        if (item.id.startsWith("drawer_group_")) {
            syncDrawerGroupFromFolder(item)
            return
        }
        item.folderMembers.removeAll { m ->
            allApps.none { it.packageName == m.packageName && it.activityName == m.activityName }
        }
        when {
            item.folderMembers.isEmpty() -> homeItems.removeAll { it.id == item.id }
            item.folderMembers.size == 1 -> {
                val last = item.folderMembers[0]
                homeItems.removeAll { it.id == item.id }
                homeItems.add(
                    HomeItem(
                        id = UUID.randomUUID().toString(),
                        packageName = last.packageName,
                        activityName = last.activityName,
                        label = last.label,
                        screen = item.screen,
                        cellX = item.cellX,
                        cellY = item.cellY
                    )
                )
            }
        }
    }

    private fun pruneAllFolders() {
        homeItems.filter { it.isFolder }.toList().forEach { pruneFolder(it) }
    }

    private fun showFolderAppPicker(folder: HomeItem) {
        val density = resources.displayMetrics.density
        val apps = allApps.sortedBy { it.label.lowercase() }
        val selected = folder.folderMembers
            .map { "${it.packageName}/${it.activityName}" }
            .toMutableSet()
        val checked = BooleanArray(apps.size) { i ->
            selected.contains("${apps[i].packageName}/${apps[i].activityName}")
        }

        val scroll = android.widget.ScrollView(this).apply {
            setBackgroundColor(0xFF1A1A1A.toInt())
            isVerticalScrollBarEnabled = true
            isScrollbarFadingEnabled = false
        }
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((12 * density).toInt(), (8 * density).toInt(), (12 * density).toInt(), (8 * density).toInt())
        }
        scroll.addView(list)
        val iconSize = (40 * density).toInt()
        val pad = (10 * density).toInt()

        apps.forEachIndexed { i, app ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(pad, pad, pad, pad)
                isClickable = true
                isFocusable = true
            }
            row.addView(ImageView(this).apply {
                setImageDrawable(safeIcon(app.icon))
                layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                    marginEnd = (14 * density).toInt()
                }
                scaleType = ImageView.ScaleType.FIT_CENTER
            })
            row.addView(TextView(this).apply {
                text = app.label
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 15f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            val cb = android.widget.CheckBox(this).apply {
                isChecked = checked[i]
                setOnCheckedChangeListener { _, isChk -> checked[i] = isChk }
            }
            row.addView(cb)
            row.setOnClickListener { cb.isChecked = !cb.isChecked }
            list.addView(row)
        }

        android.app.AlertDialog.Builder(this)
            .setTitle(folder.folderName.ifBlank { "Carpeta" })
            .setView(scroll)
            .setPositiveButton("Guardar") { d, _ ->
                folder.folderMembers.clear()
                for (i in apps.indices) {
                    if (checked[i]) {
                        val a = apps[i]
                        folder.folderMembers.add(
                            FolderMember(a.packageName, a.activityName, a.label)
                        )
                    }
                }
                if (folder.id.startsWith("drawer_group_")) {
                    syncDrawerGroupFromFolder(folder)
                } else {
                    saveAllItems()
                    renderDesktop()
                }
                closeFolderOverlay()
                openFolder(folder)
                d.dismiss()
            }
            .setNegativeButton("Cancelar") { d, _ -> d.dismiss() }
            .show()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun makeFolderAppCell(
        app: AppInfo,
        folder: HomeItem,
        density: Float,
        iconSize: Int
    ): View {
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val p = (6 * density).toInt()
            setPadding(p, p, p, p)
            isClickable = true
            isFocusable = true
        }
        cell.addView(ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
            setImageDrawable(safeIcon(app.icon))
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
        })
        cell.addView(TextView(this).apply {
            text = app.label
            textSize = 11f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            maxLines = 2
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        })

        cell.setOnClickListener {
            if (draggingApp != null || currentPopup != null) return@setOnClickListener
            closeFolderOverlay()
            launchApp(app)
        }

        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        val longTimeout = ViewConfiguration.getLongPressTimeout().toLong()
        var downX = 0f
        var downY = 0f
        var armed = false
        var dragging = false
        val arm = Runnable {
            if (draggingApp != null) return@Runnable
            armed = true
            iconLongPressActive = true
            try {
                cell.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                elevateView(cell)
                // Stop parent folder scroll/paging from racing with drag
                cell.parent?.requestDisallowInterceptTouchEvent(true)
                (cell.parent as? View)?.parent?.requestDisallowInterceptTouchEvent(true)
                showFolderAppOptions(app, folder, cell)
            } catch (_: Exception) {}
        }
        cell.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX
                    downY = ev.rawY
                    armed = false
                    dragging = false
                    v.postDelayed(arm, longTimeout)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downX
                    val dy = ev.rawY - downY
                    val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()
                    if (!armed && dist > touchSlop) {
                        v.removeCallbacks(arm)
                        // Horizontal → let folder pager intercept for page change
                        if (abs(dx) > abs(dy) * 1.15f) {
                            // Do NOT disallow parent intercept
                            return@setOnTouchListener false
                        }
                    }
                    // Long-press shown menu → move = enter drag, close menu
                    if (armed && !dragging && dist > touchSlop && this.draggingApp == null) {
                        dragging = true
                        iconLongPressActive = true
                        dismissCurrentPopup()
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                        (v.parent as? View)?.parent?.requestDisallowInterceptTouchEvent(true)
                        startFolderDesktopDrag(app, folder, ev.rawX, ev.rawY)
                        return@setOnTouchListener true
                    }
                    if (dragging || this.draggingApp != null) return@setOnTouchListener true
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.removeCallbacks(arm)
                    if (ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                        // Parent intercepted for page swipe — don't launch
                        if (!armed && !dragging) resetElevation(v)
                        return@setOnTouchListener true
                    }
                    if (dragging || this.draggingApp != null) {
                        return@setOnTouchListener true
                    }
                    if (armed && currentPopup != null) {
                        return@setOnTouchListener true
                    }
                    val dist = hypot((ev.rawX - downX).toDouble(), (ev.rawY - downY).toDouble()).toFloat()
                    if (!armed && !dragging && dist < touchSlop * 2 &&
                        this.draggingApp == null && currentPopup == null
                    ) {
                        resetElevation(v)
                        closeFolderOverlay()
                        launchApp(app)
                        return@setOnTouchListener true
                    }
                    resetElevation(v)
                    true
                }
                else -> false
            }
        }
        return cell
    }

    /**
     * Folder → desktop drag using the SAME pipeline as desktop icons
     * (draggingApp + dragShadow + dispatchTouchEvent MOVE/UP).
     * Folder overlay is only hidden, not destroyed, until drop completes.
     */
    private fun startFolderDesktopDrag(
        app: AppInfo,
        folder: HomeItem,
        rawX: Float,
        rawY: Float
    ) {
        if (draggingApp != null) return
        dismissCurrentPopup()

        try {
            folder.folderMembers.removeAll {
                it.packageName == app.packageName && it.activityName == app.activityName
            }
            if (folder.id.startsWith("drawer_group_")) {
                syncDrawerGroupFromFolder(folder)
            } else {
                pruneFolder(folder)
                saveAllItems()
            }
        } catch (_: Exception) {}

        // Hide folder visually but keep hierarchy stable until drop ends
        folderOverlay?.alpha = 0f
        folderOverlay?.isClickable = false

        calculateMetrics()
        // Do NOT renderDesktop yet — wait until drop to avoid jank mid-drag

        draggingApp = app
        draggingHomeItem = null
        suppressEmptyLongPress = true
        folderDragInProgress = true

        val shadow = createDragShadow(app)
        dragShadow = shadow
        shadow.scaleX = 1.14f
        shadow.scaleY = 1.14f
        (binding.homeRoot as ViewGroup).addView(shadow)
        moveDragShadow(rawX, rawY)
        ensureDropOverlay()
        updateDropHighlight(rawX, rawY)
    }

    private fun showFolderAppOptions(app: AppInfo, folder: HomeItem, anchor: View) {
        dismissCurrentPopup()
        val popup = PopupMenu(this, anchor, Gravity.END)
        popup.menu.add(0, 1, 0, "Quitar de la carpeta")
        popup.menu.add(0, 2, 1, "Desagrupar (al escritorio)")
        popup.menu.add(0, 3, 2, "Info de la app")
        popup.menu.add(0, 4, 3, "Desinstalar")
        popup.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                1 -> {
                    folder.folderMembers.removeAll {
                        it.packageName == app.packageName && it.activityName == app.activityName
                    }
                    if (folder.id.startsWith("drawer_group_")) {
                        syncDrawerGroupFromFolder(folder)
                        closeFolderOverlay()
                        openFolder(folder)
                    } else {
                        pruneFolder(folder)
                        saveAllItems()
                        closeFolderOverlay()
                        renderDesktop()
                        if (folder.isFolder && folder.folderMembers.size > 1) openFolder(folder)
                    }
                }
                2 -> {
                    folder.folderMembers.removeAll {
                        it.packageName == app.packageName && it.activityName == app.activityName
                    }
                    val free = findFirstFreeCell()
                    if (free != null) {
                        homeItems.add(
                            HomeItem(
                                id = UUID.randomUUID().toString(),
                                packageName = app.packageName,
                                activityName = app.activityName,
                                label = app.label,
                                screen = currentPage,
                                cellX = free.x,
                                cellY = free.y
                            )
                        )
                    }
                    if (folder.id.startsWith("drawer_group_")) {
                        syncDrawerGroupFromFolder(folder)
                    } else {
                        pruneFolder(folder)
                    }
                    saveAllItems()
                    closeFolderOverlay()
                    renderDesktop()
                    if (folder.isFolder && folder.folderMembers.isNotEmpty()) openFolder(folder)
                }
                3 -> openAppInfo(app)
                4 -> uninstallApp(app)
            }
            true
        }
        popup.setOnDismissListener {
            if (draggingApp == null) {
                resetElevation(anchor)
                iconLongPressActive = false
            }
            if (currentPopup == popup) currentPopup = null
        }
        currentPopup = popup
        popup.show()
    }

    private fun showDrawerAppOptions(app: AppInfo, anchor: View) {
        dismissCurrentPopup()
        val popup = PopupMenu(this, anchor, Gravity.END)
        popup.menu.add(0, 1, 0, "Ocultar")
        popup.menu.add(0, 2, 1, "Info de la app")
        popup.menu.add(0, 3, 2, "Desinstalar")
        popup.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                1 -> {
                    prefs.setHidden(app.packageName, true)
                    submitDrawerApps(allApps)
                    Toast.makeText(this, "Oculta: ${app.label}", Toast.LENGTH_SHORT).show()
                }
                2 -> openAppInfo(app)
                3 -> uninstallApp(app)
            }
            anchor.animate().scaleX(1f).scaleY(1f).translationZ(0f).setDuration(100).start()
            true
        }
        popup.setOnDismissListener {
            if (draggingApp == null) {
                anchor.animate().scaleX(1f).scaleY(1f).translationZ(0f).setDuration(100).start()
            }
            if (currentPopup == popup) currentPopup = null
        }
        currentPopup = popup
        popup.show()
    }

    private fun removeFromHomeWithUndo(item: HomeItem) {
        lastRemovedItem = item
        homeItems.removeAll { it.id == item.id }
        saveAllItems()
        renderDesktop()
        Snackbar.make(binding.homeRoot, "Elemento eliminado", Snackbar.LENGTH_LONG)
            .setAction("Deshacer") {
                lastRemovedItem?.let { restored ->
                    if (homeItems.none { it.id == restored.id }) {
                        homeItems.add(restored)
                        saveAllItems()
                        renderDesktop()
                    }
                }
                lastRemovedItem = null
            }.show()
    }

    private fun openAppInfo(app: AppInfo) {
        startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = android.net.Uri.parse("package:${app.packageName}")
        })
    }

    private fun uninstallApp(app: AppInfo) {
        startActivity(Intent(Intent.ACTION_DELETE).apply {
            data = android.net.Uri.parse("package:${app.packageName}")
        })
    }

    private fun openWallpaperPicker() {
        try {
            startActivity(
                Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), getString(R.string.change_wallpaper))
            )
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudo abrir el selector de fondos", Toast.LENGTH_SHORT).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            isOverviewMode -> exitOverviewMode()
            isDrawerOpen || binding.drawerPanel.visibility == View.VISIBLE -> closeDrawerAnimated()
        }
    }

    override fun onResume() {
        super.onResume()
        applySettingsFromPrefs()
        lifecycleScope.launch {
            allApps = appRepo.loadApps(force = false)
            val allItems = homeRepo.loadItems()
            homeItems = allItems.filter { !it.isDock }.toMutableList()
            dockItems = allItems.filter { it.isDock }.toMutableList()
            submitDrawerApps(allApps)
            calculateMetrics()
            // Clamp icons into current grid after settings change
            var changed = false
            for (item in homeItems) {
                val nx = item.cellX.coerceIn(0, (columns - 1).coerceAtLeast(0))
                val ny = item.cellY.coerceIn(0, (rows - 1).coerceAtLeast(0))
                if (nx != item.cellX || ny != item.cellY) {
                    item.cellX = nx
                    item.cellY = ny
                    changed = true
                }
            }
            if (changed) saveAllItems()
            currentPage = currentPage.coerceIn(0, (prefs.pageCount - 1).coerceAtLeast(0))
            if (cellWidth > 0) renderDesktop()
        }
    }
}
