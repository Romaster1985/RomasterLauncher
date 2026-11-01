package com.romaster.launcher.ui

import android.annotation.SuppressLint
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Intent
import android.graphics.Point
import android.graphics.Matrix
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
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
import android.view.animation.AccelerateInterpolator
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
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.snackbar.Snackbar
import com.romaster.launcher.R
import com.romaster.launcher.RomasterApp
import com.romaster.launcher.data.AppInfo
import com.romaster.launcher.data.HomeItem
import com.romaster.launcher.data.FolderMember
import com.romaster.launcher.data.StackWidgetMember
import com.romaster.launcher.data.LauncherPrefs
import com.romaster.launcher.data.FileLogger
import com.romaster.launcher.data.ReactionGlobalsStore
import com.romaster.launcher.data.ReactionJson
import com.romaster.launcher.data.ReactionRecipe
import com.romaster.launcher.data.TriggerKind
import com.romaster.launcher.data.BuiltInWallpapers
import com.romaster.launcher.data.DrawerGroupStore
import com.romaster.launcher.databinding.ActivityHomeBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding
    private lateinit var prefs: LauncherPrefs
    private lateinit var appWidgetManager: AppWidgetManager
    private lateinit var appWidgetHost: AppWidgetHost
    private val APPWIDGET_HOST_ID = 0x524F4D53 // "ROMS"
    /** One AppWidgetHostView per id — createView only once */
    private val widgetHostViews = mutableMapOf<Int, android.appwidget.AppWidgetHostView>()
    private var pendingWidgetInfo: AppWidgetProviderInfo? = null
    private var pendingWidgetSpanX = 1
    private var pendingWidgetSpanY = 1
    private var pendingPlaceCellX = -1
    private var pendingPlaceCellY = -1
    private var pendingDragWidgetInfo: AppWidgetProviderInfo? = null
    private var pendingDragSpanX = 1
    private var pendingDragSpanY = 1
    /** "stack" | "gif" when dragging a Romaster custom widget from the picker */
    private var pendingDragCustomType: String? = null
    private var pendingGifItemId: String? = null
    private var resizingWidgetId: String? = null
    private var widgetResizeFrame: FrameLayout? = null
    private lateinit var drawerGroupStore: DrawerGroupStore
    private lateinit var drawerAdapter: AppIconAdapter

    private val appRepo by lazy { (application as RomasterApp).appRepository }
    private val homeRepo by lazy { (application as RomasterApp).homeLayoutRepository }

    private var allApps: List<AppInfo> = emptyList()
    private var appByKey: Map<String, AppInfo> = emptyMap()
    private var homeItems: MutableList<HomeItem> = mutableListOf()
    private var dockItems: MutableList<HomeItem> = mutableListOf()
    /**
     * When false, onResume skips removeAllViews + full rebuild (instant return from apps).
     * Set true after layout changes, item edits, page rebuild needs, or cold start.
     */
    private var desktopNeedsFullRender = true
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
    /** Relative drag: cell under finger moves when finger travels one cell of pixels. */
    private var dragOriginCellX = 0
    private var dragOriginCellY = 0
    private var dragStartRawX = 0f
    private var dragStartRawY = 0f
    /** Span locked at drag start — must not change on drop (was expanding to full grid). */
    private var dragOriginSpanX = 1
    private var dragOriginSpanY = 1
    /**
     * Grab point inside the drag shadow (px from shadow top-left to finger).
     * Shadow follows the finger by this offset — does NOT jump to center under finger.
     */
    private var dragGrabOffsetX = 0f
    private var dragGrabOffsetY = 0f
    /** Real widget view being dragged in free-placement mode (stays visible). */
    private var draggingWidgetView: View? = null
    private val reactionEngine = ReactionEngine()
    private val globalBoolListener = ReactionGlobalsStore.OnBoolChanged { name, value ->
        onGlobalBoolChanged(name, value)
    }
    private var isDrawerOpen = false
    /** When true, the app drawer shows the widget browser instead of app icons. */
    private var drawerShowingWidgets = false
    private var drawerWidgetScrollView: android.widget.ScrollView? = null
    private var drawerWidgetFilter: ((String) -> Unit)? = null
    private var currentPopup: PopupMenu? = null
    /** Custom floating menu for widget stacks (PopupMenu cancels the touch stream). */
    private var stackMenuView: View? = null
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
    /** True when ACTION_DOWN landed on a Widget Stack — blocks drawer / notifications / page swipe. */
    private var touchStartedOnWidgetStack = false
    /** True when DOWN landed on GIF/IMG with swipe reaction triggers — blocks drawer/page/notifications. */
    private var touchStartedOnGifSwipe = false
    /** Ignore global-bool reaction fires until this elapsedRealtime (unlock grace). */
    private var globalTriggerGraceUntil = 0L
    private var dockPageSwipeActive = false
    /** Dual-page transition layers (origin + destination coexist) */
    private var outgoingPageLayer: FrameLayout? = null
    private var incomingPageLayer: FrameLayout? = null
    private var dualTransitionDir = 0 // -1 next (finger left), +1 prev (finger right)
    private var dualTransitionActive = false
    private var suppressWidgetSizeOptions = false
    /** Auto-recover broken widgets (standalone + stack members) after cold start / bind fail */
    private val widgetRecoverPending = mutableSetOf<String>()
    private val widgetRecoverAttempts = mutableMapOf<String, Int>()

    private var pageTouchStartX = 0f
    private var pageTouchStartY = 0f
    private var pageStartTranslation = 0f
    private var edgePageFlipRunnable: Runnable? = null
    private var pendingEdgeDir: Int = 0 // -1 left, +1 right
    private var homeButtonView: View? = null
    private var overviewOverlay: FrameLayout? = null
    private var addPageButton: TextView? = null

    private var isOverviewMode = false
    /** Keeps overview padding/frame until exit zoom finishes (avoids frame jump). */
    private var overviewLayoutActive = false
    private var lastOverviewChromeAddPage = false
    private var unlockReceiverRegistered = false
    /** True after SCREEN_OFF pre-posed icons/widgets/dock at animation start. */
    private var unlockAnimPrepared = false
    /** Avoid double-play when USER_PRESENT and SCREEN_ON fire close together. */
    private var unlockAnimPlaying = false
    private var unlockSafetyRunnable: Runnable? = null
    private var unlockPlayStartedAt = 0L
    private var screenWasOff = false
    private var lastUnlockAnimAt = 0L
    private val unlockReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            val action = intent?.action ?: return
            val kg = try { isKeyguardLocked() } catch (_: Exception) { false }
            FileLogger.log(
                "Unlock",
                "broadcast action=$action keyguard=$kg prepared=$unlockAnimPrepared playing=$unlockAnimPlaying screenWasOff=$screenWasOff"
            )
            when (action) {
                android.content.Intent.ACTION_SCREEN_OFF -> {
                    screenWasOff = true
                    if (prefs.unlockAnimationsEnabled) {
                        binding.homeRoot.post { prepareUnlockAnimationState() }
                    }
                }
                android.content.Intent.ACTION_SCREEN_ON -> {
                    if (!prefs.unlockAnimationsEnabled) return
                    if (kg) {
                        binding.homeRoot.post { prepareUnlockAnimationState() }
                    } else if (unlockAnimPrepared && !unlockAnimPlaying) {
                        binding.homeRoot.post { playUnlockAnimations() }
                    }
                }
                android.content.Intent.ACTION_USER_PRESENT -> {
                    if (!prefs.unlockAnimationsEnabled) {
                        binding.homeRoot.post { forceUnlockSettled("disabled-on-present") }
                        return
                    }
                    binding.homeRoot.post {
                        if (unlockAnimPlaying && unlockPlayStartedAt > 0L &&
                            System.currentTimeMillis() - unlockPlayStartedAt < 800L
                        ) {
                            FileLogger.log("Unlock", "USER_PRESENT ignored — play in progress")
                            return@post
                        }
                        if (unlockAnimPlaying) {
                            forceUnlockSettled("stuck-before-present")
                        }
                        if (!unlockAnimPrepared) prepareUnlockAnimationState()
                        playUnlockAnimations()
                    }
                }
            }
        }
    }
    private var currentPage = 0

    // Suppress empty long-press while gesturing
    private var suppressEmptyLongPress = false
    /** Blocks desktop/dock icon long-press & click while drawer gesture runs / settles */
    private var suppressIconInteractions = false
    private var releaseIconBlockRunnable: Runnable? = null
    /** True while an icon long-press menu is open or arming drag — blocks page swipes */
    private var iconLongPressActive = false

    private val bindWidgetLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        // Restore pending state from prefs if process/activity lost fields during the system dialog
        restorePendingWidgetBindFromPrefs()
        restorePendingStackQueueFromPrefs()
        var id = pendingBindWidgetId
        var info = pendingWidgetInfo
        FileLogger.log(
            "WidgetBind",
            "bindResult code=${result.resultCode} ok=${result.resultCode == RESULT_OK} " +
                "id=$id info=${info?.provider} data=${result.data}"
        )
        // Some OEMs return non-OK even when bind succeeded — trust AppWidgetManager
        val boundInfo = if (id != -1) {
            try { appWidgetManager.getAppWidgetInfo(id) } catch (e: Exception) {
                FileLogger.e("WidgetBind", "getAppWidgetInfo after result failed", e)
                null
            }
        } else null
        if (info == null && boundInfo != null) info = boundInfo
        val success = id != -1 && info != null && (result.resultCode == RESULT_OK || boundInfo != null)
        FileLogger.log("WidgetBind", "success=$success boundInfo=${boundInfo?.provider}")
        if (success) {
            clearPendingWidgetBindPrefs()
            if (pendingStackItem != null) {
                // Append bound widget to the stack instead of placing a standalone widget
                finishStackMemberBind(id, info!!)
            } else {
                continueAfterBind(id, info!!, pendingWidgetSpanX, pendingWidgetSpanY)
            }
        } else {
            pendingBindWidgetId = -1
            pendingWidgetInfo = null
            clearPendingWidgetBindPrefs()
            if (id != -1 && boundInfo == null) {
                try { appWidgetHost.deleteAppWidgetId(id) } catch (_: Exception) {}
            }
            if (pendingStackItem != null) {
                pendingStackFailed++
                FileLogger.e("WidgetStack", "bind dialog denied/failed for one member")
                drainPendingStackQueue()
            } else {
                try {
                    Toast.makeText(this, "No se pudo vincular el widget", Toast.LENGTH_SHORT).show()
                } catch (_: Exception) {}
                FileLogger.e("WidgetBind", "bind failed — showing toast")
                renderDesktop()
            }
        }
    }

    private val pickGifLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        val itemId = pendingGifItemId
        pendingGifItemId = null
        if (uri == null || itemId == null) return@registerForActivityResult
        val item = homeItems.find { it.id == itemId && it.isGifWidget } ?: return@registerForActivityResult
        try {
            // Persist permission if possible
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {}
            val dest = copyGifToFiles(itemId, uri)
            if (dest != null) {
                // Delete previous file if different
                val prev = item.gifPath
                item.gifPath = dest.absolutePath
                if (prev.isNotBlank() && prev != item.gifPath) {
                    try { File(prev).delete() } catch (_: Exception) {}
                }
                saveAllItems()
                renderDesktop()
                Toast.makeText(this, "Imagen cargada", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "No se pudo cargar la imagen", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            FileLogger.e("GifWidget", "pick failed", e)
            Toast.makeText(this, "Error al cargar la imagen", Toast.LENGTH_SHORT).show()
        }
    }

    private val configureWidgetLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        restorePendingWidgetBindFromPrefs()
        val id = pendingBindWidgetId
        val info = pendingWidgetInfo
        val spanX = pendingWidgetSpanX
        val spanY = pendingWidgetSpanY
        FileLogger.log("WidgetBind", "configureResult code=${result.resultCode} id=$id info=${info?.provider}")
        // Many third-party widgets (KWGT, OEM) return non-OK even after a successful save.
        // Trust AppWidgetManager: if the id is still bound, proceed.
        val stillBound = id != -1 && try {
            appWidgetManager.getAppWidgetInfo(id) != null
        } catch (_: Exception) { false }
        val ok = (result.resultCode == RESULT_OK || stillBound) && id != -1 && info != null
        pendingBindWidgetId = -1
        pendingWidgetInfo = null
        clearPendingWidgetBindPrefs()
        if (ok) {
            try { appWidgetHost.startListening() } catch (_: Exception) {}
            placeBoundWidget(id, info!!, spanX, spanY)
            binding.desktopContainer.postDelayed({
                try {
                    val hv = widgetHostViews[id]
                    if (hv != null && hv.width > 1 && hv.height > 1) {
                        applyWidgetSizeOptions(id, hv, info, hv.width, hv.height)
                    }
                    renderDesktop()
                } catch (_: Exception) {}
            }, 400)
        } else if (id != -1) {
            try { appWidgetHost.deleteAppWidgetId(id) } catch (_: Exception) {}
            try {
                Toast.makeText(this, "Configuración del widget cancelada", Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {}
            FileLogger.w("WidgetBind", "configure cancelled/failed stillBound=$stillBound")
            renderDesktop()
        }
    }
    private var pendingBindWidgetId = -1
    /** Queue of providers still needing system bind permission for a widget stack. */
    private var pendingStackItem: HomeItem? = null
    private val pendingStackQueue = mutableListOf<AppWidgetProviderInfo>()
    private var pendingStackAdded = 0
    private var pendingStackFailed = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FileLogger.init(this)
        FileLogger.log("Lifecycle", "onCreate")
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // Listen for SCREEN_OFF/ON/USER_PRESENT for the whole Activity lifetime
        registerUnlockReceiver()

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
        appWidgetManager = AppWidgetManager.getInstance(this)
        // Non-AppCompat inflate context so RemoteViews ImageView stays framework ImageView
        appWidgetHost = RomasterAppWidgetHost(widgetInflateContext(), APPWIDGET_HOST_ID)
        drawerGroupStore = DrawerGroupStore(this)
        columns = prefs.desktopColumns
        rows = prefs.desktopRows
        currentPage = prefs.defaultPage.coerceAtMost((prefs.pageCount - 1).coerceAtLeast(0))

        setupDesktop()
        setupDrawer()
        setupGestures()
        loadData()
        // First install: apply bundled default wallpaper once (assets/wallpapers/default.*)
        applyDefaultWallpaperIfNeeded()
    }

    /**
     * On first launch, set the system wallpaper from assets/wallpapers/default.*
     * (or the first bundled image). Skipped once applied or if no assets exist.
     */
    private fun applyDefaultWallpaperIfNeeded() {
        if (prefs.defaultWallpaperApplied) return
        val entry = BuiltInWallpapers.defaultEntry(this)
        if (entry == null) {
            // No bundled images yet — don't retry every cold start forever;
            // mark applied only when we had a chance with empty list after user has data?
            // Keep false so when user adds default.* and reinstalls/updates it can apply.
            // But for normal updates without wallpapers, avoid repeated work:
            if (BuiltInWallpapers.list(this).isEmpty()) {
                // Still allow future apply when assets appear on upgrade with wallpapers
                return
            }
        }
        // Run off UI thread — decoding/setBitmap can be heavy
        Thread {
            val ok = entry != null && BuiltInWallpapers.applyAsWallpaper(this, entry)
            prefs.defaultWallpaperApplied = true
            if (ok) {
                runOnUiThread {
                    try { applyWallpaperOffset() } catch (_: Exception) {}
                }
            }
        }.start()
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
            // ── 1) Layout from disk (same source as backup) — UI thread after IO ──
            val allItems = withContext(Dispatchers.IO) {
                try { homeRepo.loadItems() } catch (_: Exception) { emptyList() }
            }
            homeItems = allItems.filter { !it.isDock }.toMutableList()
            dockItems = allItems.filter { it.isDock }.toMutableList()
            columns = prefs.desktopColumns.coerceAtLeast(1)
            rows = prefs.desktopRows.coerceAtLeast(1)
            currentPage = prefs.defaultPage.coerceIn(0, (prefs.pageCount - 1).coerceAtLeast(0))
            dockPageIndex = 0

            // ── 2) Dock / chrome BEFORE metrics so grid height accounts for dock ──
            lastShowDock = prefs.showDock
            lastShowDrawerBtn = prefs.showDrawerButton
            lastDesktopIconPct = prefs.desktopIconSizePercent
            lastDockIconPct = prefs.dockIconSizePercent
            applyDrawerButtonVisibility()
            if (prefs.showDock) {
                binding.dockArea.visibility = View.VISIBLE
            } else {
                binding.dockArea.visibility = View.GONE
            }
            applyBottomBarOrder()
            binding.bottomBar.requestLayout()

            // ── 3) First paint: widgets/stacks/gifs + empty slots; grid size final ──
            desktopNeedsFullRender = true
            suspend fun paintAll() {
                withContext(Dispatchers.Main) {
                    fun doPaint() {
                        calculateMetrics()
                        if (cellWidth <= 1f || cellHeight <= 1f) return
                        renderDesktop()
                        renderDock()
                        desktopNeedsFullRender = false
                    }
                    if (binding.desktopContainer.width > 0 && binding.bottomBar.height > 0) {
                        doPaint()
                    } else {
                        binding.homeRoot.requestLayout()
                        binding.desktopContainer.doOnLayout {
                            binding.bottomBar.post { doPaint() }
                        }
                        binding.desktopContainer.postDelayed({
                            if (desktopNeedsFullRender || binding.desktopContainer.childCount == 0) {
                                doPaint()
                            }
                        }, 48L)
                    }
                }
            }
            // Paint structure immediately (icons may still be missing)
            paintAll()

            // ── 4) Apps: phase-1 default icons → paint; phase-2 pack icons → paint ──
            val cached = try {
                (application as? RomasterApp)?.appRepository?.getCachedApps().orEmpty()
            } catch (_: Exception) {
                emptyList()
            }
            var paintGeneration = 0
            suspend fun onAppsReady(list: List<AppInfo>) {
                if (list.isEmpty()) return
                allApps = list
                paintGeneration++
                val gen = paintGeneration
                if (allApps.isNotEmpty()) {
                    try { pruneAllFolders() } catch (_: Exception) {}
                }
                if (::drawerAdapter.isInitialized) {
                    submitDrawerApps(list)
                }
                // Debounce: only the latest generation paints (phase-2 wins over phase-1)
                withContext(Dispatchers.Main) {
                    if (gen != paintGeneration) return@withContext
                    desktopNeedsFullRender = true
                    calculateMetrics()
                    renderDesktop()
                    renderDock()
                    desktopNeedsFullRender = false
                }
            }
            try {
                if (cached.isNotEmpty()) {
                    onAppsReady(cached)
                    // Still run load to apply icon pack if process was killed mid-phase-2
                    appRepo.loadApps(force = false) { partial ->
                        onAppsReady(partial)
                    }
                } else {
                    appRepo.loadApps(force = true) { partial ->
                        onAppsReady(partial)
                    }
                }
            } catch (_: Exception) {
                // last resort
                try {
                    val late = appRepo.loadApps(force = true)
                    onAppsReady(late)
                } catch (_: Exception) {
                }
            }
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

    /**
     * Desktop grid metrics.
     *
     * The dock and drawer button live OUTSIDE desktopContainer (bottomBar), so the
     * container's measured height is already the area available for icons/widgets.
     * We only apply a small bottom gap padding so the last row does not sit flush
     * against the dock. Cell size = usable area / (columns × rows).
     *
     * Every widget/icon position goes through [gridRect] / [snapSpanToGrid] so the
     * footprint is always an exact matrix of cells (chessboard style).
     */
    private fun calculateMetrics() {
        val container = binding.desktopContainer
        val density = resources.displayMetrics.density
        var bottomPad = when {
            prefs.showDock || prefs.showDrawerButton ->
                (prefs.desktopDockGapDp * density).toInt().coerceAtLeast((2 * density).toInt())
            else -> (8 * density).toInt()
        }
        if (overviewLayoutActive || isOverviewMode) {
            bottomPad += (88 * density).toInt()
        }
        if (container.paddingBottom != bottomPad) {
            container.setPadding(container.paddingLeft, container.paddingTop, container.paddingRight, bottomPad)
        }
        val w = (container.width - container.paddingLeft - container.paddingRight).toFloat()
        val h = (container.height - container.paddingTop - container.paddingBottom).toFloat()
        if (w <= 0f || h <= 0f) return
        columns = prefs.desktopColumns.coerceAtLeast(1)
        rows = prefs.desktopRows.coerceAtLeast(1)
        cellWidth = w / columns
        cellHeight = h / rows

        val baseIcon = 56 * density
        desktopIconPx = (baseIcon * prefs.desktopIconSizePercent / 100f).roundToInt()
        drawerIconPx = (baseIcon * prefs.drawerIconSizePercent / 100f).roundToInt()
    }

    /** Pixel rect for a span matrix anchored at (cellX, cellY). */
    private fun gridRect(cellX: Int, cellY: Int, spanX: Int, spanY: Int): android.graphics.Rect {
        val sx = spanX.coerceAtLeast(1)
        val sy = spanY.coerceAtLeast(1)
        val cx = cellX.coerceIn(0, (columns - sx).coerceAtLeast(0))
        val cy = cellY.coerceIn(0, (rows - sy).coerceAtLeast(0))
        val left = kotlin.math.round(cx * cellWidth).toInt()
        val top = kotlin.math.round(cy * cellHeight).toInt()
        val right = kotlin.math.round((cx + sx) * cellWidth).toInt()
        val bottom = kotlin.math.round((cy + sy) * cellHeight).toInt()
        return android.graphics.Rect(left, top, right, bottom)
    }

    /**
     * Snap a free-floating top-left (container coords) to the cell where the span
     * matrix best fits. Uses a 50% rule: if the top-left crosses mid-cell, advances.
     */
    private fun snapSpanToGrid(leftPx: Float, topPx: Float, spanX: Int, spanY: Int): Pair<Int, Int> {
        val sx = spanX.coerceAtLeast(1).coerceAtMost(columns.coerceAtLeast(1))
        val sy = spanY.coerceAtLeast(1).coerceAtMost(rows.coerceAtLeast(1))
        val cw = cellWidth.coerceAtLeast(1f)
        val ch = cellHeight.coerceAtLeast(1f)
        val cellX = kotlin.math.floor((leftPx / cw) + 0.5f).toInt()
            .coerceIn(0, (columns - sx).coerceAtLeast(0))
        val cellY = kotlin.math.floor((topPx / ch) + 0.5f).toInt()
            .coerceIn(0, (rows - sy).coerceAtLeast(0))
        return cellX to cellY
    }

    /**
     * Clamp a HomeItem widget span/cell into the current grid. Returns true if changed.
     *
     * ONLY enforces grid bounds (1..columns / 1..rows). Do NOT force small default
     * sizes here — that was killing intentional large resizes (e.g. 4x6 → snapped to 4x2).
     */
    private fun clampItemToGrid(item: HomeItem): Boolean {
        if (!item.isAnyWidget) return false
        if (item.freePlacement) return false
        val maxC = columns.coerceAtLeast(1)
        val maxR = rows.coerceAtLeast(1)
        val sx = item.spanX.coerceIn(1, maxC)
        val sy = item.spanY.coerceIn(1, maxR)
        val cx = item.cellX.coerceIn(0, (maxC - sx).coerceAtLeast(0))
        val cy = item.cellY.coerceIn(0, (maxR - sy).coerceAtLeast(0))
        val changed = item.spanX != sx || item.spanY != sy || item.cellX != cx || item.cellY != cy
        item.spanX = sx
        item.spanY = sy
        item.cellX = cx
        item.cellY = cy
        return changed
    }


    private fun rebuildAppIndex() {
        val map = HashMap<String, AppInfo>(allApps.size.coerceAtLeast(8) * 2)
        for (a in allApps) {
            map[a.packageName + "\u0000" + a.activityName] = a
        }
        appByKey = map
    }

    private fun findApp(packageName: String, activityName: String): AppInfo? {
        appByKey[packageName + "\u0000" + activityName]?.let { return it }
        return allApps.find { it.packageName == packageName && it.activityName == activityName }
    }

    private fun refreshVisibleWidgetSizes() {
        val screen = currentPage
        for (item in homeItems) {
            if (item.screen != screen || item.isDock) continue
            try {
                if (item.isWidget && item.appWidgetId >= 0) {
                    val hv = widgetHostViews[item.appWidgetId] ?: continue
                    if (hv.width <= 0 || hv.height <= 0) continue
                    val info = try { appWidgetManager.getAppWidgetInfo(item.appWidgetId) } catch (_: Exception) { null }
                    applyWidgetSizeOptions(item.appWidgetId, hv, info, hv.width, hv.height)
                } else if (item.isWidgetStack) {
                    for (m in item.stackMembers) {
                        if (m.appWidgetId < 0) continue
                        val hv = widgetHostViews[m.appWidgetId] ?: continue
                        if (hv.width <= 0 || hv.height <= 0) continue
                        val info = try { appWidgetManager.getAppWidgetInfo(m.appWidgetId) } catch (_: Exception) { null }
                        applyWidgetSizeOptions(m.appWidgetId, hv, info, hv.width, hv.height)
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun renderDesktop() {
        val container = binding.desktopContainer
        if (appByKey.isEmpty() && allApps.isNotEmpty()) rebuildAppIndex()
        // Always refresh metrics when the container is laid out
        if (container.width > 0 && container.height > 0) {
            calculateMetrics()
        } else if (cellWidth <= 1f || cellHeight <= 1f) {
            // Not laid out yet — schedule a real render after layout
            container.doOnLayout {
                calculateMetrics()
                if (cellWidth > 1f) renderDesktop()
            }
            return
        }
        val wasDragging = dropOverlay != null && draggingApp != null
        snapshotVisibleGifAnimStates(container)
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
        var needSaveClamp = false
        for (item in pageItems) {
            if (draggingHomeItem?.id == item.id) continue
            when {
                item.isWidgetStack -> {
                    if (!item.freePlacement && clampItemToGrid(item)) needSaveClamp = true
                    val sv = createWidgetStackView(item) ?: continue
                    val sw: Int
                    val sh: Int
                    if (item.freePlacement) {
                        val fb = gridRect(item.cellX, item.cellY, item.spanX.coerceAtLeast(1), item.spanY.coerceAtLeast(1))
                        sw = (if (item.freeW > 0) item.freeW else fb.width()).coerceAtLeast(1)
                        sh = (if (item.freeH > 0) item.freeH else fb.height()).coerceAtLeast(1)
                    } else {
                        val rect = gridRect(item.cellX, item.cellY, item.spanX, item.spanY)
                        sw = rect.width().coerceAtLeast(1)
                        sh = rect.height().coerceAtLeast(1)
                    }
                    sv.layoutParams = FrameLayout.LayoutParams(sw, sh)
                    positionWidget(sv, item)
                    container.addView(sv)
                    positionWidget(sv, item)
                }
                item.isGifWidget -> {
                    if (!item.freePlacement && clampItemToGrid(item)) needSaveClamp = true
                    val gv = createGifWidgetView(item) ?: continue
                    val gw: Int
                    val gh: Int
                    if (item.freePlacement) {
                        val fb = gridRect(item.cellX, item.cellY, item.spanX.coerceAtLeast(1), item.spanY.coerceAtLeast(1))
                        gw = (if (item.freeW > 0) item.freeW else fb.width()).coerceAtLeast(1)
                        gh = (if (item.freeH > 0) item.freeH else fb.height()).coerceAtLeast(1)
                    } else {
                        val rect = gridRect(item.cellX, item.cellY, item.spanX, item.spanY)
                        gw = rect.width().coerceAtLeast(1)
                        gh = rect.height().coerceAtLeast(1)
                    }
                    gv.layoutParams = FrameLayout.LayoutParams(gw, gh)
                    positionWidget(gv, item)
                    container.addView(gv)
                    positionWidget(gv, item)
                }
                item.isWidget -> {
                    if (!item.freePlacement && clampItemToGrid(item)) needSaveClamp = true
                    val wv = createWidgetView(item) ?: continue
                    val ww: Int
                    val wh: Int
                    if (item.freePlacement) {
                        val fb = gridRect(item.cellX, item.cellY, item.spanX.coerceAtLeast(1), item.spanY.coerceAtLeast(1))
                        ww = (if (item.freeW > 0) item.freeW else fb.width()).coerceAtLeast(1)
                        wh = (if (item.freeH > 0) item.freeH else fb.height()).coerceAtLeast(1)
                    } else {
                        val rect = gridRect(item.cellX, item.cellY, item.spanX, item.spanY)
                        ww = rect.width().coerceAtLeast(1)
                        wh = rect.height().coerceAtLeast(1)
                    }
                    wv.layoutParams = FrameLayout.LayoutParams(ww, wh)
                    positionWidget(wv, item)
                    container.addView(wv)
                    positionWidget(wv, item)
                }
                item.isFolder -> {
                    val iconView = createFolderIconView(item)
                    positionIcon(iconView, item.cellX, item.cellY)
                    container.addView(iconView)
                }
                else -> {
                    val app = findApp(item.packageName, item.activityName) ?: continue
                    val iconView = createDesktopIconView(app, item)
                    positionIcon(iconView, item.cellX, item.cellY)
                    container.addView(iconView)
                }
            }
        }
        if (needSaveClamp) saveAllItems()

        if (wasDragging) {
            ensureDropOverlay()
        }
        applyWidgetZOrder(container)
        // renderDesktop rebuilds views at settled transforms — re-apply pre-pose if waiting to unlock
        if (unlockAnimPrepared && !unlockAnimPlaying && prefs.unlockAnimationsEnabled) {
            prepareUnlockAnimationState()
        }
    }

    /**
     * Layer order on a page:
     *   widgets with aboveIcons=false  →  desktop icons/folders  →  widgets with aboveIcons=true
     * Within each widget group, order by [HomeItem.zOrder].
     */
    private fun applyWidgetZOrder(
        container: ViewGroup = binding.desktopContainer,
        screen: Int = currentPage
    ) {
        val pageItems = homeItems.filter { it.screen == screen && !it.isDock }
        val below = pageItems.filter { it.isAnyWidget && !it.aboveIcons }.sortedBy { it.zOrder }
        val icons = pageItems.filter { !it.isAnyWidget }
        val above = pageItems.filter { it.isAnyWidget && it.aboveIcons }.sortedBy { it.zOrder }
        fun front(item: HomeItem) {
            val v = container.findViewWithTag<View>(item.id) ?: return
            container.bringChildToFront(v)
        }
        for (w in below) front(w)
        for (i in icons) front(i)
        for (w in above) front(w)
    }

    private fun showIconsLayerDialog(item: HomeItem) {
        val modes = arrayOf("Delante de íconos", "Detrás de íconos")
        val live = homeItems.find { it.id == item.id } ?: item
        val checked = if (live.aboveIcons) 0 else 1
        AlertDialog.Builder(this)
            .setTitle("Icons Layer")
            .setSingleChoiceItems(modes, checked) { dialog, which ->
                val target = homeItems.find { it.id == item.id } ?: item
                target.aboveIcons = which == 0
                item.aboveIcons = target.aboveIcons
                saveAllItems()
                applyWidgetZOrder()
                val msg = if (target.aboveIcons) "Delante de íconos" else "Detrás de íconos"
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun bringWidgetToFront(item: HomeItem) {
        val maxZ = homeItems
            .filter { it.screen == item.screen && it.isAnyWidget }
            .maxOfOrNull { it.zOrder } ?: 0
        val live = homeItems.find { it.id == item.id } ?: item
        live.zOrder = maxZ + 1
        item.zOrder = live.zOrder
        saveAllItems()
        applyWidgetZOrder()
        Toast.makeText(this, "Al frente", Toast.LENGTH_SHORT).show()
    }

    private fun sendWidgetToBack(item: HomeItem) {
        val minZ = homeItems
            .filter { it.screen == item.screen && it.isAnyWidget }
            .minOfOrNull { it.zOrder } ?: 0
        val live = homeItems.find { it.id == item.id } ?: item
        live.zOrder = minZ - 1
        item.zOrder = live.zOrder
        saveAllItems()
        applyWidgetZOrder()
        Toast.makeText(this, "Al fondo", Toast.LENGTH_SHORT).show()
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
        // Pre-widget: x/y relative to FrameLayout content box (same origin as GridDropOverlay)
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
            onStartDrag = { app, view, ev ->
                dismissCurrentPopup()
                startDrawerDrag(app, view, ev)
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
        binding.appsGrid.itemAnimator = null
        binding.appsGrid.isVerticalScrollBarEnabled = false
        binding.appsGrid.overScrollMode = View.OVER_SCROLL_NEVER
        binding.appsGrid.clipToPadding = false
        // Extra bottom padding so last row is above gesture/nav bar (fixes dead taps at end of list)
        applyDrawerGridInsets()
        setupDrawerScrollIndicator()

        // Toggle Apps ↔ Widgets (replaces the old settings button in the drawer)
        binding.btnSettings.contentDescription = "Widgets"
        binding.btnSettings.setImageResource(R.drawable.ic_widgets)
        binding.btnSettings.setOnClickListener {
            drawerShowingWidgets = !drawerShowingWidgets
            applyDrawerContentMode()
        }
        // Long-press still opens Settings
        binding.btnSettings.setOnLongClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
            true
        }
        binding.searchApps.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString().orEmpty()
                if (drawerShowingWidgets) {
                    drawerWidgetFilter?.invoke(q)
                    return
                }
                // Avoid random missed taps while DiffUtil is animating filtered rows
                binding.appsGrid.itemAnimator = null
                if (q.isBlank()) {
                    submitDrawerApps(allApps)
                } else {
                    val hidden = prefs.getHiddenPackages()
                    val filtered = allApps.filter {
                        it.packageName !in hidden &&
                            (it.label.contains(q, true) || it.packageName.contains(q, true))
                    }
                    drawerAdapter.submitApps(filtered, alphabetical = prefs.drawerAlphabetical) {
                        binding.appsGrid.stopScroll()
                        binding.appsGrid.scrollToPosition(0)
                        updateDrawerScrollThumb()
                    }
                    return
                }
                binding.appsGrid.stopScroll()
                binding.appsGrid.scrollToPosition(0)
                updateDrawerScrollThumb()
            }
        })
    }

    /** Panel height in px; falls back to root height if not laid out yet */
    private fun cancelPendingIconLongPresses() {
        suppressIconInteractions = true
        iconLongPressActive = false
        suppressEmptyLongPress = true
        suppressIconClick = true
        dismissCurrentPopup()
        // Long-press runnables check suppressIconInteractions / drawerGestureActive before firing
    }

    /**
     * Clears touch blocks after drawer open/close settles.
     * Uses a short grace period so the finger-up that closed the drawer (or a
     * residual long-press that armed over a desktop icon) cannot steal the
     * next real tap. Matches the "first tap does nothing" bug after close.
     */
    private fun releaseIconInteractionBlock(delayMs: Long = 120L) {
        // Always wipe menus / long-press state immediately
        dismissCurrentPopup()
        iconLongPressActive = false
        releaseIconBlockRunnable?.let { binding.homeRoot.removeCallbacks(it) }
        val run = Runnable {
            releaseIconBlockRunnable = null
            suppressIconInteractions = false
            iconLongPressActive = false
            suppressEmptyLongPress = false
            drawerGestureActive = false
            // Brief click suppress so residual gesture-up cannot launch an app
            suppressIconClick = true
            binding.homeRoot.postDelayed({ suppressIconClick = false }, 160L)
        }
        releaseIconBlockRunnable = run
        if (delayMs <= 0L) {
            binding.homeRoot.post(run)
        } else {
            binding.homeRoot.postDelayed(run, delayMs)
        }
    }

    /** Current wallpaper X offset 0..1 (tracks continuously for parallax). */
    private var wallpaperOffsetX = 0.5f
    private var wallpaperOffsetAnimator: android.animation.ValueAnimator? = null
    /** Depth factor: wallpaper moves slower than pages (0.55–0.7 feels like Nova). */
    private val wallpaperParallaxFactor = 0.62f

    /** Map fractional page index → wallpaper X (0..1). */
    private fun pageFractionToWallpaperX(pageFraction: Float): Float {
        val mode = prefs.wallpaperScroll
        if (mode == "off") return 0.5f
        val pages = prefs.pageCount.coerceAtLeast(1)
        if (pages <= 1) return 0.5f
        val raw = (pageFraction / (pages - 1).toFloat()).coerceIn(0f, 1f)
        return when (mode) {
            "inverse" -> 1f - raw
            else -> raw
        }
    }

    /** Apply wallpaper offset immediately for a fractional page position. */
    private fun applyWallpaperOffsetAt(pageFraction: Float) {
        if (prefs.wallpaperScroll == "off") return
        try {
            val pages = prefs.pageCount.coerceAtLeast(1)
            val steps = if (pages <= 1) 0f else 1f / (pages - 1).toFloat()
            val wm = android.app.WallpaperManager.getInstance(this)
            wm.setWallpaperOffsetSteps(steps, 0f)
            val x = pageFractionToWallpaperX(pageFraction)
            wallpaperOffsetX = x
            val token = window.decorView.windowToken ?: return
            wm.setWallpaperOffsets(token, x, 0.5f)
        } catch (_: Exception) {}
    }

    /** Settle wallpaper to the exact current page (instant — call after transitions end). */
    private fun applyWallpaperOffset() {
        wallpaperOffsetAnimator?.cancel()
        wallpaperOffsetAnimator = null
        applyWallpaperOffsetAt(currentPage.toFloat())
    }

    /**
     * Live parallax during finger swipe or dual-layer progress.
     * [progress] 0..1 of the page transition, [pageDelta] +1 next / -1 prev.
     * Wallpaper lag = parallax factor for depth.
     */
    private fun updateWallpaperParallax(progress: Float, pageDelta: Int) {
        if (prefs.wallpaperScroll == "off") return
        // Fully synced with page transition progress (starts and ends together)
        val p = progress.coerceIn(0f, 1f)
        val pageFrac = currentPage.toFloat() + pageDelta * p
        applyWallpaperOffsetAt(pageFrac)
    }


    /** Switch drawer body between app icons and widget browser. */
    private fun applyDrawerContentMode() {
        val container = binding.appsGridContainer
        // Keep search bar + switch in the same place as apps mode
        binding.searchApps.visibility = View.VISIBLE
        binding.btnSettings.visibility = View.VISIBLE

        for (i in container.childCount - 1 downTo 0) {
            val c = container.getChildAt(i)
            if (c !== binding.appsGrid) container.removeViewAt(i)
        }
        drawerWidgetScrollView = null
        drawerWidgetFilter = null

        if (drawerShowingWidgets) {
            binding.appsGrid.visibility = View.GONE
            binding.searchApps.hint = "Buscar widgets..."
            binding.searchApps.setText("")
            binding.btnSettings.setColorFilter(0xFF4FC3F7.toInt())

            val host = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                tag = "drawer_widgets_host"
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }
            val picker = WidgetPickerSheet(
                this,
                columns,
                onPick = { info, spanX, spanY ->
                    closeDrawerAnimated()
                    beginBindWidget(info, spanX, spanY)
                },
                onLongPressDrag = { info, spanX, spanY, event ->
                    closeDrawerAnimated()
                    startNewWidgetDragFromPicker(info, spanX, spanY, event)
                },
                showSearchBar = false,
                cellWidthPx = cellWidth.toInt().coerceAtLeast(1),
                cellHeightPx = cellHeight.toInt().coerceAtLeast(1),
                maxRows = rows.coerceAtLeast(1),
                onPickStack = {
                    closeDrawerAnimated()
                    placeEmptyWidgetStack()
                },
                onLongPressDragStack = { sx, sy, event ->
                    closeDrawerAnimated()
                    startCustomWidgetDragFromPicker("stack", sx, sy, event)
                },
                onPickGif = {
                    closeDrawerAnimated()
                    placeGifWidget()
                },
                onLongPressDragGif = { sx, sy, event ->
                    closeDrawerAnimated()
                    startCustomWidgetDragFromPicker("gif", sx, sy, event)
                }
            )
            picker.attachTo(host)
            drawerWidgetScrollView = picker.listScrollView
            drawerWidgetFilter = picker.filterFunction
            container.addView(host)
        } else {
            binding.appsGrid.visibility = View.VISIBLE
            binding.searchApps.hint = "Buscar aplicaciones..."
            binding.btnSettings.clearColorFilter()
            // Clear search + full list (avoid sticking on previous filter when search looks empty)
            if (binding.searchApps.text?.isNotEmpty() == true) {
                binding.searchApps.setText("")
            } else {
                submitDrawerApps(allApps)
            }
            binding.appsGrid.itemAnimator = null
            binding.appsGrid.stopScroll()
            binding.appsGrid.scrollToPosition(0)
            // Restore custom scrollbar (widget host may have covered / removed it)
            setupDrawerScrollIndicator()
            binding.appsGrid.post { updateDrawerScrollThumb() }
        }
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
        // Cold-start: drawer may open before phase-2 icons; always bind current list
        if (::drawerAdapter.isInitialized && allApps.isNotEmpty()) {
            try { submitDrawerApps(allApps) } catch (_: Exception) {}
        }

        val density = resources.displayMetrics.density
        val size = if (drawerIconPx > 0) drawerIconPx else (52 * density).toInt()
        if (::drawerAdapter.isInitialized) {
            drawerAdapter.updateIconSize(size)
        }
        if (isDrawerOpen && binding.drawerPanel.translationY == 0f && binding.drawerPanel.alpha >= 0.99f) return
        isDrawerOpen = true
        suppressEmptyLongPress = true
        suppressIconInteractions = true
        binding.drawerPanel.visibility = View.VISIBLE
        binding.drawerPanel.isClickable = true
        binding.drawerPanel.isFocusable = true
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
                isDrawerOpen = true
                drawerGestureActive = false
                // Drawer is open: keep icon interactions suppressed on the desktop
                suppressIconInteractions = true
                suppressEmptyLongPress = true
                updateDrawerScrollThumb()
                binding.appsGrid.post { updateDrawerScrollThumb() }
            }
            .start()
        binding.appsGrid.post { updateDrawerScrollThumb() }
    }

    private fun closeDrawerAnimated() {
        // Keep isDrawerOpen true until the panel is gone so desktop icons cannot
        // arm long-press under the closing finger (haptic + dead first tap).
        suppressEmptyLongPress = true
        suppressIconInteractions = true
        dismissCurrentPopup()
        val h = drawerHeight()
        binding.drawerPanel.animate().cancel()
        binding.drawerPanel.animate()
            .translationY(h)
            .alpha(0f)
            .setDuration(260)
            .setInterpolator(DecelerateInterpolator(1.4f))
            .withEndAction {
                isDrawerOpen = false
                binding.drawerPanel.visibility = View.GONE
                // Ensure closed panel cannot intercept the next touch
                binding.drawerPanel.isClickable = false
                binding.drawerPanel.isFocusable = false
                applyDrawerOpenVisual(h)
                applyDrawerButtonVisibility()
                drawerGestureActive = false
                releaseIconInteractionBlock(120)
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
                    isDrawerOpen = true
                    drawerGestureActive = false
                    suppressIconInteractions = true
                    suppressEmptyLongPress = true
                }
                .start()
            binding.btnOpenDrawer.visibility = View.INVISIBLE
        } else {
            // Closing: keep isDrawerOpen until panel is gone
            suppressIconInteractions = true
            suppressEmptyLongPress = true
            binding.drawerPanel.animate().cancel()
            val distance = h - ty
            val duration = (220 + (distance / h) * 120).toLong().coerceIn(160, 320)
            binding.drawerPanel.animate()
                .translationY(h)
                .alpha(0f)
                .setDuration(duration)
                .setInterpolator(DecelerateInterpolator(1.5f))
                .withEndAction {
                    isDrawerOpen = false
                    binding.drawerPanel.visibility = View.GONE
                    binding.drawerPanel.isClickable = false
                    binding.drawerPanel.isFocusable = false
                    applyDrawerOpenVisual(h)
                    applyDrawerButtonVisibility()
                    drawerGestureActive = false
                    releaseIconInteractionBlock(120)
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
                    suppressIconInteractions = true
                    suppressEmptyLongPress = true
                }
                .start()
        } else {
            suppressIconInteractions = true
            suppressEmptyLongPress = true
            binding.drawerPanel.animate()
                .translationY(h)
                .alpha(0f)
                .setDuration(180)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction {
                    binding.drawerPanel.visibility = View.GONE
                    binding.drawerPanel.isClickable = false
                    binding.drawerPanel.isFocusable = false
                    applyDrawerOpenVisual(h)
                    applyDrawerButtonVisibility()
                    isDrawerOpen = false
                    drawerGestureActive = false
                    releaseIconInteractionBlock(120)
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
        if (draggingApp != null && (dragShadow != null || draggingWidgetView != null)) {
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
                    // When the drawer is open the stack is covered — never treat drawer touches
                    // as "on stack" or swipe-to-close stops working over that region.
                    touchStartedOnWidgetStack = !isDrawerOpen && isTouchOnWidgetStack(ev.rawX, ev.rawY)
                    // GIF swipe flag is set by the GIF view itself on DOWN when recipes need swipes

                    // Stack floating menu: any tap outside dismisses it and unlocks gestures.
                    // Without this, iconLongPressActive stayed true and page/dock/folder
                    // swipes were permanently blocked after opening the stack menu.
                    if (stackMenuView != null) {
                        val onMenu = isTouchOnView(stackMenuView, ev.rawX, ev.rawY)
                        if (!onMenu) {
                            FileLogger.log("WidgetStack", "outside tap → dismiss stack menu")
                            dismissStackMenu()
                        }
                    } else if (
                        iconLongPressActive &&
                        draggingApp == null &&
                        resizingWidgetId == null &&
                        currentPopup == null &&
                        !touchStartedOnWidgetStack && !touchStartedOnGifSwipe
                    ) {
                        // Safety net: clear a stuck long-press lock from a previous gesture
                        FileLogger.log("WidgetStack", "safety clear stuck iconLongPressActive")
                        iconLongPressActive = false
                        suppressEmptyLongPress = false
                    }

                    if (touchStartedOnDock) {
                        // Never let desktop page swipe or empty long-press start
                        suppressEmptyLongPress = true
                        binding.desktopContainer.cancelLongPress()
                    }
                    // Intentionally do NOT set suppressEmptyLongPress for stacks: that flag
                    // aborted the stack long-press Runnable. Gesture blocking uses the flag only.
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
                        // Require clearer horizontal intent so long-press can still fire
                        if (!dockPageSwipeActive && abs(dx) > touchSlop * 2.2f && abs(dx) > abs(dy) * 1.5f) {
                            dockPageSwipeActive = true
                            suppressEmptyLongPress = true
                            binding.desktopContainer.cancelLongPress()
                            // Cancel any dock-icon long-press callbacks (user is swiping pages)
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
                    } else if (!touchStartedOnDock && !touchStartedOnWidgetStack && !touchStartedOnGifSwipe && !isDrawerOpen && !drawerGestureActive && !pageSwipeActive &&
                        !iconLongPressActive && currentPopup == null && draggingApp == null &&
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

                    // ── Drawer open/close + notifications (not on dock / not on widget stack) ──
                    if (currentPopup == null && !iconLongPressActive && draggingApp == null &&
                        !touchStartedOnDock && !touchStartedOnWidgetStack && !touchStartedOnGifSwipe && !isOverviewMode && !pageSwipeActive
                    ) {
                        if (!drawerGestureActive) {
                            if (!isDrawerOpen && dy > touchSlop * 4 && abs(dy) > abs(dx) * 1.5f) {
                                openNotificationShade()
                                drawerTouchStartY = ev.rawY
                            } else if (!isDrawerOpen && dy < -touchSlop * 2 && abs(dy) > abs(dx)) {
                                startDrawerGesture(opening = true)
                            } else if (isDrawerOpen && dy > touchSlop && abs(dy) > abs(dx)) {
                                val atTop = if (drawerShowingWidgets) {
                                    val sv = drawerWidgetScrollView
                                    sv == null || !sv.canScrollVertically(-1)
                                } else {
                                    !binding.appsGrid.canScrollVertically(-1)
                                }
                                if (atTop) startDrawerGesture(opening = false)
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
                    if (touchStartedOnDock || touchStartedOnWidgetStack || touchStartedOnGifSwipe) {
                        suppressEmptyLongPress = false
                    }
                    touchStartedOnDock = false
                    touchStartedOnWidgetStack = false
                    touchStartedOnGifSwipe = false
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
            // Snapshot reaction transforms from both layers before flattening
            snapshotVisibleGifAnimStates(container)
            if (out != null) snapshotVisibleGifAnimStates(out)
            snapshotVisibleGifAnimStates(inc)
            // Flatten incoming children into container
            val children = mutableListOf<View>()
            for (i in 0 until inc.childCount) children.add(inc.getChildAt(i))
            container.removeAllViews()
            for (ch in children) {
                inc.removeView(ch)
                container.addView(ch)
            }
            container.post { refreshVisibleWidgetSizes() }
        } else if (!keepIncoming && out != null) {
            // Restore outgoing — still snapshot any transforms first
            snapshotVisibleGifAnimStates(container)
            snapshotVisibleGifAnimStates(out)
            if (inc != null) snapshotVisibleGifAnimStates(inc)
            val children = mutableListOf<View>()
            for (i in 0 until out.childCount) children.add(out.getChildAt(i))
            container.removeAllViews()
            for (ch in children) {
                out.removeView(ch)
                container.addView(ch)
            }
        } else {
            snapshotVisibleGifAnimStates(container)
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
        if (appByKey.isEmpty() && allApps.isNotEmpty()) rebuildAppIndex()
        val pageItems = homeItems.filter { it.screen == screen && !it.isDock }
        val icons = ArrayList<HomeItem>(pageItems.size)
        val widgets = ArrayList<HomeItem>(pageItems.size)
        for (item in pageItems) {
            if (draggingHomeItem?.id == item.id) continue
            if (item.isAnyWidget) widgets.add(item) else icons.add(item)
        }
        for (item in icons) {
            when {
                item.isFolder -> {
                    val iconView = createFolderIconView(item)
                    positionIcon(iconView, item.cellX, item.cellY)
                    layer.addView(iconView)
                }
                else -> {
                    val app = findApp(item.packageName, item.activityName) ?: continue
                    val iconView = createDesktopIconView(app, item)
                    positionIcon(iconView, item.cellX, item.cellY)
                    layer.addView(iconView)
                }
            }
        }
        suppressWidgetSizeOptions = true
        try {
            for (item in widgets) {
                when {
                    item.isWidgetStack -> {
                        val sv = createWidgetStackView(item) ?: continue
                        positionWidget(sv, item)
                        layer.addView(sv)
                    }
                    item.isGifWidget -> {
                        val gv = createGifWidgetView(item) ?: continue
                        positionWidget(gv, item)
                        layer.addView(gv)
                    }
                    item.isWidget -> {
                        val wv = createWidgetView(item) ?: continue
                        positionWidget(wv, item)
                        layer.addView(wv)
                    }
                }
            }
        } finally {
            suppressWidgetSizeOptions = false
        }
        applyWidgetZOrder(layer, screen)
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
        try {
            outgoing.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            incoming.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        } catch (_: Exception) {}

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
        // Layers are children of desktopContainer (already at 0.82 in overview).
        // Must stay 1f or every page-swipe starts with an abrupt extra shrink.
        val baseScale = 1f
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
                // pageDelta +1 (next): outgoing moves left (negative X), incoming from right (+w → 0)
                val outX = if (pageDelta > 0) -w * p else w * p
                val inX = if (pageDelta > 0) w * (1f - p) else -w * (1f - p)
                outgoing.translationX = outX
                incoming.translationX = inX
                outgoing.alpha = 1f - p * 0.4f
                incoming.alpha = 0.6f + p * 0.4f
                outgoing.rotationY = 0f
                incoming.rotationY = 0f
                if (isOverviewMode) {
                    // Carousel depth: zoom locked to swipe progress p (finger speed = zoom speed).
                    // Mild: origin 0.82→0.76, dest 0.76→0.82. No bounce — settle is exact at p=1.
                    val depth = 0.30f // ~30% depth synced to swipe X
                    val outScale = baseScale * (1f - depth * p)
                    val inScale = baseScale * (1f - depth * (1f - p))
                    outgoing.scaleX = outScale
                    outgoing.scaleY = outScale
                    incoming.scaleX = inScale
                    incoming.scaleY = inScale
                    // Keep pages fully readable while sliding
                    outgoing.alpha = 1f - p * 0.15f
                    incoming.alpha = 0.85f + p * 0.15f
                } else {
                    outgoing.scaleX = baseScale
                    outgoing.scaleY = baseScale
                    incoming.scaleX = baseScale
                    incoming.scaleY = baseScale
                }
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
        updateWallpaperParallax(progress, pageDelta)
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
                        val p = va.animatedValue as Float
                        setLayerProgress(out, inc, p, dualTransitionDir, effect)
                        updateWallpaperParallax(p, dualTransitionDir)
                    }
                    anim.addListener(object : android.animation.AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: android.animation.Animator) {
                            clearDualLayers(keepIncoming = false)
                            applyWallpaperOffset() // settle to current page
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
            if (isOverviewMode) softUpdateOverviewChrome()
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
            if (isOverviewMode) softUpdateOverviewChrome()
            return
        }
        val startP = (abs(dx) / width).coerceIn(0f, 1f)
        val anim = android.animation.ValueAnimator.ofFloat(startP, 1f)
        anim.duration = 240
        anim.interpolator = DecelerateInterpolator(1.3f)
        val originPage = currentPage
        anim.addUpdateListener { va ->
            val p = va.animatedValue as Float
            setLayerProgress(out, inc, p, pageDelta, effect)
            // Synced with page settle progress
            val pageFrac = originPage + pageDelta * p
            applyWallpaperOffsetAt(pageFrac)
        }
        anim.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                currentPage = (originPage + pageDelta).coerceIn(0, maxPage)
                // Finish wallpaper a bit slower for depth
                applyWallpaperOffset()
                clearDualLayers(keepIncoming = true)
                // Do not renderDesktop(): recreating views restarts GIF from frame 0
                if (isOverviewMode) {
                    // Scale already at baseScale (0.82) from dual transition — no bounce
                    binding.desktopContainer.scaleX = 0.82f
                    binding.desktopContainer.scaleY = 0.82f
                    softUpdateOverviewChrome()
                }
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
            if (isOverviewMode) softUpdateOverviewChrome()
            return
        }
        val dest = currentPage
        val origin = (currentPage - pageDelta).coerceAtLeast(0)
        // Render origin, then dual-transition into dest
        currentPage = origin
        renderDesktop()
        ensureDualTransition(pageDelta)
        currentPage = dest
        val out = outgoingPageLayer
        val inc = incomingPageLayer
        if (out == null || inc == null) {
            applyWallpaperOffset()
            renderDesktop()
            if (isOverviewMode) softUpdateOverviewChrome()
            return
        }
        val anim = android.animation.ValueAnimator.ofFloat(0f, 1f)
        anim.duration = 300
        anim.interpolator = DecelerateInterpolator(1.35f)
        anim.addUpdateListener { va ->
            val p = va.animatedValue as Float
            setLayerProgress(out, inc, p, pageDelta, effect)
            val pageFrac = origin + pageDelta * p
            applyWallpaperOffsetAt(pageFrac)
        }
        anim.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                applyWallpaperOffset()
                clearDualLayers(keepIncoming = true)
                // Do not renderDesktop(): recreating views restarts GIF from frame 0
                if (isOverviewMode) {
                    // Scale already at baseScale (0.82) from dual transition — no bounce
                    binding.desktopContainer.scaleX = 0.82f
                    binding.desktopContainer.scaleY = 0.82f
                    softUpdateOverviewChrome()
                }
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
            binding.drawerPanel.isClickable = true
            binding.drawerPanel.isFocusable = true
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
        // Keep drawerGestureActive = true until fling/bounce endAction clears it.
        // Clearing early let desktop icons arm long-press (haptic) under the finger
        // and ate the next tap.
        suppressIconInteractions = true
        suppressEmptyLongPress = true
        dismissCurrentPopup()
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

    private fun startDrawerDrag(app: AppInfo, sourceView: View, event: MotionEvent) {
        if (draggingApp != null) return
        draggingApp = app
        draggingHomeItem = null
        // Icons always occupy 1 cell — never inherit leftover widget spans
        dragOriginSpanX = 1
        dragOriginSpanY = 1
        // Finger position at drag start (absolute cell under finger while over desktop)
        dragStartRawX = event.rawX
        dragStartRawY = event.rawY
        calculateMetrics()
        val deskLoc = IntArray(2)
        binding.desktopContainer.getLocationOnScreen(deskLoc)
        val localX = event.rawX - deskLoc[0]
        val localY = event.rawY - deskLoc[1]
        val maxX = (columns - 1).coerceAtLeast(0)
        val maxY = (rows - 1).coerceAtLeast(0)
        dragOriginCellX = if (cellWidth > 0f) (localX / cellWidth).toInt().coerceIn(0, maxX) else 0
        dragOriginCellY = if (cellHeight > 0f) (localY / cellHeight).toInt().coerceIn(0, maxY) else 0

        dragShadow = createDragShadow(app)
        (binding.homeRoot as ViewGroup).addView(dragShadow)
        // Center of the shadow stays under the finger (same as desktop drag)
        val shadowSize = (dragShadow?.layoutParams?.width ?: desktopIconPx).toFloat().coerceAtLeast(1f)
        dragGrabOffsetX = shadowSize / 2f
        dragGrabOffsetY = shadowSize / 2f
        dragShadow?.scaleX = 1.14f
        dragShadow?.scaleY = 1.14f
        moveDragShadow(event.rawX, event.rawY)

        ensureDropOverlay()
        updateDropHighlight(event.rawX, event.rawY)
        closeDrawerAnimated()
    }

    private fun startDesktopDrag(app: AppInfo, item: HomeItem, sourceView: View, event: MotionEvent) {
        if (draggingApp != null) return
        // Widgets (system / stack / gif) must use startWidgetDrag so free-mode
        // keeps the real view visible and drop saves freeX/Y correctly.
        if (item.isAnyWidget) {
            startWidgetDrag(item, sourceView, event)
            return
        }
        iconLongPressActive = true
        dismissCurrentPopup()
        draggingApp = app
        draggingHomeItem = item
        dragOriginCellX = item.cellX
        dragOriginCellY = item.cellY
        dragOriginSpanX = 1
        dragOriginSpanY = 1
        dragStartRawX = event.rawX
        dragStartRawY = event.rawY
        sourceView.visibility = View.INVISIBLE

        dragShadow = createDragShadow(app)
        (binding.homeRoot as ViewGroup).addView(dragShadow)
        dragShadow?.scaleX = 1.14f
        dragShadow?.scaleY = 1.14f
        val shadowSize = (dragShadow?.layoutParams?.width ?: desktopIconPx).toFloat().coerceAtLeast(1f)
        dragGrabOffsetX = shadowSize / 2f
        dragGrabOffsetY = shadowSize / 2f
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
        // FREE: move real widget view under finger
        if (draggingHomeItem?.freePlacement == true) {
            val freeView = draggingWidgetView ?: return
            val deskLoc = IntArray(2)
            binding.desktopContainer.getLocationOnScreen(deskLoc)
            val lp = (freeView.layoutParams as? FrameLayout.LayoutParams)
                ?: FrameLayout.LayoutParams(
                    freeView.width.coerceAtLeast(1),
                    freeView.height.coerceAtLeast(1)
                )
            lp.leftMargin = (rawX - deskLoc[0] - dragGrabOffsetX).toInt()
            lp.topMargin = (rawY - deskLoc[1] - dragGrabOffsetY).toInt()
            lp.gravity = Gravity.TOP or Gravity.START
            freeView.layoutParams = lp
            freeView.translationX = 0f
            freeView.translationY = 0f
            return
        }
        // GRID / icons: move shadow
        val shadow = dragShadow ?: return
        val rootLoc = IntArray(2)
        binding.homeRoot.getLocationOnScreen(rootLoc)
        shadow.x = rawX - rootLoc[0] - dragGrabOffsetX
        shadow.y = rawY - rootLoc[1] - dragGrabOffsetY
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
        // Free-placement: no overlay needed; view already moved in moveDragShadow
        if (draggingHomeItem?.freePlacement == true) {
            scheduleEdgePageFlip(rawX)
            return
        }
        val overlay = dropOverlay ?: return
        // Keep overlay grid in sync with current prefs/metrics
        calculateMetrics()
        overlay.configure(columns, rows)
        if (cellWidth <= 0f || cellHeight <= 0f) return

        // Span locked at drag-start — highlight size always matches the widget matrix
        val spanX = dragOriginSpanX.coerceAtLeast(1).coerceAtMost(columns.coerceAtLeast(1))
        val spanY = dragOriginSpanY.coerceAtLeast(1).coerceAtMost(rows.coerceAtLeast(1))
        val maxX = (columns - spanX).coerceAtLeast(0)
        val maxY = (rows - spanY).coerceAtLeast(0)
        val cw = cellWidth.coerceAtLeast(1f)
        val ch = cellHeight.coerceAtLeast(1f)

        val cellX: Int
        val cellY: Int
        if (draggingHomeItem == null) {
            // New item from drawer / widget picker: cell under the finger on the desktop grid
            // so the blue highlight sits under the drag shadow (same as desktop reposition).
            val deskLoc = IntArray(2)
            binding.desktopContainer.getLocationOnScreen(deskLoc)
            val localX = rawX - deskLoc[0]
            val localY = rawY - deskLoc[1]
            cellX = (localX / cw).toInt().coerceIn(0, maxX)
            cellY = (localY / ch).toInt().coerceIn(0, maxY)
        } else {
            // Existing icon/folder/widget: cell-by-cell from origin (one cell of travel → one step)
            val dCellsX = kotlin.math.round((rawX - dragStartRawX) / cw).toInt()
            val dCellsY = kotlin.math.round((rawY - dragStartRawY) / ch).toInt()
            cellX = (dragOriginCellX + dCellsX).coerceIn(0, maxX)
            cellY = (dragOriginCellY + dCellsY).coerceIn(0, maxY)
        }
        lastDropCell = Point(cellX, cellY)

        // Free-placement widget: no grid highlight; real view follows via moveDragShadow
        if (draggingHomeItem?.freePlacement == true) {
            try { overlay.visibility = View.GONE } catch (_: Exception) {}
            scheduleEdgePageFlip(rawX)
            return
        }
        overlay.visibility = View.VISIBLE
        overlay.setFocus(cellX, cellY, 1f, spanX, spanY)

        // Only resize the drag shadow to the cell matrix when dragging a GRID widget.
        if (draggingHomeItem?.isAnyWidget == true && draggingHomeItem?.freePlacement != true) {
            dragShadow?.let { shadow ->
                val rect = gridRect(0, 0, spanX, spanY)
                val sw = rect.width().coerceAtLeast(1)
                val sh = rect.height().coerceAtLeast(1)
                val lp = shadow.layoutParams
                if (lp != null && (lp.width != sw || lp.height != sh)) {
                    lp.width = sw
                    lp.height = sh
                    shadow.layoutParams = lp
                }
            }
        }
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
        // Dropping a Romaster custom widget (stack / gif) from the picker
        val customType = pendingDragCustomType
        if (customType != null) {
            val sx = dragOriginSpanX.coerceAtLeast(1).coerceAtMost(columns.coerceAtLeast(1))
            val sy = dragOriginSpanY.coerceAtLeast(1).coerceAtMost(rows.coerceAtLeast(1))
            cellX = cellX.coerceIn(0, (columns - sx).coerceAtLeast(0))
            cellY = cellY.coerceIn(0, (rows - sy).coerceAtLeast(0))
            pendingDragCustomType = null
            FileLogger.log("WidgetBind", "dropCustom type=$customType span=${sx}x${sy} cell=$cellX,$cellY")
            cleanupDrag()
            when (customType) {
                "stack" -> placeEmptyWidgetStackAt(cellX, cellY, sx, sy)
                "gif" -> placeGifWidgetAt(cellX, cellY, sx, sy, openPicker = true)
            }
            return
        }
        // Dropping a NEW system widget dragged from the picker — exact span from picker
        val newWidgetInfo = pendingDragWidgetInfo
        if (newWidgetInfo != null) {
            val sx = dragOriginSpanX.coerceAtLeast(1).coerceAtMost(columns.coerceAtLeast(1))
            val sy = dragOriginSpanY.coerceAtLeast(1).coerceAtMost(rows.coerceAtLeast(1))
            cellX = cellX.coerceIn(0, (columns - sx).coerceAtLeast(0))
            cellY = cellY.coerceIn(0, (rows - sy).coerceAtLeast(0))
            pendingDragWidgetInfo = null
            pendingWidgetSpanX = sx
            pendingWidgetSpanY = sy
            pendingPlaceCellX = cellX
            pendingPlaceCellY = cellY
            FileLogger.log("WidgetBind", "dropNewWidget span=${sx}x${sy} cell=$cellX,$cellY")
            cleanupDrag()
            beginBindWidget(newWidgetInfo, sx, sy)
            return
        }
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
        } else if (existingItem != null && existingItem.isAnyWidget) {
            existingItem.screen = currentPage
            if (existingItem.freePlacement) {
                val v = draggingWidgetView
                val lp = v?.layoutParams as? FrameLayout.LayoutParams
                if (lp != null) {
                    existingItem.freeX = lp.leftMargin.toFloat()
                    existingItem.freeY = lp.topMargin.toFloat()
                    existingItem.freeW = lp.width.coerceAtLeast(1)
                    existingItem.freeH = lp.height.coerceAtLeast(1)
                } else {
                    // Fallback from finger
                    val deskLoc = IntArray(2)
                    binding.desktopContainer.getLocationOnScreen(deskLoc)
                    existingItem.freeX = rawX - deskLoc[0] - dragGrabOffsetX
                    existingItem.freeY = rawY - deskLoc[1] - dragGrabOffsetY
                }
                saveAllItems()
                FileLogger.log(
                    "WidgetBind",
                    "dropMove FREE id=${existingItem.id} xy=${existingItem.freeX},${existingItem.freeY} " +
                        "size=${existingItem.freeW}x${existingItem.freeH}"
                )
            } else {
                val sx = dragOriginSpanX.coerceAtLeast(1).coerceAtMost(columns.coerceAtLeast(1))
                val sy = dragOriginSpanY.coerceAtLeast(1).coerceAtMost(rows.coerceAtLeast(1))
                existingItem.spanX = sx
                existingItem.spanY = sy
                existingItem.cellX = cellX.coerceIn(0, (columns - sx).coerceAtLeast(0))
                existingItem.cellY = cellY.coerceIn(0, (rows - sy).coerceAtLeast(0))
                clampItemToGrid(existingItem)
                saveAllItems()
                FileLogger.log(
                    "WidgetBind",
                    "dropMove id=${existingItem.id} cell=${existingItem.cellX},${existingItem.cellY} " +
                        "span=${existingItem.spanX}x${existingItem.spanY} stack=${existingItem.isWidgetStack}"
                )
            }
        } else if (existingItem != null) {
            val occupant = homeItems.find {
                it.screen == currentPage && it.cellX == cellX && it.cellY == cellY && it.id != existingItem.id
            }
            if (occupant != null && !occupant.isAnyWidget) {
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

        // Free placement: already at final pixel position — no cell snap animation
        if (draggingHomeItem?.freePlacement == true) {
            val itemId = draggingHomeItem?.id
            val v = draggingWidgetView
            v?.elevation = 0f
            cleanupDrag()
            closeFolderOverlay()
            val item = itemId?.let { id -> homeItems.find { it.id == id } }
            if (item != null && item.freePlacement) {
                binding.desktopContainer.findViewWithTag<View>(item.id)?.let { view ->
                    view.visibility = View.VISIBLE
                    positionWidget(view, item)
                } ?: run { renderDesktop() }
            } else {
                renderDesktop()
            }
            return
        }

        // Animate drop into cell center (grid mode)
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
        draggingWidgetView?.let { v ->
            try { v.elevation = 0f } catch (_: Exception) {}
            try { v.visibility = View.VISIBLE } catch (_: Exception) {}
        }
        draggingWidgetView = null
        try {
            dropOverlay?.let { o ->
                (o.parent as? ViewGroup)?.removeView(o)
            }
        } catch (_: Exception) {}
        dropOverlay = null
        lastDropCell = null
        draggingApp = null
        draggingHomeItem = null
        pendingDragWidgetInfo = null
        pendingDragCustomType = null
        // Always release interaction locks after any drag ends (free or grid)
        iconLongPressActive = false
        suppressEmptyLongPress = false
        // Reset span/grab so the next icon/folder drag never inherits widget state
        dragOriginSpanX = 1
        dragOriginSpanY = 1
        dragOriginCellX = 0
        dragOriginCellY = 0
        dragGrabOffsetX = 0f
        dragGrabOffsetY = 0f
        dragStartRawX = 0f
        dragStartRawY = 0f
        folderDragInProgress = false
        suppressEmptyLongPress = false
        iconLongPressActive = false
        dismissStackMenu()
        // Close hidden folder overlay after a folder drag
        if (folderOverlay != null && folderOverlay?.alpha == 0f) {
            closeFolderOverlay()
        }
    }

    private fun submitDrawerApps(apps: List<AppInfo>) {
        rebuildAppIndex()
        val hidden = prefs.getHiddenPackages()
        val groups = try {
            if (!::drawerGroupStore.isInitialized) emptyList()
            else drawerGroupStore.load().filter { it.enabled && it.members.isNotEmpty() }
        } catch (_: Exception) { emptyList() }
        // Apps already inside a drawer group must not appear loose in the drawer
        val groupedKeys = groups.flatMap { g ->
            g.members.map { "${it.packageName}/${it.activityName}" }
        }.toSet()
        val visible = apps.filter {
            it.packageName !in hidden &&
                "${it.packageName}/${it.activityName}" !in groupedKeys
        }
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
        if (!prefs.showDock) {
            pager.removeAllViews()
            indicators.removeAllViews()
            area.visibility = View.GONE
            applyBottomBarOrder()
            return
        }
        area.visibility = View.VISIBLE
        applyBottomBarOrder()
        // After restore / cold layout the pager may still be 0×0 — wait for measure
        // so icons are not built into a 1px-wide page (invisible until swipe).
        if (pager.width <= 1 || pager.height <= 1) {
            pager.post {
                if (pager.width > 1 && pager.height > 1) {
                    renderDock()
                } else {
                    pager.doOnLayout { renderDock() }
                }
            }
            return
        }
        pager.removeAllViews()
        indicators.removeAllViews()
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
            // NOTE: do NOT check suppressEmptyLongPress — that flag is set on dock ACTION_DOWN
            // to block *desktop* empty long-press, and would kill dock icon menus.
            if (dockPageSwipeActive || pageSwipeActive || canceled || draggingApp != null) return@Runnable
            if (isDrawerOpen || drawerGestureActive || suppressIconInteractions) return@Runnable
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
                    // Only cancel pending long-press if this is clearly a horizontal dock-page swipe
                    if (!armed && dist > touchSlop && abs(dx) > abs(dy) * 1.3f && abs(dx) > touchSlop * 1.5f) {
                        canceled = true
                        v.handler?.removeCallbacks(arm)
                        return@OnTouchListener false
                    }
                    // Move after menu → drag mode (any direction)
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


    // ───────────────────────────── Widgets ─────────────────────────────

    private var widgetSheetView: View? = null
    private var widgetSheetExpanded = false
    private var widgetSheetFullHeight = 0
    private var widgetSheetPeekHeight = 0

    /** Nova-style: peek (partial) or full widgets panel from bottom of edit mode. */
    private fun showWidgetPicker(peek: Boolean = false) {
        if (widgetSheetView != null) {
            if (!peek && !widgetSheetExpanded) expandWidgetSheet()
            return
        }
        val density = resources.displayMetrics.density
        // Use root height (true display area) so the sheet reaches the physical bottom edge
        val screenH = binding.homeRoot.height.takeIf { it > 0 }
            ?: resources.displayMetrics.heightPixels
        val insets = try {
            androidx.core.view.ViewCompat.getRootWindowInsets(window.decorView)
        } catch (_: Exception) { null }
        var statusTop = try {
            insets?.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars())?.top ?: 0
        } catch (_: Exception) { 0 }
        if (statusTop <= 0) {
            try {
                val rid = resources.getIdentifier("status_bar_height", "dimen", "android")
                if (rid > 0) statusTop = resources.getDimensionPixelSize(rid)
            } catch (_: Exception) {}
            if (statusTop <= 0) statusTop = (28 * density).toInt()
        }
        // Height = from physical bottom of the screen up to just below the status bar.
        // NO navigation-bar subtraction — sheet must reach the device bottom edge (like app drawer).
        widgetSheetFullHeight = (screenH - statusTop).coerceAtLeast((240 * density).toInt())
        // Thin peek strip (handle + title only)
        widgetSheetPeekHeight = (48 * density).toInt()

        val sheet = FrameLayout(this).apply {
            tag = "widget_sheet"
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF1C1C1E.toInt())
                cornerRadii = floatArrayOf(
                    22f * density, 22f * density,
                    22f * density, 22f * density,
                    0f, 0f, 0f, 0f
                )
            }
            elevation = 48f * density
            isClickable = true
        }
        // Handle
        val handle = View(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 2.5f * density
                setColor(0xFF888888.toInt())
            }
        }
        sheet.addView(handle, FrameLayout.LayoutParams(
            (40 * density).toInt(), (4 * density).toInt(), Gravity.TOP or Gravity.CENTER_HORIZONTAL
        ).apply { topMargin = (8 * density).toInt() })

        // Nova-like title visible in the thin peek strip
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((18 * density).toInt(), (16 * density).toInt(), (18 * density).toInt(), (6 * density).toInt())
        }
        titleRow.addView(TextView(this).apply {
            text = "▦  Widgets"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        sheet.addView(titleRow, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP
        ))

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // Top room for handle + title; bottom goes to the real edge of the screen (no nav gap)
            setPadding(0, (48 * density).toInt(), 0, 0)
        }
        val picker = WidgetPickerSheet(
            this,
            columns,
            onPick = { info, spanX, spanY ->
                pendingWidgetInfo = info
                pendingWidgetSpanX = spanX
                pendingWidgetSpanY = spanY
                dismissWidgetSheet()
                beginBindWidget(info, spanX, spanY)
            },
            onLongPressDrag = { info, spanX, spanY, event ->
                startNewWidgetDragFromPicker(info, spanX, spanY, event)
            },
            cellWidthPx = cellWidth.toInt().coerceAtLeast(1),
            cellHeightPx = cellHeight.toInt().coerceAtLeast(1),
            maxRows = rows.coerceAtLeast(1),
            onPickStack = {
                dismissWidgetSheet()
                placeEmptyWidgetStack()
            },
            onLongPressDragStack = { sx, sy, event ->
                dismissWidgetSheet()
                startCustomWidgetDragFromPicker("stack", sx, sy, event)
            },
            onPickGif = {
                dismissWidgetSheet()
                placeGifWidget()
            },
            onLongPressDragGif = { sx, sy, event ->
                dismissWidgetSheet()
                startCustomWidgetDragFromPicker("gif", sx, sy, event)
            }
        )
        picker.attachTo(content)

        sheet.addView(content, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            widgetSheetFullHeight,
            Gravity.BOTTOM
        )
        (binding.homeRoot as ViewGroup).addView(sheet, lp)
        widgetSheetView = sheet

        // Start off-screen then animate to peek or full
        sheet.translationY = widgetSheetFullHeight.toFloat()
        val targetTy = if (peek) {
            widgetSheetExpanded = false
            (widgetSheetFullHeight - widgetSheetPeekHeight).toFloat()
        } else {
            widgetSheetExpanded = true
            0f
        }
        sheet.animate().translationY(targetTy).setDuration(260)
            .setInterpolator(DecelerateInterpolator()).start()

        // ── Gestures (same idea as app drawer) ──
        // translationY: 0 = fully open, peekTy = peek strip, fullH = dismissed
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        val fullH = widgetSheetFullHeight.toFloat()
        val peekTy = (widgetSheetFullHeight - widgetSheetPeekHeight).toFloat()

        fun animateSheetTo(target: Float, expanded: Boolean) {
            widgetSheetExpanded = expanded
            sheet.animate().translationY(target).setDuration(220)
                .setInterpolator(DecelerateInterpolator()).start()
        }

        fun settleSheet(ty: Float, deltaY: Float) {
            // deltaY > 0 = finger moved down; < 0 = finger moved up
            when {
                // Clear upward fling / drag → always expand
                deltaY < -touchSlop * 3 -> animateSheetTo(0f, true)
                // Clear downward fling from open/peek
                deltaY > touchSlop * 3 -> {
                    if (ty > peekTy * 0.35f || !widgetSheetExpanded) {
                        // From peek or already dragged past → dismiss
                        if (ty >= peekTy * 0.85f) dismissWidgetSheet()
                        else animateSheetTo(peekTy, false)
                    } else {
                        animateSheetTo(peekTy, false)
                    }
                }
                else -> {
                    // Snap to nearest stable state
                    val distOpen = kotlin.math.abs(ty - 0f)
                    val distPeek = kotlin.math.abs(ty - peekTy)
                    val distClose = kotlin.math.abs(ty - fullH)
                    when {
                        distOpen <= distPeek && distOpen <= distClose -> animateSheetTo(0f, true)
                        distPeek <= distClose -> animateSheetTo(peekTy, false)
                        else -> dismissWidgetSheet()
                    }
                }
            }
        }

        // Handle + title: always draggable (up expands, down collapses)
        var handleDownY = 0f
        var handleStartTy = 0f
        var handleLastY = 0f
        val handleDrag = View.OnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    handleDownY = ev.rawY
                    handleLastY = ev.rawY
                    handleStartTy = sheet.translationY
                    sheet.animate().cancel()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    handleLastY = ev.rawY
                    val dy = ev.rawY - handleDownY
                    sheet.translationY = (handleStartTy + dy).coerceIn(0f, fullH)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val delta = handleLastY - handleDownY
                    settleSheet(sheet.translationY, delta)
                    true
                }
                else -> false
            }
        }
        handle.setOnTouchListener(handleDrag)
        titleRow.setOnTouchListener(handleDrag)
        titleRow.setOnClickListener {
            if (sheet.translationY > peekTy * 0.5f) expandWidgetSheet()
        }
        handle.setOnClickListener {
            if (sheet.translationY > peekTy * 0.5f) expandWidgetSheet()
        }

        // List area: swipe-down closes only when scroll is at the top
        val scroll = picker.listScrollView
        if (scroll != null) {
            var downY = 0f
            var startTy = 0f
            var lastY = 0f
            var draggingSheet = false
            scroll.setOnTouchListener { sv, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downY = ev.rawY
                        lastY = ev.rawY
                        startTy = sheet.translationY
                        draggingSheet = false
                        false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dy = ev.rawY - downY
                        val atTop = !sv.canScrollVertically(-1)
                        // From peek (sheet not fully open): any vertical drag moves the sheet
                        val fromPeek = sheet.translationY > 1f
                        if (!draggingSheet && (
                                (atTop && dy > touchSlop) ||
                                (fromPeek && kotlin.math.abs(dy) > touchSlop)
                            )
                        ) {
                            draggingSheet = true
                            startTy = sheet.translationY
                            downY = ev.rawY
                            lastY = ev.rawY
                            sv.parent?.requestDisallowInterceptTouchEvent(true)
                        }
                        if (draggingSheet) {
                            lastY = ev.rawY
                            val drag = ev.rawY - downY
                            sheet.translationY = (startTy + drag).coerceIn(0f, fullH)
                            true
                        } else {
                            false
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (draggingSheet) {
                            draggingSheet = false
                            settleSheet(sheet.translationY, lastY - downY)
                            true
                        } else {
                            false
                        }
                    }
                    else -> false
                }
            }
        }
    }

    private fun expandWidgetSheet() {
        val sheet = widgetSheetView ?: run {
            showWidgetPicker(peek = false)
            return
        }
        widgetSheetExpanded = true
        sheet.animate().translationY(0f).setDuration(240)
            .setInterpolator(DecelerateInterpolator()).start()
    }

    private fun dismissWidgetSheet() {
        val sheet = widgetSheetView ?: return
        widgetSheetView = null
        widgetSheetExpanded = false
        sheet.animate().translationY(sheet.height.toFloat().coerceAtLeast(1f))
            .setDuration(220)
            .withEndAction {
                try { (sheet.parent as? ViewGroup)?.removeView(sheet) } catch (_: Exception) {}
            }.start()
    }


    private fun startNewWidgetDragFromPicker(
        info: AppWidgetProviderInfo,
        spanX: Int,
        spanY: Int,
        event: MotionEvent
    ) {
        if (draggingApp != null) return
        calculateMetrics()
        dismissWidgetSheet()
        exitOverviewMode()
        // Metrics may change after sheet dismiss / overview exit — recalculate
        calculateMetrics()
        iconLongPressActive = true
        suppressEmptyLongPress = true
        pendingDragWidgetInfo = info
        val maxC = columns.coerceAtLeast(1)
        val maxR = rows.coerceAtLeast(1)
        pendingDragSpanX = spanX.coerceIn(1, maxC)
        pendingDragSpanY = spanY.coerceIn(1, maxR)
        dragOriginSpanX = pendingDragSpanX
        dragOriginSpanY = pendingDragSpanY
        // Origin = cell under the finger so the drop highlight starts where the user is,
        // not at (0,0) while the shadow follows the finger (dual-frame bug in the video).
        val contLoc = IntArray(2)
        binding.desktopContainer.getLocationOnScreen(contLoc)
        val localX = event.rawX - contLoc[0] - binding.desktopContainer.paddingLeft
        val localY = event.rawY - contLoc[1] - binding.desktopContainer.paddingTop
        val cw = cellWidth.coerceAtLeast(1f)
        val ch = cellHeight.coerceAtLeast(1f)
        dragOriginCellX = (localX / cw).toInt()
            .coerceIn(0, (maxC - pendingDragSpanX).coerceAtLeast(0))
        dragOriginCellY = (localY / ch).toInt()
            .coerceIn(0, (maxR - pendingDragSpanY).coerceAtLeast(0))
        dragStartRawX = event.rawX
        dragStartRawY = event.rawY
        FileLogger.log(
            "WidgetBind",
            "pickerDrag start span=${pendingDragSpanX}x${pendingDragSpanY} " +
                "originCell=${dragOriginCellX},${dragOriginCellY} cellPx=${cw.toInt()}x${ch.toInt()}"
        )
        draggingHomeItem = HomeItem(
            id = "pending_widget",
            packageName = info.provider.packageName,
            activityName = info.provider.className,
            label = try { info.loadLabel(packageManager).toString() } catch (_: Exception) { "Widget" },
            spanX = pendingDragSpanX,
            spanY = pendingDragSpanY,
            isWidget = true
        )
        draggingApp = AppInfo(
            packageName = info.provider.packageName,
            activityName = info.provider.className,
            label = draggingHomeItem!!.label,
            icon = null
        )
        val shadowRect = gridRect(0, 0, pendingDragSpanX, pendingDragSpanY)
        val sw = shadowRect.width().coerceAtLeast(1)
        val sh = shadowRect.height().coerceAtLeast(1)
        val density = resources.displayMetrics.density
        val shadow = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(sw, sh)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0x33FFFFFF)
                setStroke((2.5f * density).toInt(), 0xFF4FC3F7.toInt())
                cornerRadius = 14f * density
            }
            elevation = 32f
        }
        dragShadow = shadow
        (binding.homeRoot as ViewGroup).addView(shadow)
        shadow.measure(
            View.MeasureSpec.makeMeasureSpec(sw, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(sh, View.MeasureSpec.EXACTLY)
        )
        shadow.layout(0, 0, sw, sh)
        // From picker there is no on-screen widget — grab at center of the new shadow
        dragGrabOffsetX = sw / 2f
        dragGrabOffsetY = sh / 2f
        moveDragShadow(event.rawX, event.rawY)
        ensureDropOverlay()
        updateDropHighlight(event.rawX, event.rawY)
    }


    private fun savePendingWidgetBindToPrefs() {
        try {
            val p = getSharedPreferences("romaster_widget_pending", MODE_PRIVATE).edit()
            p.putInt("id", pendingBindWidgetId)
            p.putInt("spanX", pendingWidgetSpanX)
            p.putInt("spanY", pendingWidgetSpanY)
            p.putInt("cellX", pendingPlaceCellX)
            p.putInt("cellY", pendingPlaceCellY)
            p.putString("provider", pendingWidgetInfo?.provider?.flattenToString())
            p.apply()
            FileLogger.log("WidgetBind", "saved pending id=$pendingBindWidgetId provider=${pendingWidgetInfo?.provider}")
        } catch (e: Exception) {
            FileLogger.e("WidgetBind", "save pending failed", e)
        }
    }

    private fun restorePendingWidgetBindFromPrefs() {
        if (pendingBindWidgetId != -1 && pendingWidgetInfo != null) return
        try {
            val p = getSharedPreferences("romaster_widget_pending", MODE_PRIVATE)
            val id = p.getInt("id", -1)
            val flat = p.getString("provider", null)
            if (id == -1 || flat.isNullOrBlank()) return
            val cn = android.content.ComponentName.unflattenFromString(flat) ?: return
            val info = appWidgetManager.installedProviders.firstOrNull { it.provider == cn }
            if (info == null) return
            pendingBindWidgetId = id
            pendingWidgetInfo = info
            pendingWidgetSpanX = p.getInt("spanX", 1).coerceAtLeast(1)
            pendingWidgetSpanY = p.getInt("spanY", 1).coerceAtLeast(1)
            pendingPlaceCellX = p.getInt("cellX", -1)
            pendingPlaceCellY = p.getInt("cellY", -1)
            FileLogger.log("WidgetBind", "restored pending id=$id provider=$cn")
        } catch (e: Exception) {
            FileLogger.e("WidgetBind", "restore pending failed", e)
        }
    }

    private fun clearPendingWidgetBindPrefs() {
        try {
            getSharedPreferences("romaster_widget_pending", MODE_PRIVATE).edit().clear().apply()
        } catch (_: Exception) {}
    }

    private fun beginBindWidget(info: AppWidgetProviderInfo, spanX: Int, spanY: Int) {
        if (!::appWidgetHost.isInitialized) return
        calculateMetrics()
        val maxC = columns.coerceAtLeast(1)
        val maxR = rows.coerceAtLeast(1)
        pendingWidgetSpanX = spanX.coerceIn(1, maxC)
        pendingWidgetSpanY = spanY.coerceIn(1, maxR)
        // Soft-expand only when the requested span is smaller than the provider minimum.
        // Fixes Google News Glance "Cargando…" (targetCell 1×1 but minWidth ≈ 368px).
        // Does NOT expand above minSpans — Spotify 4×2 stays 4×2.
        val (minSx, minSy) = minSpansForWidget(info)
        if (pendingWidgetSpanX < minSx) pendingWidgetSpanX = minSx
        if (pendingWidgetSpanY < minSy) pendingWidgetSpanY = minSy
        pendingWidgetInfo = info
        FileLogger.log(
            "WidgetBind",
            "beginBind spans using=${pendingWidgetSpanX}x${pendingWidgetSpanY} " +
                "requested=${spanX}x${spanY} minSpans=${minSx}x${minSy} " +
                "cell=${cellWidth.toInt()}x${cellHeight.toInt()} grid=${maxC}x${maxR}"
        )

        val id = try {
            appWidgetHost.allocateAppWidgetId()
        } catch (e: Exception) {
            Toast.makeText(this, "Error al crear widget", Toast.LENGTH_SHORT).show()
            return
        }
        pendingBindWidgetId = id

        // Launcher3 passes size options AT bind time — Calendar needs this
        val bindOpts = buildWidgetOptionsForSpan(pendingWidgetSpanX, pendingWidgetSpanY)
        val allowed = try {
            appWidgetManager.bindAppWidgetIdIfAllowed(id, info.provider, bindOpts)
        } catch (_: Exception) {
            try {
                appWidgetManager.bindAppWidgetIdIfAllowed(id, info.provider)
            } catch (_: Exception) { false }
        }

        FileLogger.log("WidgetBind", "beginBind id=$id provider=${info.provider} allowed=$allowed span=${pendingWidgetSpanX}x${pendingWidgetSpanY} optsDp=${bindOpts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)}x${bindOpts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)}")
        if (allowed) {
            continueAfterBind(id, info, pendingWidgetSpanX, pendingWidgetSpanY)
        } else {
            val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_OPTIONS, buildWidgetOptionsForSpan(pendingWidgetSpanX, pendingWidgetSpanY))
            }
            try {
                savePendingWidgetBindToPrefs()
                FileLogger.log("WidgetBind", "launching ACTION_APPWIDGET_BIND id=$id")
                bindWidgetLauncher.launch(intent)
            } catch (e: Exception) {
                FileLogger.e("WidgetBind", "launch bind intent failed", e)
                try { appWidgetHost.deleteAppWidgetId(id) } catch (_: Exception) {}
                pendingBindWidgetId = -1
                pendingWidgetInfo = null
                clearPendingWidgetBindPrefs()
                Toast.makeText(this, "No se pudo abrir el permiso de widgets", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** After bind is allowed (or user granted it), run configure if needed then place. */
    private fun continueAfterBind(
        widgetId: Int,
        info: AppWidgetProviderInfo,
        spanX: Int,
        spanY: Int
    ) {
        FileLogger.log("WidgetBind", "continueAfterBind id=$widgetId provider=${info.provider} span=${spanX}x${spanY} configure=${info.configure}")
        pendingBindWidgetId = widgetId
        pendingWidgetInfo = info
        pendingWidgetSpanX = spanX
        pendingWidgetSpanY = spanY

        if (info.configure != null) {
            try {
                val i = Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE).apply {
                    component = info.configure
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                }
                // Only place after RESULT_OK from configureWidgetLauncher
                configureWidgetLauncher.launch(i)
            } catch (e: Exception) {
                // Configure activity not launchable — try placing without it
                placeBoundWidget(widgetId, info, spanX, spanY)
            }
        } else {
            placeBoundWidget(widgetId, info, spanX, spanY)
        }
    }

    /** Final step: add HomeItem and render. */
    private fun placeBoundWidget(
        widgetId: Int,
        info: AppWidgetProviderInfo,
        spanX: Int,
        spanY: Int
    ) {
        // Ensure host is listening BEFORE any createView
        try { appWidgetHost.startListening() } catch (_: Exception) {}

        val boundInfo = try {
            appWidgetManager.getAppWidgetInfo(widgetId)
        } catch (_: Exception) { null }
        if (boundInfo == null) {
            try { appWidgetHost.deleteAppWidgetId(widgetId) } catch (_: Exception) {}
            pendingBindWidgetId = -1
            pendingWidgetInfo = null
            Toast.makeText(this, "No se pudo agregar el widget (sin vínculo)", Toast.LENGTH_SHORT).show()
            return
        }

        // Size options are applied AFTER the host view is laid out (see applyWidgetSizeOptions).
        // Pushing options here with incomplete metrics makes Calendar/list providers render the
        // system error RemoteViews ("No se pudo agregar el widget").
        calculateMetrics()
        val maxC = columns.coerceAtLeast(1)
        val maxR = rows.coerceAtLeast(1)
        // Exact span from picker — the chessboard matrix size, nothing else
        val sx = spanX.coerceIn(1, maxC)
        val sy = spanY.coerceIn(1, maxR)
        FileLogger.log(
            "WidgetBind",
            "placeBoundWidget id=$widgetId provider=${info.provider} span=${sx}x${sy} " +
                "requested=${spanX}x${spanY} cellPx=${cellWidth.toInt()}x${cellHeight.toInt()} grid=${maxC}x${maxR}"
        )
        // IMPORTANT: do NOT call createView here — AppWidgetHost only allows ONE host view
        // per widget id. createView is done once in createWidgetView during renderDesktop.
        // A probe createView caused a brief correct flash then "No se pudo agregar el widget".

        exitOverviewMode()
        val cell = if (pendingPlaceCellX >= 0 && pendingPlaceCellY >= 0) {
            val c = pendingPlaceCellX to pendingPlaceCellY
            pendingPlaceCellX = -1
            pendingPlaceCellY = -1
            c
        } else {
            findFreeCell(sx, sy) ?: (0 to 0)
        }
        val cx = cell.first.coerceIn(0, (columns - sx).coerceAtLeast(0))
        val cy = cell.second.coerceIn(0, (rows - sy).coerceAtLeast(0))
        // If rebinding an existing slot (same page/cell), update instead of duplicating
        val existingIdx = homeItems.indexOfFirst {
            it.isWidget && it.screen == currentPage && it.cellX == cx && it.cellY == cy
        }
        val item = if (existingIdx >= 0) {
            val old = homeItems[existingIdx]
            if (old.appWidgetId != widgetId && old.appWidgetId >= 0) {
                widgetHostViews.remove(old.appWidgetId)
                try { appWidgetHost.deleteAppWidgetId(old.appWidgetId) } catch (_: Exception) {}
            }
            old.appWidgetId = widgetId
            old.spanX = sx
            old.spanY = sy
            homeItems[existingIdx] = old
            old
        } else {
            HomeItem(
                id = "widget_" + widgetId,
                packageName = info.provider.packageName,
                activityName = info.provider.className,
                label = try { info.loadLabel(packageManager).toString() } catch (_: Exception) { "Widget" },
                screen = currentPage,
                cellX = cx,
                cellY = cy,
                spanX = sx,
                spanY = sy,
                isWidget = true,
                appWidgetId = widgetId
            ).also { homeItems.add(it) }
        }
        saveAllItems()
        pendingBindWidgetId = -1
        pendingWidgetInfo = null
        try { appWidgetHost.startListening() } catch (_: Exception) {}
        renderDesktop()
        // Nudge Glance / list providers:
        // 1) re-apply sizeOptions with real host size
        // 2) broadcast ACTION_APPWIDGET_UPDATE so the provider's WorkManager runs
        // 3) notifyAppWidgetViewDataChanged for collection-style RemoteViews
        fun nudgeWidget() {
            try {
                val hv = widgetHostViews[widgetId]
                if (hv != null && hv.width > 1 && hv.height > 1) {
                    lastSizeOptionsKey.remove(widgetId)
                    lastSizeOptionsAt.remove(widgetId)
                    applyWidgetSizeOptions(widgetId, hv, info, hv.width, hv.height)
                }
            } catch (_: Exception) {}
            requestProviderRefresh(widgetId, info)
        }
        binding.desktopContainer.postDelayed({ nudgeWidget() }, 400L)
        binding.desktopContainer.postDelayed({ nudgeWidget() }, 1500L)
        binding.desktopContainer.postDelayed({ nudgeWidget() }, 4000L)
        // Do not auto-show resize frame on place — user opens it via long-press menu
    }

    /**
     * Ask the widget provider to recompose / push new RemoteViews.
     * Glance (Google News) runs provideGlance on WorkManager and often only emits the
     * "Cargando…" shell until an UPDATE broadcast or data-changed notify arrives.
     */
    private fun requestProviderRefresh(widgetId: Int, info: AppWidgetProviderInfo) {
        try {
            val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE).apply {
                component = info.provider
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, intArrayOf(widgetId))
            }
            sendBroadcast(intent)
            FileLogger.log("WidgetBind", "APPWIDGET_UPDATE broadcast id=$widgetId provider=${info.provider.shortClassName}")
        } catch (e: Exception) {
            FileLogger.e("WidgetBind", "APPWIDGET_UPDATE failed id=$widgetId", e)
        }
        try {
            // Collection / list widgets (and some Glance hosts) listen to these
            appWidgetManager.notifyAppWidgetViewDataChanged(widgetId, android.R.id.list)
            appWidgetManager.notifyAppWidgetViewDataChanged(widgetId, android.R.id.content)
        } catch (_: Exception) {}
    }

    // Keep old name as alias used by earlier call sites
    private fun finalizePlaceWidget(
        widgetId: Int,
        info: AppWidgetProviderInfo,
        spanX: Int,
        spanY: Int
    ) {
        continueAfterBind(widgetId, info, spanX, spanY)
    }

    // ───────────────────────────── Widget Stack ─────────────────────────────

    /**
     * Place an empty Widget Stack on the current page (default 4×2).
     * User then taps + to pick members.
     */
    private fun placeEmptyWidgetStack() {
        val sx = 4.coerceAtMost(columns.coerceAtLeast(1))
        val sy = 2.coerceAtMost(rows.coerceAtLeast(1))
        val free = findFreeCellForSpan(sx, sy)
            ?: run {
                Toast.makeText(this, "No hay espacio libre para la pila", Toast.LENGTH_SHORT).show()
                return
            }
        placeEmptyWidgetStackAt(free.x, free.y, sx, sy)
    }

    private fun placeEmptyWidgetStackAt(cellX: Int, cellY: Int, spanX: Int, spanY: Int) {
        val item = HomeItem(
            id = "stack_" + UUID.randomUUID().toString().take(8),
            label = "Pila de Widgets",
            screen = currentPage,
            cellX = cellX,
            cellY = cellY,
            spanX = spanX.coerceAtLeast(1),
            spanY = spanY.coerceAtLeast(1),
            isWidgetStack = true,
            stackSwipeVertical = true,
            stackCurrentPage = 0
        )
        homeItems.add(item)
        saveAllItems()
        renderDesktop()
        FileLogger.log("WidgetStack", "placed empty stack id=${item.id} cell=$cellX,$cellY span=${item.spanX}x${item.spanY}")
        binding.desktopContainer.post { showStackMemberPicker(item) }
    }

    private fun placeGifWidget() {
        val sx = 2.coerceAtMost(columns.coerceAtLeast(1))
        val sy = 2.coerceAtMost(rows.coerceAtLeast(1))
        val free = findFreeCellForSpan(sx, sy)
            ?: run {
                Toast.makeText(this, "No hay espacio libre para la imagen", Toast.LENGTH_SHORT).show()
                return
            }
        placeGifWidgetAt(free.x, free.y, sx, sy, openPicker = true)
    }

    private fun placeGifWidgetAt(cellX: Int, cellY: Int, spanX: Int, spanY: Int, openPicker: Boolean) {
        val item = HomeItem(
            id = "gif_" + UUID.randomUUID().toString().take(8),
            label = "Imágenes & Gif's Animados",
            screen = currentPage,
            cellX = cellX,
            cellY = cellY,
            spanX = spanX.coerceAtLeast(1),
            spanY = spanY.coerceAtLeast(1),
            isGifWidget = true,
            gifPath = ""
        )
        homeItems.add(item)
        saveAllItems()
        renderDesktop()
        FileLogger.log("GifWidget", "placed id=${item.id} cell=$cellX,$cellY span=${item.spanX}x${item.spanY}")
        if (openPicker) {
            binding.desktopContainer.post { openGifFilePicker(item) }
        }
    }

    private fun openGifFilePicker(item: HomeItem) {
        pendingGifItemId = item.id
        try {
            pickGifLauncher.launch(arrayOf(
                "image/gif", "image/png", "image/jpeg", "image/webp",
                "image/bmp", "image/heic", "image/heif", "image/*"
            ))
        } catch (e: Exception) {
            FileLogger.e("GifWidget", "launch picker failed", e)
            Toast.makeText(this, "No se pudo abrir el selector", Toast.LENGTH_SHORT).show()
            pendingGifItemId = null
        }
    }

    private fun copyGifToFiles(itemId: String, uri: Uri): File? {
        return try {
            val dir = File(filesDir, "gifs").apply { if (!exists()) mkdirs() }
            val mime = contentResolver.getType(uri)?.lowercase().orEmpty()
            val ext = when {
                mime.contains("gif") -> "gif"
                mime.contains("png") -> "png"
                mime.contains("webp") -> "webp"
                mime.contains("jpeg") || mime.contains("jpg") -> "jpg"
                mime.contains("bmp") -> "bmp"
                mime.contains("heic") || mime.contains("heif") -> "heic"
                else -> {
                    // Fallback from path segment
                    val name = uri.lastPathSegment.orEmpty().lowercase()
                    when {
                        name.endsWith(".gif") -> "gif"
                        name.endsWith(".png") -> "png"
                        name.endsWith(".webp") -> "webp"
                        name.endsWith(".jpg") || name.endsWith(".jpeg") -> "jpg"
                        name.endsWith(".bmp") -> "bmp"
                        else -> "img"
                    }
                }
            }
            val dest = File(dir, "$itemId.$ext")
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(dest).use { output -> input.copyTo(output) }
            } ?: return null
            if (dest.length() <= 0L) {
                dest.delete()
                return null
            }
            dest
        } catch (e: Exception) {
            FileLogger.e("GifWidget", "copy failed", e)
            null
        }
    }

    private fun startCustomWidgetDragFromPicker(
        type: String,
        spanX: Int,
        spanY: Int,
        event: MotionEvent
    ) {
        if (draggingApp != null) return
        calculateMetrics()
        dismissWidgetSheet()
        exitOverviewMode()
        calculateMetrics()
        iconLongPressActive = true
        suppressEmptyLongPress = true
        pendingDragCustomType = type
        pendingDragWidgetInfo = null
        val maxC = columns.coerceAtLeast(1)
        val maxR = rows.coerceAtLeast(1)
        pendingDragSpanX = spanX.coerceIn(1, maxC)
        pendingDragSpanY = spanY.coerceIn(1, maxR)
        dragOriginSpanX = pendingDragSpanX
        dragOriginSpanY = pendingDragSpanY
        val contLoc = IntArray(2)
        binding.desktopContainer.getLocationOnScreen(contLoc)
        val localX = event.rawX - contLoc[0] - binding.desktopContainer.paddingLeft
        val localY = event.rawY - contLoc[1] - binding.desktopContainer.paddingTop
        val cw = cellWidth.coerceAtLeast(1f)
        val ch = cellHeight.coerceAtLeast(1f)
        dragOriginCellX = (localX / cw).toInt()
            .coerceIn(0, (maxC - pendingDragSpanX).coerceAtLeast(0))
        dragOriginCellY = (localY / ch).toInt()
            .coerceIn(0, (maxR - pendingDragSpanY).coerceAtLeast(0))
        dragStartRawX = event.rawX
        dragStartRawY = event.rawY
        val label = if (type == "gif") "Imágenes & Gif's Animados" else "Pila de Widgets"
        val fake = AppInfo(
            packageName = "romaster.$type",
            activityName = type,
            label = label,
            icon = null
        )
        draggingApp = fake
        draggingHomeItem = HomeItem(
            id = "pending_$type",
            label = label,
            spanX = pendingDragSpanX,
            spanY = pendingDragSpanY,
            isWidgetStack = type == "stack",
            isGifWidget = type == "gif"
        )
        val shadowRect = gridRect(0, 0, pendingDragSpanX, pendingDragSpanY)
        val sw = shadowRect.width().coerceAtLeast(1)
        val sh = shadowRect.height().coerceAtLeast(1)
        val density = resources.displayMetrics.density
        val shadow = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(sw, sh)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0x33FFFFFF)
                setStroke((2.5f * density).toInt(), 0xFF4FC3F7.toInt())
                cornerRadius = 14f * density
            }
            elevation = 32f
        }
        dragShadow = shadow
        (binding.homeRoot as ViewGroup).addView(shadow)
        shadow.measure(
            View.MeasureSpec.makeMeasureSpec(sw, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(sh, View.MeasureSpec.EXACTLY)
        )
        shadow.layout(0, 0, sw, sh)
        dragGrabOffsetX = sw / 2f
        dragGrabOffsetY = sh / 2f
        moveDragShadow(event.rawX, event.rawY)
        ensureDropOverlay()
        updateDropHighlight(event.rawX, event.rawY)
    }


    /** Find top-left cell where a spanX×spanY rectangle fits without overlapping other items. */
    private fun findFreeCellForSpan(spanX: Int, spanY: Int): Point? {
        val maxX = (columns - spanX).coerceAtLeast(0)
        val maxY = (rows - spanY).coerceAtLeast(0)
        fun overlaps(cx: Int, cy: Int): Boolean {
            for (item in homeItems) {
                if (item.screen != currentPage || item.isDock) continue
                val ix2 = item.cellX + item.spanX - 1
                val iy2 = item.cellY + item.spanY - 1
                val cx2 = cx + spanX - 1
                val cy2 = cy + spanY - 1
                if (cx <= ix2 && cx2 >= item.cellX && cy <= iy2 && cy2 >= item.cellY) return true
            }
            return false
        }
        for (y in 0..maxY) {
            for (x in 0..maxX) {
                if (!overlaps(x, y)) return Point(x, y)
            }
        }
        return null
    }

    /**
     * Build the visual stack container for a HomeItem that is a widget stack.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun createWidgetStackView(item: HomeItem): View? {
        if (!::appWidgetHost.isInitialized) return null
        val density = resources.displayMetrics.density

        // Shared ref so long-press can lock the carousel immediately
        var stackViewRef: RomasterWidgetStackView? = null

        val wrap = object : FrameLayout(this) {
            private val touchSlop = ViewConfiguration.get(this@HomeActivity).scaledTouchSlop
            private val longTimeout = ViewConfiguration.getLongPressTimeout().toLong()
            private var downX = 0f
            private var downY = 0f
            private var longPressArmed = false
            private var dragStarted = false
            private var intercepting = false
            private var stackPagingActive = false
            private val arm = Runnable {
                if (resizingWidgetId != null) return@Runnable
                if (stackPagingActive) return@Runnable
                if (draggingApp != null || isDrawerOpen || suppressIconInteractions) return@Runnable
                if (currentPopup != null) return@Runnable
                longPressArmed = true
                intercepting = true
                iconLongPressActive = true
                // Lock carousel so long-press never competes with page swipe
                stackViewRef?.pagingLocked = true
                try { performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS) } catch (_: Exception) {}
                animate().scaleX(1.03f).scaleY(1.03f).setDuration(100).start()
                showWidgetOptions(item, this)
                FileLogger.log("WidgetStack", "longPress armed id=${item.id}")
            }
            fun notifyStackPagingStarted() {
                stackPagingActive = true
                removeCallbacks(arm)
                longPressArmed = false
            }
            fun notifyStackPagingEnded() {
                stackPagingActive = false
            }

            override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
                if (resizingWidgetId != null) return false
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        if (suppressIconInteractions || isDrawerOpen || drawerGestureActive) return false
                        downX = ev.rawX
                        downY = ev.rawY
                        longPressArmed = false
                        dragStarted = false
                        intercepting = false
                        stackPagingActive = false
                        stackViewRef?.pagingLocked = false
                        postDelayed(arm, longTimeout)
                        return false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dist = hypot((ev.rawX - downX).toDouble(), (ev.rawY - downY).toDouble()).toFloat()
                        if (!longPressArmed && dist > touchSlop) {
                            removeCallbacks(arm)
                        }
                        // After long-press, we own all further motion (menu → drag)
                        if (longPressArmed) return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        removeCallbacks(arm)
                        if (!longPressArmed) iconLongPressActive = false
                        stackPagingActive = false
                        if (!dragStarted) stackViewRef?.pagingLocked = false
                    }
                }
                return intercepting || longPressArmed
            }

            override fun onTouchEvent(ev: MotionEvent): Boolean {
                if (resizingWidgetId != null) {
                    removeCallbacks(arm)
                    return false
                }
                when (ev.actionMasked) {
                    MotionEvent.ACTION_MOVE -> {
                        val dist = hypot((ev.rawX - downX).toDouble(), (ev.rawY - downY).toDouble()).toFloat()
                        // Finger moved after long-press → close menu and start relocating the stack
                        if (longPressArmed && !dragStarted && dist > touchSlop && draggingApp == null) {
                            dragStarted = true
                            dismissCurrentPopup()
                            clearWidgetResize()
                            stackViewRef?.pagingLocked = true
                            FileLogger.log("WidgetStack", "longPress→drag id=${item.id}")
                            val fake = AppInfo(
                                packageName = "stack",
                                activityName = item.id,
                                label = item.label.ifBlank { "Pila" },
                                icon = null
                            )
                            startDesktopDrag(fake, item, this, ev)
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        removeCallbacks(arm)
                        if (draggingHomeItem?.id == item.id) return true
                        animate().scaleX(1f).scaleY(1f).setDuration(100).start()
                        stackPagingActive = false
                        if (!dragStarted) {
                            stackViewRef?.pagingLocked = false
                        }
                        return true
                    }
                }
                return super.onTouchEvent(ev)
            }
        }.apply {
            tag = item.id
            isClickable = true
            isFocusable = true
            clipChildren = true
            clipToPadding = true
            setTag(0x70F10001, true) // mark as widget-like for unlock anim etc.
        }

        val stackView = RomasterWidgetStackView(this).apply {
            swipeVertical = item.stackSwipeVertical
            onAddClick = { showStackMemberPicker(item) }
            onPageChanged = { page ->
                item.stackCurrentPage = page
                saveAllItems()
            }
            onPagingStarted = { wrap.notifyStackPagingStarted() }
            onPagingEnded = { wrap.notifyStackPagingEnded() }
        }
        stackViewRef = stackView

        // Build page views from members
        val pageViews = mutableListOf<View>()
        for (member in item.stackMembers) {
            val page = createStackMemberHostView(item, member)
            if (page != null) pageViews.add(page)
        }
        stackView.setPages(pageViews, item.stackCurrentPage.coerceIn(0, (pageViews.size - 1).coerceAtLeast(0)))

        wrap.addView(
            stackView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        try {
            wrap.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        } catch (_: Exception) {}

        // Apply size options to all member widgets after layout
        wrap.post {
            val expected = gridRect(item.cellX, item.cellY, item.spanX, item.spanY)
            val ew = expected.width().coerceAtLeast(1)
            val eh = expected.height().coerceAtLeast(1)
            for (member in item.stackMembers) {
                if (member.appWidgetId < 0) continue
                val hv = widgetHostViews[member.appWidgetId] ?: continue
                val info = try { appWidgetManager.getAppWidgetInfo(member.appWidgetId) } catch (_: Exception) { null }
                    ?: continue
                applyWidgetSizeOptions(member.appWidgetId, hv, info, ew, eh)
            }
        }
        return wrap
    }

    /**
     * Create (or reuse) an AppWidgetHostView for one stack member.
     */
    private fun createStackMemberHostView(stackItem: HomeItem, member: StackWidgetMember): View? {
        if (member.appWidgetId < 0 || !::appWidgetHost.isInitialized) {
            return createStackMemberPlaceholder(member)
        }
        val info = try {
            appWidgetManager.getAppWidgetInfo(member.appWidgetId)
        } catch (_: Exception) { null }
        if (info == null) return createStackMemberPlaceholder(member)

        val hostView = widgetHostViews[member.appWidgetId] ?: run {
            FileLogger.log("WidgetStack", "createView member id=${member.appWidgetId} provider=${info.provider}")
            val hv = try {
                try { appWidgetHost.startListening() } catch (_: Exception) {}
                appWidgetHost.createView(this, member.appWidgetId, info)
            } catch (e: Exception) {
                FileLogger.e("WidgetStack", "createView failed id=${member.appWidgetId}", e)
                return createStackMemberPlaceholder(member)
            }
            widgetHostViews[member.appWidgetId] = hv
            hv
        }
        (hostView.parent as? ViewGroup)?.removeView(hostView)
        hostView.isLongClickable = false
        try {
            val p = AppWidgetHostView.getDefaultPaddingForWidget(this, info.provider, null)
            hostView.setPadding(p.left, p.top, p.right, p.bottom)
        } catch (_: Exception) {
            val d = (6 * resources.displayMetrics.density).toInt()
            hostView.setPadding(d, d, d, d)
        }
        // Push stack span as size options so providers that adapt (and those that
        // only look right after a resize) get a correct size immediately.
        if (hostView.getTag(0x70F10D03) != true) {
            hostView.setTag(0x70F10D03, true)
            hostView.addOnLayoutChangeListener { v, left, top, right, bottom, _, _, _, _ ->
                val w = right - left
                val h = bottom - top
                if (w <= 1 || h <= 1) return@addOnLayoutChangeListener
                if (resizingWidgetId != null) return@addOnLayoutChangeListener
                try {
                    applyWidgetSizeOptions(member.appWidgetId, hostView, info, w, h)
                } catch (_: Exception) {}
            }
        }
        // Also push once from stack span in case layout is delayed
        hostView.post {
            try {
                val rect = gridRect(stackItem.cellX, stackItem.cellY, stackItem.spanX, stackItem.spanY)
                val w = rect.width().coerceAtLeast(1)
                val h = rect.height().coerceAtLeast(1)
                applyWidgetSizeOptions(member.appWidgetId, hostView, info, w, h)
            } catch (_: Exception) {}
        }
        return hostView
    }

    private fun createStackMemberPlaceholder(member: StackWidgetMember): View {
        val density = resources.displayMetrics.density
        val parentStack = homeItems.find {
            it.isWidgetStack && it.stackMembers.any { m -> m.providerKey == member.providerKey }
        }
        if (parentStack != null) {
            scheduleStackMemberRecover(parentStack, member)
        }
        return TextView(this).apply {
            text = "Widget\nno disponible"
            gravity = Gravity.CENTER
            setTextColor(0xAAFFFFFF.toInt())
            textSize = 12f
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0x44000000)
                cornerRadius = 12f * density
            }
        }
    }

    /**
     * Multi-checkbox dialog: pick widgets that fit the current stack size.
     */
    /**
     * Selector multi-checkbox con el mismo estilo visual del cajón de widgets
     * (grupos por app expandibles + preview + checkbox por widget).
     */
    private fun showStackMemberPicker(item: HomeItem) {
        val mgr = appWidgetManager
        val pm = packageManager
        // Show every widget that can live on the desktop. Stack members are always
        // resized to the stack's current span — do NOT filter by stack size or many
        // providers disappear until the user manually enlarges the stack.
        val maxC = columns.coerceAtLeast(1)
        val maxR = rows.coerceAtLeast(1)
        val density = resources.displayMetrics.density
        val cw = cellWidth.coerceAtLeast(1f)
        val ch = cellHeight.coerceAtLeast(1f)
        FileLogger.log(
            "WidgetStack",
            "picker open stack=${item.id} span=${item.spanX}x${item.spanY} desktop=${maxC}x${maxR}"
        )

        data class Cand(
            val info: AppWidgetProviderInfo,
            val label: String,
            val sx: Int,
            val sy: Int,
            val preview: android.graphics.drawable.Drawable?,
            val pkg: String,
            val appLabel: String,
            val appIcon: android.graphics.drawable.Drawable?
        )

        val candidates = mutableListOf<Cand>()
        val providers = try { mgr.installedProviders } catch (_: Exception) { emptyList() }
        val appLabelCache = HashMap<String, String>()
        val appIconCache = HashMap<String, android.graphics.drawable.Drawable?>()
        for (info in providers) {
            var sx = 1
            var sy = 1
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                try {
                    val tx = info.targetCellWidth
                    val ty = info.targetCellHeight
                    if (tx > 0 && ty > 0) {
                        sx = tx.coerceIn(1, columns)
                        sy = ty.coerceIn(1, rows)
                    }
                } catch (_: Exception) {}
            }
            if (sx == 1 && sy == 1 && (info.minWidth > 0 || info.minHeight > 0)) {
                sx = kotlin.math.ceil(info.minWidth.toDouble() / cw).toInt().coerceIn(1, columns)
                sy = kotlin.math.ceil(info.minHeight.toDouble() / ch).toInt().coerceIn(1, rows)
            }
            // Only exclude widgets that cannot fit the desktop at all
            if (sx > maxC || sy > maxR) continue
            val pkg = info.provider.packageName
            if (pkg !in appLabelCache) {
                try {
                    val ai = pm.getApplicationInfo(pkg, 0)
                    appLabelCache[pkg] = pm.getApplicationLabel(ai).toString()
                    appIconCache[pkg] = pm.getApplicationIcon(ai)
                } catch (_: Exception) {
                    appLabelCache[pkg] = pkg
                    appIconCache[pkg] = null
                }
            }
            val label = try { info.loadLabel(pm).toString() } catch (_: Exception) { info.provider.className }
            val preview = try {
                info.loadPreviewImage(this, resources.displayMetrics.densityDpi)
                    ?: info.loadIcon(this, resources.displayMetrics.densityDpi)
            } catch (_: Exception) { null }
            candidates.add(
                Cand(
                    info, label, sx, sy, preview, pkg,
                    appLabelCache[pkg] ?: pkg,
                    appIconCache[pkg]
                )
            )
        }
        if (candidates.isEmpty()) {
            Toast.makeText(this, "No hay widgets que quepan en ${maxC}×${maxR}", Toast.LENGTH_SHORT).show()
            return
        }

        FileLogger.log("WidgetStack", "picker candidates=${candidates.size} (desktop filter)")
        // Group by package, preserve insertion order of labels
        val groups = linkedMapOf<String, MutableList<Cand>>()
        for (c in candidates.sortedBy { it.appLabel.lowercase() }) {
            groups.getOrPut(c.pkg) { mutableListOf() }.add(c)
        }
        for ((_, list) in groups) list.sortBy { it.label.lowercase() }

        val selectedKeys = mutableSetOf<String>()
        for (m in item.stackMembers) selectedKeys.add(m.providerKey)

        val dialog = android.app.Dialog(this, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.setCancelable(true)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF1C1C1E.toInt())
                cornerRadii = floatArrayOf(
                    22f * density, 22f * density,
                    22f * density, 22f * density,
                    0f, 0f, 0f, 0f
                )
            }
            setPadding(0, (8 * density).toInt(), 0, 0)
        }

        // Handle
        root.addView(View(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 2.5f * density
                setColor(0xFF888888.toInt())
            }
            layoutParams = LinearLayout.LayoutParams(
                (40 * density).toInt(), (4 * density).toInt()
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = (4 * density).toInt()
                bottomMargin = (8 * density).toInt()
            }
        })

        root.addView(TextView(this).apply {
            text = "Agregar a la pila  (${maxC}×${maxR})"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding((18 * density).toInt(), (4 * density).toInt(), (18 * density).toInt(), (12 * density).toInt())
        })

        val scroll = android.widget.ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            isFillViewport = true
        }
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((12 * density).toInt(), 0, (12 * density).toInt(), (16 * density).toInt())
        }
        scroll.addView(list)

        fun providerKey(info: AppWidgetProviderInfo) =
            "${info.provider.packageName}/${info.provider.className}"

        fun makeCheckBox(checked: Boolean): android.widget.CheckBox {
            return android.widget.CheckBox(this).apply {
                isChecked = checked
                // Keep default system checkbox for clarity
                buttonTintList = android.content.res.ColorStateList.valueOf(0xFF4FC3F7.toInt())
            }
        }

        for ((pkg, entries) in groups) {
            val first = entries.first()
            var expanded = false
            val header = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding((14 * density).toInt(), (12 * density).toInt(), (14 * density).toInt(), (12 * density).toInt())
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 14f * density
                    setColor(0xFF2C2C2E.toInt())
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (8 * density).toInt() }
            }
            header.addView(ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams((36 * density).toInt(), (36 * density).toInt()).apply {
                    marginEnd = (12 * density).toInt()
                }
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageDrawable(first.appIcon)
            })
            val texts = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            texts.addView(TextView(this).apply {
                text = first.appLabel
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 15f
            })
            texts.addView(TextView(this).apply {
                text = if (entries.size == 1) "1 widget" else "${entries.size} widgets"
                setTextColor(0xFFAAAAAA.toInt())
                textSize = 12f
            })
            header.addView(texts)
            val arrow = TextView(this).apply {
                tag = "arrow"
                text = "▼"
                setTextColor(0xFFAAAAAA.toInt())
                textSize = 14f
            }
            header.addView(arrow)

            val body = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
                setPadding((4 * density).toInt(), 0, (4 * density).toInt(), (12 * density).toInt())
            }

            header.setOnClickListener {
                expanded = !expanded
                body.visibility = if (expanded) View.VISIBLE else View.GONE
                arrow.text = if (expanded) "▲" else "▼"
            }

            var row: LinearLayout? = null
            entries.forEachIndexed { index, cand ->
                if (index % 2 == 0) {
                    row = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        )
                    }
                    body.addView(row)
                }
                val key = providerKey(cand.info)
                val cell = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding((6 * density).toInt(), (8 * density).toInt(), (6 * density).toInt(), (8 * density).toInt())
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    isClickable = true
                    isFocusable = true
                }
                val previewBox = FrameLayout(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        (88 * density).toInt()
                    )
                    background = android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = 12f * density
                        setColor(0xFF3A3A3C.toInt())
                    }
                }
                previewBox.addView(ImageView(this).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setPadding((6 * density).toInt(), (6 * density).toInt(), (6 * density).toInt(), (6 * density).toInt())
                    setImageDrawable(cand.preview)
                })
                val check = makeCheckBox(selectedKeys.contains(key)).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.TOP or Gravity.END
                    ).apply {
                        topMargin = (4 * density).toInt()
                        marginEnd = (4 * density).toInt()
                    }
                    // Prevent checkbox from eating the cell click oddly
                    isClickable = false
                    isFocusable = false
                }
                previewBox.addView(check)
                cell.addView(previewBox)
                cell.addView(TextView(this).apply {
                    text = cand.label
                    setTextColor(0xFFE0E0E0.toInt())
                    textSize = 12f
                    gravity = Gravity.CENTER
                    maxLines = 2
                    setPadding(0, (6 * density).toInt(), 0, 0)
                })
                cell.addView(TextView(this).apply {
                    text = "${cand.sx} × ${cand.sy}"
                    setTextColor(0xFF888888.toInt())
                    textSize = 11f
                    gravity = Gravity.CENTER
                })
                fun toggle() {
                    val now = !check.isChecked
                    check.isChecked = now
                    if (now) selectedKeys.add(key) else selectedKeys.remove(key)
                }
                cell.setOnClickListener { toggle() }
                check.setOnClickListener { /* mirrored via cell */ }
                row!!.addView(cell)
            }

            list.addView(header)
            list.addView(body)
        }

        root.addView(scroll)

        // Bottom actions
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding((16 * density).toInt(), (10 * density).toInt(), (16 * density).toInt(), (16 * density).toInt())
            gravity = Gravity.END
        }
        actions.addView(TextView(this).apply {
            text = "Cancelar"
            setTextColor(0xFFAAAAAA.toInt())
            textSize = 15f
            setPadding((16 * density).toInt(), (12 * density).toInt(), (16 * density).toInt(), (12 * density).toInt())
            setOnClickListener { dialog.dismiss() }
        })
        actions.addView(TextView(this).apply {
            text = "Crear"
            setTextColor(0xFF4FC3F7.toInt())
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding((16 * density).toInt(), (12 * density).toInt(), (16 * density).toInt(), (12 * density).toInt())
            setOnClickListener {
                val chosen = candidates.filter { providerKey(it.info) in selectedKeys }.map { it.info }
                FileLogger.log(
                    "WidgetStack",
                    "picker Crear selectedKeys=${selectedKeys.size} chosen=${chosen.size} " +
                        "keys=${selectedKeys.joinToString()}"
                )
                dialog.dismiss()
                if (chosen.isEmpty()) {
                    deleteStackMembers(item)
                    item.stackMembers.clear()
                    item.stackCurrentPage = 0
                    saveAllItems()
                    renderDesktop()
                } else {
                    applyStackMembers(item, chosen)
                }
            }
        })
        root.addView(actions)

        dialog.setContentView(root)
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            (resources.displayMetrics.heightPixels * 0.82f).toInt()
        )
        dialog.window?.setGravity(Gravity.BOTTOM)
        dialog.show()
    }

    private fun applyStackMembers(item: HomeItem, providers: List<AppWidgetProviderInfo>) {
        FileLogger.log(
            "WidgetStack",
            "applyStackMembers START item=${item.id} selected=${providers.size} " +
                "existing=${item.stackMembers.size} span=${item.spanX}x${item.spanY}"
        )
        val keep = mutableListOf<StackWidgetMember>()
        val existingByKey = item.stackMembers.associateBy { it.providerKey }.toMutableMap()
        val needPermission = mutableListOf<AppWidgetProviderInfo>()
        var addedNow = 0
        var reused = 0
        var allocateFail = 0
        var silentFail = 0

        for ((idx, info) in providers.withIndex()) {
            val key = "${info.provider.packageName}/${info.provider.className}"
            val existing = existingByKey.remove(key)
            if (existing != null && existing.appWidgetId >= 0) {
                // Verify host id is still valid
                val still = try { appWidgetManager.getAppWidgetInfo(existing.appWidgetId) } catch (_: Exception) { null }
                if (still != null) {
                    keep.add(existing)
                    reused++
                    FileLogger.log("WidgetStack", "[$idx] REUSE id=${existing.appWidgetId} $key")
                    continue
                }
                FileLogger.log("WidgetStack", "[$idx] stale existing id=${existing.appWidgetId} for $key — rebind")
            }
            val id = try { appWidgetHost.allocateAppWidgetId() } catch (e: Exception) {
                FileLogger.e("WidgetStack", "[$idx] allocate FAILED $key", e)
                allocateFail++
                -1
            }
            if (id < 0) continue

            // Try silent bind: 2-arg first (most reliable), then 3-arg with options
            var bound = false
            var boundHow = ""
            try {
                bound = appWidgetManager.bindAppWidgetIdIfAllowed(id, info.provider)
                if (bound) boundHow = "2arg"
            } catch (e: Exception) {
                FileLogger.e("WidgetStack", "[$idx] bind 2arg threw $key", e)
            }
            if (!bound) {
                try {
                    val opts = android.os.Bundle().apply {
                        putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, (item.spanX * cellWidth).toInt().coerceAtLeast(1))
                        putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, (item.spanY * cellHeight).toInt().coerceAtLeast(1))
                        putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, (item.spanX * cellWidth).toInt().coerceAtLeast(1))
                        putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, (item.spanY * cellHeight).toInt().coerceAtLeast(1))
                    }
                    bound = appWidgetManager.bindAppWidgetIdIfAllowed(id, info.provider, opts)
                    if (bound) boundHow = "3arg"
                } catch (e: Exception) {
                    FileLogger.e("WidgetStack", "[$idx] bind 3arg threw $key", e)
                }
            }
            // Confirm with AppWidgetManager (some OEMs return true then clear)
            val confirmed = if (bound) {
                try { appWidgetManager.getAppWidgetInfo(id) != null } catch (_: Exception) { false }
            } else false

            FileLogger.log(
                "WidgetStack",
                "[$idx] bind id=$id $key bound=$bound how=$boundHow confirmed=$confirmed"
            )

            if (bound && confirmed) {
                keep.add(
                    StackWidgetMember(
                        packageName = info.provider.packageName,
                        className = info.provider.className,
                        appWidgetId = id
                    )
                )
                addedNow++
            } else {
                silentFail++
                try { appWidgetHost.deleteAppWidgetId(id) } catch (_: Exception) {}
                needPermission.add(info)
                FileLogger.log("WidgetStack", "[$idx] → queue for system bind dialog: $key")
            }
        }

        // Delete host ids of removed members
        for ((key, orphan) in existingByKey) {
            FileLogger.log("WidgetStack", "remove orphan $key id=${orphan.appWidgetId}")
            if (orphan.appWidgetId >= 0) {
                widgetHostViews.remove(orphan.appWidgetId)
                try { appWidgetHost.deleteAppWidgetId(orphan.appWidgetId) } catch (_: Exception) {}
            }
        }
        // Prefer to keep showing the same provider if it survived; otherwise stay on same index
        val prevKey = item.stackMembers.getOrNull(item.stackCurrentPage)?.providerKey
        item.stackMembers.clear()
        item.stackMembers.addAll(keep)
        val newIdx = when {
            keep.isEmpty() -> 0
            prevKey != null -> {
                val i = keep.indexOfFirst { it.providerKey == prevKey }
                if (i >= 0) i else item.stackCurrentPage.coerceIn(0, keep.lastIndex)
            }
            else -> item.stackCurrentPage.coerceIn(0, keep.lastIndex)
        }
        item.stackCurrentPage = newIdx
        try { appWidgetHost.startListening() } catch (_: Exception) {}
        saveAllItems()
        renderDesktop()
        FileLogger.log(
            "WidgetStack",
            "applyStackMembers DONE keep=${keep.size} addedNow=$addedNow reused=$reused " +
                "silentFail=$silentFail allocFail=$allocateFail needPerm=${needPermission.size}"
        )
        binding.desktopContainer.postDelayed({
            for (m in item.stackMembers) {
                if (m.appWidgetId < 0) continue
                val info = try { appWidgetManager.getAppWidgetInfo(m.appWidgetId) } catch (_: Exception) { null }
                if (info != null) requestProviderRefresh(m.appWidgetId, info)
            }
        }, 500L)

        if (needPermission.isEmpty()) {
            if (addedNow > 0 || reused > 0) {
                Toast.makeText(
                    this,
                    "Pila: ${keep.size} widget(s) (${addedNow} nuevos)",
                    Toast.LENGTH_SHORT
                ).show()
            }
            return
        }
        pendingStackItem = item
        pendingStackQueue.clear()
        pendingStackQueue.addAll(needPermission)
        pendingStackAdded = addedNow + reused
        pendingStackFailed = 0
        savePendingStackQueueToPrefs()
        Toast.makeText(
            this,
            "Permiso para ${needPermission.size} widget(s)…",
            Toast.LENGTH_SHORT
        ).show()
        FileLogger.log("WidgetStack", "starting permission queue size=${needPermission.size}")
        drainPendingStackQueue()
    }

    private fun savePendingStackQueueToPrefs() {
        try {
            val p = getSharedPreferences("romaster_widget_pending", MODE_PRIVATE).edit()
            p.putString("stackItemId", pendingStackItem?.id)
            p.putInt("stackAdded", pendingStackAdded)
            p.putInt("stackFailed", pendingStackFailed)
            val arr = org.json.JSONArray()
            for (info in pendingStackQueue) {
                arr.put(info.provider.flattenToString())
            }
            p.putString("stackQueue", arr.toString())
            p.apply()
            FileLogger.log("WidgetStack", "saved stack queue size=${pendingStackQueue.size} item=${pendingStackItem?.id}")
        } catch (e: Exception) {
            FileLogger.e("WidgetStack", "save stack queue failed", e)
        }
    }

    private fun restorePendingStackQueueFromPrefs() {
        if (pendingStackItem != null) return
        try {
            val p = getSharedPreferences("romaster_widget_pending", MODE_PRIVATE)
            val itemId = p.getString("stackItemId", null) ?: return
            val item = homeItems.find { it.id == itemId && it.isWidgetStack } ?: run {
                FileLogger.log("WidgetStack", "restore queue: item $itemId not found")
                return
            }
            val qJson = p.getString("stackQueue", null) ?: return
            val arr = org.json.JSONArray(qJson)
            val list = mutableListOf<AppWidgetProviderInfo>()
            for (i in 0 until arr.length()) {
                val flat = arr.optString(i) ?: continue
                val cn = android.content.ComponentName.unflattenFromString(flat) ?: continue
                val info = appWidgetManager.installedProviders.firstOrNull { it.provider == cn }
                if (info != null) list.add(info)
            }
            pendingStackItem = item
            pendingStackQueue.clear()
            pendingStackQueue.addAll(list)
            pendingStackAdded = p.getInt("stackAdded", 0)
            pendingStackFailed = p.getInt("stackFailed", 0)
            FileLogger.log("WidgetStack", "restored stack queue size=${list.size} item=$itemId")
        } catch (e: Exception) {
            FileLogger.e("WidgetStack", "restore stack queue failed", e)
        }
    }

    private fun clearPendingStackQueuePrefs() {
        try {
            getSharedPreferences("romaster_widget_pending", MODE_PRIVATE).edit()
                .remove("stackItemId")
                .remove("stackQueue")
                .remove("stackAdded")
                .remove("stackFailed")
                .apply()
        } catch (_: Exception) {}
    }

    /** Process next provider in the stack bind-permission queue. */
    private fun drainPendingStackQueue() {
        restorePendingStackQueueFromPrefs()
        val item = pendingStackItem
        if (item == null) {
            FileLogger.log("WidgetStack", "drain: no pendingStackItem")
            return
        }
        if (pendingStackQueue.isEmpty()) {
            val total = item.stackMembers.size
            val failed = pendingStackFailed
            FileLogger.log("WidgetStack", "drain DONE members=$total failed=$failed")
            pendingStackItem = null
            clearPendingStackQueuePrefs()
            saveAllItems()
            renderDesktop()
            val msg = when {
                failed == 0 -> "Pila actualizada ($total widget(s))"
                total > 0 -> "Pila: $total agregados, $failed sin permiso"
                else -> "No se pudieron agregar widgets (sin permiso)"
            }
            try { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() } catch (_: Exception) {}
            return
        }
        val info = pendingStackQueue.removeAt(0)
        savePendingStackQueueToPrefs()
        FileLogger.log(
            "WidgetStack",
            "drain next provider=${info.provider.shortClassName} remaining=${pendingStackQueue.size}"
        )
        val id = try { appWidgetHost.allocateAppWidgetId() } catch (_: Exception) { -1 }
        if (id < 0) {
            pendingStackFailed++
            FileLogger.e("WidgetStack", "drain allocate failed")
            savePendingStackQueueToPrefs()
            drainPendingStackQueue()
            return
        }
        pendingBindWidgetId = id
        pendingWidgetInfo = info
        pendingWidgetSpanX = item.spanX.coerceAtLeast(1)
        pendingWidgetSpanY = item.spanY.coerceAtLeast(1)
        // Try silent bind again (permission may already be granted)
        var allowed = false
        try {
            allowed = appWidgetManager.bindAppWidgetIdIfAllowed(id, info.provider)
        } catch (_: Exception) {}
        FileLogger.log("WidgetStack", "drain silent bind id=$id allowed=$allowed")
        if (allowed) {
            val confirmed = try { appWidgetManager.getAppWidgetInfo(id) != null } catch (_: Exception) { false }
            if (confirmed) {
                finishStackMemberBind(id, info)
                return
            }
            FileLogger.log("WidgetStack", "drain silent bind not confirmed")
        }
        val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
        }
        try {
            savePendingWidgetBindToPrefs()
            savePendingStackQueueToPrefs()
            FileLogger.log("WidgetStack", "launching bind dialog id=$id provider=${info.provider.shortClassName}")
            bindWidgetLauncher.launch(intent)
        } catch (e: Exception) {
            FileLogger.e("WidgetStack", "launch bind dialog failed", e)
            try { appWidgetHost.deleteAppWidgetId(id) } catch (_: Exception) {}
            pendingBindWidgetId = -1
            pendingWidgetInfo = null
            clearPendingWidgetBindPrefs()
            pendingStackFailed++
            savePendingStackQueueToPrefs()
            drainPendingStackQueue()
        }
    }

    /** After a successful system bind while filling a stack. */
    private fun finishStackMemberBind(widgetId: Int, info: AppWidgetProviderInfo) {
        restorePendingStackQueueFromPrefs()
        val item = pendingStackItem
        pendingBindWidgetId = -1
        pendingWidgetInfo = null
        clearPendingWidgetBindPrefs()
        if (item == null) {
            FileLogger.log("WidgetStack", "finishStackMemberBind: no pendingStackItem → standalone place")
            continueAfterBind(widgetId, info, pendingWidgetSpanX, pendingWidgetSpanY)
            return
        }
        // Avoid duplicates if dialog re-delivered
        val key = "${info.provider.packageName}/${info.provider.className}"
        if (item.stackMembers.any { it.providerKey == key && it.appWidgetId >= 0 }) {
            FileLogger.log("WidgetStack", "finishStackMemberBind duplicate skip $key")
            try { appWidgetHost.deleteAppWidgetId(widgetId) } catch (_: Exception) {}
            drainPendingStackQueue()
            return
        }
        item.stackMembers.add(
            StackWidgetMember(
                packageName = info.provider.packageName,
                className = info.provider.className,
                appWidgetId = widgetId
            )
        )
        pendingStackAdded++
        try { appWidgetHost.startListening() } catch (_: Exception) {}
        saveAllItems()
        savePendingStackQueueToPrefs()
        renderDesktop()
        FileLogger.log(
            "WidgetStack",
            "finishStackMemberBind id=$widgetId provider=${info.provider.shortClassName} " +
                "total=${item.stackMembers.size} remaining=${pendingStackQueue.size}"
        )
        drainPendingStackQueue()
    }

    /**
     * Remove the currently visible page from the stack and advance to the next
     * (or previous if we were on the last page). Host id is released.
     */
    private fun removeCurrentStackMember(item: HomeItem) {
        if (!item.isWidgetStack) return
        if (item.stackMembers.isEmpty()) {
            Toast.makeText(this, "La pila está vacía", Toast.LENGTH_SHORT).show()
            return
        }
        val idx = item.stackCurrentPage.coerceIn(0, item.stackMembers.lastIndex)
        val removed = item.stackMembers.removeAt(idx)
        FileLogger.log(
            "WidgetStack",
            "removeCurrent page=$idx id=${removed.appWidgetId} provider=${removed.providerKey} remaining=${item.stackMembers.size}"
        )
        if (removed.appWidgetId >= 0) {
            widgetHostViews.remove(removed.appWidgetId)
            try { appWidgetHost.deleteAppWidgetId(removed.appWidgetId) } catch (_: Exception) {}
        }
        if (item.stackMembers.isEmpty()) {
            item.stackCurrentPage = 0
            Toast.makeText(this, "Pila vacía — agregá widgets desde Administrar", Toast.LENGTH_SHORT).show()
        } else {
            // Stay on same index (now the next widget) or clamp to last
            item.stackCurrentPage = idx.coerceIn(0, item.stackMembers.lastIndex)
            Toast.makeText(this, "Widget quitado (${item.stackMembers.size} restantes)", Toast.LENGTH_SHORT).show()
        }
        saveAllItems()
        renderDesktop()
    }

    private fun deleteStackMembers(item: HomeItem) {
        for (m in item.stackMembers) {
            if (m.appWidgetId >= 0) {
                widgetHostViews.remove(m.appWidgetId)
                try { appWidgetHost.deleteAppWidgetId(m.appWidgetId) } catch (_: Exception) {}
            }
        }
        item.stackMembers.clear()
    }

    /**
     * Placeholder when a widget is not bound or the system denied access.
     * Tapping the gear re-launches ACTION_APPWIDGET_BIND (same dialog Lawnchair shows).
     */
    private fun createWidgetPermissionPlaceholder(item: HomeItem): View {
        val density = resources.displayMetrics.density
        val box = FrameLayout(this).apply {
            tag = item.id
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0x99000000.toInt())
                setStroke((1.5f * density).toInt(), 0x66FFFFFF)
                cornerRadius = 12f * density
            }
            isClickable = true
            isFocusable = true
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        val gear = TextView(this).apply {
            text = "⚙"
            textSize = 28f
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
        }
        val msg = TextView(this).apply {
            text = "Tocar para permitir widget"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(0xCCFFFFFF.toInt())
            setPadding((8 * density).toInt(), (6 * density).toInt(), (8 * density).toInt(), 0)
        }
        col.addView(gear)
        col.addView(msg)
        box.addView(col)
        box.setOnClickListener { rebindWidgetFromPlaceholder(item) }
        box.setOnLongClickListener {
            showWidgetOptions(item, box)
            true
        }
        // Auto-retry in background (same path as "Reintentar / actualizar")
        scheduleStandaloneWidgetRecover(item)
        return box
    }

    private fun rebindWidgetFromPlaceholder(item: HomeItem) {
        val provider = android.content.ComponentName(item.packageName, item.activityName)
        val info = try {
            appWidgetManager.installedProviders.firstOrNull { it.provider == provider }
        } catch (_: Exception) { null }
        FileLogger.log("WidgetBind", "rebind tap itemId=${item.id} oldId=${item.appWidgetId} provider=$provider found=${info != null}")
        if (info == null) {
            Toast.makeText(this, "Widget no disponible", Toast.LENGTH_SHORT).show()
            return
        }
        pendingWidgetSpanX = item.spanX.coerceAtLeast(1)
        pendingWidgetSpanY = item.spanY.coerceAtLeast(1)
        pendingWidgetInfo = info
        pendingPlaceCellX = item.cellX
        pendingPlaceCellY = item.cellY
        if (item.screen != currentPage) {
            currentPage = item.screen.coerceIn(0, (prefs.pageCount - 1).coerceAtLeast(0))
        }

        // Always use a fresh host id on retry — reusing a half-bound id is what yields
        // RESULT_CANCELED / "No se pudo vincular" after the user presses Crear.
        val oldId = item.appWidgetId
        if (oldId >= 0) {
            widgetHostViews.remove(oldId)
            try { appWidgetHost.deleteAppWidgetId(oldId) } catch (_: Exception) {}
            lastSizeOptionsKey.remove(oldId)
            lastSizeOptionsAt.remove(oldId)
            FileLogger.log("WidgetBind", "deleted old id=$oldId")
        }
        val id = try {
            appWidgetHost.allocateAppWidgetId()
        } catch (e: Exception) {
            FileLogger.e("WidgetBind", "allocate failed", e)
            Toast.makeText(this, "Error al crear widget", Toast.LENGTH_SHORT).show()
            return
        }
        item.appWidgetId = id
        val idxItem = homeItems.indexOfFirst { it.id == item.id }
        if (idxItem >= 0) homeItems[idxItem].appWidgetId = id
        saveAllItems()
        pendingBindWidgetId = id
        FileLogger.log("WidgetBind", "rebind allocated id=$id cell=${item.cellX},${item.cellY}")

        // Prefer silent bind when host already has permission; else system dialog
        val bindOpts = buildWidgetOptionsForSpan(pendingWidgetSpanX, pendingWidgetSpanY)
        val allowed = try {
            appWidgetManager.bindAppWidgetIdIfAllowed(id, info.provider, bindOpts)
        } catch (e: Exception) {
            FileLogger.e("WidgetBind", "bindIfAllowed failed", e)
            try {
                appWidgetManager.bindAppWidgetIdIfAllowed(id, info.provider)
            } catch (_: Exception) { false }
        }
        FileLogger.log("WidgetBind", "rebind allowed=$allowed optsDp=${bindOpts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)}x${bindOpts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)}")
        if (allowed) {
            clearPendingWidgetBindPrefs()
            continueAfterBind(id, info, pendingWidgetSpanX, pendingWidgetSpanY)
            return
        }
        val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_OPTIONS, buildWidgetOptionsForSpan(pendingWidgetSpanX, pendingWidgetSpanY))
        }
        try {
            savePendingWidgetBindToPrefs()
            bindWidgetLauncher.launch(intent)
        } catch (e: Exception) {
            FileLogger.e("WidgetBind", "rebind launch failed", e)
            Toast.makeText(this, "No se pudo abrir el permiso de widgets", Toast.LENGTH_SHORT).show()
        }
    }



    // ── Auto-recover widgets that failed to bind / show "Widget no disponible" ──

    private fun recoverKey(itemId: String, memberKey: String? = null): String =
        if (memberKey != null) "$itemId|$memberKey" else itemId

    /**
     * True when AppWidgetHostView is showing the system error shell
     * ("No se pudo agregar el widget" / "Can't load widget"), which can happen
     * even when getAppWidgetInfo() still returns a provider (e.g. Google Search).
     */
    private fun hostViewShowsWidgetError(root: View?): Boolean {
        if (root == null) return true
        if (root.getTag(0x70F10D20) == "error_shell") return true
        // Empty host after layout often means framework error shell not yet attached
        if (root is ViewGroup && root.childCount == 0 && root.width > 1 && root.height > 1) {
            return true
        }
        val needles = arrayOf(
            "No se pudo agregar",
            "No se pudo cargar",
            "No se pudo vincular",
            "Can't load widget",
            "Couldn't add",
            "Could not add",
            "Problem loading widget",
            "Widget no disponible",
            "Error loading widget",
            "Can't add widget",
            "Unable to add"
        )
        fun walk(v: View): Boolean {
            if (v is TextView) {
                val s = v.text?.toString().orEmpty()
                if (s.isNotEmpty() && needles.any { s.contains(it, ignoreCase = true) }) return true
            }
            // Some OEM error shells put text on contentDescription
            val cd = v.contentDescription?.toString().orEmpty()
            if (cd.isNotEmpty() && needles.any { cd.contains(it, ignoreCase = true) }) return true
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) {
                    if (walk(v.getChildAt(i))) return true
                }
            }
            return false
        }
        return walk(root)
    }

    /**
     * After a full desktop paint, scan for unbound / dead widget ids and for
     * host views stuck on the system error shell; schedule silent recovery.
     */
    private fun scheduleRecoverAllBrokenWidgets() {
        if (!::appWidgetHost.isInitialized) return
        for (item in homeItems) {
            if (item.isWidget && !item.isWidgetStack) {
                val info = try {
                    if (item.appWidgetId >= 0) appWidgetManager.getAppWidgetInfo(item.appWidgetId) else null
                } catch (_: Exception) { null }
                if (info == null) {
                    scheduleStandaloneWidgetRecover(item)
                } else {
                    // Bound id can still render the error shell — health-check after layout
                    scheduleStandaloneWidgetHealthCheck(item)
                }
            } else if (item.isWidgetStack) {
                for (m in item.stackMembers) {
                    val info = try {
                        if (m.appWidgetId >= 0) appWidgetManager.getAppWidgetInfo(m.appWidgetId) else null
                    } catch (_: Exception) { null }
                    if (info == null) scheduleStackMemberRecover(item, m)
                    else scheduleStackMemberHealthCheck(item, m)
                }
            }
        }
    }

    /** Delayed checks for error-shell RemoteViews (Google Search, etc.). */
    private fun scheduleStandaloneWidgetHealthCheck(item: HomeItem) {
        val key = recoverKey(item.id) + ":hc"
        if (!widgetRecoverPending.add(key)) return
        fun checkOnce(tag: String) {
            val live = homeItems.find { it.id == item.id && it.isWidget && !it.isWidgetStack }
                ?: return
            val hv = widgetHostViews[live.appWidgetId]
            if (hv == null || hostViewShowsWidgetError(hv)) {
                FileLogger.log(
                    "WidgetBind",
                    "healthCheck $tag errorShell=true id=${live.appWidgetId} item=${live.id} → silent rebind"
                )
                // Allow recover attempts even if a previous soft path marked pending
                widgetRecoverPending.remove(recoverKey(live.id))
                val k = recoverKey(live.id)
                val a = widgetRecoverAttempts[k] ?: 0
                if (a < 4) scheduleStandaloneWidgetRecover(live)
            }
        }
        binding.desktopContainer.postDelayed({
            widgetRecoverPending.remove(key)
            checkOnce("t1")
        }, 1100L)
        binding.desktopContainer.postDelayed({ checkOnce("t2") }, 3200L)
        binding.desktopContainer.postDelayed({ checkOnce("t3") }, 6500L)
    }

    private fun scheduleStackMemberHealthCheck(stackItem: HomeItem, member: StackWidgetMember) {
        val key = recoverKey(stackItem.id, member.providerKey) + ":hc"
        if (!widgetRecoverPending.add(key)) return
        fun checkOnce() {
            val liveStack = homeItems.find { it.id == stackItem.id && it.isWidgetStack } ?: return
            val liveMember = liveStack.stackMembers.find { it.providerKey == member.providerKey } ?: return
            val hv = widgetHostViews[liveMember.appWidgetId]
            if (hv == null || hostViewShowsWidgetError(hv)) {
                widgetRecoverPending.remove(recoverKey(liveStack.id, liveMember.providerKey))
                scheduleStackMemberRecover(liveStack, liveMember)
            }
        }
        binding.desktopContainer.postDelayed({
            widgetRecoverPending.remove(key)
            checkOnce()
        }, 1200L)
        binding.desktopContainer.postDelayed({ checkOnce() }, 4000L)
    }

    private fun scheduleStandaloneWidgetRecover(item: HomeItem) {
        val key = recoverKey(item.id)
        val attempt = widgetRecoverAttempts[key] ?: 0
        if (attempt >= 4) return
        if (!widgetRecoverPending.add(key)) return
        val delays = longArrayOf(500L, 1500L, 3500L, 7000L)
        val delay = delays[attempt.coerceIn(0, delays.lastIndex)]
        binding.desktopContainer.postDelayed({
            widgetRecoverPending.remove(key)
            val live = homeItems.find { it.id == item.id && it.isWidget && !it.isWidgetStack }
                ?: return@postDelayed
            val info = try {
                if (live.appWidgetId >= 0) appWidgetManager.getAppWidgetInfo(live.appWidgetId) else null
            } catch (_: Exception) { null }
            val hv = widgetHostViews[live.appWidgetId]
            val errorShell = hostViewShowsWidgetError(hv)
            if (info != null && !errorShell) {
                // Healthy (or still loading content) — soft nudge only
                try {
                    requestProviderRefresh(live.appWidgetId, info)
                    if (hv != null && hv.width > 1) {
                        lastSizeOptionsKey.remove(live.appWidgetId)
                        applyWidgetSizeOptions(live.appWidgetId, hv, info, hv.width, hv.height)
                    }
                } catch (_: Exception) {}
                return@postDelayed
            }
            // Unbound OR system error shell → full silent rebind (same as manual Reintentar)
            widgetRecoverAttempts[key] = attempt + 1
            FileLogger.log(
                "WidgetBind",
                "autoRecover attempt=${attempt + 1} item=${live.id} oldId=${live.appWidgetId} " +
                    "infoNull=${info == null} errorShell=$errorShell"
            )
            if (silentRebindStandaloneWidget(live)) {
                scheduleStandaloneWidgetHealthCheck(live)
            }
        }, delay)
    }

    private fun scheduleStackMemberRecover(stackItem: HomeItem, member: StackWidgetMember) {
        val key = recoverKey(stackItem.id, member.providerKey)
        val attempt = widgetRecoverAttempts[key] ?: 0
        if (attempt >= 4) return
        if (!widgetRecoverPending.add(key)) return
        val delays = longArrayOf(800L, 2000L, 4500L, 9000L)
        val delay = delays[attempt.coerceIn(0, delays.lastIndex)]
        binding.desktopContainer.postDelayed({
            widgetRecoverPending.remove(key)
            val liveStack = homeItems.find { it.id == stackItem.id && it.isWidgetStack }
                ?: return@postDelayed
            val liveMember = liveStack.stackMembers.find { it.providerKey == member.providerKey }
                ?: return@postDelayed
            val info = try {
                if (liveMember.appWidgetId >= 0) appWidgetManager.getAppWidgetInfo(liveMember.appWidgetId) else null
            } catch (_: Exception) { null }
            val hv = widgetHostViews[liveMember.appWidgetId]
            val errorShell = hostViewShowsWidgetError(hv)
            if (info != null && !errorShell) {
                try {
                    requestProviderRefresh(liveMember.appWidgetId, info)
                    if (hv != null && hv.width > 1) {
                        lastSizeOptionsKey.remove(liveMember.appWidgetId)
                        applyWidgetSizeOptions(
                            liveMember.appWidgetId, hv, info, hv.width, hv.height
                        )
                    }
                } catch (_: Exception) {}
                return@postDelayed
            }
            widgetRecoverAttempts[key] = attempt + 1
            if (silentRebindStackMember(liveStack, liveMember)) {
                scheduleStackMemberRecover(liveStack, liveMember)
            }
        }, delay)
    }

    /**
     * Silent rebind for a desktop widget (no toast / permission dialog).
     * Returns false if the system requires user-facing bind permission.
     */
    private fun silentRebindStandaloneWidget(item: HomeItem): Boolean {
        if (!::appWidgetHost.isInitialized) return false
        val provider = android.content.ComponentName(item.packageName, item.activityName)
        val info = try {
            appWidgetManager.installedProviders.firstOrNull { it.provider == provider }
        } catch (_: Exception) { null } ?: return false

        val oldId = item.appWidgetId
        if (oldId >= 0) {
            widgetHostViews.remove(oldId)
            try { appWidgetHost.deleteAppWidgetId(oldId) } catch (_: Exception) {}
            lastSizeOptionsKey.remove(oldId)
            lastSizeOptionsAt.remove(oldId)
        }
        val id = try {
            appWidgetHost.allocateAppWidgetId()
        } catch (_: Exception) {
            return false
        }
        var bound = false
        try {
            val bindOpts = buildWidgetOptionsForSpan(item.spanX, item.spanY)
            bound = try {
                appWidgetManager.bindAppWidgetIdIfAllowed(id, info.provider, bindOpts)
            } catch (_: Exception) {
                false
            }
            if (!bound) {
                bound = try {
                    appWidgetManager.bindAppWidgetIdIfAllowed(id, info.provider)
                } catch (_: Exception) {
                    false
                }
            }
        } catch (_: Exception) {
            bound = false
        }
        if (!bound) {
            try { appWidgetHost.deleteAppWidgetId(id) } catch (_: Exception) {}
            // Need user permission — leave placeholder; manual Reintentar still works
            return false
        }
        item.appWidgetId = id
        val idx = homeItems.indexOfFirst { it.id == item.id }
        if (idx >= 0) homeItems[idx].appWidgetId = id
        saveAllItems()
        try { appWidgetHost.startListening() } catch (_: Exception) {}
        try { requestProviderRefresh(id, info) } catch (_: Exception) {}
        // Clear health-check gate so post-rebind checks can run again
        widgetRecoverPending.remove(recoverKey(item.id) + ":hc")
        desktopNeedsFullRender = true
        paintDesktopNow()
        FileLogger.log("WidgetBind", "silentRebind ok newId=$id item=${item.id} provider=${info.provider}")
        // One more delayed nudge — Google widgets often need UPDATE after host create
        binding.desktopContainer.postDelayed({
            try {
                requestProviderRefresh(id, info)
                val hv = widgetHostViews[id]
                if (hv != null && hv.width > 1) {
                    lastSizeOptionsKey.remove(id)
                    applyWidgetSizeOptions(id, hv, info, hv.width, hv.height)
                }
            } catch (_: Exception) {}
        }, 900L)
        return true
    }

    /**
     * Silent rebind for one stack member; refreshes stack UI when bind succeeds.
     */
    private fun silentRebindStackMember(stackItem: HomeItem, member: StackWidgetMember): Boolean {
        if (!::appWidgetHost.isInitialized) return false
        val provider = android.content.ComponentName(member.packageName, member.className)
        val info = try {
            appWidgetManager.installedProviders.firstOrNull { it.provider == provider }
        } catch (_: Exception) { null } ?: return false

        val oldId = member.appWidgetId
        if (oldId >= 0) {
            widgetHostViews.remove(oldId)
            try { appWidgetHost.deleteAppWidgetId(oldId) } catch (_: Exception) {}
            lastSizeOptionsKey.remove(oldId)
            lastSizeOptionsAt.remove(oldId)
        }
        val id = try {
            appWidgetHost.allocateAppWidgetId()
        } catch (_: Exception) {
            return false
        }
        var bound = false
        try {
            bound = appWidgetManager.bindAppWidgetIdIfAllowed(id, info.provider)
            if (!bound) {
                val opts = buildWidgetOptionsForSpan(stackItem.spanX, stackItem.spanY)
                bound = appWidgetManager.bindAppWidgetIdIfAllowed(id, info.provider, opts)
            }
        } catch (_: Exception) {
            bound = false
        }
        if (!bound) {
            try { appWidgetHost.deleteAppWidgetId(id) } catch (_: Exception) {}
            return false
        }
        member.appWidgetId = id
        // Persist stack members
        val idx = homeItems.indexOfFirst { it.id == stackItem.id }
        if (idx >= 0) {
            val mIdx = homeItems[idx].stackMembers.indexOfFirst { it.providerKey == member.providerKey }
            if (mIdx >= 0) homeItems[idx].stackMembers[mIdx].appWidgetId = id
        }
        saveAllItems()
        try { appWidgetHost.startListening() } catch (_: Exception) {}
        try { requestProviderRefresh(id, info) } catch (_: Exception) {}
        desktopNeedsFullRender = true
        paintDesktopNow()
        FileLogger.log("WidgetStack", "silentRebind member ok id=$id key=${member.providerKey}")
        return true
    }

    private fun createWidgetView(item: HomeItem): View? {
        if (item.appWidgetId < 0 || !::appWidgetHost.isInitialized) {
            return createWidgetPermissionPlaceholder(item)
        }
        val info = try {
            appWidgetManager.getAppWidgetInfo(item.appWidgetId)
        } catch (_: Exception) { null }
        if (info == null) {
            // Not bound / revoked — show gear to re-request system bind permission
            return createWidgetPermissionPlaceholder(item)
        }

        // Custom frame that only intercepts after long-press so short taps reach the widget.
        val wrap = object : FrameLayout(this) {
            private val touchSlop = ViewConfiguration.get(this@HomeActivity).scaledTouchSlop
            private val longTimeout = ViewConfiguration.getLongPressTimeout().toLong()
            private var downX = 0f
            private var downY = 0f
            private var longPressArmed = false
            private var dragStarted = false
            private var intercepting = false
            private val arm = Runnable {
                // Never arm long-press / menu while this (or any) widget is being resized
                if (resizingWidgetId != null) return@Runnable
                if (draggingApp != null || isDrawerOpen || suppressIconInteractions || suppressEmptyLongPress) return@Runnable
                longPressArmed = true
                intercepting = true
                iconLongPressActive = true
                suppressEmptyLongPress = true
                try { performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS) } catch (_: Exception) {}
                animate().scaleX(1.03f).scaleY(1.03f).setDuration(100).start()
                showWidgetOptions(item, this)
            }

            override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
                // While resize handles are active, do not intercept — let the frame handles work
                if (resizingWidgetId != null) return false
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        if (suppressIconInteractions || isDrawerOpen || drawerGestureActive) return false
                        downX = ev.rawX
                        downY = ev.rawY
                        longPressArmed = false
                        dragStarted = false
                        intercepting = false
                        postDelayed(arm, longTimeout)
                        return false // let host receive until long-press
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dist = hypot((ev.rawX - downX).toDouble(), (ev.rawY - downY).toDouble()).toFloat()
                        if (!longPressArmed && dist > touchSlop) {
                            removeCallbacks(arm)
                        }
                        if (longPressArmed) return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        removeCallbacks(arm)
                        if (!longPressArmed) {
                            iconLongPressActive = false
                        }
                    }
                }
                return intercepting || longPressArmed
            }

            override fun onTouchEvent(ev: MotionEvent): Boolean {
                // Block drag start while resizing
                if (resizingWidgetId != null) {
                    removeCallbacks(arm)
                    return false
                }
                when (ev.actionMasked) {
                    MotionEvent.ACTION_MOVE -> {
                        val dist = hypot((ev.rawX - downX).toDouble(), (ev.rawY - downY).toDouble()).toFloat()
                        if (longPressArmed && !dragStarted && dist > touchSlop && draggingApp == null) {
                            dragStarted = true
                            dismissCurrentPopup()
                            clearWidgetResize()
                            startWidgetDrag(item, this, ev)
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        removeCallbacks(arm)
                        if (draggingHomeItem?.id == item.id) return true
                        if (longPressArmed && !dragStarted) {
                            animate().scaleX(1.03f).scaleY(1.03f).setDuration(80).start()
                        } else {
                            animate().scaleX(1f).scaleY(1f).setDuration(100).start()
                        }
                        return true
                    }
                }
                return super.onTouchEvent(ev)
            }
        }.apply {
            tag = item.id
            isClickable = true
            isFocusable = true
            // Clip so RemoteViews cannot paint outside the span box (was looking "giant")
            clipChildren = true
            clipToPadding = true
        }

        // Reuse existing host view: createView more than once per id breaks RemoteViews
        // (system shows "No se pudo agregar el widget" after the first flash).
        val hostView = widgetHostViews[item.appWidgetId] ?: run {
            FileLogger.log("WidgetBind", "createView id=${item.appWidgetId} provider=${info.provider}")
            val hv = try {
                try { appWidgetHost.startListening() } catch (_: Exception) {}
                appWidgetHost.createView(this, item.appWidgetId, info)
            } catch (e: Exception) {
                FileLogger.e("WidgetBind", "createView failed id=${item.appWidgetId}", e)
                return createWidgetPermissionPlaceholder(item)
            }
            widgetHostViews[item.appWidgetId] = hv
            // Material You colors only for packages known to reference @android:color/system_*.
            // Applying the mapping to every provider broke third-party widgets (ColorNote,
            // Chrome bookmarks, KWGT, etc.) that inflate with their own resources.
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                val pkg = info.provider.packageName
                if (pkg.startsWith("com.google.") || pkg.startsWith("com.android.") ||
                    pkg.contains("systemui") || pkg.contains("launcher")
                ) {
                    applyDefaultWidgetColorResources(hv)
                }
            }
            FileLogger.log("WidgetBind", "createView ok id=${item.appWidgetId} hv=${hv.javaClass.simpleName}")
            hv
        }
        // Detach from previous parent (renderDesktop removes children, but be safe)
        (hostView.parent as? android.view.ViewGroup)?.removeView(hostView)
        hostView.isLongClickable = false
        // Use system default widget padding (Launcher3 style). Zero padding breaks some providers.
        try {
            val p = android.appwidget.AppWidgetHostView.getDefaultPaddingForWidget(
                this, info.provider, null
            )
            hostView.setPadding(p.left, p.top, p.right, p.bottom)
        } catch (_: Exception) {
            try {
                val d = (6 * resources.displayMetrics.density).toInt()
                hostView.setPadding(d, d, d, d)
            } catch (_: Exception) {}
        }
        // Do NOT call setAppWidget again — createView already did; re-calling resets the host
        // and is a common cause of Calendar going from brief content → error.
        wrap.addView(
            hostView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        // GPU layer: scale/transition animates a texture instead of re-rasterizing RemoteViews
        try {
            wrap.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            hostView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        } catch (_: Exception) {}
        // ONE layout listener only — re-adding every renderDesktop was spamming size options.
        // CRITICAL: ignore full-screen / unconstrained layouts (wrap not sized yet). Those
        // were sending sizeOptions 1016x1920 and making the widget look giant.
        if (hostView.getTag(0x70F10D02) != true) {
            hostView.setTag(0x70F10D02, true)
            hostView.addOnLayoutChangeListener { v, left, top, right, bottom, _, _, _, _ ->
                val w = right - left
                val h = bottom - top
                if (w <= 1 || h <= 1) return@addOnLayoutChangeListener
                // Never push sizeOptions while the user is actively resizing — it
                // causes mid-gesture reflows and the "jump up" the user sees.
                if (resizingWidgetId != null) return@addOnLayoutChangeListener
                if (isOverviewMode || dualTransitionActive || pageSwipeActive) return@addOnLayoutChangeListener
                val expected = gridRect(item.cellX, item.cellY, item.spanX, item.spanY)
                // Reject layouts much larger than the reserved matrix (unconstrained measure)
                if (expected.width() > 1 && (w > expected.width() * 1.35f || h > expected.height() * 1.35f)) {
                    FileLogger.log(
                        "WidgetBind",
                        "skip sizeOptions full-screen id=${item.appWidgetId} got=${w}x${h} expected=${expected.width()}x${expected.height()}"
                    )
                    return@addOnLayoutChangeListener
                }
                applyWidgetSizeOptions(item.appWidgetId, hostView, info, w, h)
            }
        }
        // Apply options from the GRID span, not from whatever the host measured at 0x0 attach
        wrap.post {
            val expected = gridRect(item.cellX, item.cellY, item.spanX, item.spanY)
            val ew = expected.width().coerceAtLeast(1)
            val eh = expected.height().coerceAtLeast(1)
            if (hostView.width > 1 && hostView.height > 1 &&
                hostView.width <= ew * 1.35f && hostView.height <= eh * 1.35f
            ) {
                applyWidgetSizeOptions(item.appWidgetId, hostView, info, hostView.width, hostView.height)
            } else {
                applyWidgetSizeOptions(item.appWidgetId, hostView, info, ew, eh)
            }
        }
        // No gear overlay — it steals taps from the widget (KWGT configure, lists, buttons).
        // Recovery actions live in the long-press menu (showWidgetOptions).
        wrap.postDelayed({
            try {
                FileLogger.log(
                    "WidgetBind",
                    "host after layout id=${item.appWidgetId} size=${hostView.width}x${hostView.height} " +
                        "children=${hostView.childCount} error=${hostViewShowsWidgetError(hostView)}"
                )
            } catch (_: Exception) {}
        }, 600)
        // Per-widget health: Google Search etc. often paint error shell after first createView
        scheduleStandaloneWidgetHealthCheck(item)
        return wrap
    }

    /**
     * Publish host size to the widget provider (dp + HOME_SCREEN category).
     * Safe to call multiple times — dedupes with a tag on the host view.
     */
    /** Size options in DP for bind/update — matches Launcher3 getDefaultOptionsForWidget. */
    private fun buildWidgetOptionsForSpan(spanX: Int, spanY: Int): android.os.Bundle {
        calculateMetrics()
        val density = resources.displayMetrics.density.coerceAtLeast(0.5f)
        val sx = spanX.coerceAtLeast(1)
        val sy = spanY.coerceAtLeast(1)
        // Launcher3 subtracts default host padding so provider layouts for the content area
        val pad = try {
            android.appwidget.AppWidgetHostView.getDefaultPaddingForWidget(this, null, null)
        } catch (_: Exception) {
            android.graphics.Rect()
        }
        val wPx = (cellWidth * sx - pad.left - pad.right).toInt().coerceAtLeast(1)
        val hPx = (cellHeight * sy - pad.top - pad.bottom).toInt().coerceAtLeast(1)
        val wDp = (wPx / density).toInt().coerceAtLeast(1)
        val hDp = (hPx / density).toInt().coerceAtLeast(1)
        FileLogger.log("WidgetBind", "buildOpts span=${sx}x${sy} px=${wPx}x${hPx} dp=${wDp}x${hDp} pad=${pad.left},${pad.top},${pad.right},${pad.bottom}")
        return android.os.Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, wDp)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, wDp)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, hDp)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, hDp)
            putInt(
                AppWidgetManager.OPTION_APPWIDGET_HOST_CATEGORY,
                AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN
            )
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                try {
                    putParcelableArrayList(
                        AppWidgetManager.OPTION_APPWIDGET_SIZES,
                        arrayListOf(android.util.SizeF(wDp.toFloat(), hDp.toFloat()))
                    )
                } catch (_: Exception) {}
            }
        }
    }

    /**
     * Minimum cell span for a widget. Prefer provider targetCell (API 31+).
     * Fall back to ceil(minPx / cellPx). Never use cellWidth≈1 fallbacks that expand
     * to the full grid (that made Spotify/Weather land as full-screen giants).
     */
    private fun minSpansForWidget(info: AppWidgetProviderInfo): Pair<Int, Int> {
        calculateMetrics()
        val maxC = columns.coerceAtLeast(1)
        val maxR = rows.coerceAtLeast(1)
        val metricsReady = cellWidth > 40f && cellHeight > 40f
        val dm = resources.displayMetrics
        val cw = if (metricsReady) cellWidth else (dm.widthPixels / maxC.toFloat()).coerceAtLeast(1f)
        val ch = if (metricsReady) cellHeight else ((dm.heightPixels * 0.65f) / maxR).coerceAtLeast(1f)

        var sx = 1
        var sy = 1
        // 1) targetCellWidth/Height — start here (API 31+)
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
        // 2) If still 1×1 (or target missing), derive from min px
        if (sx == 1 && sy == 1 && (info.minWidth > 0 || info.minHeight > 0)) {
            sx = kotlin.math.ceil(info.minWidth / cw.toDouble()).toInt().coerceIn(1, maxC)
            sy = kotlin.math.ceil(info.minHeight / ch.toDouble()).toInt().coerceIn(1, maxR)
        }
        // 3) Always grow until the reserved cells cover minWidth/minHeight.
        //    Critical for Glance/Google News which declare targetCell=1×1 but min≈368px —
        //    undersized hosts stay forever on "Cargando…". Never expands past the grid.
        while (sx < maxC && sx * cw < info.minWidth) sx++
        while (sy < maxR && sy * ch < info.minHeight) sy++
        FileLogger.log(
            "WidgetBind",
            "minSpans → ${sx}x${sy} minPx=${info.minWidth}x${info.minHeight} " +
                "cell=${cw.toInt()}x${ch.toInt()} ready=$metricsReady provider=${info.provider.shortClassName}"
        )
        return sx to sy
    }

    /** Last size options sent per widget id — avoid spam during resize. */
    private val lastSizeOptionsKey = mutableMapOf<Int, String>()
    private val lastSizeOptionsAt = mutableMapOf<Int, Long>()

    private fun applyWidgetSizeOptions(
        widgetId: Int,
        hostView: AppWidgetHostView,
        info: AppWidgetProviderInfo?,
        widthPx: Int,
        heightPx: Int
    ) {
        if (suppressWidgetSizeOptions) return
        try {
            if (widthPx <= 1 || heightPx <= 1) return
            // Never push size options while the page is scaled/transitioning — that
            // reflows RemoteViews mid-animation and makes widgets stutter or change content.
            if (isOverviewMode || dualTransitionActive || pageSwipeActive) return
            val density = resources.displayMetrics.density.coerceAtLeast(0.5f)
            // Launcher3: options describe the CONTENT area (host size minus default padding).
            val pad = try {
                android.appwidget.AppWidgetHostView.getDefaultPaddingForWidget(
                    this, info?.provider, null
                )
            } catch (_: Exception) {
                android.graphics.Rect()
            }
            // Report the REAL content area only. Never inflate to provider minWidth —
            // lying about size (host 211px, options 368px) keeps Glance widgets on "Cargando…".
            val wPx = (widthPx - pad.left - pad.right).coerceAtLeast(1)
            val hPx = (heightPx - pad.top - pad.bottom).coerceAtLeast(1)
            val wDp = (wPx / density).toInt().coerceAtLeast(1)
            val hDp = (hPx / density).toInt().coerceAtLeast(1)
            val key = "${wDp}x${hDp}"
            val now = System.currentTimeMillis()
            val prevKey = lastSizeOptionsKey[widgetId]
            val prevAt = lastSizeOptionsAt[widgetId] ?: 0L
            // Debounce: same size → skip; any change within 350ms → skip (resize drag)
            if (key == prevKey) return
            if (now - prevAt < 350L) return
            lastSizeOptionsKey[widgetId] = key
            lastSizeOptionsAt[widgetId] = now

            val opts = android.os.Bundle().apply {
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, wDp)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, wDp)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, hDp)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, hDp)
                putInt(
                    AppWidgetManager.OPTION_APPWIDGET_HOST_CATEGORY,
                    AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN
                )
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    try {
                        val size = android.util.SizeF(wDp.toFloat(), hDp.toFloat())
                        putParcelableArrayList(
                            AppWidgetManager.OPTION_APPWIDGET_SIZES,
                            arrayListOf(size)
                        )
                    } catch (_: Exception) {}
                }
            }
            appWidgetManager.updateAppWidgetOptions(widgetId, opts)
            FileLogger.log(
                "WidgetBind",
                "sizeOptions id=$widgetId px=${wPx}x${hPx} dp=${wDp}x${hDp} " +
                    "providerMinPx=${info?.minWidth}x${info?.minHeight}"
            )
        } catch (e: Exception) {
            FileLogger.e("WidgetBind", "applyWidgetSizeOptions failed id=$widgetId", e)
        }
    }

    private fun startWidgetDrag(item: HomeItem, sourceView: View, event: MotionEvent) {
        if (draggingApp != null) return
        if (resizingWidgetId != null) return
        calculateMetrics()
        iconLongPressActive = true
        suppressEmptyLongPress = true
        dismissCurrentPopup()
        clearWidgetResize()
        draggingHomeItem = item
        dragOriginCellX = item.cellX
        dragOriginCellY = item.cellY
        dragOriginSpanX = item.spanX.coerceAtLeast(1)
        dragOriginSpanY = item.spanY.coerceAtLeast(1)
        dragStartRawX = event.rawX
        dragStartRawY = event.rawY
        draggingApp = AppInfo(
            packageName = item.packageName,
            activityName = item.activityName,
            label = item.label,
            icon = null
        )
        draggingWidgetView = null

        // ═══════════════ FREE MODE (independent from grid) ═══════════════
        if (item.freePlacement) {
            val density = resources.displayMetrics.density
            val sw = (if (item.freeW > 0) item.freeW else sourceView.width).coerceAtLeast(1)
            val sh = (if (item.freeH > 0) item.freeH else sourceView.height).coerceAtLeast(1)
            draggingWidgetView = sourceView
            sourceView.visibility = View.VISIBLE
            sourceView.elevation = 40f * density
            try { sourceView.bringToFront() } catch (_: Exception) {}
            dragShadow = null
            // No grid overlay in free mode
            try { dropOverlay?.visibility = View.GONE } catch (_: Exception) {}

            val deskLoc = IntArray(2)
            binding.desktopContainer.getLocationOnScreen(deskLoc)
            val widgetLoc = IntArray(2)
            sourceView.getLocationOnScreen(widgetLoc)
            // Grab = finger offset inside the widget (desktopContainer space)
            dragGrabOffsetX = event.rawX - widgetLoc[0]
            dragGrabOffsetY = event.rawY - widgetLoc[1]
            val lp = (sourceView.layoutParams as? FrameLayout.LayoutParams)
                ?: FrameLayout.LayoutParams(sw, sh)
            lp.width = sw
            lp.height = sh
            lp.leftMargin = (event.rawX - deskLoc[0] - dragGrabOffsetX).toInt()
            lp.topMargin = (event.rawY - deskLoc[1] - dragGrabOffsetY).toInt()
            lp.gravity = Gravity.TOP or Gravity.START
            sourceView.layoutParams = lp
            sourceView.translationX = 0f
            sourceView.translationY = 0f
            return
        }

        // ═══════════════ GRID MODE (original behavior) ═══════════════
        // Hide real widget; show blue cell-matrix shadow + grid overlay
        val shadowRect = gridRect(0, 0, dragOriginSpanX, dragOriginSpanY)
        val sw = shadowRect.width().coerceAtLeast(1)
        val sh = shadowRect.height().coerceAtLeast(1)
        val density = resources.displayMetrics.density
        val shadow = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(sw, sh)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0x33FFFFFF)
                setStroke((2.5f * density).toInt(), 0xFF4FC3F7.toInt())
                cornerRadius = 14f * density
            }
            alpha = 0.92f
            elevation = 32f
            addView(View(this@HomeActivity).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                setBackgroundColor(0x224FC3F7)
            })
        }
        dragShadow = shadow
        (binding.homeRoot as ViewGroup).addView(shadow)
        shadow.measure(
            View.MeasureSpec.makeMeasureSpec(sw, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(sh, View.MeasureSpec.EXACTLY)
        )
        shadow.layout(0, 0, sw, sh)

        val rootLoc = IntArray(2)
        binding.homeRoot.getLocationOnScreen(rootLoc)
        val widgetLoc = IntArray(2)
        sourceView.getLocationOnScreen(widgetLoc)
        val widgetRootX = (widgetLoc[0] - rootLoc[0]).toFloat()
        val widgetRootY = (widgetLoc[1] - rootLoc[1]).toFloat()
        shadow.x = widgetRootX
        shadow.y = widgetRootY
        dragGrabOffsetX = (event.rawX - rootLoc[0]) - widgetRootX
        dragGrabOffsetY = (event.rawY - rootLoc[1]) - widgetRootY

        sourceView.visibility = View.INVISIBLE
        ensureDropOverlay()
        dropOverlay?.visibility = View.VISIBLE
        updateDropHighlight(event.rawX, event.rawY)
    }


    /**
     * Place a widget wrap on the desktop grid.
     *
     * IMPORTANT: use FrameLayout margins as the only position source of truth.
     * Mixing layoutParams (margin=0) + view.x/y causes a race with the next
     * layout pass: FrameLayout re-lays out at (0,0) from margins and overwrites
     * the x/y we just set — that is the "jumps up on resize / fixed after page
     * change" bug (page change rebuilds views cleanly via renderDesktop).
     */
    private fun positionWidget(view: View, item: HomeItem) {
        if (cellWidth <= 1f || cellHeight <= 1f) return
        val w: Int
        val h: Int
        val left: Int
        val top: Int
        if (item.freePlacement) {
            val fb = gridRect(item.cellX, item.cellY, item.spanX.coerceAtLeast(1), item.spanY.coerceAtLeast(1))
            w = (if (item.freeW > 0) item.freeW else fb.width()).coerceAtLeast(1)
            h = (if (item.freeH > 0) item.freeH else fb.height()).coerceAtLeast(1)
            left = item.freeX.roundToInt()
            top = item.freeY.roundToInt()
        } else {
            clampItemToGrid(item)
            val rect = gridRect(item.cellX, item.cellY, item.spanX, item.spanY)
            w = rect.width().coerceAtLeast(1)
            h = rect.height().coerceAtLeast(1)
            left = rect.left
            top = rect.top
        }
        val lp = (view.layoutParams as? FrameLayout.LayoutParams)
            ?: FrameLayout.LayoutParams(w, h)
        lp.width = w
        lp.height = h
        lp.leftMargin = left
        lp.topMargin = top
        lp.rightMargin = 0
        lp.bottomMargin = 0
        lp.gravity = Gravity.TOP or Gravity.START
        view.layoutParams = lp
        // Layout position is via margins; clear drag leftovers for normal widgets.
        // GIF/IMG reaction transforms (translate/scale) must survive page rebuilds —
        // positionWidget used to zero them and that overwrote applyGifAnimState.
        if (item.isGifWidget) {
            applyGifAnimState(view, item)
        } else {
            view.translationX = 0f
            view.translationY = 0f
            view.scaleX = 1f
            view.scaleY = 1f
        }
        if (view is ViewGroup && view.childCount > 0) {
            val host = view.getChildAt(0)
            val hlp = (host.layoutParams as? FrameLayout.LayoutParams)
                ?: FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            hlp.width = FrameLayout.LayoutParams.MATCH_PARENT
            hlp.height = FrameLayout.LayoutParams.MATCH_PARENT
            host.layoutParams = hlp
        }
    }

    @SuppressLint("ClickableViewAccessibility")

    private fun gifReactionTriggers(item: HomeItem): Set<TriggerKind> {
        val recipes = ReactionJson.decodeList(item.reactionsJson)
        val out = mutableSetOf<TriggerKind>()
        recipes.forEach { r ->
            r.blocks.forEach { b ->
                if (b.type == com.romaster.launcher.data.ReactionBlockType.TRIGGER) {
                    out.add(b.triggerKind)
                }
            }
        }
        return out
    }

    /** Copy current View transforms into the HomeItem model (in memory). */
    private fun snapshotGifAnimState(view: View, item: HomeItem) {
        item.animTx = view.translationX
        item.animTy = view.translationY
        item.animRot = view.rotation
        item.animScale = view.scaleX
        item.animAlpha = view.alpha
    }

    /** Apply persisted reaction transforms onto a freshly created GIF/IMG view. */
    private fun applyGifAnimState(view: View, item: HomeItem) {
        view.translationX = item.animTx
        view.translationY = item.animTy
        view.rotation = item.animRot
        val s = if (item.animScale == 0f) 1f else item.animScale
        view.scaleX = s
        view.scaleY = s
        view.alpha = item.animAlpha.coerceIn(0f, 1f)
    }

    /**
     * Before tearing down the current desktop layer, capture every GIF widget's
     * live transform so page changes / rebuilds don't lose reaction positions.
     */
    private fun snapshotVisibleGifAnimStates(root: android.view.ViewGroup = binding.desktopContainer) {
        fun walk(vg: android.view.ViewGroup) {
            for (i in 0 until vg.childCount) {
                val child = vg.getChildAt(i)
                val id = child.tag as? String
                if (id != null) {
                    val item = homeItems.find { it.id == id && it.isGifWidget }
                    if (item != null) snapshotGifAnimState(child, item)
                }
                if (child is android.view.ViewGroup) walk(child)
            }
        }
        walk(root)
    }

    private fun persistGifAnimState(view: View) {
        val id = view.tag as? String ?: return
        val item = homeItems.find { it.id == id && it.isGifWidget } ?: return
        snapshotGifAnimState(view, item)
        try { saveAllItems() } catch (_: Exception) {}
    }

    private fun createGifWidgetView(item: HomeItem): View? {
        val density = resources.displayMetrics.density
        val triggers = gifReactionTriggers(item)
        val needsSwipe = triggers.any {
            it == TriggerKind.SWIPE_UP || it == TriggerKind.SWIPE_DOWN ||
                it == TriggerKind.SWIPE_LEFT || it == TriggerKind.SWIPE_RIGHT
        }
        val needsDouble = TriggerKind.DOUBLE_TAP in triggers
        val needsTap = TriggerKind.TAP in triggers

        val wrap = object : FrameLayout(this) {
            private val touchSlop = ViewConfiguration.get(this@HomeActivity).scaledTouchSlop
            private val longTimeout = ViewConfiguration.getLongPressTimeout().toLong()
            private val doubleTimeout = ViewConfiguration.getDoubleTapTimeout().toLong()
            private var downX = 0f
            private var downY = 0f
            private var downTime = 0L
            private var longPressArmed = false
            private var dragStarted = false
            private var gestureConsumed = false
            private var swipeClaimed = false
            private var lastTapUpTime = 0L
            private var waitingSecondTap = false
            private val arm = Runnable {
                if (resizingWidgetId != null) return@Runnable
                if (draggingApp != null || isDrawerOpen || suppressIconInteractions) return@Runnable
                if (currentPopup != null || stackMenuView != null) return@Runnable
                if (gestureConsumed || dragStarted || swipeClaimed) return@Runnable
                longPressArmed = true
                iconLongPressActive = true
                try { performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS) } catch (_: Exception) {}
                animate().scaleX(1.03f).scaleY(1.03f).setDuration(100).start()
                // Long-press always opens widget options (configure / move / reactions).
                // LONG_PRESS is intentionally NOT a reaction trigger — it would lock the menu.
                showWidgetOptions(item, this)
            }
            override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
                if (resizingWidgetId != null) return false
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        if (suppressIconInteractions || isDrawerOpen || drawerGestureActive) return false
                        downX = ev.rawX
                        downY = ev.rawY
                        downTime = android.os.SystemClock.elapsedRealtime()
                        longPressArmed = false
                        dragStarted = false
                        gestureConsumed = false
                        swipeClaimed = false
                        if (needsSwipe) {
                            touchStartedOnGifSwipe = true
                            parent?.requestDisallowInterceptTouchEvent(true)
                        }
                        postDelayed(arm, longTimeout)
                        return needsSwipe // claim early when swipe reactions exist
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = ev.rawX - downX
                        val dy = ev.rawY - downY
                        val adx = kotlin.math.abs(dx)
                        val ady = kotlin.math.abs(dy)
                        val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()
                        if (!longPressArmed && dist > touchSlop) removeCallbacks(arm)
                        if (needsSwipe && !swipeClaimed && dist > touchSlop * 0.7f) {
                            swipeClaimed = true
                            removeCallbacks(arm)
                            parent?.requestDisallowInterceptTouchEvent(true)
                            touchStartedOnGifSwipe = true
                            return true
                        }
                        if (longPressArmed) return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        removeCallbacks(arm)
                        if (!longPressArmed) iconLongPressActive = false
                        parent?.requestDisallowInterceptTouchEvent(false)
                    }
                }
                return longPressArmed || swipeClaimed
            }
            override fun onTouchEvent(ev: MotionEvent): Boolean {
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = ev.rawX
                        downY = ev.rawY
                        downTime = android.os.SystemClock.elapsedRealtime()
                        if (needsSwipe) {
                            touchStartedOnGifSwipe = true
                            parent?.requestDisallowInterceptTouchEvent(true)
                        }
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = ev.rawX - downX
                        val dy = ev.rawY - downY
                        val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()
                        if (!longPressArmed && dist > touchSlop) removeCallbacks(arm)
                        if (needsSwipe && dist > touchSlop * 0.55f) {
                            swipeClaimed = true
                            parent?.requestDisallowInterceptTouchEvent(true)
                            touchStartedOnGifSwipe = true
                        }
                        if (longPressArmed && !dragStarted && dist > touchSlop && draggingApp == null ) {
                            // Only start drag when long-press opened the menu path
                            dragStarted = true
                            gestureConsumed = true
                            dismissCurrentPopup()
                            clearWidgetResize()
                            val fake = AppInfo(
                                packageName = "gif",
                                activityName = item.id,
                                label = item.label.ifBlank { "IMG/GIF" },
                                icon = null
                            )
                            startDesktopDrag(fake, item, this, ev)
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        removeCallbacks(arm)
                        parent?.requestDisallowInterceptTouchEvent(false)
                        touchStartedOnGifSwipe = false
                        if (draggingHomeItem?.id == item.id) return true
                        animate().scaleX(1f).scaleY(1f).setDuration(100).start()
                        if (ev.actionMasked != MotionEvent.ACTION_UP) {
                            if (!longPressArmed) iconLongPressActive = false
                            return true
                        }
                        if (gestureConsumed || longPressArmed || dragStarted || resizingWidgetId != null) {
                            if (!longPressArmed) iconLongPressActive = false
                            return true
                        }
                        val dx = ev.rawX - downX
                        val dy = ev.rawY - downY
                        val adx = kotlin.math.abs(dx)
                        val ady = kotlin.math.abs(dy)
                        val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()
                        // Swipe detection
                        if (needsSwipe && dist > touchSlop * 2f) {
                            val trigger = when {
                                ady >= adx && dy < 0 -> TriggerKind.SWIPE_UP
                                ady >= adx && dy > 0 -> TriggerKind.SWIPE_DOWN
                                adx > ady && dx < 0 -> TriggerKind.SWIPE_LEFT
                                adx > ady && dx > 0 -> TriggerKind.SWIPE_RIGHT
                                else -> null
                            }
                            if (trigger != null && trigger in triggers) {
                                fireGifReaction(item, trigger, this)
                                waitingSecondTap = false
                                return true
                            }
                        }
                        // Tap / double-tap (only if not a swipe)
                        if (dist <= touchSlop * 1.5f) {
                            val now = android.os.SystemClock.elapsedRealtime()
                            if (needsDouble && waitingSecondTap && (now - lastTapUpTime) <= doubleTimeout) {
                                waitingSecondTap = false
                                fireGifReaction(item, TriggerKind.DOUBLE_TAP, this)
                                return true
                            }
                            if (needsDouble && TriggerKind.DOUBLE_TAP in triggers) {
                                waitingSecondTap = true
                                lastTapUpTime = now
                                // Wait for possible second tap; fire single TAP after timeout if also needed
                                postDelayed({
                                    if (waitingSecondTap) {
                                        waitingSecondTap = false
                                        if (needsTap) fireGifReaction(item, TriggerKind.TAP, this)
                                    }
                                }, doubleTimeout)
                                return true
                            }
                            if (needsTap) {
                                fireGifReaction(item, TriggerKind.TAP, this)
                            }
                        }
                        return true
                    }
                }
                return true
            }
        }.apply {
            tag = item.id
            isClickable = true
            isFocusable = true
            clipChildren = true
            clipToPadding = true
            setTag(0x70F10001, true)
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }

        val image = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            adjustViewBounds = false
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            tag = "gif_image"
        }
        wrap.addView(image)

        val path = item.gifPath
        if (path.isNotBlank() && File(path).exists()) {
            try {
                if (android.os.Build.VERSION.SDK_INT >= 28) {
                    val src = ImageDecoder.createSource(File(path))
                    val drawable = ImageDecoder.decodeDrawable(src) { decoder, _, _ ->
                        decoder.isMutableRequired = false
                        decoder.setTargetColorSpace(
                            android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB)
                        )
                    }
                    image.setImageDrawable(drawable)
                    if (drawable is AnimatedImageDrawable) {
                        drawable.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                        drawable.start()
                    }
                } else {
                    image.setImageURI(Uri.fromFile(File(path)))
                }
                applyGifAspectMode(image, item.gifAspectMode)
            } catch (e: Exception) {
                FileLogger.e("GifWidget", "decode failed path=$path", e)
                image.setImageDrawable(null)
                addGifPlaceholder(wrap, density, empty = false)
            }
        } else {
            wrap.background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 16f * density
                setColor(0x22000000)
            }
            addGifPlaceholder(wrap, density, empty = true)
            wrap.setOnClickListener {
                if (draggingApp == null && resizingWidgetId == null) openGifFilePicker(item)
            }
        }
        applyGifAnimState(wrap, item)
        return wrap
    }


    private fun applyGifAspectMode(image: ImageView, mode: String) {
        val m = mode.lowercase().ifBlank { "fit" }
        // Ensure parent clips overflow for matrix / unscaled modes
        (image.parent as? ViewGroup)?.clipChildren = true
        image.clipToOutline = false
        when (m) {
            "stretch" -> {
                image.scaleType = ImageView.ScaleType.FIT_XY
                image.imageMatrix = Matrix()
            }
            "fill" -> {
                // Cover widget completely; crop whatever sticks out (diagram FILL MODE)
                image.scaleType = ImageView.ScaleType.CENTER_CROP
                image.imageMatrix = Matrix()
            }
            "crop" -> {
                // True CENTER CROP: never scale — original pixels, centered, clipped by view bounds
                image.scaleType = ImageView.ScaleType.MATRIX
                fun applyNativeCenter() {
                    val d = image.drawable ?: return
                    val dw = d.intrinsicWidth
                    val dh = d.intrinsicHeight
                    val vw = image.width
                    val vh = image.height
                    if (dw <= 0 || dh <= 0 || vw <= 0 || vh <= 0) return
                    val dx = (vw - dw) * 0.5f
                    val dy = (vh - dh) * 0.5f
                    val matrix = Matrix()
                    matrix.setTranslate(dx, dy)
                    image.imageMatrix = matrix
                }
                if (image.getTag(0x70F10D11) != true) {
                    image.setTag(0x70F10D11, true)
                    image.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
                        if (right - left > 0 && bottom - top > 0 &&
                            image.scaleType == ImageView.ScaleType.MATRIX &&
                            // Only while still in native-crop mode (no scale in matrix other than translate)
                            itemGifModeIsCrop(image)
                        ) {
                            applyNativeCenter()
                        }
                    }
                }
                // Mark this ImageView as native-crop so the layout listener stays correct
                image.setTag(0x70F10D12, "crop")
                if (image.width > 0 && image.height > 0) applyNativeCenter()
                else image.post { applyNativeCenter() }
            }
            else -> { // fit
                image.scaleType = ImageView.ScaleType.FIT_CENTER
                image.imageMatrix = Matrix()
                image.setTag(0x70F10D12, "fit")
            }
        }
        if (m != "crop") {
            image.setTag(0x70F10D12, m)
        }
    }

    /** Helper for native-crop layout listener — only re-apply when still in crop mode. */
    private fun itemGifModeIsCrop(image: ImageView): Boolean {
        return image.getTag(0x70F10D12) == "crop"
    }

    private fun addGifPlaceholder(parent: FrameLayout, density: Float, empty: Boolean) {
        val tv = TextView(this).apply {
            text = if (empty) "Tocá para\ncargar imagen" else "Imagen\nno disponible"
            gravity = Gravity.CENTER
            setTextColor(0xCCFFFFFF.toInt())
            textSize = 13f
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        parent.addView(tv)
    }



    private fun openReactionEditor(item: HomeItem) {
        val live = homeItems.find { it.id == item.id } ?: item
        val recipes = ReactionJson.decodeList(live.reactionsJson)
        ReactionEditorDialog(this, recipes) { updated ->
            live.reactionsJson = ReactionJson.encodeList(updated)
            item.reactionsJson = live.reactionsJson
            saveAllItems()
            Toast.makeText(this, "Reacciones guardadas", Toast.LENGTH_SHORT).show()
        }.show()
    }


    private fun onGlobalBoolChanged(name: String, value: Boolean) {
        // Only real transitions reach here (ReactionGlobalsStore filters no-ops).
        // Ignore during unlock/resume grace and when activity is not resumed.
        if (android.os.SystemClock.elapsedRealtime() < globalTriggerGraceUntil) return
        if (lifecycle.currentState != androidx.lifecycle.Lifecycle.State.RESUMED) return
        // Fire IMG/GIF recipes with Trigger Global bool matching name/value.
        val targets = homeItems.filter { it.isGifWidget && it.reactionsJson.isNotBlank() }
        if (targets.isEmpty()) return
        for (item in targets) {
            val recipes = ReactionJson.decodeList(item.reactionsJson)
            val matches = recipes.any { recipe ->
                recipe.enabled && recipe.blocks.any { b ->
                    b.type == com.romaster.launcher.data.ReactionBlockType.TRIGGER &&
                        b.triggerKind == TriggerKind.GLOBAL_BOOL &&
                        b.conditionGlobalKey == name &&
                        b.conditionBoolValue == value
                }
            }
            if (!matches) continue
            val view = findGifReactionView(item)
            if (view != null) {
                // Different widget than the one that assigned — can run in parallel (per-target lock)
                fireGifReaction(item, TriggerKind.GLOBAL_BOOL, view)
            } else {
                // View not in current hierarchy (other page / not laid out yet): retry shortly
                binding.desktopContainer.post {
                    val v2 = findGifReactionView(item)
                    if (v2 != null) {
                        fireGifReaction(item, TriggerKind.GLOBAL_BOOL, v2)
                    } else {
                        // Still missing: queue until engine is idle + another frame
                        reactionEngine.runWhenIdle {
                            binding.desktopContainer.post {
                                findGifReactionView(item)?.let {
                                    fireGifReaction(item, TriggerKind.GLOBAL_BOOL, it)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /** Resolve GIF widget view on current desktop (or any child under homeRoot). */
    private fun findGifReactionView(item: HomeItem): View? {
        binding.desktopContainer.findViewWithTag<View>(item.id)?.let { return it }
        // Search entire home root (page layers / overlays)
        return findViewWithTagRecursive(binding.homeRoot, item.id)
    }

    private fun findViewWithTagRecursive(root: View?, tag: Any?): View? {
        if (root == null || tag == null) return null
        if (tag == root.tag) return root
        if (root is android.view.ViewGroup) {
            for (i in 0 until root.childCount) {
                findViewWithTagRecursive(root.getChildAt(i), tag)?.let { return it }
            }
        }
        return null
    }

    private fun fireGifReaction(item: HomeItem, trigger: TriggerKind, target: View) {
        val live = homeItems.find { it.id == item.id } ?: item
        val recipes = ReactionJson.decodeList(live.reactionsJson)
        if (recipes.isEmpty()) return
        val battery = try {
            val bm = getSystemService(BATTERY_SERVICE) as android.os.BatteryManager
            bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        } catch (_: Exception) { 100 }
        reactionEngine.handleTrigger(
            context = this,
            recipes = recipes,
            trigger = trigger,
            target = target,
            currentPage = currentPage,
            batteryPct = battery,
            openApp = { pkg, act ->
                try {
                    val intent = if (act.isNotBlank()) {
                        Intent().setClassName(pkg, act).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    } else {
                        packageManager.getLaunchIntentForPackage(pkg)
                    }
                    if (intent != null) startActivity(intent)
                } catch (e: Exception) {
                    FileLogger.e("Reaction", "openApp failed $pkg/$act", e)
                }
            },
            openShortcut = { pkg, shortcutId ->
                try {
                    val la = getSystemService(LAUNCHER_APPS_SERVICE) as android.content.pm.LauncherApps
                    la.startShortcut(pkg, shortcutId, null, null, android.os.Process.myUserHandle())
                } catch (e: Exception) {
                    FileLogger.e("Reaction", "shortcut failed $pkg/$shortcutId", e)
                    try {
                        packageManager.getLaunchIntentForPackage(pkg)?.let { startActivity(it) }
                    } catch (_: Exception) {}
                }
            },
            onTransformSettled = { v -> persistGifAnimState(v) }
        )
    }

    private fun showPlacementModeDialog(item: HomeItem) {
        val modes = arrayOf("Grilla", "Libre")
        val checked = if (item.freePlacement) 1 else 0
        AlertDialog.Builder(this)
            .setTitle("Modo de ubicación")
            .setSingleChoiceItems(modes, checked) { dialog, which ->
                val live = homeItems.find { it.id == item.id } ?: item
                if (which == 1) {
                    // Switch to free: seed pixel pos/size from current grid rect
                    if (!live.freePlacement) {
                        val rect = gridRect(live.cellX, live.cellY, live.spanX, live.spanY)
                        live.freeX = rect.left.toFloat()
                        live.freeY = rect.top.toFloat()
                        live.freeW = rect.width().coerceAtLeast(1)
                        live.freeH = rect.height().coerceAtLeast(1)
                    }
                    live.freePlacement = true
                } else {
                    // Switch to grid: snap current free rect to nearest cells
                    if (live.freePlacement) {
                        val left = live.freeX
                        val top = live.freeY
                        val w = (if (live.freeW > 0) live.freeW else gridRect(0, 0, live.spanX, live.spanY).width()).toFloat()
                        val h = (if (live.freeH > 0) live.freeH else gridRect(0, 0, live.spanX, live.spanY).height()).toFloat()
                        val cw = cellWidth.coerceAtLeast(1f)
                        val ch = cellHeight.coerceAtLeast(1f)
                        val sx = (w / cw).roundToInt().coerceIn(1, columns.coerceAtLeast(1))
                        val sy = (h / ch).roundToInt().coerceIn(1, rows.coerceAtLeast(1))
                        val (cx, cy) = snapSpanToGrid(left, top, sx, sy)
                        live.spanX = sx
                        live.spanY = sy
                        live.cellX = cx
                        live.cellY = cy
                        clampItemToGrid(live)
                    }
                    live.freePlacement = false
                }
                item.freePlacement = live.freePlacement
                item.freeX = live.freeX
                item.freeY = live.freeY
                item.freeW = live.freeW
                item.freeH = live.freeH
                item.cellX = live.cellX
                item.cellY = live.cellY
                item.spanX = live.spanX
                item.spanY = live.spanY
                saveAllItems()
                desktopNeedsFullRender = true
                paintDesktopNow()
                dialog.dismiss()
                Toast.makeText(
                    this,
                    if (live.freePlacement) "Ubicación libre" else "Ubicación en grilla",
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showGifOptionsMenu(item: HomeItem, anchor: View) {
        dismissStackMenu()
        val density = resources.displayMetrics.density
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xF0222224.toInt())
                cornerRadius = 14f * density
                setStroke((1 * density).toInt(), 0x33FFFFFF)
            }
            elevation = 28f * density
            setPadding((4 * density).toInt(), (6 * density).toInt(), (4 * density).toInt(), (6 * density).toInt())
        }
        fun addRow(label: String, onClick: () -> Unit) {
            val row = TextView(this).apply {
                text = label
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 15f
                setPadding((18 * density).toInt(), (14 * density).toInt(), (22 * density).toInt(), (14 * density).toInt())
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    dismissStackMenu()
                    iconLongPressActive = false
                    onClick()
                }
            }
            card.addView(row)
        }
        addRow("Redimensionar") { postShowWidgetResize(item) }
        addRow("Modo de ubicación") { showPlacementModeDialog(item) }
        addRow("Cargar imagen / GIF") { openGifFilePicker(item) }
        addRow("Reacciones") {
            openReactionEditor(item)
        }
        addRow("Aspect Mode") { showGifAspectModeDialog(item) }
        addRow("Traer al Frente") { bringWidgetToFront(item) }
        addRow("Mover al Fondo") { sendWidgetToBack(item) }
        addRow("Icons Layer") { showIconsLayerDialog(item) }
        addRow("Quitar") {
            if (item.gifPath.isNotBlank()) {
                try { File(item.gifPath).delete() } catch (_: Exception) {}
            }
            homeItems.removeAll { it.id == item.id }
            saveAllItems()
            clearWidgetResize()
            renderDesktop()
        }
        card.measure(
            View.MeasureSpec.makeMeasureSpec((220 * density).toInt(), View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val mw = card.measuredWidth.coerceAtLeast((160 * density).toInt())
        val mh = card.measuredHeight
        val loc = IntArray(2)
        anchor.getLocationOnScreen(loc)
        val rootLoc = IntArray(2)
        binding.homeRoot.getLocationOnScreen(rootLoc)
        var x = (loc[0] - rootLoc[0] + anchor.width - mw).toFloat()
        var y = (loc[1] - rootLoc[1] + anchor.height / 3).toFloat()
        val maxX = (binding.homeRoot.width - mw - 8).toFloat().coerceAtLeast(8f)
        val maxY = (binding.homeRoot.height - mh - 8).toFloat().coerceAtLeast(8f)
        x = x.coerceIn(8f, maxX)
        y = y.coerceIn(8f, maxY)
        val lp = FrameLayout.LayoutParams(mw, FrameLayout.LayoutParams.WRAP_CONTENT)
        card.x = x
        card.y = y
        card.isClickable = true
        binding.homeRoot.addView(card, lp)
        stackMenuView = card
    }

    private fun showGifAspectModeDialog(item: HomeItem) {
        val modes = arrayOf(
            "Fill Mode" to "fill",
            "Fit Mode" to "fit",
            "Stretch Mode" to "stretch",
            "Center Crop" to "crop"
        )
        val labels = modes.map { it.first }.toTypedArray()
        val current = item.gifAspectMode.ifBlank { "fit" }.lowercase()
        val checked = modes.indexOfFirst { it.second == current }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Aspect Mode")
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                val mode = modes[which].second
                item.gifAspectMode = mode
                saveAllItems()
                // Live-update the on-screen ImageView if present
                val wrap = binding.desktopContainer.findViewWithTag<View>(item.id)
                val image = (wrap as? ViewGroup)?.findViewWithTag<ImageView>("gif_image")
                if (image != null) {
                    applyGifAspectMode(image, mode)
                } else {
                    renderDesktop()
                }
                dialog.dismiss()
                Toast.makeText(this, labels[which], Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }


    private fun showWidgetOptions(item: HomeItem, anchor: View) {
        dismissCurrentPopup()
        iconLongPressActive = true
        suppressEmptyLongPress = true
        if (item.isWidgetStack) {
            // Custom floating menu — PopupMenu cancels the active touch stream so
            // "move after long-press → drag" would never fire.
            showStackOptionsMenu(item, anchor)
            return
        }
        if (item.isGifWidget) {
            showGifOptionsMenu(item, anchor)
            return
        }
        val popup = PopupMenu(this, anchor, Gravity.END)
        popup.menu.add(0, 1, 0, "Redimensionar")
        popup.menu.add(0, 7, 1, "Modo de ubicación")
        popup.menu.add(0, 3, 2, "Reintentar / actualizar")
        val info = try {
            if (item.appWidgetId >= 0) appWidgetManager.getAppWidgetInfo(item.appWidgetId) else null
        } catch (_: Exception) { null }
        if (info?.configure != null) {
            popup.menu.add(0, 4, 2, "Configurar widget")
        }
        popup.menu.add(0, 5, 3, "Traer al Frente")
        popup.menu.add(0, 6, 4, "Mover al Fondo")
        popup.menu.add(0, 8, 5, "Icons Layer")
        popup.menu.add(0, 2, 6, "Quitar")
        popup.setOnMenuItemClickListener { mi ->
            when (mi.itemId) {
                1 -> {
                    iconLongPressActive = false
                    postShowWidgetResize(item)
                }
                7 -> {
                    iconLongPressActive = false
                    showPlacementModeDialog(item)
                }
                3 -> {
                    iconLongPressActive = false
                    rebindWidgetFromPlaceholder(item)
                }
                4 -> {
                    iconLongPressActive = false
                    try {
                        val i = Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE).apply {
                            component = info!!.configure
                            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, item.appWidgetId)
                        }
                        pendingBindWidgetId = item.appWidgetId
                        pendingWidgetInfo = info
                        pendingWidgetSpanX = item.spanX.coerceAtLeast(1)
                        pendingWidgetSpanY = item.spanY.coerceAtLeast(1)
                        configureWidgetLauncher.launch(i)
                    } catch (e: Exception) {
                        FileLogger.e("WidgetBind", "launch configure from menu failed", e)
                        Toast.makeText(this, "No se pudo abrir la configuración", Toast.LENGTH_SHORT).show()
                    }
                }
                5 -> {
                    iconLongPressActive = false
                    bringWidgetToFront(item)
                }
                6 -> {
                    iconLongPressActive = false
                    sendWidgetToBack(item)
                }
                8 -> {
                    iconLongPressActive = false
                    showIconsLayerDialog(item)
                }
                2 -> {
                    iconLongPressActive = false
                    homeItems.removeAll { it.id == item.id }
                    if (item.appWidgetId >= 0) {
                        widgetHostViews.remove(item.appWidgetId)
                        try { appWidgetHost.deleteAppWidgetId(item.appWidgetId) } catch (_: Exception) {}
                    }
                    saveAllItems()
                    clearWidgetResize()
                    renderDesktop()
                }
            }
            true
        }
        popup.setOnDismissListener {
            if (draggingApp == null) {
                anchor.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
                if (draggingApp == null) {
                    iconLongPressActive = false
                }
            }
            if (currentPopup == popup) currentPopup = null
        }
        currentPopup = popup
        popup.show()
    }


    /**
     * Reorder stack members with drag handles (cards + previews, smooth ItemTouchHelper).
     */
    private fun showStackOrderDialog(stackItem: HomeItem) {
        val live = homeItems.find { it.id == stackItem.id && it.isWidgetStack } ?: stackItem
        if (live.stackMembers.size < 2) {
            Toast.makeText(this, "Agregá al menos 2 widgets a la pila", Toast.LENGTH_SHORT).show()
            return
        }
        val density = resources.displayMetrics.density
        val members = live.stackMembers.toMutableList()
        val currentKey = members.getOrNull(live.stackCurrentPage.coerceIn(0, members.lastIndex))?.providerKey

        val dialog = android.app.Dialog(this, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val root = FrameLayout(this).apply {
            setPadding((16 * density).toInt(), (24 * density).toInt(), (16 * density).toInt(), (24 * density).toInt())
        }
        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                // Deep purple / dark panel similar to the reference
                setColor(0xFF2A1B4A.toInt())
                cornerRadius = 28f * density
            }
            elevation = 24f * density
            setPadding((14 * density).toInt(), (18 * density).toInt(), (14 * density).toInt(), (14 * density).toInt())
        }
        val title = TextView(this).apply {
            text = "Orden de Pila"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, (12 * density).toInt())
        }
        sheet.addView(title)

        val rv = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@HomeActivity)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (420 * density).toInt()
            )
            clipToPadding = false
            setPadding(0, (4 * density).toInt(), 0, (4 * density).toInt())
        }

        fun providerLabel(m: StackWidgetMember): String {
            return try {
                val cn = android.content.ComponentName(m.packageName, m.className)
                val info = appWidgetManager.installedProviders.firstOrNull { it.provider == cn }
                info?.loadLabel(packageManager)?.toString()
                    ?: packageManager.getApplicationLabel(
                        packageManager.getApplicationInfo(m.packageName, 0)
                    ).toString()
            } catch (_: Exception) {
                m.packageName.substringAfterLast('.').replaceFirstChar { it.uppercase() }
            }
        }
        fun providerIcon(m: StackWidgetMember): android.graphics.drawable.Drawable? {
            return try {
                val cn = android.content.ComponentName(m.packageName, m.className)
                val info = appWidgetManager.installedProviders.firstOrNull { it.provider == cn }
                info?.loadIcon(this@HomeActivity, resources.displayMetrics.densityDpi)
                    ?: packageManager.getApplicationIcon(m.packageName)
            } catch (_: Exception) {
                null
            }
        }
        fun providerPreview(m: StackWidgetMember): android.graphics.drawable.Drawable? {
            return try {
                val cn = android.content.ComponentName(m.packageName, m.className)
                val info = appWidgetManager.installedProviders.firstOrNull { it.provider == cn }
                info?.loadPreviewImage(this@HomeActivity, resources.displayMetrics.densityDpi)
                    ?: info?.loadIcon(this@HomeActivity, resources.displayMetrics.densityDpi)
            } catch (_: Exception) {
                null
            }
        }

        class VH(val row: FrameLayout) : RecyclerView.ViewHolder(row) {
            val card: LinearLayout = row.getChildAt(0) as LinearLayout
            val icon: ImageView = card.findViewWithTag("icon")
            val preview: ImageView = card.findViewWithTag("preview")
            val label: TextView = card.findViewWithTag("label")
            val handle: ImageView = card.findViewWithTag("handle")
        }

        val adapter = object : RecyclerView.Adapter<VH>() {
            override fun getItemCount(): Int = members.size
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
                val pad = (12 * density).toInt()
                val row = FrameLayout(this@HomeActivity).apply {
                    layoutParams = RecyclerView.LayoutParams(
                        RecyclerView.LayoutParams.MATCH_PARENT,
                        RecyclerView.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topMargin = (6 * density).toInt()
                        bottomMargin = (6 * density).toInt()
                    }
                }
                val card = LinearLayout(this@HomeActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(0xF01C142E.toInt())
                        cornerRadius = 18f * density
                        setStroke((1 * density).toInt(), 0x33FFFFFF)
                    }
                    setPadding(pad, pad, pad, pad)
                    elevation = 4f * density
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT
                    )
                }
                val icon = ImageView(this@HomeActivity).apply {
                    tag = "icon"
                    layoutParams = LinearLayout.LayoutParams(
                        (44 * density).toInt(), (44 * density).toInt()
                    ).apply { marginEnd = (10 * density).toInt() }
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }
                val mid = LinearLayout(this@HomeActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                val preview = ImageView(this@HomeActivity).apply {
                    tag = "preview"
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        (56 * density).toInt()
                    )
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(0x22000000)
                        cornerRadius = 10f * density
                    }
                }
                val label = TextView(this@HomeActivity).apply {
                    tag = "label"
                    setTextColor(0xFFE8E0FF.toInt())
                    textSize = 13f
                    setPadding(0, (6 * density).toInt(), 0, 0)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }
                mid.addView(preview)
                mid.addView(label)
                val handle = ImageView(this@HomeActivity).apply {
                    tag = "handle"
                    layoutParams = LinearLayout.LayoutParams(
                        (28 * density).toInt(), (40 * density).toInt()
                    ).apply { marginStart = (8 * density).toInt() }
                    // Simple 3-line drag handle
                    setImageDrawable(object : android.graphics.drawable.Drawable() {
                        private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                            color = 0x99FFFFFF.toInt()
                            strokeWidth = 3f * density
                            strokeCap = android.graphics.Paint.Cap.ROUND
                        }
                        override fun draw(canvas: android.graphics.Canvas) {
                            val b = bounds
                            val cx = b.exactCenterX()
                            val cy = b.exactCenterY()
                            val w = 12f * density
                            for (i in -1..1) {
                                val y = cy + i * 7f * density
                                canvas.drawLine(cx - w, y, cx + w, y, p)
                            }
                        }
                        override fun setAlpha(alpha: Int) { p.alpha = alpha }
                        override fun setColorFilter(cf: android.graphics.ColorFilter?) { p.colorFilter = cf }
                        override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
                    })
                }
                card.addView(icon)
                card.addView(mid)
                card.addView(handle)
                row.addView(card)
                return VH(row)
            }
            override fun onBindViewHolder(holder: VH, position: Int) {
                val m = members[position]
                holder.label.text = providerLabel(m)
                holder.icon.setImageDrawable(providerIcon(m))
                holder.preview.setImageDrawable(providerPreview(m))
            }
        }
        rv.adapter = adapter

        val touch = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun isLongPressDragEnabled(): Boolean = false
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
                java.util.Collections.swap(members, from, to)
                adapter.notifyItemMoved(from, to)
                return true
            }
            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}
            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && viewHolder != null) {
                    viewHolder.itemView.animate()
                        .scaleX(1.03f).scaleY(1.03f)
                        .translationZ(12f * density)
                        .setDuration(120).start()
                    viewHolder.itemView.alpha = 0.95f
                }
            }
            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                viewHolder.itemView.animate()
                    .scaleX(1f).scaleY(1f)
                    .translationZ(0f)
                    .setDuration(120).start()
                viewHolder.itemView.alpha = 1f
            }
        })
        touch.attachToRecyclerView(rv)

        // Drag from handle only
        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {})
        // After layout, set handle touch
        rv.addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(view: View) {
                val handle = view.findViewWithTag<View>("handle") ?: return
                handle.setOnTouchListener { _, ev ->
                    if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
                        val vh = rv.getChildViewHolder(view)
                        touch.startDrag(vh)
                    }
                    false
                }
            }
            override fun onChildViewDetachedFromWindow(view: View) {}
        })

        sheet.addView(rv)

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, (12 * density).toInt(), 0, 0)
        }
        fun btn(text: String, filled: Boolean, onClick: () -> Unit): TextView {
            return TextView(this).apply {
                this.text = text
                setTextColor(if (filled) 0xFFFFFFFF.toInt() else 0xFFD0C4FF.toInt())
                textSize = 15f
                setPadding((18 * density).toInt(), (10 * density).toInt(), (18 * density).toInt(), (10 * density).toInt())
                if (filled) {
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(0xFF6C4DFF.toInt())
                        cornerRadius = 12f * density
                    }
                }
                setOnClickListener { onClick() }
            }
        }
        btnRow.addView(btn("Cancelar", false) { dialog.dismiss() })
        btnRow.addView(btn("Listo", true) {
            // Preserve visible page by provider key
            val newIdx = currentKey?.let { key -> members.indexOfFirst { it.providerKey == key } }?.takeIf { it >= 0 } ?: 0
            live.stackMembers.clear()
            live.stackMembers.addAll(members)
            live.stackCurrentPage = newIdx.coerceIn(0, (members.size - 1).coerceAtLeast(0))
            val idx = homeItems.indexOfFirst { it.id == live.id }
            if (idx >= 0) {
                homeItems[idx].stackMembers.clear()
                homeItems[idx].stackMembers.addAll(members)
                homeItems[idx].stackCurrentPage = live.stackCurrentPage
            }
            saveAllItems()
            renderDesktop()
            dialog.dismiss()
            Toast.makeText(this, "Orden actualizado", Toast.LENGTH_SHORT).show()
        }.apply {
            (layoutParams as? LinearLayout.LayoutParams)?.marginStart = (8 * density).toInt()
        })
        // fix margin for Listo
        val listo = btnRow.getChildAt(1)
        listo.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = (8 * density).toInt() }

        sheet.addView(btnRow)
        root.addView(sheet, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        ))
        dialog.setContentView(root)
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.92f).toInt(),
            FrameLayout.LayoutParams.WRAP_CONTENT
        )
        dialog.show()
    }

    /** Floating card menu for stacks that does not cancel the active touch sequence. */
    private fun showStackOptionsMenu(item: HomeItem, anchor: View) {
        dismissStackMenu()
        val density = resources.displayMetrics.density
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xF0222224.toInt())
                cornerRadius = 14f * density
                setStroke((1 * density).toInt(), 0x33FFFFFF)
            }
            elevation = 28f * density
            setPadding((4 * density).toInt(), (6 * density).toInt(), (4 * density).toInt(), (6 * density).toInt())
        }
        fun addRow(label: String, onClick: () -> Unit) {
            val row = TextView(this).apply {
                text = label
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 15f
                setPadding((18 * density).toInt(), (14 * density).toInt(), (22 * density).toInt(), (14 * density).toInt())
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    dismissStackMenu()
                    iconLongPressActive = false
                    onClick()
                }
            }
            card.addView(row)
        }
        addRow("Redimensionar") { postShowWidgetResize(item) }
        addRow("Modo de ubicación") { showPlacementModeDialog(item) }
        addRow("Administrar Widgets") { showStackMemberPicker(item) }
        addRow("Orden de Pila") { showStackOrderDialog(item) }
        addRow("Quitar este widget") { removeCurrentStackMember(item) }
        addRow("Traer al Frente") { bringWidgetToFront(item) }
        addRow("Mover al Fondo") { sendWidgetToBack(item) }
        addRow("Icons Layer") { showIconsLayerDialog(item) }
        val swipeLabel = if (item.stackSwipeVertical) "Swipe: Vertical" else "Swipe: Horizontal"
        addRow(swipeLabel) {
            item.stackSwipeVertical = !item.stackSwipeVertical
            saveAllItems()
            renderDesktop()
            val dir = if (item.stackSwipeVertical) "vertical" else "horizontal"
            Toast.makeText(this, "Swipe $dir", Toast.LENGTH_SHORT).show()
        }
        addRow("Quitar pila") {
            deleteStackMembers(item)
            homeItems.removeAll { it.id == item.id }
            saveAllItems()
            clearWidgetResize()
            renderDesktop()
        }

        // Measure then place near the stack, within the screen
        card.measure(
            View.MeasureSpec.makeMeasureSpec((220 * density).toInt(), View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val mw = card.measuredWidth.coerceAtLeast((160 * density).toInt())
        val mh = card.measuredHeight
        val loc = IntArray(2)
        anchor.getLocationOnScreen(loc)
        val rootLoc = IntArray(2)
        binding.homeRoot.getLocationOnScreen(rootLoc)
        var x = (loc[0] - rootLoc[0] + anchor.width - mw).toFloat()
        var y = (loc[1] - rootLoc[1] + anchor.height / 3).toFloat()
        val maxX = (binding.homeRoot.width - mw - 8).toFloat().coerceAtLeast(8f)
        val maxY = (binding.homeRoot.height - mh - 8).toFloat().coerceAtLeast(8f)
        x = x.coerceIn(8f, maxX)
        y = y.coerceIn(8f, maxY)
        val lp = FrameLayout.LayoutParams(mw, FrameLayout.LayoutParams.WRAP_CONTENT)
        card.x = x
        card.y = y
        // Don't steal the active finger — menu is display-only until tapped with a new touch
        card.isClickable = true
        binding.homeRoot.addView(card, lp)
        stackMenuView = card
        FileLogger.log("WidgetStack", "stack menu shown id=${item.id}")
    }

    private fun postShowWidgetResize(item: HomeItem) {
        binding.desktopContainer.post {
            try { showWidgetResizeFrame(item) } catch (_: Exception) {}
        }
    }

    private fun clearWidgetResize() {
        val wasId = resizingWidgetId
        try {
            widgetResizeFrame?.let { f ->
                f.animate().cancel()
                (f.parent as? ViewGroup)?.removeView(f)
            }
        } catch (_: Exception) {}
        try {
            binding.desktopContainer.requestDisallowInterceptTouchEvent(false)
        } catch (_: Exception) {}
        widgetResizeFrame = null
        resizingWidgetId = null
        // Leave no sticky long-press / suppress flags if we are not mid-drag
        if (draggingApp == null) {
            iconLongPressActive = false
            suppressEmptyLongPress = false
        }
        // Snap the widget back to its cell matrix so it does not stay visually shifted
        // after exiting resize (page change used to be the only thing that fixed it).
        if (wasId != null) {
            val item = homeItems.find { it.id == wasId }
            val v = binding.desktopContainer.findViewWithTag<View>(wasId)
            if (item != null && v != null) {
                positionWidget(v, item)
            }
        }
        if (draggingApp == null) {
            try {
                dropOverlay?.let { o -> (o.parent as? ViewGroup)?.removeView(o) }
            } catch (_: Exception) {}
            dropOverlay = null
        }
        if (draggingApp == null && currentPopup == null && stackMenuView == null) {
            iconLongPressActive = false
            suppressEmptyLongPress = false
        }
        // Always drop a leftover stack menu when leaving resize
        if (stackMenuView != null) dismissStackMenu()
    }

    /**
     * Resize mode — single source of truth: gridRect.
     * Frame and widget are ALWAYS set to the exact same pixel rect.
     * Opposite edge stays fixed in cell space when dragging a handle.
     * sizeOptions only applied on finger UP (not mid-gesture).
     */
    private fun showWidgetResizeFrame(item: HomeItem) {
        clearWidgetResize()
        if (cellWidth <= 1f || cellHeight <= 1f) {
            calculateMetrics()
            if (cellWidth <= 1f || cellHeight <= 1f) return
        }
        // Freeze metrics for the entire resize session
        val lockedCw = cellWidth
        val lockedCh = cellHeight
        val lockedCols = columns
        val lockedRows = rows

        dropOverlay?.visibility = View.GONE
        dropOverlay?.let { try { (it.parent as? android.view.ViewGroup)?.removeView(it) } catch (_: Exception) {} }
        dropOverlay = null
        resizingWidgetId = item.id
        iconLongPressActive = true
        suppressEmptyLongPress = true
        val density = resources.displayMetrics.density

        // Snap widget to its cell matrix FIRST so frame can match it exactly
        val widgetView = binding.desktopContainer.findViewWithTag<View>(item.id)
        widgetView?.let { v ->
            v.animate().cancel()
            v.scaleX = 1f
            v.scaleY = 1f
            v.translationZ = 0f
            v.translationX = 0f
            v.translationY = 0f
            positionWidget(v, item)
        }

        val frame = FrameLayout(this).apply {
            tag = "widget_resize"
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0x2200B0FF)
                setStroke((2.5f * density).toInt(), 0xFF4FC3F7.toInt())
                cornerRadius = 12f * density
            }
            isClickable = true
            elevation = 20f
        }
        // Visual ~22dp dot inside a larger ~48dp touch target so edges are easy to grab
        val visualHandle = (22 * density).toInt()
        val handleSize = (48 * density).toInt()
        fun makeHandle(gravity: Int): View {
            return FrameLayout(this).apply {
                val lp = FrameLayout.LayoutParams(handleSize, handleSize, gravity)
                val m = -handleSize / 2
                lp.setMargins(m, m, m, m)
                layoutParams = lp
                elevation = 24f
                isClickable = true
                // Centered visible grip
                addView(View(this@HomeActivity).apply {
                    background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(0xFF4FC3F7.toInt())
                        setStroke((2 * density).toInt(), 0xFFFFFFFF.toInt())
                    }
                    layoutParams = FrameLayout.LayoutParams(visualHandle, visualHandle, Gravity.CENTER)
                })
                // Captured on DOWN — fixed edge reference for the whole gesture
                var originSpanX = item.spanX
                var originSpanY = item.spanY
                var originCellX = item.cellX
                var originCellY = item.cellY
                var originFreeX = item.freeX
                var originFreeY = item.freeY
                var originFreeW = item.freeW
                var originFreeH = item.freeH
                var startRawX = 0f
                var startRawY = 0f
                var lastNsx = item.spanX
                var lastNsy = item.spanY
                var lastNx = item.cellX
                var lastNy = item.cellY
                var lastFw = item.freeW
                var lastFh = item.freeH
                var lastFx = item.freeX
                var lastFy = item.freeY
                setOnTouchListener { _, ev ->
                    when (ev.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            originSpanX = item.spanX
                            originSpanY = item.spanY
                            originCellX = item.cellX
                            originCellY = item.cellY
                            // Seed free size from current layout if missing
                            if (item.freePlacement) {
                                if (item.freeW <= 0 || item.freeH <= 0) {
                                    val fb = gridRect(item.cellX, item.cellY, item.spanX, item.spanY)
                                    if (item.freeW <= 0) item.freeW = fb.width()
                                    if (item.freeH <= 0) item.freeH = fb.height()
                                }
                            }
                            originFreeX = item.freeX
                            originFreeY = item.freeY
                            originFreeW = item.freeW.coerceAtLeast(1)
                            originFreeH = item.freeH.coerceAtLeast(1)
                            lastNsx = originSpanX
                            lastNsy = originSpanY
                            lastNx = originCellX
                            lastNy = originCellY
                            lastFw = originFreeW
                            lastFh = originFreeH
                            lastFx = originFreeX
                            lastFy = originFreeY
                            startRawX = ev.rawX
                            startRawY = ev.rawY
                            parent?.requestDisallowInterceptTouchEvent(true)
                            true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val dx = ev.rawX - startRawX
                            val dy = ev.rawY - startRawY
                            if (item.freePlacement) {
                                // Free pixel resize — no grid snap
                                val minPx = (48 * density).toInt()
                                var fx = originFreeX
                                var fy = originFreeY
                                var fw = originFreeW
                                var fh = originFreeH
                                when (gravity) {
                                    Gravity.END or Gravity.CENTER_VERTICAL -> {
                                        fw = (originFreeW + dx).toInt().coerceAtLeast(minPx)
                                    }
                                    Gravity.START or Gravity.CENTER_VERTICAL -> {
                                        fw = (originFreeW - dx).toInt().coerceAtLeast(minPx)
                                        fx = originFreeX + (originFreeW - fw)
                                    }
                                    Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL -> {
                                        fh = (originFreeH + dy).toInt().coerceAtLeast(minPx)
                                    }
                                    Gravity.TOP or Gravity.CENTER_HORIZONTAL -> {
                                        fh = (originFreeH - dy).toInt().coerceAtLeast(minPx)
                                        fy = originFreeY + (originFreeH - fh)
                                    }
                                }
                                if (fx == lastFx && fy == lastFy && fw == lastFw && fh == lastFh) {
                                    return@setOnTouchListener true
                                }
                                lastFx = fx; lastFy = fy; lastFw = fw; lastFh = fh
                                item.freeX = fx
                                item.freeY = fy
                                item.freeW = fw
                                item.freeH = fh
                                // Apply immediately to frame + widget view
                                val live = homeItems.find { it.id == item.id } ?: item
                                live.freeX = fx; live.freeY = fy; live.freeW = fw; live.freeH = fh
                                (frame.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                                    lp.width = fw; lp.height = fh
                                    lp.leftMargin = fx.roundToInt(); lp.topMargin = fy.roundToInt()
                                    frame.layoutParams = lp
                                }
                                binding.desktopContainer.findViewWithTag<View>(item.id)?.let { v ->
                                    positionWidget(v, live)
                                }
                                return@setOnTouchListener true
                            }
                            // Grid resize — one cell of finger travel = one cell of resize
                            val dCellsX = kotlin.math.round(dx / lockedCw).toInt()
                            val dCellsY = kotlin.math.round(dy / lockedCh).toInt()
                            var nx = originCellX
                            var ny = originCellY
                            var nsx = originSpanX
                            var nsy = originSpanY
                            when (gravity) {
                                // Right edge: left edge (cellX) fixed
                                Gravity.END or Gravity.CENTER_VERTICAL -> {
                                    nsx = (originSpanX + dCellsX).coerceIn(1, lockedCols - originCellX)
                                }
                                // Left edge: right edge (cellX+spanX) fixed
                                Gravity.START or Gravity.CENTER_VERTICAL -> {
                                    val right = originCellX + originSpanX
                                    nsx = (originSpanX - dCellsX).coerceIn(1, right)
                                    nx = (right - nsx).coerceAtLeast(0)
                                    nsx = (right - nx).coerceIn(1, lockedCols - nx)
                                }
                                // Bottom edge: top edge (cellY) fixed
                                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL -> {
                                    nsy = (originSpanY + dCellsY).coerceIn(1, lockedRows - originCellY)
                                }
                                // Top edge: bottom edge (cellY+spanY) fixed
                                Gravity.TOP or Gravity.CENTER_HORIZONTAL -> {
                                    val bottom = originCellY + originSpanY
                                    nsy = (originSpanY - dCellsY).coerceIn(1, bottom)
                                    ny = (bottom - nsy).coerceAtLeast(0)
                                    nsy = (bottom - ny).coerceIn(1, lockedRows - ny)
                                }
                            }
                            // Skip work if nothing changed — prevents micro-jumps
                            if (nx == lastNx && ny == lastNy && nsx == lastNsx && nsy == lastNsy) {
                                return@setOnTouchListener true
                            }
                            lastNx = nx
                            lastNy = ny
                            lastNsx = nsx
                            lastNsy = nsy
                            item.cellX = nx
                            item.cellY = ny
                            item.spanX = nsx
                            item.spanY = nsy
                            // ONE function sets both frame and widget to the same rect
                            syncResizeFrameAndWidget(frame, item)
                            true
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            parent?.requestDisallowInterceptTouchEvent(false)
                            // Persist free or grid size
                            val live = homeItems.find { it.id == item.id }
                            if (live != null) {
                                live.freePlacement = item.freePlacement
                                live.freeX = item.freeX
                                live.freeY = item.freeY
                                live.freeW = item.freeW
                                live.freeH = item.freeH
                                live.cellX = item.cellX
                                live.cellY = item.cellY
                                live.spanX = item.spanX
                                live.spanY = item.spanY
                            }
                            saveAllItems()
                            syncResizeFrameAndWidget(frame, item)
                            // Final size options once only (pixel size in free mode)
                            val rw: Int
                            val rh: Int
                            if (item.freePlacement) {
                                rw = item.freeW.coerceAtLeast(1)
                                rh = item.freeH.coerceAtLeast(1)
                            } else {
                                val rect = gridRect(item.cellX, item.cellY, item.spanX, item.spanY)
                                rw = rect.width().coerceAtLeast(1)
                                rh = rect.height().coerceAtLeast(1)
                            }
                            if (item.isWidgetStack) {
                                for (m in item.stackMembers) {
                                    if (m.appWidgetId < 0) continue
                                    val hv = widgetHostViews[m.appWidgetId] ?: continue
                                    val info = try {
                                        appWidgetManager.getAppWidgetInfo(m.appWidgetId)
                                    } catch (_: Exception) { null }
                                    try {
                                        applyWidgetSizeOptions(m.appWidgetId, hv, info, rw, rh)
                                    } catch (_: Exception) {}
                                }
                            } else if (item.appWidgetId >= 0) {
                                val hv = widgetHostViews[item.appWidgetId]
                                val info = try {
                                    appWidgetManager.getAppWidgetInfo(item.appWidgetId)
                                } catch (_: Exception) { null }
                                if (hv != null) {
                                    try {
                                        applyWidgetSizeOptions(item.appWidgetId, hv, info, rw, rh)
                                    } catch (_: Exception) {}
                                }
                            }
                            true
                        }
                        else -> false
                    }
                }
            }
        }
        frame.addView(makeHandle(Gravity.END or Gravity.CENTER_VERTICAL))
        frame.addView(makeHandle(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        frame.addView(makeHandle(Gravity.START or Gravity.CENTER_VERTICAL))
        frame.addView(makeHandle(Gravity.TOP or Gravity.CENTER_HORIZONTAL))

        binding.desktopContainer.addView(frame)
        widgetResizeFrame = frame
        // Initial place: both on the same gridRect
        syncResizeFrameAndWidget(frame, item)

        // Tap outside ends resize mode
        binding.desktopContainer.setOnClickListener {
            if (resizingWidgetId != null) {
                clearWidgetResize()
                saveAllItems()
                try {
                    dropOverlay?.let { o -> (o.parent as? ViewGroup)?.removeView(o) }
                } catch (_: Exception) {}
                dropOverlay = null
                binding.desktopContainer.setOnClickListener(null)
            }
        }
    }

    /**
     * Single path: widget wrap AND resize frame share the exact same gridRect,
     * applied via FrameLayout margins (not view.x/y) so layout passes cannot
     * desync them.
     */
    private fun syncResizeFrameAndWidget(frame: FrameLayout, item: HomeItem) {
        if (cellWidth <= 1f || cellHeight <= 1f) return
        val w: Int
        val h: Int
        val left: Int
        val top: Int
        if (item.freePlacement) {
            val fb = gridRect(item.cellX, item.cellY, item.spanX, item.spanY)
            w = (if (item.freeW > 0) item.freeW else fb.width()).coerceAtLeast(1)
            h = (if (item.freeH > 0) item.freeH else fb.height()).coerceAtLeast(1)
            left = item.freeX.roundToInt()
            top = item.freeY.roundToInt()
        } else {
            val rect = gridRect(item.cellX, item.cellY, item.spanX, item.spanY)
            w = rect.width().coerceAtLeast(1)
            h = rect.height().coerceAtLeast(1)
            left = rect.left
            top = rect.top
        }

        // Widget — margins only
        binding.desktopContainer.findViewWithTag<View>(item.id)?.let { view ->
            val lp = (view.layoutParams as? FrameLayout.LayoutParams)
                ?: FrameLayout.LayoutParams(w, h)
            lp.width = w
            lp.height = h
            lp.leftMargin = left
            lp.topMargin = top
            lp.rightMargin = 0
            lp.bottomMargin = 0
            lp.gravity = Gravity.TOP or Gravity.START
            view.layoutParams = lp
            view.translationX = 0f
            view.translationY = 0f
            view.scaleX = 1f
            view.scaleY = 1f
            if (view is ViewGroup && view.childCount > 0) {
                val host = view.getChildAt(0)
                val hlp = (host.layoutParams as? FrameLayout.LayoutParams)
                    ?: FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                hlp.width = FrameLayout.LayoutParams.MATCH_PARENT
                hlp.height = FrameLayout.LayoutParams.MATCH_PARENT
                host.layoutParams = hlp
            }
        }

        // Frame — identical margins/size
        val flp = (frame.layoutParams as? FrameLayout.LayoutParams)
            ?: FrameLayout.LayoutParams(w, h)
        flp.width = w
        flp.height = h
        flp.leftMargin = left
        flp.topMargin = top
        flp.rightMargin = 0
        flp.bottomMargin = 0
        flp.gravity = Gravity.TOP or Gravity.START
        frame.layoutParams = flp
        frame.translationX = 0f
        frame.translationY = 0f
        try { frame.bringToFront() } catch (_: Exception) {}
    }

    private fun layoutResizeFrame(frame: FrameLayout, item: HomeItem) {
        // Kept as alias so any remaining callers still work
        syncResizeFrameAndWidget(frame, item)
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
    /** Last icon-size percents applied to the desktop (detect settings changes without full rebuild always). */
    private var lastDesktopIconPct = -1
    private var lastDockIconPct = -1
    private var lastShowDock: Boolean? = null
    private var lastShowDrawerBtn: Boolean? = null

    private fun applySettingsFromPrefs(forceRender: Boolean = false) {
        val newCols = prefs.desktopColumns.coerceAtLeast(1)
        val newRows = prefs.desktopRows.coerceAtLeast(1)
        val iconPct = prefs.desktopIconSizePercent
        val dockPct = prefs.dockIconSizePercent
        val showDock = prefs.showDock
        val showDrawer = prefs.showDrawerButton
        val gridChanged = newCols != columns || newRows != rows
        val iconChanged = iconPct != lastDesktopIconPct || dockPct != lastDockIconPct
        val chromeChanged = lastShowDock != showDock || lastShowDrawerBtn != showDrawer
        columns = newCols
        rows = newRows
        lastDesktopIconPct = iconPct
        lastDockIconPct = dockPct
        lastShowDock = showDock
        lastShowDrawerBtn = showDrawer
        applyDrawerButtonVisibility()
        applyBottomBarOrder()
        binding.bottomBar.requestLayout()
        // Only rebuild when something visible actually changed — not on every resume.
        if (forceRender || gridChanged || iconChanged || chromeChanged ||
            desktopNeedsFullRender || binding.desktopContainer.childCount == 0
        ) {
            paintDesktopNow()
        } else {
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

    private fun registerUnlockReceiver() {
        if (unlockReceiverRegistered) return
        try {
            val filter = android.content.IntentFilter().apply {
                addAction(android.content.Intent.ACTION_SCREEN_OFF)
                addAction(android.content.Intent.ACTION_SCREEN_ON)
                addAction(android.content.Intent.ACTION_USER_PRESENT)
            }
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                registerReceiver(unlockReceiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(unlockReceiver, filter)
            }
            unlockReceiverRegistered = true
        } catch (_: Exception) {}
    }

    private fun unregisterUnlockReceiver() {
        if (!unlockReceiverRegistered) return
        try { unregisterReceiver(unlockReceiver) } catch (_: Exception) {}
        unlockReceiverRegistered = false
    }

    private fun isKeyguardLocked(): Boolean {
        return try {
            val km = getSystemService(android.app.KeyguardManager::class.java)
            km?.isKeyguardLocked == true
        } catch (_: Exception) {
            false
        }
    }

    private fun isDesktopWidget(child: View): Boolean {
        if (child.getTag(0x70F10001) == true) return true
        if (child is ViewGroup) {
            for (j in 0 until child.childCount) {
                if (child.getChildAt(j) is AppWidgetHostView) return true
            }
        }
        return false
    }

    private fun dockUnlockStartY(): Float {
        val dock = binding.bottomBar
        val h = dock.height.takeIf { it > 0 }
            ?: (120 * resources.displayMetrics.density).toInt()
        return h.toFloat() + 48f * resources.displayMetrics.density
    }

    /**
     * Snap icons / widgets / dock to the START of the unlock animation while the
     * screen is still off / lockscreen is up. Never leave this state without either
     * playUnlockAnimations() or forceUnlockSettled().
     */
    private fun prepareUnlockAnimationState() {
        if (!::binding.isInitialized) return
        if (isDrawerOpen || isOverviewMode || dualTransitionActive) {
            FileLogger.log("Unlock", "prepare skipped (drawer=$isDrawerOpen overview=$isOverviewMode dual=$dualTransitionActive)")
            return
        }
        val container = binding.desktopContainer
        var icons = 0
        var widgets = 0
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i) ?: continue
            if (child === dropOverlay) continue
            child.animate().cancel()
            if (isDesktopWidget(child)) {
                child.scaleX = 0.55f
                child.scaleY = 0.55f
                child.alpha = 0f
                try { child.translationZ = -24f * resources.displayMetrics.density } catch (_: Exception) {}
                widgets++
            } else {
                // Start slightly large + invisible; bounce+fade on play
                child.scaleX = 1.22f
                child.scaleY = 1.22f
                child.alpha = 0f
                icons++
            }
        }
        if (prefs.showDock) {
            val dock = binding.bottomBar
            dock.animate().cancel()
            if (dock.visibility != View.VISIBLE) dock.visibility = View.VISIBLE
            dock.translationY = dockUnlockStartY()
        }
        unlockAnimPrepared = true
        unlockAnimPlaying = false
        FileLogger.log(
            "Unlock",
            "prepare ok icons=$icons widgets=$widgets dock=${prefs.showDock} " +
                "keyguard=${isKeyguardLocked()} screenWasOff=$screenWasOff"
        )
    }

    /** Always restores a fully settled desktop (visible icons/widgets/dock). */
    private fun forceUnlockSettled(reason: String = "force") {
        if (!::binding.isInitialized) return
        unlockSafetyRunnable?.let { binding.homeRoot.removeCallbacks(it) }
        unlockSafetyRunnable = null
        unlockAnimPlaying = false
        unlockAnimPrepared = false
        screenWasOff = false
        val container = binding.desktopContainer
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i) ?: continue
            if (child === dropOverlay) continue
            child.animate().cancel()
            child.scaleX = 1f
            child.scaleY = 1f
            child.alpha = 1f
            try { child.translationZ = 0f } catch (_: Exception) {}
        }
        val dock = binding.bottomBar
        dock.animate().cancel()
        dock.translationY = 0f
        if (prefs.showDock && dock.visibility != View.VISIBLE) {
            dock.visibility = View.VISIBLE
        }
        val elapsed = if (unlockPlayStartedAt > 0L) System.currentTimeMillis() - unlockPlayStartedAt else -1L
        FileLogger.log("Unlock", "settled reason=$reason elapsedMs=$elapsed")
        unlockPlayStartedAt = 0L
    }

    /**
     * Run unlock animations from the prepared start pose.
     * Always schedules a safety timeout so the desktop cannot stay invisible.
     */
    private fun playUnlockAnimations() {
        if (!::binding.isInitialized) return
        if (!prefs.unlockAnimationsEnabled) {
            forceUnlockSettled("disabled")
            return
        }
        if (isDrawerOpen || isOverviewMode || dualTransitionActive) {
            FileLogger.log("Unlock", "play → settle (busy drawer/overview/dual)")
            forceUnlockSettled("busy")
            return
        }
        if (unlockAnimPlaying) {
            FileLogger.log("Unlock", "play skipped already playing")
            return
        }
        if (!unlockAnimPrepared) {
            prepareUnlockAnimationState()
        }
        if (!unlockAnimPrepared) {
            forceUnlockSettled("could-not-prepare")
            return
        }

        unlockAnimPlaying = true
        unlockAnimPrepared = false
        unlockPlayStartedAt = System.currentTimeMillis()
        FileLogger.log(
            "Unlock",
            "play start keyguard=${isKeyguardLocked()} children=${binding.desktopContainer.childCount}"
        )

        unlockSafetyRunnable?.let { binding.homeRoot.removeCallbacks(it) }
        val safety = Runnable {
            FileLogger.log("Unlock", "SAFETY TIMEOUT — forcing settle")
            forceUnlockSettled("timeout")
        }
        unlockSafetyRunnable = safety
        binding.homeRoot.postDelayed(safety, 1400L)

        val container = binding.desktopContainer
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i) ?: continue
            if (child === dropOverlay) continue
            child.animate().cancel()
            if (isDesktopWidget(child)) {
                child.scaleX = 0.55f
                child.scaleY = 0.55f
                child.alpha = 0f
            } else {
                child.scaleX = 1.22f
                child.scaleY = 1.22f
                child.alpha = 0f
            }
        }

        var animated = 0
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i) ?: continue
            if (child === dropOverlay) continue
            animated++
            if (isDesktopWidget(child)) {
                child.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .alpha(1f)
                    .translationZ(0f)
                    .setDuration(520)
                    .setInterpolator(OvershootInterpolator(1.35f))
                    .start()
            } else {
                // Fade-in + ball bounce (2–3 hops) like the dock, then settle at 1×
                child.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .alpha(1f)
                    .setDuration(720)
                    .setInterpolator(android.view.animation.BounceInterpolator())
                    .start()
            }
        }

        val finish = Runnable {
            val elapsed = System.currentTimeMillis() - unlockPlayStartedAt
            FileLogger.log("Unlock", "play finished elapsedMs=$elapsed animated=$animated")
            forceUnlockSettled("play-end")
        }

        if (prefs.showDock) {
            val dock = binding.bottomBar
            dock.animate().cancel()
            if (dock.visibility != View.VISIBLE) dock.visibility = View.VISIBLE
            if (dock.translationY == 0f) dock.translationY = dockUnlockStartY()
            dock.animate()
                .translationY(0f)
                .setDuration(700)
                .setInterpolator(android.view.animation.BounceInterpolator())
                .withEndAction { finish.run() }
                .start()
        } else {
            binding.homeRoot.postDelayed(finish, 780L)
        }
    }

    private fun enterOverviewMode() {
        if (isOverviewMode) return
        isOverviewMode = true
        overviewLayoutActive = true
        lastOverviewChromeAddPage = false
        binding.desktopContainer.animate().cancel()
        if (dualTransitionActive) clearDualLayers(keepIncoming = false)
        // Start from full size so the zoom-out is visible
        binding.desktopContainer.scaleX = 1f
        binding.desktopContainer.scaleY = 1f
        binding.desktopContainer.translationX = 0f
        binding.desktopContainer.translationY = 0f
        binding.desktopContainer.rotationY = 0f
        binding.desktopContainer.rotationX = 0f
        binding.desktopContainer.alpha = 1f
        // Hide dock + drawer button so desktop expands
        binding.bottomBar.visibility = View.GONE
        binding.btnOpenDrawer.visibility = View.INVISIBLE
        // Defer grid layout until overview bottomPad is applied (avoids corner drift)
        fun applyOverviewLayout() {
            if (!isOverviewMode) return
            calculateMetrics()
            renderDesktop()
            // Don't snap scale here — let the animator own scaleX/Y
        }
        binding.homeRoot.requestLayout()
        binding.desktopContainer.doOnLayout { applyOverviewLayout() }
        binding.desktopContainer.post { applyOverviewLayout() }
        binding.desktopContainer.postDelayed({ applyOverviewLayout() }, 48)
        // Soft zoom-out with a gentle decaying overshoot (settles on 0.82)
        enableDesktopHardwareLayer(true)
        binding.desktopContainer.animate()
            .scaleX(0.82f)
            .scaleY(0.82f)
            .alpha(1f)
            .translationX(0f)
            .translationY(0f)
            .rotationY(0f)
            .rotationX(0f)
            .setDuration(420)
            .setInterpolator(OvershootInterpolator(1.18f))
            .start()
        // Subtle "depth" on the root: brief scale-up of the window content so the
        // wallpaper area feels like it eases back (system wallpaper itself cannot
        // be scaled; this parallax-style cue is what most launchers approximate).
        binding.homeRoot.animate().cancel()
        binding.homeRoot.scaleX = 1f
        binding.homeRoot.scaleY = 1f
        binding.homeRoot.pivotX = binding.homeRoot.width / 2f
        binding.homeRoot.pivotY = binding.homeRoot.height / 2f
        binding.homeRoot.animate()
            .scaleX(1.03f)
            .scaleY(1.03f)
            .setDuration(420)
            .setInterpolator(DecelerateInterpolator(1.4f))
            .start()
        showOverviewChrome()
    }

    private fun exitOverviewMode() {
        dismissWidgetSheet()
        if (!isOverviewMode) return
        isOverviewMode = false
        if (currentPage >= prefs.pageCount) {
            currentPage = (prefs.pageCount - 1).coerceAtLeast(0)
        }
        if (dualTransitionActive) clearDualLayers(keepIncoming = false)
        binding.desktopContainer.setOnClickListener(null)
        fadeOutOverviewChrome {
            clearOverviewChrome()
        }
        // Keep bottomBar hidden + overview padding until zoom finishes so the page
        // frame does not suddenly shrink vertically mid-animation.
        binding.desktopContainer.animate().cancel()
        binding.desktopContainer.animate()
            .scaleX(1f)
            .scaleY(1f)
            .translationX(0f)
            .translationY(0f)
            .rotationY(0f)
            .rotationX(0f)
            .alpha(1f)
            .setDuration(320)
            .setInterpolator(DecelerateInterpolator(1.5f))
            .withEndAction {
                overviewLayoutActive = false
                binding.bottomBar.visibility = View.VISIBLE
                applyDrawerButtonVisibility()
                applyBottomBarOrder()
                resetDesktopTransforms()
                enableDesktopHardwareLayer(false)
                clearWidgetHardwareLayers()
                binding.homeRoot.requestLayout()
                binding.desktopContainer.post {
                    calculateMetrics()
                    renderDesktop()
                    renderDock()
                }
            }
            .start()
        binding.homeRoot.animate().cancel()
        binding.homeRoot.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(320)
            .setInterpolator(DecelerateInterpolator(1.5f))
            .start()
    }

    /** Drop GPU layers and reset local transforms on widget wraps after overview. */
    private fun clearWidgetHardwareLayers() {
        val container = binding.desktopContainer
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i) ?: continue
            val isWidget = child.getTag(0x70F10001) == true ||
                (child is ViewGroup && (0 until child.childCount).any {
                    child.getChildAt(it) is AppWidgetHostView
                })
            if (!isWidget) continue
            try {
                child.animate().cancel()
                child.scaleX = 1f
                child.scaleY = 1f
                child.translationX = 0f
                child.translationY = 0f
                child.translationZ = 0f
                child.alpha = 1f
                child.setLayerType(View.LAYER_TYPE_NONE, null)
                if (child is ViewGroup) {
                    for (j in 0 until child.childCount) {
                        val hv = child.getChildAt(j)
                        if (hv is AppWidgetHostView) {
                            hv.setLayerType(View.LAYER_TYPE_NONE, null)
                            hv.requestLayout()
                        }
                    }
                }
            } catch (_: Exception) {}
        }
    }

    /** Quick fade-out of overview buttons before removal. */
    private fun fadeOutOverviewChrome(onEnd: () -> Unit) {
        val views = mutableListOf<View>()
        for (i in 0 until binding.homeRoot.childCount) {
            val c = binding.homeRoot.getChildAt(i)
            if (c.tag == "overview_chrome") views.add(c)
        }
        if (views.isEmpty()) {
            onEnd()
            return
        }
        var left = views.size
        views.forEach { v ->
            v.animate().cancel()
            v.animate()
                .alpha(0f)
                .setDuration(140)
                .setInterpolator(AccelerateInterpolator())
                .withEndAction {
                    left--
                    if (left <= 0) onEnd()
                }
                .start()
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
     * Update overview chrome without destroying it (keeps house / wallpaper / settings
     * visible and stable during page swipes in edit mode).
     */
    private fun softUpdateOverviewChrome() {
        if (!isOverviewMode) return
        val isAdd = currentPage >= prefs.pageCount
        // Crossing into/out of the virtual "+" page needs full chrome rebuild
        if (isAdd != lastOverviewChromeAddPage) {
            lastOverviewChromeAddPage = isAdd
            refreshOverviewChrome()
            return
        }
        // Only swap home icon on/off for default page — never rebuild the overlay.
        val home = homeButtonView as? ImageView ?: return
        val isDefault = currentPage == prefs.defaultPage
        try {
            home.setImageResource(
                if (isDefault) R.drawable.ic_overview_home_on
                else R.drawable.ic_overview_home_off
            )
        } catch (_: Exception) {}
    }

    /**
     * Destination page: zoom-in with bounce after overview page change.
     * Origin already did a soft zoom-out during the dual swipe (no bounce there).
     */
    private fun pulseOverviewPageZoom() {
        if (!isOverviewMode) return
        val c = binding.desktopContainer
        c.animate().cancel()
        val base = 0.82f
        // Start smaller so the overshoot reads as a clear zoom-in + rubber bounce
        c.scaleX = base * 0.78f
        c.scaleY = base * 0.78f
        c.animate()
            .scaleX(base)
            .scaleY(base)
            .setDuration(380)
            .setInterpolator(OvershootInterpolator(1.45f))
            .start()
    }

    private fun enableDesktopHardwareLayer(enable: Boolean) {
        try {
            binding.desktopContainer.setLayerType(
                if (enable) View.LAYER_TYPE_HARDWARE else View.LAYER_TYPE_NONE,
                null
            )
        } catch (_: Exception) {}
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
        lastOverviewChromeAddPage = isAddPageScreen

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
                bottomMargin = (24 * density).toInt()
            }
        )

        // Fade-in chrome (home + bottom actions + plus) — quick but smooth
        fadeInOverviewChrome(overlay)
    }

    private fun fadeInOverviewChrome(overlay: ViewGroup) {
        fun fade(v: View, delay: Long) {
            v.animate().cancel()
            v.alpha = 0f
            v.animate()
                .alpha(1f)
                .setStartDelay(delay)
                .setDuration(180)
                .setInterpolator(DecelerateInterpolator(1.2f))
                .start()
        }
        // Children of the overlay (home, plus, bottomBar)
        for (i in 0 until overlay.childCount) {
            val child = overlay.getChildAt(i)
            fade(child, 160L + i * 40L)
        }
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
        dismissStackMenu()
        if (draggingApp == null) {
            // Don't clear mid-drag; cleanupDrag handles that
        }
    }

    private fun dismissStackMenu() {
        val v = stackMenuView ?: return
        stackMenuView = null
        try {
            v.animate().cancel()
            (v.parent as? ViewGroup)?.removeView(v)
        } catch (_: Exception) {}
        // Menu closed → release gesture lock unless a drag is in progress
        if (draggingApp == null && resizingWidgetId == null) {
            iconLongPressActive = false
            suppressEmptyLongPress = false
        }
    }


    /** First free top-left cell that fits a spanX × spanY widget on the current page. */
    private fun findFreeCell(spanX: Int, spanY: Int): Pair<Int, Int>? {
        val sx = spanX.coerceAtLeast(1)
        val sy = spanY.coerceAtLeast(1)
        val occupied = hashSetOf<Pair<Int, Int>>()
        for (it in homeItems.filter { it.screen == currentPage && !it.isDock }) {
            val isx = it.spanX.coerceAtLeast(1)
            val isy = it.spanY.coerceAtLeast(1)
            for (x in it.cellX until (it.cellX + isx)) {
                for (y in it.cellY until (it.cellY + isy)) {
                    occupied.add(x to y)
                }
            }
        }
        for (y in 0 until rows) {
            for (x in 0 until columns) {
                if (x + sx > columns || y + sy > rows) continue
                var free = true
                outer@ for (dx in 0 until sx) {
                    for (dy in 0 until sy) {
                        if ((x + dx to y + dy) in occupied) {
                            free = false
                            break@outer
                        }
                    }
                }
                if (free) return x to y
            }
        }
        return null
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

    private fun isTouchOnView(view: View?, rawX: Float, rawY: Float): Boolean {
        if (view == null || view.visibility != View.VISIBLE) return false
        val loc = IntArray(2)
        try { view.getLocationOnScreen(loc) } catch (_: Exception) { return false }
        val l = loc[0].toFloat()
        val top = loc[1].toFloat()
        return rawX >= l && rawX <= l + view.width && rawY >= top && rawY <= top + view.height
    }

    /** True if the touch is over a Widget Stack on the current page. */
    private fun isTouchOnWidgetStack(rawX: Float, rawY: Float): Boolean {
        val cont = binding.desktopContainer
        if (cont.width <= 0 || cont.height <= 0) return false
        val loc = IntArray(2)
        cont.getLocationOnScreen(loc)
        val lx = rawX - loc[0]
        val ly = rawY - loc[1]
        if (lx < 0 || ly < 0 || lx > cont.width || ly > cont.height) return false
        for (item in homeItems) {
            if (!item.isWidgetStack || item.isDock || item.screen != currentPage) continue
            val rect = gridRect(item.cellX, item.cellY, item.spanX, item.spanY)
            if (lx >= rect.left && lx <= rect.right && ly >= rect.top && ly <= rect.bottom) {
                return true
            }
        }
        return false
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
                            // Folders are always 1x1 — never inherit widget spans
                            dragOriginCellX = item.cellX
                            dragOriginCellY = item.cellY
                            dragOriginSpanX = 1
                            dragOriginSpanY = 1
                            dragStartRawX = event.rawX
                            dragStartRawY = event.rawY
                            dragGrabOffsetX = iconSize / 2f
                            dragGrabOffsetY = iconSize / 2f
                            view.visibility = View.INVISIBLE
                            dragShadow = ImageView(this).apply {
                                layoutParams = FrameLayout.LayoutParams(iconSize, iconSize)
                                setImageDrawable(createFolderPreviewDrawable(item, iconSize))
                            }
                            (binding.homeRoot as ViewGroup).addView(dragShadow)
                            moveDragShadow(event.rawX, event.rawY)
                            ensureDropOverlay()
                            updateDropHighlight(event.rawX, event.rawY)
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

    /** Keep last drawer icons above system gesture/nav bar so taps still work. */
    private fun applyDrawerGridInsets() {
        val grid = binding.appsGrid
        val baseBottom = (32 * resources.displayMetrics.density).toInt()
        fun apply(navBottom: Int) {
            val pad = baseBottom + navBottom.coerceAtLeast(0)
            if (grid.paddingBottom != pad) {
                grid.setPadding(grid.paddingLeft, grid.paddingTop, grid.paddingRight, pad)
            }
        }
        // Immediate estimate
        val resId = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        val navH = if (resId > 0) resources.getDimensionPixelSize(resId) else (48 * resources.displayMetrics.density).toInt()
        apply(navH)
        // Accurate insets when available
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(grid) { v, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            val gest = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemGestures())
            apply(maxOf(bars.bottom, gest.bottom))
            insets
        }
        androidx.core.view.ViewCompat.requestApplyInsets(grid)
    }

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
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
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
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
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
        val ov = folderOverlay
        if (ov == null) {
            openFolderItem = null
            folderPageIndex = 0
            folderDragInProgress = false
            folderDragCatcher = null
            return
        }
        // Already animating out
        if (ov.getTag(0x7f0A0001) == true) return
        ov.setTag(0x7f0A0001, true)

        var panel: View? = null
        if (ov is ViewGroup) {
            for (i in 0 until ov.childCount) {
                val c = ov.getChildAt(i)
                if (c.tag == "folder_panel") {
                    panel = c
                    break
                }
            }
            if (panel == null && ov.childCount > 0) panel = ov.getChildAt(0)
        }

        val remove = {
            try {
                ov.animate().cancel()
                panel?.animate()?.cancel()
                (ov.parent as? ViewGroup)?.removeView(ov)
            } catch (_: Exception) {}
            if (folderOverlay === ov) folderOverlay = null
            openFolderItem = null
            folderPageIndex = 0
            folderDragInProgress = false
            folderDragCatcher = null
        }

        // Zoom-out without bounce, fade scrim
        ov.animate().cancel()
        panel?.animate()?.cancel()
        ov.animate()
            .alpha(0f)
            .setDuration(200)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction { remove() }
            .start()
        panel?.animate()
            ?.scaleX(0.82f)
            ?.scaleY(0.82f)
            ?.alpha(0f)
            ?.setDuration(200)
            ?.setInterpolator(AccelerateInterpolator())
            ?.start()
            ?: run {
                // No panel — remove after fade
            }
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
        // Zoom-in from folder with soft overshoot bounce
        panel.tag = "folder_panel"
        root.alpha = 0f
        panel.scaleX = 0.72f
        panel.scaleY = 0.72f
        panel.alpha = 0f
        root.animate()
            .alpha(1f)
            .setDuration(180)
            .setInterpolator(DecelerateInterpolator())
            .start()
        panel.animate()
            .scaleX(1f)
            .scaleY(1f)
            .alpha(1f)
            .setDuration(320)
            .setInterpolator(OvershootInterpolator(1.35f))
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

        val isDrawerGroup = folder.id.startsWith("drawer_group_")

        // Desktop folders: taking an app out removes it from the folder.
        // Drawer groups are organizational only — keep membership; placing on
        // desktop/dock is a shortcut, not a remove-from-group.
        if (!isDrawerGroup) {
            try {
                folder.folderMembers.removeAll {
                    it.packageName == app.packageName && it.activityName == app.activityName
                }
                pruneFolder(folder)
                saveAllItems()
            } catch (_: Exception) {}
        }

        // Close folder overlay (and drawer if this is a group) so the desktop is free
        closeFolderOverlay()
        if (isDrawerGroup || isDrawerOpen) {
            // Instant close so drag continues smoothly on the home surface
            isDrawerOpen = false
            drawerGestureActive = false
            binding.drawerPanel.animate().cancel()
            binding.drawerPanel.visibility = View.GONE
            binding.drawerPanel.translationY = drawerHeight()
            binding.drawerPanel.alpha = 0f
            applyDrawerButtonVisibility()
        }

        calculateMetrics()

        draggingApp = app
        draggingHomeItem = null
        suppressEmptyLongPress = true
        folderDragInProgress = true
        iconLongPressActive = true

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
            startActivity(Intent(this, WallpaperPickerActivity::class.java))
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

    override fun onStart() {
        super.onStart()
        try { appWidgetHost.startListening() } catch (_: Exception) {}
        val kg = isKeyguardLocked()
        FileLogger.log("Unlock", "onStart keyguard=$kg prepared=$unlockAnimPrepared playing=$unlockAnimPlaying screenWasOff=$screenWasOff")
        if (prefs.unlockAnimationsEnabled && !unlockAnimPlaying && (kg || unlockAnimPrepared || screenWasOff)) {
            prepareUnlockAnimationState()
        }
    }

    override fun onStop() {
        // Keep host listening (Lawnchair: Calendar blanks if host stops in background)
        // try { appWidgetHost.stopListening() } catch (_: Exception) {}
        super.onStop()
    }

    /**
     * Custom host → our HostView that never crashes the launcher on bad RemoteViews
     * (calendar / list widgets sometimes push null or broken updates).
     */
    /**
     * Framework-only context for widget inflation.
     * AppCompatActivity's LayoutInflater replaces <ImageView> with AppCompatImageView,
     * which rejects RemoteViews setImageURI → Spotify/others fall to getErrorView.
     * Application context keeps plain ImageView (Launcher3 / system launcher behavior).
     */
    private fun widgetInflateContext(): android.content.Context {
        val app = applicationContext
        return object : android.content.ContextWrapper(app) {
            override fun getSystemService(name: String): Any? {
                if (LAYOUT_INFLATER_SERVICE == name) {
                    return android.view.LayoutInflater.from(app)
                }
                return super.getSystemService(name)
            }
        }
    }

    private class RomasterAppWidgetHost(
        private val inflateContext: android.content.Context,
        hostId: Int
    ) : AppWidgetHost(inflateContext, hostId) {
        override fun onCreateView(
            context: android.content.Context,
            appWidgetId: Int,
            appWidget: AppWidgetProviderInfo?
        ): AppWidgetHostView {
            // Always inflate with non-AppCompat context (see widgetInflateContext).
            return RomasterAppWidgetHostView(inflateContext)
        }
    }


    /**
     * Inject system dynamic color mapping so Material You widgets (Calendar, Clock, etc.)
     * can resolve @android:color/system_* references during RemoteViews inflation.
     * Official API 31+ signature: setColorResources(SparseIntArray).
     */
    private fun applyDefaultWidgetColorResources(hostView: AppWidgetHostView) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) return
        try {
            val colorsInt = android.util.SparseIntArray()
            fun add(id: Int) {
                try {
                    val c = getColor(id)
                    colorsInt.put(id, c)
                } catch (_: Exception) {}
            }
            // Core Material You system colors used by Google widgets
            val ids = intArrayOf(
                android.R.color.system_accent1_0, android.R.color.system_accent1_10,
                android.R.color.system_accent1_50, android.R.color.system_accent1_100,
                android.R.color.system_accent1_200, android.R.color.system_accent1_300,
                android.R.color.system_accent1_400, android.R.color.system_accent1_500,
                android.R.color.system_accent1_600, android.R.color.system_accent1_700,
                android.R.color.system_accent1_800, android.R.color.system_accent1_900,
                android.R.color.system_accent1_1000,
                android.R.color.system_accent2_0, android.R.color.system_accent2_10,
                android.R.color.system_accent2_50, android.R.color.system_accent2_100,
                android.R.color.system_accent2_200, android.R.color.system_accent2_300,
                android.R.color.system_accent2_400, android.R.color.system_accent2_500,
                android.R.color.system_accent2_600, android.R.color.system_accent2_700,
                android.R.color.system_accent2_800, android.R.color.system_accent2_900,
                android.R.color.system_accent2_1000,
                android.R.color.system_accent3_0, android.R.color.system_accent3_10,
                android.R.color.system_accent3_50, android.R.color.system_accent3_100,
                android.R.color.system_accent3_200, android.R.color.system_accent3_300,
                android.R.color.system_accent3_400, android.R.color.system_accent3_500,
                android.R.color.system_accent3_600, android.R.color.system_accent3_700,
                android.R.color.system_accent3_800, android.R.color.system_accent3_900,
                android.R.color.system_accent3_1000,
                android.R.color.system_neutral1_0, android.R.color.system_neutral1_10,
                android.R.color.system_neutral1_50, android.R.color.system_neutral1_100,
                android.R.color.system_neutral1_200, android.R.color.system_neutral1_300,
                android.R.color.system_neutral1_400, android.R.color.system_neutral1_500,
                android.R.color.system_neutral1_600, android.R.color.system_neutral1_700,
                android.R.color.system_neutral1_800, android.R.color.system_neutral1_900,
                android.R.color.system_neutral1_1000,
                android.R.color.system_neutral2_0, android.R.color.system_neutral2_10,
                android.R.color.system_neutral2_50, android.R.color.system_neutral2_100,
                android.R.color.system_neutral2_200, android.R.color.system_neutral2_300,
                android.R.color.system_neutral2_400, android.R.color.system_neutral2_500,
                android.R.color.system_neutral2_600, android.R.color.system_neutral2_700,
                android.R.color.system_neutral2_800, android.R.color.system_neutral2_900,
                android.R.color.system_neutral2_1000
            )
            for (id in ids) add(id)

            // Direct call — compileSdk 35 exposes setColorResources(SparseIntArray)
            hostView.setColorResources(colorsInt)
            FileLogger.log("WidgetBind", "setColorResources(SparseIntArray) entries=${colorsInt.size()}")
        } catch (e: Exception) {
            FileLogger.e("WidgetBind", "applyDefaultWidgetColorResources", e)
        }
    }


    /**
     * Launcher3-style host view:
     * - Inflated with non-AppCompat context (plain ImageView for setImageURI)
     * - Apply RemoteViews on the main thread (async executor broke some Glance providers
     *   that stay forever on "Cargando…" after the first shell RemoteViews)
     * - Pure super.updateAppWidget; on error keep previous View hierarchy
     */
    private class RomasterAppWidgetHostView(context: android.content.Context) :
        AppWidgetHostView(context) {

        private var lastGoodView: android.view.View? = null

        private fun childName(): String =
            if (childCount > 0) getChildAt(0)?.javaClass?.simpleName ?: "none" else "none"

        private fun isErrorChild(): Boolean =
            childCount > 0 && getChildAt(0) is android.widget.TextView

        private fun isGoodChild(): Boolean {
            if (childCount <= 0) return false
            val c = getChildAt(0)
            return c is android.view.ViewGroup && c !is android.widget.TextView
        }

        override fun updateAppWidget(remoteViews: android.widget.RemoteViews?) {
            val previousGood = if (isGoodChild()) getChildAt(0) else lastGoodView
            try {
                FileLogger.log(
                    "WidgetBind",
                    "hostUpdate id=$appWidgetId rv=${remoteViews != null} " +
                        "size=${width}x${height} childBefore=$childCount/${childName()} " +
                        "pkg=${appWidgetInfo?.provider}"
                )
                super.updateAppWidget(remoteViews)

                if (isGoodChild()) {
                    lastGoodView = getChildAt(0)
                    FileLogger.log(
                        "WidgetBind",
                        "hostUpdate done id=$appWidgetId childAfter=$childCount/${childName()} good=true"
                    )
                    return
                }

                // If framework showed error TextView, put back the last good VIEW (not RemoteViews).
                // Re-applying RemoteViews can fail again (Spotify setImageURI); the View is already built.
                if (isErrorChild() && previousGood != null && previousGood !is android.widget.TextView) {
                    FileLogger.w(
                        "WidgetBind",
                        "restore last good VIEW after error id=$appWidgetId was=${childName()}"
                    )
                    try {
                        removeAllViews()
                        (previousGood.parent as? android.view.ViewGroup)?.removeView(previousGood)
                        addView(
                            previousGood,
                            android.widget.FrameLayout.LayoutParams(
                                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
                            )
                        )
                        lastGoodView = previousGood
                    } catch (e: Exception) {
                        FileLogger.e("WidgetBind", "view restore failed id=$appWidgetId", e)
                    }
                    FileLogger.w(
                        "WidgetBind",
                        "restored VIEW id=$appWidgetId now=${childName()} good=${isGoodChild()}"
                    )
                    return
                }

                FileLogger.log(
                    "WidgetBind",
                    "hostUpdate done id=$appWidgetId childAfter=$childCount/${childName()} good=false"
                )
            } catch (e: Exception) {
                FileLogger.e("WidgetBind", "updateAppWidget exception id=$appWidgetId", e)
            }
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            try {
                super.onLayout(changed, left, top, right, bottom)
            } catch (e: RuntimeException) {
                FileLogger.e("WidgetBind", "onLayout exception id=$appWidgetId", e)
            }
        }

        override fun getErrorView(): android.view.View {
            FileLogger.w(
                "WidgetBind",
                "getErrorView id=$appWidgetId pkg=${appWidgetInfo?.provider} size=${width}x${height}"
            )
            // Mark so health-check can find this host even if text is OEM-specific
            setTag(0x70F10D20, "error_shell")
            return super.getErrorView()
        }

        fun wasErrorShell(): Boolean = getTag(0x70F10D20) == "error_shell"
    }



    /**
     * Reliable desktop + dock paint. Avoids the race where doOnLayout never fires
     * (container already laid out with 0 metrics) leaving a blank home until the
     * user opens the drawer and forces a layout pass.
     */
    private fun paintDesktopNow() {
        applyDrawerButtonVisibility()
        applyBottomBarOrder()
        binding.bottomBar.requestLayout()
        val container = binding.desktopContainer
        fun doPaint() {
            calculateMetrics()
            if (cellWidth <= 1f || cellHeight <= 1f) {
                container.post {
                    calculateMetrics()
                    if (cellWidth > 1f && cellHeight > 1f) {
                        renderDesktop()
                        renderDock()
                        applyWallpaperOffset()
                        desktopNeedsFullRender = false
                        // AFTER host views exist
                        scheduleRecoverAllBrokenWidgets()
                    }
                }
                return
            }
            renderDesktop()
            renderDock()
            applyWallpaperOffset()
            desktopNeedsFullRender = false
            scheduleRecoverAllBrokenWidgets()
        }
        if (container.width > 0 && container.height > 0) {
            doPaint()
        } else {
            container.requestLayout()
            container.doOnLayout { doPaint() }
            // Safety net if doOnLayout is skipped
            container.postDelayed({
                if (desktopNeedsFullRender || container.childCount == 0) doPaint()
            }, 48L)
        }
    }

    override fun onDestroy() {
        unregisterUnlockReceiver()
        super.onDestroy()
    }

    override fun onPause() {
        try { ReactionGlobalsStore.removeListener(globalBoolListener) } catch (_: Exception) {}

        super.onPause()
        // Keep unlockReceiver registered so we still get SCREEN_OFF while stopped.
    }

    override fun onResume() {
        try {
            // Reload persisted globals (no notifications) so unlock never re-fires triggers
            ReactionGlobalsStore.reloadFromDisk(this)
            // Grace window: ignore global-bool fires right after unlock/resume
            globalTriggerGraceUntil = android.os.SystemClock.elapsedRealtime() + 1200L
            ReactionGlobalsStore.addListener(globalBoolListener)
            // Clear any stuck per-view locks from a killed animation mid-flight
            reactionEngine.clearAllRunning()
        } catch (_: Exception) {}

        super.onResume()
        try { appWidgetHost.startListening() } catch (_: Exception) {}
        registerUnlockReceiver()

        val kgR = isKeyguardLocked()
        FileLogger.log(
            "Unlock",
            "onResume keyguard=$kgR prepared=$unlockAnimPrepared playing=$unlockAnimPlaying screenWasOff=$screenWasOff"
        )
        if (prefs.unlockAnimationsEnabled) {
            if (kgR) {
                prepareUnlockAnimationState()
            } else if ((unlockAnimPrepared || screenWasOff) && !unlockAnimPlaying) {
                binding.homeRoot.post { playUnlockAnimations() }
            } else if (!unlockAnimPlaying && !unlockAnimPrepared) {
                forceUnlockSettled("resume-clean")
            }
        } else {
            forceUnlockSettled("resume-disabled")
        }
        if (dualTransitionActive) clearDualLayers(keepIncoming = false)
        if (!unlockAnimPlaying) {
            resetDesktopTransforms()
        }
        wallpaperOffsetAnimator?.cancel()
        wallpaperOffsetAnimator = null

        // Settings flags (icon pack / grid / backup) — only then rebuild
        val layoutReload = prefs.pendingLayoutReload
        val drawerReload = prefs.pendingDrawerReload
        if (layoutReload) prefs.pendingLayoutReload = false
        if (drawerReload) prefs.pendingDrawerReload = false

        // ── Soft resume (normal return from WhatsApp/Chrome/etc.) ───────────
        // Process still warm + desktop already painted + no settings change:
        // do NOT reload apps, do NOT paintDesktopNow, do NOT touch the dock.
        val warmUi =
            binding.desktopContainer.childCount > 0 &&
                allApps.isNotEmpty() &&
                homeItems.isNotEmpty()
        if (warmUi && !layoutReload && !drawerReload) {
            FileLogger.log("Lifecycle", "onResume soft — keep live UI (no reload)")
            return
        }

        // ── Warm process but activity recreated (views gone) ───────────────
        // Apps may still be in Application cache — paint immediately, no PM scan.
        if (!layoutReload && !drawerReload && allApps.isEmpty()) {
            val cached = try {
                (application as? RomasterApp)?.appRepository?.getCachedApps().orEmpty()
            } catch (_: Exception) {
                emptyList()
            }
            if (cached.isNotEmpty()) {
                allApps = cached

                rebuildAppIndex()
            }
        }

        lifecycleScope.launch {
            // Only hit PackageManager when list is still empty after cache check
            if (allApps.isEmpty() || layoutReload) {
                try {
                    appRepo.loadApps(force = layoutReload) { partial ->
                        if (partial.isNotEmpty()) {
                            allApps = partial

                            rebuildAppIndex()
                            if (::drawerAdapter.isInitialized) {
                                try { submitDrawerApps(partial) } catch (_: Exception) {}
                            }
                        }
                    }
                } catch (_: Exception) {}
            }

            if (layoutReload || (homeItems.isEmpty() && dockItems.isEmpty())) {
                val allItems = try {
                    withContext(Dispatchers.IO) { homeRepo.loadItems() }
                } catch (_: Exception) {
                    emptyList()
                }
                homeItems = allItems.filter { !it.isDock }.toMutableList()
                dockItems = allItems.filter { it.isDock }.toMutableList()
                dockPageIndex = 0
                columns = prefs.desktopColumns.coerceAtLeast(1)
                rows = prefs.desktopRows.coerceAtLeast(1)
                currentPage = prefs.defaultPage.coerceIn(0, (prefs.pageCount - 1).coerceAtLeast(0))
            }

            currentPage = currentPage.coerceIn(0, (prefs.pageCount - 1).coerceAtLeast(0))

            if (drawerReload || layoutReload) {
                val density = resources.displayMetrics.density
                val size = if (prefs.drawerIconSizePercent > 0) {
                    ((52 * density) * (prefs.drawerIconSizePercent / 100f)).toInt().coerceAtLeast(1)
                } else drawerIconPx
                if (size > 0) {
                    drawerIconPx = size
                    try { drawerAdapter.updateIconSize(size) } catch (_: Exception) {}
                }
                if (allApps.isNotEmpty()) submitDrawerApps(allApps)
            }

            val needFullPaint =
                layoutReload ||
                    binding.desktopContainer.childCount == 0 ||
                    (desktopNeedsFullRender && !warmUi)

            if (needFullPaint) {
                lastShowDock = null
                lastShowDrawerBtn = null
                columns = prefs.desktopColumns.coerceAtLeast(1)
                rows = prefs.desktopRows.coerceAtLeast(1)
                lastDesktopIconPct = prefs.desktopIconSizePercent
                lastDockIconPct = prefs.dockIconSizePercent
                lastShowDock = prefs.showDock
                lastShowDrawerBtn = prefs.showDrawerButton
                dockPageIndex = 0
                paintDesktopNow()
            } else if (drawerReload) {
                // Drawer list only — desktop stays as-is
            } else {
                applySettingsFromPrefs(forceRender = false)
            }
        }
    }

}
