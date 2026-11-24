package com.rohit.share_scan

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import java.math.BigDecimal
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** A compact native bid × ask footprint renderer for imported, explicitly classified trades. */
internal class FootprintCanvasView(context: Context) : View(context) {
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 27f }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.LTGRAY; textSize = 22f }
    private val cell = Paint(Paint.ANTI_ALIAS_FLAG)
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(245, 190, 65)
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
    }
    private var bars: List<OrderFlowBar> = emptyList()
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

    fun setBars(value: List<OrderFlowBar>) {
        bars = value.takeLast(5)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val visible = bars
        if (visible.isEmpty()) {
            small.textAlign = Paint.Align.CENTER
            canvas.drawText("CSV से executed trades import करें", width / 2f, height / 2f, small)
            small.textAlign = Paint.Align.LEFT
            return
        }
        val prices = visible.flatMap { it.levels.keys }.distinct().sortedDescending().take(14)
        if (prices.isEmpty()) return
        val left = 86f
        val top = 34f
        val footer = 38f
        val columnWidth = ((width - left - 8f) / visible.size).coerceAtLeast(36f)
        val rowHeight = ((height - top - footer) / prices.size).coerceAtLeast(24f)
        small.textSize = 17f
        text.textSize = 16f
        text.textAlign = Paint.Align.CENTER
        small.textAlign = Paint.Align.RIGHT

        visible.forEachIndexed { index, bar ->
            val x = left + index * columnWidth
            small.textAlign = Paint.Align.CENTER
            canvas.drawText(timeFormat.format(bar.start), x + columnWidth / 2f, 20f, small)
            prices.forEachIndexed { row, price ->
                val y = top + row * rowHeight
                val level = bar.levels[price]
                val bid = level?.bidVolume ?: 0L
                val ask = level?.askVolume ?: 0L
                val delta = ask - bid
                cell.color = when {
                    level == null -> Color.rgb(43, 45, 57)
                    delta > 0 -> Color.argb(205, 20, 112, 76)
                    delta < 0 -> Color.argb(205, 145, 54, 52)
                    else -> Color.rgb(69, 72, 85)
                }
                val rect = RectF(x + 2f, y + 1f, x + columnWidth - 2f, y + rowHeight - 2f)
                canvas.drawRoundRect(rect, 5f, 5f, cell)
                if (bar.poc == price) canvas.drawRoundRect(rect, 5f, 5f, outline)
                canvas.drawText("$bid × $ask", rect.centerX(), y + rowHeight * .66f, text)
            }
        }

        small.textAlign = Paint.Align.RIGHT
        prices.forEachIndexed { row, price ->
            val y = top + row * rowHeight + rowHeight * .66f
            canvas.drawText(price.stripTrailingZeros().toPlainString(), left - 8f, y, small)
        }
        text.textAlign = Paint.Align.LEFT
        small.textAlign = Paint.Align.LEFT
    }
}
