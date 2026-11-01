package com.romaster.launcher.ui

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Container that hosts multiple AppWidgetHostViews as pages of a stack.
 * Swipe (vertical by default, or horizontal) cycles pages with infinite loop.
 * Empty state shows a large + button.
 *
 * Touch policy:
 * - When the user starts a swipe in the stack direction, we requestDisallowIntercept
 *   so the desktop page swipe / drawer does not steal the gesture.
 * - Short taps are forwarded to the current page's host view.
 * - Long-press is left to the outer wrap in HomeActivity (same as normal widgets).
 */
class RomasterWidgetStackView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    /** Pages currently attached (host views or placeholders). Index = page order. */
    private val pages = mutableListOf<View>()

    var currentPage: Int = 0
        private set

    /** true = vertical swipe (Action Launcher style); false = horizontal */
    var swipeVertical: Boolean = true

    /** When true (long-press menu / drag), refuse to start page swipes. */
    var pagingLocked: Boolean = false

    /** Called when the user taps the empty-state + button */
    var onAddClick: (() -> Unit)? = null

    /** Called after a successful page change (for persisting stackCurrentPage) */
    var onPageChanged: ((Int) -> Unit)? = null

    /** Called as soon as a page-swipe is recognized (cancel long-press menus). */
    var onPagingStarted: (() -> Unit)? = null

    /** Called when the paging gesture ends (UP/CANCEL or settle). */
    var onPagingEnded: (() -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val pageIndicatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var tracking = false
    private var settled = true
    private var dragOffset = 0f
    private var pageAnimator: ValueAnimator? = null

    private var emptyView: View? = null

    init {
        clipChildren = true
        clipToPadding = true
        setWillNotDraw(false)
    }

    // ── Public API ──────────────────────────────────────────────────────────

    fun setPages(newPages: List<View>, startPage: Int = 0) {
        // Detach old
        for (p in pages) {
            (p.parent as? ViewGroup)?.removeView(p)
        }
        pages.clear()
        removeAllViews()
        emptyView = null

        if (newPages.isEmpty()) {
            showEmptyState()
            currentPage = 0
            return
        }

        for (v in newPages) {
            (v.parent as? ViewGroup)?.removeView(v)
            pages.add(v)
            addView(
                v,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            )
            v.visibility = View.INVISIBLE
        }
        currentPage = startPage.coerceIn(0, pages.lastIndex)
        settleImmediate()
        invalidate()
    }

    fun pageCount(): Int = pages.size

    fun isEmpty(): Boolean = pages.isEmpty()

    fun setSwipeOrientation(vertical: Boolean) {
        if (swipeVertical == vertical) return
        swipeVertical = vertical
        settleImmediate()
        invalidate()
    }

    // ── Empty state ─────────────────────────────────────────────────────────

    private fun showEmptyState() {
        val d = density
        val container = FrameLayout(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            background = GradientDrawable().apply {
                cornerRadius = 16f * d
                setColor(0x22FFFFFF)
                setStroke((1.5f * d).toInt(), 0x44FFFFFF)
            }
        }
        val plus = TextView(context).apply {
            text = "+"
            textSize = 42f
            setTextColor(0xCCFFFFFF.toInt())
            gravity = Gravity.CENTER
            layoutParams = LayoutParams(
                (72 * d).toInt(),
                (72 * d).toInt(),
                Gravity.CENTER
            )
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x33FFFFFF)
            }
            setOnClickListener { onAddClick?.invoke() }
        }
        val label = TextView(context).apply {
            text = "Administrar Widgets"
            textSize = 13f
            setTextColor(0xAAFFFFFF.toInt())
            gravity = Gravity.CENTER
            layoutParams = LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            ).apply {
                topMargin = (48 * d).toInt()
            }
        }
        container.addView(plus)
        container.addView(label)
        // Make whole area clickable too
        container.setOnClickListener { onAddClick?.invoke() }
        emptyView = container
        addView(container)
    }

    // ── Layout / draw ───────────────────────────────────────────────────────

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (!settled) applyDragOffset(dragOffset)
        else settleImmediate()
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        drawPageIndicators(canvas)
    }

    private fun drawPageIndicators(canvas: Canvas) {
        val n = pages.size
        if (n <= 1) return
        val d = density
        val r = 3.2f * d
        val gap = 8f * d
        val total = n * (r * 2) + (n - 1) * gap
        if (swipeVertical) {
            // dots on the right edge, centered vertically
            val cx = width - 10f * d
            var cy = (height - total) / 2f + r
            for (i in 0 until n) {
                pageIndicatorPaint.color = if (i == currentPage) 0xEEFFFFFF.toInt() else 0x55FFFFFF
                canvas.drawCircle(cx, cy, r, pageIndicatorPaint)
                cy += r * 2 + gap
            }
        } else {
            // dots at the bottom, centered horizontally
            val cy = height - 10f * d
            var cx = (width - total) / 2f + r
            for (i in 0 until n) {
                pageIndicatorPaint.color = if (i == currentPage) 0xEEFFFFFF.toInt() else 0x55FFFFFF
                canvas.drawCircle(cx, cy, r, pageIndicatorPaint)
                cx += r * 2 + gap
            }
        }
    }

    // ── Paging math ─────────────────────────────────────────────────────────

    private fun settleImmediate() {
        settled = true
        dragOffset = 0f
        val w = width
        val h = height
        for (i in pages.indices) {
            val v = pages[i]
            v.visibility = View.VISIBLE
            if (swipeVertical) {
                v.translationX = 0f
                v.translationY = ((i - currentPage) * h).toFloat()
            } else {
                v.translationY = 0f
                v.translationX = ((i - currentPage) * w).toFloat()
            }
            v.alpha = if (i == currentPage) 1f else 0.35f
        }
        invalidate()
    }

    private fun applyDragOffset(offset: Float) {
        val w = width.coerceAtLeast(1)
        val h = height.coerceAtLeast(1)
        val size = if (swipeVertical) h else w
        for (i in pages.indices) {
            val v = pages[i]
            v.visibility = View.VISIBLE
            val base = (i - currentPage) * size
            if (swipeVertical) {
                v.translationX = 0f
                v.translationY = base + offset
            } else {
                v.translationY = 0f
                v.translationX = base + offset
            }
            // Fade non-visible pages a bit
            val dist = abs((base + offset) / size.toFloat())
            v.alpha = (1f - dist * 0.55f).coerceIn(0.25f, 1f)
        }
        invalidate()
    }

    private fun animateToPage(target: Int) {
        if (pages.isEmpty()) return
        val n = pages.size
        val dest = ((target % n) + n) % n
        if (dest == currentPage && settled) return

        pageAnimator?.cancel()
        settled = false
        val size = if (swipeVertical) height.coerceAtLeast(1) else width.coerceAtLeast(1)
        // Compute how far we need to travel from current dragOffset
        // We treat dest as relative step from currentPage
        var delta = dest - currentPage
        if (delta > n / 2) delta -= n
        if (delta < -n / 2) delta += n
        val targetOffset = -delta * size.toFloat()
        val startOffset = dragOffset

        pageAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220L
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                val t = anim.animatedValue as Float
                dragOffset = startOffset + (targetOffset - startOffset) * t
                applyDragOffset(dragOffset)
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    currentPage = dest
                    dragOffset = 0f
                    settleImmediate()
                    onPageChanged?.invoke(currentPage)
                }
            })
            start()
        }
    }

    // ── Touch ───────────────────────────────────────────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (pagingLocked) return false
        if (pages.size <= 1) return false // let empty / single page pass through to host / +
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                lastX = ev.x
                lastY = ev.y
                tracking = false
                pageAnimator?.cancel()
                // Do NOT requestDisallow on DOWN — parent wrap must still see MOVE
                // to cancel its long-press arm when the user is just swiping pages.
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - downX
                val dy = ev.y - downY
                val adx = abs(dx)
                val ady = abs(dy)
                if (!tracking) {
                    // Any clear movement cancels parent long-press via onPagingStarted
                    if (adx > touchSlop * 0.55f || ady > touchSlop * 0.55f) {
                        onPagingStarted?.invoke()
                    }
                    if (swipeVertical) {
                        if (ady > touchSlop * 0.7f && ady >= adx) {
                            tracking = true
                            parent?.requestDisallowInterceptTouchEvent(true)
                            return true
                        }
                    } else {
                        if (adx > touchSlop * 0.7f && adx >= ady) {
                            tracking = true
                            parent?.requestDisallowInterceptTouchEvent(true)
                            return true
                        }
                    }
                }
                return tracking
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                tracking = false
                parent?.requestDisallowInterceptTouchEvent(false)
                onPagingEnded?.invoke()
            }
        }
        return false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (pagingLocked) return false
        if (pages.size <= 1) return super.onTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                lastX = ev.x
                lastY = ev.y
                tracking = false
                settled = false
                pageAnimator?.cancel()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx0 = ev.x - downX
                val dy0 = ev.y - downY
                if (!tracking) {
                    val adx = abs(dx0)
                    val ady = abs(dy0)
                    if (adx > touchSlop * 0.55f || ady > touchSlop * 0.55f) {
                        onPagingStarted?.invoke()
                    }
                    val start = if (swipeVertical) {
                        ady > touchSlop * 0.7f && ady >= adx
                    } else {
                        adx > touchSlop * 0.7f && adx >= ady
                    }
                    if (!start) return true
                    tracking = true
                    settled = false
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                val dx = ev.x - lastX
                val dy = ev.y - lastY
                lastX = ev.x
                lastY = ev.y
                if (swipeVertical) {
                    dragOffset += dy
                } else {
                    dragOffset += dx
                }
                // Rubber-band slightly at ends (we still allow infinite loop, so mild)
                applyDragOffset(dragOffset)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!tracking) return false
                tracking = false
                parent?.requestDisallowInterceptTouchEvent(false)
                onPagingEnded?.invoke()
                val size = if (swipeVertical) height.coerceAtLeast(1) else width.coerceAtLeast(1)
                val threshold = size * 0.22f
                val velocityHint = dragOffset // simple: use displacement as proxy
                val step = when {
                    velocityHint < -threshold -> 1
                    velocityHint > threshold -> -1
                    else -> 0
                }
                if (step != 0) {
                    animateToPage(currentPage + step)
                } else {
                    // snap back
                    pageAnimator?.cancel()
                    pageAnimator = ValueAnimator.ofFloat(dragOffset, 0f).apply {
                        duration = 180L
                        interpolator = DecelerateInterpolator()
                        addUpdateListener { anim ->
                            dragOffset = anim.animatedValue as Float
                            applyDragOffset(dragOffset)
                        }
                        addListener(object : android.animation.AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: android.animation.Animator) {
                                settleImmediate()
                            }
                        })
                        start()
                    }
                }
                return true
            }
        }
        return super.onTouchEvent(ev)
    }
}
