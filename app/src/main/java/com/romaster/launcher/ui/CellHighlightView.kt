package com.romaster.launcher.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Nova-style drop target: faint rounded rect + white L-shaped corners.
 * Corner opacity increases as the drag point gets closer to the cell center.
 */
class CellHighlightView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = resources.displayMetrics.density

    private val rectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = 0x66FFFFFF
    }

    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        strokeCap = Paint.Cap.ROUND
        color = 0xFFFFFFFF.toInt()
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x18FFFFFF
    }

    /** 0f = far / between cells, 1f = centered on this cell */
    var focusStrength: Float = 1f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    private val cornerLen: Float get() = 12f * density
    private val inset: Float get() = 3f * density

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val left = inset
        val top = inset
        val right = w - inset
        val bottom = h - inset
        val radius = 14f * density

        // Soft fill + border (stronger when focused)
        val a = (0x18 * focusStrength).toInt().coerceIn(0, 0x28)
        fillPaint.color = (a shl 24) or 0x00FFFFFF
        canvas.drawRoundRect(left, top, right, bottom, radius, radius, fillPaint)

        rectPaint.alpha = (80 + 100 * focusStrength).toInt().coerceIn(40, 180)
        canvas.drawRoundRect(left, top, right, bottom, radius, radius, rectPaint)

        // White corner brackets
        cornerPaint.alpha = (90 + 165 * focusStrength).toInt().coerceIn(60, 255)
        val cl = cornerLen

        // Top-left
        canvas.drawLine(left, top + cl, left, top, cornerPaint)
        canvas.drawLine(left, top, left + cl, top, cornerPaint)
        // Top-right
        canvas.drawLine(right - cl, top, right, top, cornerPaint)
        canvas.drawLine(right, top, right, top + cl, cornerPaint)
        // Bottom-left
        canvas.drawLine(left, bottom - cl, left, bottom, cornerPaint)
        canvas.drawLine(left, bottom, left + cl, bottom, cornerPaint)
        // Bottom-right
        canvas.drawLine(right - cl, bottom, right, bottom, cornerPaint)
        canvas.drawLine(right, bottom, right, bottom - cl, cornerPaint)
    }
}
