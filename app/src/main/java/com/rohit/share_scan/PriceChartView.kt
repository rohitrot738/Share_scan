package com.rohit.share_scan

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Line chart of recent daily closes: a smooth line with a soft gradient fill under it, a dot on the latest
 * close, and dashed horizontal [Level]s whose labels sit in pills along the right edge (nudged apart when
 * two levels are close). Levels come in priority order: one whose line would sit within a few dp of an
 * earlier one is left out instead of drawing two lines on top of each other. With fewer than two closes it
 * draws a short "no data" message instead.
 *
 * Colours come from colour resources, so the chart follows light and dark mode. Callers should set a
 * content description that summarises the data; [setData] only sets one for the empty state.
 */
class PriceChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    /** A horizontal price level, drawn dashed in [color] and labelled with [label] at the right edge. */
    data class Level(val value: Double, val label: String, @ColorInt val color: Int)

    /** A level with its geometry worked out for the current size. */
    private class PlacedLevel(
        val path: Path,
        val pill: RectF,
        val text: String,
        val textX: Float,
        val baseline: Float,
        @ColorInt val color: Int,
        @ColorInt val fill: Int,
    )

    private var closes: List<Double> = emptyList()
    private var levels: List<Level> = emptyList()
    private var placed: List<PlacedLevel> = emptyList()
    private val linePath = Path()
    private val fillPath = Path()
    private var plotLeft = 0f
    private var plotRight = 0f
    private var lastX = 0f
    private var lastY = 0f

    /** 0..1: how much of the line is drawn, animated left to right when new data arrives. */
    private var reveal = 1f
    private var revealPending = false
    private var revealAnimator: ValueAnimator? = null

    private val density = resources.displayMetrics.density

    @ColorInt
    private val cardColor = ContextCompat.getColor(context, R.color.card)
    private val emptyText = context.getString(R.string.detail_chart_empty)

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.25f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = ContextCompat.getColor(context, R.color.brand_primary)
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val levelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.25f)
        pathEffect = DashPathEffect(floatArrayOf(dp(5f), dp(4f)), 0f)
    }
    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(10.5f)
        typeface = Typeface.DEFAULT_BOLD
        fontFeatureSettings = "tnum"
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(13f)
        textAlign = Paint.Align.CENTER
        color = ContextCompat.getColor(context, R.color.text_secondary)
    }

    /**
     * Shows [closes] (oldest first) drawn in [lineColor], with [levels] (most important first) as dashed
     * lines. Non-finite and non-positive values are ignored. The vertical range always includes every level.
     */
    fun setData(closes: List<Double>, levels: List<Level>, @ColorInt lineColor: Int) {
        this.closes = closes.filter { it.isFinite() && it > 0.0 }
        this.levels = levels.filter { it.value.isFinite() && it.value > 0.0 }
        linePaint.color = lineColor
        if (this.closes.size < 2) contentDescription = emptyText
        layoutChart()

        revealAnimator?.cancel()
        revealAnimator = null
        revealPending = this.closes.size >= 2
        reveal = if (revealPending) 0f else 1f
        if (revealPending && isAttachedToWindow) startReveal()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val measuredWidth = getDefaultSize(suggestedMinimumWidth, widthMeasureSpec)
        val desiredHeight = dp(DEFAULT_HEIGHT_DP).toInt() + paddingTop + paddingBottom
        setMeasuredDimension(measuredWidth, resolveSize(desiredHeight, heightMeasureSpec))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutChart()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (revealPending) startReveal()
    }

    override fun onDetachedFromWindow() {
        revealAnimator?.cancel()
        revealAnimator = null
        revealPending = false
        reveal = 1f
        super.onDetachedFromWindow()
    }

    private fun startReveal() {
        revealPending = false
        revealAnimator?.cancel()
        revealAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            startDelay = REVEAL_DELAY_MS
            duration = REVEAL_MS
            interpolator = DecelerateInterpolator(1.5f)
            addUpdateListener { animator ->
                reveal = animator.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    /** Builds the line, fill and level geometry for the current size and data; nothing is allocated in onDraw. */
    private fun layoutChart() {
        linePath.reset()
        fillPath.reset()
        fillPaint.shader = null
        placed = emptyList()
        if (width == 0 || height == 0 || closes.size < 2) return

        val values = closes + levels.map { it.value }
        val highest = values.max()
        val lowest = values.min()
        // 8% headroom above and below; a flat series still gets a 2% band so it sits in the middle.
        val middle = (highest + lowest) / 2.0
        val half = max((highest - lowest) / 2.0, middle * 0.01) * 1.08
        val low = middle - half
        val range = 2.0 * half

        val metrics = labelPaint.fontMetrics
        val pillHeight = metrics.descent - metrics.ascent + dp(6f)
        val pillPadding = dp(7f)
        val top = paddingTop + pillHeight / 2
        val bottom = height - paddingBottom - pillHeight / 2
        if (bottom - top < dp(40f)) return

        fun yOf(value: Double): Float = bottom - ((value - low) / range).toFloat() * (bottom - top)

        // Keep a level only when its line is clear of every more important one already kept.
        val kept = ArrayList<Pair<Level, Float>>(levels.size)
        for (level in levels) {
            val y = yOf(level.value)
            if (kept.none { abs(it.second - y) < dp(MERGE_DP) }) kept += level to y
        }

        // The pills get a column on the right, as wide as the widest kept label (within a limit).
        val maxText = width * MAX_LABEL_FRACTION
        val widestLabel = kept.maxOfOrNull { labelPaint.measureText(it.first.label) } ?: 0f
        val column = if (kept.isEmpty()) 0f else min(widestLabel, maxText) + 2 * pillPadding + dp(8f)
        plotLeft = paddingLeft + dp(2f)
        plotRight = width - paddingRight - column - dp(8f)
        if (plotRight - plotLeft < dp(40f)) return

        // Smooth line: each segment is a cubic whose control points share the x midpoint, so the curve
        // never overshoots above or below the two closes it joins.
        val step = (plotRight - plotLeft) / (closes.size - 1)
        var previousX = plotLeft
        var previousY = yOf(closes[0])
        var highestY = previousY
        linePath.moveTo(previousX, previousY)
        for (index in 1 until closes.size) {
            val x = plotLeft + step * index
            val y = yOf(closes[index])
            val midX = (previousX + x) / 2f
            linePath.cubicTo(midX, previousY, midX, y, x, y)
            previousX = x
            previousY = y
            highestY = min(highestY, y)
        }
        lastX = previousX
        lastY = previousY

        val fillBottom = (height - paddingBottom).toFloat()
        fillPath.set(linePath)
        fillPath.lineTo(lastX, fillBottom)
        fillPath.lineTo(plotLeft, fillBottom)
        fillPath.close()
        fillPaint.shader = LinearGradient(
            0f, highestY, 0f, fillBottom,
            ColorUtils.setAlphaComponent(linePaint.color, FILL_ALPHA),
            ColorUtils.setAlphaComponent(linePaint.color, 0),
            Shader.TileMode.CLAMP,
        )

        // Pills start centred on their line; overlapping ones are pushed apart, then back inside the view.
        val sorted = kept.sortedBy { it.second }
        val centers = FloatArray(sorted.size) { sorted[it].second }
        val gap = pillHeight + dp(3f)
        val minCenter = paddingTop + pillHeight / 2
        val maxCenter = height - paddingBottom - pillHeight / 2
        for (index in centers.indices) {
            centers[index] = max(centers[index], if (index == 0) minCenter else centers[index - 1] + gap)
        }
        for (index in centers.indices.reversed()) {
            centers[index] = min(centers[index], if (index == centers.lastIndex) maxCenter else centers[index + 1] - gap)
        }

        val right = (width - paddingRight).toFloat()
        val baselineOffset = -(metrics.ascent + metrics.descent) / 2f
        placed = sorted.mapIndexed { index, (level, lineY) ->
            val text = TextUtils.ellipsize(level.label, labelPaint, maxText, TextUtils.TruncateAt.END).toString()
            val center = centers[index]
            val pill = RectF(right - labelPaint.measureText(text) - 2 * pillPadding, center - pillHeight / 2, right, center + pillHeight / 2)
            // Flat across the plot, with a short elbow just before the pill when the pill was nudged.
            val path = Path().apply {
                moveTo(plotLeft, lineY)
                lineTo(max(plotRight + dp(4f), pill.left - dp(12f)), lineY)
                lineTo(pill.left - dp(3f), center)
            }
            PlacedLevel(
                path = path,
                pill = pill,
                text = text,
                textX = pill.left + pillPadding,
                baseline = center + baselineOffset,
                color = level.color,
                fill = ColorUtils.setAlphaComponent(level.color, PILL_ALPHA),
            )
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (closes.size < 2) {
            val x = (paddingLeft + width - paddingRight) / 2f
            val y = (paddingTop + height - paddingBottom) / 2f - (emptyPaint.ascent() + emptyPaint.descent()) / 2f
            canvas.drawText(emptyText, x, y, emptyPaint)
            return
        }
        if (linePath.isEmpty) return

        for (level in placed) {
            levelPaint.color = level.color
            canvas.drawPath(level.path, levelPaint)
        }

        val saved = canvas.save()
        canvas.clipRect(0f, 0f, plotLeft + (plotRight - plotLeft) * reveal + linePaint.strokeWidth, height.toFloat())
        canvas.drawPath(fillPath, fillPaint)
        canvas.drawPath(linePath, linePaint)
        canvas.restoreToCount(saved)

        if (reveal >= 1f) {
            dotPaint.color = ColorUtils.setAlphaComponent(linePaint.color, HALO_ALPHA)
            canvas.drawCircle(lastX, lastY, dp(8f), dotPaint)
            dotPaint.color = cardColor
            canvas.drawCircle(lastX, lastY, dp(5f), dotPaint)
            dotPaint.color = linePaint.color
            canvas.drawCircle(lastX, lastY, dp(3.5f), dotPaint)
        }

        for (level in placed) {
            val radius = level.pill.height() / 2f
            pillPaint.color = cardColor
            canvas.drawRoundRect(level.pill, radius, radius, pillPaint)
            pillPaint.color = level.fill
            canvas.drawRoundRect(level.pill, radius, radius, pillPaint)
            labelPaint.color = level.color
            canvas.drawText(level.text, level.textX, level.baseline, labelPaint)
        }
    }

    private fun dp(value: Float): Float = value * density

    private fun sp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    private companion object {
        const val DEFAULT_HEIGHT_DP = 200f
        const val MAX_LABEL_FRACTION = 0.34f
        const val MERGE_DP = 4f
        const val REVEAL_MS = 700L
        const val REVEAL_DELAY_MS = 150L
        const val FILL_ALPHA = 0x5C
        const val PILL_ALPHA = 0x2E
        const val HALO_ALPHA = 0x33
    }
}
