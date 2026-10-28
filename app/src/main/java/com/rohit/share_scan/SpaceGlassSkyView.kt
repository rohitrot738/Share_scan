package com.rohit.share_scan

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import androidx.annotation.RequiresApi
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

/**
 * The Space Glass background (Menu → Background), drawn afresh every frame instead of baked like [GalaxySkyView]'s
 * Milky Way: deep space (#0B0F19) with soft cyan, emerald and indigo lights drifting slowly behind the glass, a
 * trading terminal's grid running towards the viewer below a faint horizon, and a glowing price line scrolling across
 * the middle with a pulse running along it. No stars: the stars are the Milky Way's. Under another theme than Space
 * Glass it takes that theme's colours ([tintWith]).
 *
 * Like the Milky Way it redraws about 30 times a second while on show (15 in battery saver), holds still when the
 * system's animations are off or Settings turns [animate] off, and draws a frosted pane behind the scan board
 * ([frost]): the lights here are soft already, so the pane only darkens and tints what is behind it.
 */
class SpaceGlassSkyView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val density = resources.displayMetrics.density
    private val power = context.getSystemService(PowerManager::class.java)
    private val startedAt = SystemClock.uptimeMillis()

    /** Settings' sky animation switch: off holds the lights, grid and line where they were. */
    var animate: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            updateAnimating()
        }

    // The colours, Space Glass's own until [tintWith] gives the theme's.
    private var spaceTop = SPACE_TOP
    private var spaceMid = SPACE_MID
    private var spaceBottom = SPACE_BOTTOM
    private var accent = CYAN
    private var second = EMERALD

    private val space = Paint()
    /** One paint a light, its round glow in its own colour, moved and scaled into place each frame. */
    private val lights = Array(LIGHTS.size) { Paint(Paint.ANTI_ALIAS_FLAG) }
    private val lightMatrix = Matrix()
    private val gridLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val horizonGlow = Paint()
    private val chartLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val chartHalo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val chartFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val chartPath = Path()
    private val fillPath = Path()
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)

    // The frosted pane behind the scan board.
    private var frostTarget: View? = null
    private var frostShade = 0f
    private var frostRadius = 0f
    private val frostPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val frostBox = RectF()
    private val targetAt = IntArray(2)
    private val selfAt = IntArray(2)

    /**
     * Paints in the [context] theme's colours: its background for the space at the top, so the strip behind the
     * status bar runs into it, its accent for the first light, the grid and the price line, and its second colour for
     * the second light and the line's tail. Space Glass's own theme needs none of it.
     */
    fun tintWith(context: Context) {
        spaceTop = MaterialColors.getColor(context, android.R.attr.colorBackground, SPACE_TOP)
        accent = MaterialColors.getColor(context, androidx.appcompat.R.attr.colorPrimary, CYAN)
        second = MaterialColors.getColor(context, com.google.android.material.R.attr.colorSecondary, EMERALD)
        spaceMid = ColorUtils.blendARGB(spaceTop, accent, TINT_MID)
        spaceBottom = ColorUtils.blendARGB(spaceTop, second, TINT_BOTTOM)
        if (width > 0 && height > 0) onSizeChanged(width, height, width, height)
        invalidate()
    }

    /**
     * A frosted pane behind [target], rounded by [radius]: [strength] how much glass tint it gets and [shade] how
     * much it darkens what is behind it, so text on the glass stays easy to read.
     */
    fun frost(target: View?, strength: Float, shade: Float, radius: Float) {
        frostTarget = target
        frostShade = (shade + strength * FROST_TINT).coerceIn(0f, 0.85f)
        frostRadius = radius
        invalidate()
    }

    private var animating = false
    private var lastFrameNanos = 0L
    private var stillAt = startedAt
    private var heldFor = 0L
    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!animating) return
            val gap = if (power?.isPowerSaveMode == true) SAVER_FRAME_NANOS else MIN_FRAME_NANOS
            if (frameTimeNanos - lastFrameNanos >= gap) {
                lastFrameNanos = frameTimeNanos
                invalidate()
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private val scaleListener: Any? =
        if (Build.VERSION.SDK_INT >= 33) ValueAnimator.DurationScaleChangeListener { post { updateAnimating() } } else null

    /** False while View's constructor runs, which can report a visibility change before these fields exist. */
    private val ready = true

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (Build.VERSION.SDK_INT >= 33) listenForScale(true)
        updateAnimating()
    }

    override fun onDetachedFromWindow() {
        if (Build.VERSION.SDK_INT >= 33) listenForScale(false)
        stopAnimating()
        super.onDetachedFromWindow()
    }

    @RequiresApi(33)
    private fun listenForScale(on: Boolean) {
        val listener = scaleListener as ValueAnimator.DurationScaleChangeListener
        if (on) ValueAnimator.registerDurationScaleChangeListener(listener)
        else ValueAnimator.unregisterDurationScaleChangeListener(listener)
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        updateAnimating()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        updateAnimating()
    }

    private fun updateAnimating() {
        if (!ready) return
        val run = animate && isAttachedToWindow && windowVisibility == VISIBLE && isShown &&
            ValueAnimator.areAnimatorsEnabled()
        if (run == animating) return
        if (run) {
            animating = true
            heldFor += SystemClock.uptimeMillis() - stillAt
            Choreographer.getInstance().postFrameCallback(frame)
        } else {
            stopAnimating()
        }
        invalidate()
    }

    private fun stopAnimating() {
        if (animating) stillAt = SystemClock.uptimeMillis()
        animating = false
        Choreographer.getInstance().removeFrameCallback(frame)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        space.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(spaceTop, spaceMid, spaceBottom), floatArrayOf(0f, HORIZON, 1f), Shader.TileMode.CLAMP,
        )
        for (i in LIGHTS.indices) {
            val l = LIGHTS[i]
            val color = when (l.color) {
                CYAN -> accent
                EMERALD -> second
                else -> l.color
            }
            lights[i].shader = RadialGradient(
                0f, 0f, 1f, intArrayOf(withAlpha(color, l.alpha), withAlpha(color, l.alpha * 2 / 5), Color.TRANSPARENT),
                floatArrayOf(0f, 0.4f, 1f), Shader.TileMode.CLAMP,
            )
        }
        val horizonY = h * HORIZON
        horizonGlow.shader = LinearGradient(
            0f, horizonY - 60 * density, 0f, horizonY + 90 * density,
            intArrayOf(Color.TRANSPARENT, withAlpha(accent, 0x30), Color.TRANSPARENT), floatArrayOf(0f, 0.4f, 1f), Shader.TileMode.CLAMP,
        )
        chartFill.shader = LinearGradient(
            0f, h * CHART_Y - h * CHART_SWING, 0f, h * (CHART_Y + CHART_SWING * 2),
            intArrayOf(withAlpha(accent, 0x1C), Color.TRANSPARENT), null, Shader.TileMode.CLAMP,
        )
        chartLine.shader = LinearGradient(
            0f, 0f, w.toFloat(), 0f,
            intArrayOf(withAlpha(second, 0x00), withAlpha(second, 0x9A), withAlpha(accent, 0xB0), withAlpha(accent, 0x00)),
            floatArrayOf(0f, 0.25f, 0.75f, 1f), Shader.TileMode.CLAMP,
        )
        chartHalo.shader = chartLine.shader
        chartLine.strokeWidth = 1.6f * density
        chartHalo.strokeWidth = 7f * density
        chartHalo.alpha = 0x40
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val now = if (animating) drawingTime else stillAt
        val seconds = ((now - startedAt - heldFor) / 1000.0 % REPEAT_SECONDS).toFloat()
        canvas.drawRect(0f, 0f, w, h, space)
        drawLights(canvas, w, h, seconds)
        drawGrid(canvas, w, h, seconds)
        drawChart(canvas, w, h, seconds)
        drawFrost(canvas)
    }

    /** Three big soft lights drifting on slow loops and breathing: cyan up right, emerald low left, indigo between. */
    private fun drawLights(canvas: Canvas, w: Float, h: Float, t: Float) {
        for (i in LIGHTS.indices) {
            val l = LIGHTS[i]
            val x = w * (l.x + l.drift * sin(t * l.speed + i))
            val y = h * (l.y + l.drift * 0.6f * sin(t * l.speed * 0.7f + i * 2f))
            val radius = max(w, h) * l.size * (0.92f + 0.08f * sin(t * 0.4f + i))
            val breath = 0.75f + 0.25f * sin(t * 0.5f + i * 1.7f)
            lightMatrix.setScale(radius, radius)
            lightMatrix.postTranslate(x, y)
            val paint = lights[i]
            paint.shader.setLocalMatrix(lightMatrix)
            paint.alpha = (255 * breath).toInt()
            canvas.drawCircle(x, y, radius, paint)
        }
    }

    /**
     * The grid below the horizon: lines fanning out from a vanishing point on the horizon and cross lines running
     * towards the viewer, ever closer together near the horizon and fading into it.
     */
    private fun drawGrid(canvas: Canvas, w: Float, h: Float, t: Float) {
        val horizon = h * HORIZON
        canvas.drawRect(0f, horizon - 60 * density, w, horizon + 90 * density, horizonGlow)
        gridLine.strokeWidth = density
        gridLine.color = accent
        val cx = w / 2f
        val depth = h - horizon
        // Fanning lines: evenly spaced along the bottom edge, all meeting at the vanishing point.
        for (i in -GRID_FANS..GRID_FANS) {
            val bottomX = cx + i * w * 0.16f
            gridLine.alpha = (GRID_ALPHA * (1f - abs(i) / (GRID_FANS + 1f) * 0.6f)).toInt()
            canvas.drawLine(cx + (bottomX - cx) * 0.02f, horizon, bottomX, h, gridLine)
        }
        // Cross lines: z runs from far (0) to near (1), squared so they crowd together towards the horizon.
        val scroll = (t * GRID_SPEED) % 1f
        for (k in 0 until GRID_ROWS) {
            val z = (k + scroll) / GRID_ROWS
            val y = horizon + depth * z * z
            gridLine.alpha = (GRID_ALPHA * z).toInt()
            canvas.drawLine(0f, y, w, y, gridLine)
        }
    }

    /**
     * A price line scrolling slowly left across the middle of the screen: a sum of slow waves with a gentle climb,
     * a soft glow under it, and a bright pulse travelling along it.
     */
    private fun drawChart(canvas: Canvas, w: Float, h: Float, t: Float) {
        val base = h * CHART_Y
        val swing = h * CHART_SWING
        val step = w / CHART_POINTS
        val shift = t * CHART_SPEED
        chartPath.rewind()
        fillPath.rewind()
        var pulseX = 0f
        var pulseY = 0f
        val pulseAt = (t * PULSE_SPEED) % 1f
        for (i in 0..CHART_POINTS) {
            val x = i * step
            val u = i.toFloat() / CHART_POINTS + shift
            val y = base - swing * (
                0.55f * sin(u * 7.1f) + 0.30f * sin(u * 17.3f + 1.3f) + 0.15f * sin(u * 41f + 0.7f) +
                    0.35f * ((u * 0.9f) % 1f) - 0.17f
                )
            if (i == 0) {
                chartPath.moveTo(x, y)
                fillPath.moveTo(x, h * (CHART_Y + CHART_SWING * 2))
            }
            if (i > 0) chartPath.lineTo(x, y)
            fillPath.lineTo(x, y)
            if (abs(i.toFloat() / CHART_POINTS - pulseAt) < 0.5f / CHART_POINTS) {
                pulseX = x
                pulseY = y
            }
        }
        fillPath.lineTo(w, h * (CHART_Y + CHART_SWING * 2))
        fillPath.close()
        canvas.drawPath(fillPath, chartFill)
        canvas.drawPath(chartPath, chartHalo)
        canvas.drawPath(chartPath, chartLine)
        if (pulseX > 0f) {
            dot.color = accent
            dot.alpha = 0x44
            canvas.drawCircle(pulseX, pulseY, 7f * density, dot)
            dot.color = Color.WHITE
            dot.alpha = 0xDD
            canvas.drawCircle(pulseX, pulseY, 2.2f * density, dot)
        }
    }

    /** The frosted pane behind the scan board: a dark glass tint clipped to the board's rounded shape. */
    private fun drawFrost(canvas: Canvas) {
        val target = frostTarget ?: return
        if (frostShade <= 0f || !target.isShown || target.width == 0) return
        target.getLocationInWindow(targetAt)
        getLocationInWindow(selfAt)
        val left = (targetAt[0] - selfAt[0]).toFloat()
        val top = (targetAt[1] - selfAt[1]).toFloat()
        frostBox.set(left, top, left + target.width, top + target.height)
        frostPaint.color = FROST_COLOR
        frostPaint.alpha = (255 * frostShade).toInt()
        canvas.drawRoundRect(frostBox, frostRadius, frostRadius, frostPaint)
    }

    /** One drifting light: centre ([x], [y]) and [size] as fractions of the view, its loop and colour. */
    private class Light(val x: Float, val y: Float, val size: Float, val drift: Float, val speed: Float, val color: Int, val alpha: Int)

    private companion object {
        /** Where the horizon is, as a fraction of the height. */
        const val HORIZON = 0.66f
        const val GRID_FANS = 7
        const val GRID_ROWS = 12
        const val GRID_ALPHA = 46f
        /** Cross lines per second towards the viewer, as a share of the gap between two. */
        const val GRID_SPEED = 0.18f
        const val CHART_Y = 0.42f
        const val CHART_SWING = 0.07f
        const val CHART_POINTS = 90
        const val CHART_SPEED = 0.012f
        const val PULSE_SPEED = 0.07f
        /** The clock wraps after this many seconds, so a Float keeps every frame's precision. */
        const val REPEAT_SECONDS = 3600.0 * 6
        const val FROST_TINT = 0.35f
        const val MIN_FRAME_NANOS = 30_000_000L
        const val SAVER_FRAME_NANOS = 62_000_000L

        /** Another theme's colours ([tintWith]): how much of its accent and second colour mix into its space below. */
        const val TINT_MID = 0.05f
        const val TINT_BOTTOM = 0.1f

        const val SPACE_TOP = 0xFF0B0F19.toInt()
        const val SPACE_MID = 0xFF0A1322.toInt()
        const val SPACE_BOTTOM = 0xFF061A22.toInt()
        const val CYAN = 0xFF38BDF8.toInt()
        const val EMERALD = 0xFF10B981.toInt()
        const val INDIGO = 0xFF6366F1.toInt()
        const val FROST_COLOR = 0xFF0B1220.toInt()

        val LIGHTS = arrayOf(
            Light(0.82f, 0.16f, 0.55f, 0.06f, 0.11f, CYAN, 0x38),
            Light(0.12f, 0.78f, 0.60f, 0.07f, 0.08f, EMERALD, 0x2A),
            Light(0.40f, 0.46f, 0.45f, 0.08f, 0.06f, INDIGO, 0x22),
        )

        fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha shl 24)
    }
}
