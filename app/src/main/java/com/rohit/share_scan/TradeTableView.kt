package com.rohit.share_scan

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.View
import androidx.core.content.ContextCompat
import com.google.android.material.color.MaterialColors
import kotlin.math.ceil
import kotlin.math.min

/**
 * Insider trades or bulk/block deals as one spreadsheet: a bold header row, grid lines, figures to the right and the
 * buy/sell column in green or red. One view draws every row, so a company with over a thousand trades opens at once
 * without a view per cell; it sits in a HorizontalScrollView for its width.
 */
class TradeTableView(context: Context) : View(context) {
    private val dp = resources.displayMetrics.density
    private val padX = 8 * dp
    private val padY = 6 * dp
    private val maxColumn = 260 * dp
    private val cellPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(12f)
        color = ContextCompat.getColor(context, R.color.text_primary)
        fontFeatureSettings = "tnum"
    }
    private val headPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(11f)
        color = ContextCompat.getColor(context, R.color.text_secondary)
        typeface = Typeface.DEFAULT_BOLD
    }
    private val buyPaint = TextPaint(cellPaint).apply { color = ContextCompat.getColor(context, R.color.bull) }
    private val sellPaint = TextPaint(cellPaint).apply { color = ContextCompat.getColor(context, R.color.bear) }
    private val gridPaint = Paint().apply {
        color = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOutline, ContextCompat.getColor(context, R.color.text_secondary))
        strokeWidth = dp.coerceAtLeast(1f) / 2
    }
    private val rowHeight = ceil(maxOf(lineHeight(cellPaint), lineHeight(headPaint)) + 2 * padY)
    private val cellAscent = cellPaint.fontMetrics.ascent
    private val headAscent = headPaint.fontMetrics.ascent
    private val clip = Rect()

    private var header: List<String> = emptyList()
    private var rows: List<List<String>> = emptyList()
    private var buys: List<Boolean> = emptyList()
    private var figures: Set<Int> = emptySet()
    private var side = -1
    private var widths = FloatArray(0)

    /**
     * Fills the sheet: [header] and each row's cells in the same columns, [figures] the columns aligned right, [side]
     * the buy/sell column, coloured by [buys].
     */
    fun show(header: List<String>, rows: List<List<String>>, buys: List<Boolean>, figures: Set<Int>, side: Int) {
        this.figures = figures
        this.side = side
        widths = FloatArray(header.size) { column ->
            val widest = maxOf(headPaint.measureText(header[column]), rows.maxOfOrNull { cellPaint.measureText(it[column]) } ?: 0f)
            min(widest, maxColumn) + 2 * padX
        }
        // Longer than its column's limit (a long name), a cell ends in "…".
        this.header = header.mapIndexed { i, text -> fit(text, headPaint, i) }
        this.rows = rows.map { cells -> cells.mapIndexed { i, text -> fit(text, cellPaint, i) } }
        this.buys = buys
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(ceil(widths.sum()).toInt() + 1, (rowHeight * (rows.size + 1)).toInt() + 1)
    }

    override fun onDraw(canvas: Canvas) {
        if (header.isEmpty()) return
        val right = widths.sum()
        val bottom = rowHeight * (rows.size + 1)
        // Rows outside the clip are skipped.
        canvas.getClipBounds(clip)
        val first = (clip.top / rowHeight).toInt().coerceAtLeast(0)
        val last = min(rows.size, (clip.bottom / rowHeight).toInt() + 1)
        for (r in first..last) {
            val top = r * rowHeight
            canvas.drawLine(0f, top, right, top, gridPaint)
            val cells = if (r == 0) header else rows.getOrNull(r - 1) ?: continue
            var x = 0f
            cells.forEachIndexed { column, text ->
                val paint = when {
                    r == 0 -> headPaint
                    column == side -> if (buys[r - 1]) buyPaint else sellPaint
                    else -> cellPaint
                }
                val textX = if (column in figures) x + widths[column] - padX - paint.measureText(text) else x + padX
                canvas.drawText(text, textX, top + padY - (if (r == 0) headAscent else cellAscent), paint)
                x += widths[column]
            }
        }
        canvas.drawLine(0f, bottom, right, bottom, gridPaint)
        var x = 0f
        for (w in widths) {
            canvas.drawLine(x, 0f, x, bottom, gridPaint)
            x += w
        }
        canvas.drawLine(right, 0f, right, bottom, gridPaint)
    }

    private fun fit(text: String, paint: TextPaint, column: Int): String =
        TextUtils.ellipsize(text, paint, widths[column] - 2 * padX, TextUtils.TruncateAt.END).toString()

    private fun lineHeight(paint: Paint): Float = paint.fontMetrics.let { it.descent - it.ascent }

    private fun sp(value: Float): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)
}
