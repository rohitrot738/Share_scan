package com.rohit.share_scan

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import kotlin.math.min

/*
 * Small charts for the Watchlist page and the bento grid: a price line, a bar between two levels and a score dial.
 * Each draws from a few numbers, with no axes or labels of its own.
 */

/**
 * A line through [values], oldest at the left, scaled to its own low and high, with a soft fill under it that fades
 * to nothing; [color] paints both. Two or more values are needed to draw anything.
 */
class SparklineView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val density = resources.displayMetrics.density
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.8f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val area = Path()

    var values: List<Double> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    @ColorInt
    var color: Int = ContextCompat.getColor(context, R.color.bull)
        set(value) {
            field = value
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        val points = values.filter { it.isFinite() }
        if (points.size < 2 || width == 0 || height == 0) return
        // A little room at the top and bottom, so the stroke and the last point's dot are not cut off.
        val pad = 3f * density
        val w = width - 2 * pad
        val h = height - 2 * pad
        val low = points.min()
        val high = points.max()
        val span = (high - low).takeIf { it > 0.0 } ?: 1.0
        fun x(i: Int) = pad + w * i / (points.size - 1)
        fun y(v: Double) = pad + h - ((v - low) / span * h).toFloat()
        path.rewind()
        points.forEachIndexed { i, v -> if (i == 0) path.moveTo(x(i), y(v)) else path.lineTo(x(i), y(v)) }
        area.rewind()
        area.addPath(path)
        area.lineTo(x(points.lastIndex), height.toFloat())
        area.lineTo(x(0), height.toFloat())
        area.close()
        fill.shader = LinearGradient(
            0f, 0f, 0f, height.toFloat(),
            ColorUtils.setAlphaComponent(color, 0x40), ColorUtils.setAlphaComponent(color, 0), Shader.TileMode.CLAMP,
        )
        line.shader = LinearGradient(
            0f, 0f, width.toFloat(), 0f,
            ColorUtils.setAlphaComponent(color, 0x66), color, Shader.TileMode.CLAMP,
        )
        canvas.drawPath(area, fill)
        canvas.drawPath(path, line)
        dot.color = color
        canvas.drawCircle(x(points.lastIndex), y(points.last()), 2.6f * density, dot)
    }
}

/**
 * A track from a stock's floor (its stop or the base's low, left, red) to its next level (its trigger or target,
 * right, green), lit up to [position] (0 to 1) where a ring marks the price.
 */
class LevelBarView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val density = resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lit = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
    }
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()

    var position: Float = 0.5f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        if (width == 0 || height == 0) return
        val bull = ContextCompat.getColor(context, R.color.bull)
        val bear = ContextCompat.getColor(context, R.color.bear)
        val radius = 6f * density
        val thick = 5f * density
        val left = radius
        val right = width - radius
        val mid = height / 2f
        box.set(left, mid - thick / 2, right, mid + thick / 2)
        track.color = ColorUtils.setAlphaComponent(ContextCompat.getColor(context, R.color.text_primary), 0x1F)
        canvas.drawRoundRect(box, thick / 2, thick / 2, track)
        val at = left + (right - left) * position
        lit.shader = LinearGradient(left, 0f, right, 0f, bear, bull, Shader.TileMode.CLAMP)
        box.set(left, mid - thick / 2, at, mid + thick / 2)
        canvas.drawRoundRect(box, thick / 2, thick / 2, lit)
        // The price: a ring in the colour of where it stands along the way.
        val here = ColorUtils.blendARGB(bear, bull, position)
        knob.color = ContextCompat.getColor(context, R.color.text_primary)
        canvas.drawCircle(at, mid, radius - 1f * density, knob)
        ring.color = here
        canvas.drawCircle(at, mid, radius - 1f * density, ring)
    }
}

/** A ring filled to [score] out of 100 in [color], with nothing drawn inside: a text view laid over it says the number. */
class ScoreDialView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val density = resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
    }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val box = RectF()

    var score: Double = 0.0
        set(value) {
            field = value
            invalidate()
        }

    @ColorInt
    var color: Int = ContextCompat.getColor(context, R.color.bull)
        set(value) {
            field = value
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        val size = min(width, height).toFloat()
        if (size <= 0f) return
        val inset = arc.strokeWidth / 2 + 0.5f * density
        box.set((width - size) / 2 + inset, (height - size) / 2 + inset, (width + size) / 2 - inset, (height + size) / 2 - inset)
        track.color = ColorUtils.setAlphaComponent(color, 0x33)
        canvas.drawOval(box, track)
        arc.color = color
        canvas.drawArc(box, -90f, 360f * (score / 100.0).toFloat().coerceIn(0f, 1f), false, arc)
    }
}
