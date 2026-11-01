package com.romaster.launcher.ui

import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.romaster.launcher.R
import com.romaster.launcher.data.AppInfo
import kotlin.math.hypot

class AppIconAdapter(
    private val onClick: (AppInfo) -> Unit,
    private val onLongPressArmed: ((AppInfo, View) -> Unit)? = null,
    private val onStartDrag: ((AppInfo, View, MotionEvent) -> Unit)? = null,
    private val onCancelElevate: ((View) -> Unit)? = null,
    private val onHideApp: ((AppInfo) -> Unit)? = null,
    private val onGroupClick: ((String) -> Unit)? = null,
    iconSizePx: Int = 0,
    private val columns: Int = 4
) : ListAdapter<AppIconAdapter.Row, RecyclerView.ViewHolder>(DiffCallback()) {

    /** Mutable so host can refresh size after metrics change */
    var iconSizePx: Int = iconSizePx
        private set

    fun updateIconSize(px: Int) {
        if (px <= 0 || px == iconSizePx) return
        iconSizePx = px
        notifyDataSetChanged()
    }

    sealed class Row {
        data class App(val app: AppInfo) : Row()
        data class Header(val letter: String) : Row()
        data class Group(val id: String, val name: String, val icon: android.graphics.drawable.Drawable?) : Row()
    }

    private val handler = Handler(Looper.getMainLooper())

    fun submitAppsWithGroups(
        groups: List<Row.Group>,
        apps: List<AppInfo>,
        alphabetical: Boolean = false
    ) {
        val rows = mutableListOf<Row>()
        if (groups.isNotEmpty()) {
            rows.addAll(groups)
        }
        if (!alphabetical) {
            rows.addAll(apps.map { Row.App(it) })
            submitList(rows)
            return
        }
        var last = ""
        for (app in apps) {
            val letter = app.label.firstOrNull()?.uppercaseChar()?.toString() ?: "#"
            val key = if (letter[0].isLetter()) letter else "#"
            if (key != last) {
                rows.add(Row.Header(key))
                last = key
            }
            rows.add(Row.App(app))
        }
        submitList(rows)
    }

    fun submitApps(apps: List<AppInfo>, alphabetical: Boolean = false, committed: (() -> Unit)? = null) {
        val rows: List<Row> = if (!alphabetical) {
            apps.map { Row.App(it) }
        } else {
            val out = mutableListOf<Row>()
            var last = ""
            for (app in apps) {
                val letter = app.label.firstOrNull()?.uppercaseChar()?.toString() ?: "#"
                val key = if (letter[0].isLetter()) letter else "#"
                if (key != last) {
                    out.add(Row.Header(key))
                    last = key
                }
                out.add(Row.App(app))
            }
            out
        }
        if (committed != null) submitList(rows, committed) else submitList(rows)
    }

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is Row.Header -> 1
        else -> 0
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == 1) {
            val tv = TextView(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                setPadding(24, 28, 16, 8)
                textSize = 18f
                setTextColor(0xFFFFFFFF.toInt())
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
            // Full-span header via GridLayoutManager.SpanSizeLookup is set by host
            object : RecyclerView.ViewHolder(tv) {}
        } else {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_app_icon, parent, false)
            AppVH(view)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is Row.Header -> (holder.itemView as TextView).text = row.letter
            is Row.App -> (holder as AppVH).bind(row.app)
            is Row.Group -> (holder as AppVH).bindGroup(row)
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        super.onViewRecycled(holder)
        if (holder is AppVH) {
            holder.resetVisual()
            holder.cancelPending()
        }
    }

    override fun onViewAttachedToWindow(holder: RecyclerView.ViewHolder) {
        super.onViewAttachedToWindow(holder)
        if (holder is AppVH) {
            holder.applyIconSize()
            holder.resetVisual()
        }
    }

    inner class AppVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val iconImage: ImageView = itemView.findViewById(R.id.iconImage)
        private val iconLabel: TextView = itemView.findViewById(R.id.iconLabel)
        private val touchSlop = ViewConfiguration.get(itemView.context).scaledTouchSlop
        private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
        private var downX = 0f
        private var downY = 0f
        private var longPressArmed = false
        private var dragStarted = false
        private var canceledByScroll = false
        private var appRef: AppInfo? = null
        private val longPressRunnable = Runnable {
            val app = try {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) (getItem(pos) as? Row.App)?.app else null
            } catch (_: Exception) { null } ?: appRef ?: return@Runnable
            appRef = app
            longPressArmed = true
            itemView.animate().scaleX(1.14f).scaleY(1.14f).translationZ(20f)
                .setDuration(140).setInterpolator(OvershootInterpolator(1.6f)).start()
            itemView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            itemView.parent?.requestDisallowInterceptTouchEvent(true)
            onLongPressArmed?.invoke(app, itemView)
        }

        fun cancelPending() {
            handler.removeCallbacks(longPressRunnable)
            longPressArmed = false
            dragStarted = false
            canceledByScroll = false
        }

        fun resetVisual() {
            itemView.animate().cancel()
            itemView.scaleX = 1f
            itemView.scaleY = 1f
            itemView.translationZ = 0f
            iconImage.scaleX = 1f
            iconImage.scaleY = 1f
        }

        fun applyIconSize() {
            val size = iconSizePx
            if (size <= 0) return
            val lp = iconImage.layoutParams
            if (lp.width != size || lp.height != size) {
                lp.width = size
                lp.height = size
                iconImage.layoutParams = lp
            }
            iconImage.minimumWidth = size
            iconImage.minimumHeight = size
            iconImage.scaleType = ImageView.ScaleType.FIT_CENTER
            iconImage.requestLayout()
        }

        fun bindGroup(group: Row.Group) {
            appRef = null
            cancelPending()
            resetVisual()
            iconImage.setImageDrawable(group.icon)
            iconLabel.text = group.name
            applyIconSize()
            itemView.setOnClickListener { onGroupClick?.invoke(group.id) }
            itemView.setOnTouchListener(null)
            itemView.isClickable = true
        }

        fun bind(app: AppInfo) {
            appRef = app
            cancelPending()
            resetVisual()
            iconImage.setImageDrawable(
                try {
                    (app.icon?.constantState?.newDrawable() ?: app.icon)?.mutate()
                } catch (_: Exception) { app.icon }
            )
            iconLabel.text = app.label
            applyIconSize()

            itemView.setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.x
                        downY = event.y
                        longPressArmed = false
                        dragStarted = false
                        canceledByScroll = false
                        // Let RecyclerView handle nested scroll; claim only after long-press
                        v.parent?.requestDisallowInterceptTouchEvent(false)
                        handler.postDelayed(longPressRunnable, longPressTimeout)
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dist = hypot((event.x - downX).toDouble(), (event.y - downY).toDouble()).toFloat()
                        // Slightly higher threshold so micro-jitter at list bottom doesn't kill taps
                        val scrollSlop = touchSlop * 1.35f
                        if (!longPressArmed && dist > scrollSlop) {
                            canceledByScroll = true
                            handler.removeCallbacks(longPressRunnable)
                            v.parent?.requestDisallowInterceptTouchEvent(false)
                            return@setOnTouchListener false
                        }
                        if (longPressArmed && !dragStarted && dist > touchSlop) {
                            dragStarted = true
                            v.parent?.requestDisallowInterceptTouchEvent(true)
                            val dragApp = try {
                                val pos = bindingAdapterPosition
                                if (pos != RecyclerView.NO_POSITION) (getItem(pos) as? Row.App)?.app else null
                            } catch (_: Exception) { null } ?: app
                            onStartDrag?.invoke(dragApp, v, event)
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        handler.removeCallbacks(longPressRunnable)
                        v.parent?.requestDisallowInterceptTouchEvent(false)
                        val dist = hypot((event.x - downX).toDouble(), (event.y - downY).toDouble()).toFloat()
                        // Prefer live adapter item so filtered/recycled rows stay correct
                        val liveApp = try {
                            val pos = bindingAdapterPosition
                            if (pos != RecyclerView.NO_POSITION) {
                                (getItem(pos) as? Row.App)?.app
                            } else null
                        } catch (_: Exception) { null } ?: app
                        when {
                            dragStarted -> {}
                            longPressArmed -> {
                                // keep elevated for menu
                            }
                            !canceledByScroll && dist <= touchSlop -> onClick(liveApp)
                            else -> {
                                resetVisual()
                                onCancelElevate?.invoke(v)
                            }
                        }
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        cancelPending()
                        resetVisual()
                        onCancelElevate?.invoke(v)
                        true
                    }
                    else -> false
                }
            }
        }
    }

    private class DiffCallback : DiffUtil.ItemCallback<Row>() {
        override fun areItemsTheSame(old: Row, new: Row): Boolean = when {
            old is Row.Header && new is Row.Header -> old.letter == new.letter
            old is Row.App && new is Row.App -> old.app.uniqueId == new.app.uniqueId
            old is Row.Group && new is Row.Group -> old.id == new.id
            else -> false
        }
        override fun areContentsTheSame(old: Row, new: Row): Boolean = old == new
    }
}
