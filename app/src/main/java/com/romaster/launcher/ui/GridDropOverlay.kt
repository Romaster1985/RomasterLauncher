package com.romaster.launcher.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.hypot

/**
 * Nova-style drop overlay drawn from the overlay's own measured size
 * so it always matches the current grid after columns/rows change.
 */
class GridDropOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = resources.displayMetrics.density

    var columns: Int = 5
        private set
    var rows: Int = 6
        private set

    var targetCol: Int = 0
        private set
    var targetRow: Int = 0
        private set
    var focusStrength: Float = 1f
        private set

    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.2f * density
        strokeCap = Paint.Cap.ROUND
        color = 0xFFFFFFFF.toInt()
    }
    private val selectStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.2f * density
        color = 0xFF4FC3F7.toInt()
    }
    private val selectFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x224FC3F7
    }

    private val cornerLen = 11f * density
    private val inset = 4f * density
    private val radius = 16f * density

    fun configure(cols: Int, rws: Int) {
        columns = cols.coerceAtLeast(1)
        rows = rws.coerceAtLeast(1)
        targetCol = targetCol.coerceIn(0, columns - 1)
        targetRow = targetRow.coerceIn(0, rows - 1)
        invalidate()
    }

    fun setFocus(col: Int, row: Int, strength: Float) {
        targetCol = col.coerceIn(0, columns - 1)
        targetRow = row.coerceIn(0, rows - 1)
        focusStrength = strength.coerceIn(0f, 1f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f || columns <= 0 || rows <= 0) return

        // Always derive cell size from actual measured size → stays aligned after grid changes
        val cellW = w / columns
        val cellH = h / rows

        val focusCx = (targetCol + 0.5f) * cellW
        val focusCy = (targetRow + 0.5f) * cellH
        val maxDist = hypot(cellW.toDouble(), cellH.toDouble()).toFloat() * 1.6f

        for (r in 0 until rows) {
            for (c in 0 until columns) {
                val cx = (c + 0.5f) * cellW
                val cy = (r + 0.5f) * cellH
                val dist = hypot((cx - focusCx).toDouble(), (cy - focusCy).toDouble()).toFloat()
                if (dist > maxDist) continue

                val t = 1f - (dist / maxDist)
                val alpha = if (c == targetCol && r == targetRow) {
                    (180 + 75 * focusStrength).toInt()
                } else {
                    (40 + 120 * t * t).toInt()
                }.coerceIn(0, 255)

                if (alpha < 20) continue
                cornerPaint.alpha = alpha

                val left = c * cellW + inset
                val top = r * cellH + inset
                val right = (c + 1) * cellW - inset
                val bottom = (r + 1) * cellH - inset
                val cl = cornerLen

                canvas.drawLine(left, top + cl, left, top, cornerPaint)
                canvas.drawLine(left, top, left + cl, top, cornerPaint)
                canvas.drawLine(right - cl, top, right, top, cornerPaint)
                canvas.drawLine(right, top, right, top + cl, cornerPaint)
                canvas.drawLine(left, bottom - cl, left, bottom, cornerPaint)
                canvas.drawLine(left, bottom, left + cl, bottom, cornerPaint)
                canvas.drawLine(right - cl, bottom, right, bottom, cornerPaint)
                canvas.drawLine(right, bottom, right, bottom - cl, cornerPaint)
            }
        }

        val left = targetCol * cellW + inset
        val top = targetRow * cellH + inset
        val right = (targetCol + 1) * cellW - inset
        val bottom = (targetRow + 1) * cellH - inset
        val rect = RectF(left, top, right, bottom)

        selectFill.alpha = (20 + 25 * focusStrength).toInt()
        selectStroke.alpha = (160 + 95 * focusStrength).toInt()
        canvas.drawRoundRect(rect, radius, radius, selectFill)
        canvas.drawRoundRect(rect, radius, radius, selectStroke)

        cornerPaint.alpha = (200 + 55 * focusStrength).toInt()
        val cl = cornerLen
        canvas.drawLine(left, top + cl, left, top, cornerPaint)
        canvas.drawLine(left, top, left + cl, top, cornerPaint)
        canvas.drawLine(right - cl, top, right, top, cornerPaint)
        canvas.drawLine(right, top, right, top + cl, cornerPaint)
        canvas.drawLine(left, bottom - cl, left, bottom, cornerPaint)
        canvas.drawLine(left, bottom, left + cl, bottom, cornerPaint)
        canvas.drawLine(right - cl, bottom, right, bottom, cornerPaint)
        canvas.drawLine(right, bottom, right, bottom - cl, cornerPaint)
    }
}
