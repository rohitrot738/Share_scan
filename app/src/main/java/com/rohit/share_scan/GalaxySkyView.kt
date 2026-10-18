package com.rohit.share_scan

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * The Galaxy theme's moving sky, drawn over the static window background: stars that twinkle, stars that drift
 * out from the middle and grow as if the screen were flying through space, and a shooting star every few seconds.
 * It redraws about 40 times a second and stops while it is off screen or the system's animations are turned off.
 */
class GalaxySkyView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val density = resources.displayMetrics.density
    private val random = Random(SEED)
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val startedAt = SystemClock.uptimeMillis()

    // Twinkling stars, at fixed places given as fractions of the view; the first SPARKLERS also glint.
    private val twinkleX = FloatArray(TWINKLES) { random.nextFloat() }
    private val twinkleY = FloatArray(TWINKLES) { 0.04f + 0.96f * random.nextFloat() }
    private val twinkleRadius = FloatArray(TWINKLES) { i -> density * if (i < SPARKLERS) 1.3f + random.nextFloat() * 0.7f else 0.5f + random.nextFloat() * 0.9f }
    private val twinklePeriod = FloatArray(TWINKLES) { 1.6f + random.nextFloat() * 3.2f }
    private val twinklePhase = FloatArray(TWINKLES) { random.nextFloat() * TWO_PI }
    private val twinkleMax = FloatArray(TWINKLES) { i -> if (i < SPARKLERS) 0.9f + random.nextFloat() * 0.1f else 0.45f + random.nextFloat() * 0.45f }
    private val twinkleColor = IntArray(TWINKLES) { STAR_COLORS[random.nextInt(STAR_COLORS.size)] }

    // Stars flying out from the vanishing point; each comes back from the middle on a new heading.
    private val flyAngle = FloatArray(FLYERS) { random.nextFloat() * TWO_PI }
    private val flyLife = FloatArray(FLYERS) { 9f + random.nextFloat() * 7f }
    private val flyOffset = FloatArray(FLYERS) { random.nextFloat() }
    private val flyColor = IntArray(FLYERS) { STAR_COLORS[random.nextInt(STAR_COLORS.size)] }

    // Shooting stars: a few slots, each live from its start time for its duration.
    private val meteorLive = BooleanArray(METEORS)
    private val meteorStart = LongArray(METEORS)
    private val meteorDuration = LongArray(METEORS)
    private val meteorX = FloatArray(METEORS)
    private val meteorY = FloatArray(METEORS)
    private val meteorDx = FloatArray(METEORS)
    private val meteorDy = FloatArray(METEORS)
    private val meteorTravel = FloatArray(METEORS)
    private val meteorTail = FloatArray(METEORS)
    private var nextMeteorAt = startedAt + FIRST_METEOR_MS

    private var animating = false
    private var lastFrameNanos = 0L
    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!animating) return
            if (frameTimeNanos - lastFrameNanos >= MIN_FRAME_NANOS) {
                lastFrameNanos = frameTimeNanos
                invalidate()
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    /** False while View's constructor runs, which can report a visibility change before these fields exist. */
    private val ready = true

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateAnimating()
    }

    override fun onDetachedFromWindow() {
        stopAnimating()
        super.onDetachedFromWindow()
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
        val run = isAttachedToWindow && windowVisibility == VISIBLE && isShown && ValueAnimator.areAnimatorsEnabled()
        if (run == animating) return
        if (run) {
            animating = true
            Choreographer.getInstance().postFrameCallback(frame)
        } else {
            stopAnimating()
        }
        invalidate()
    }

    private fun stopAnimating() {
        animating = false
        Choreographer.getInstance().removeFrameCallback(frame)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val now = SystemClock.uptimeMillis()
        val seconds = (now - startedAt) / 1000f
        drawFlyers(canvas, w, h, seconds)
        drawTwinkles(canvas, w, h, seconds)
        if (animating) drawMeteors(canvas, w, h, now)
    }

    private fun drawFlyers(canvas: Canvas, w: Float, h: Float, seconds: Float) {
        val centreX = w * 0.5f
        val centreY = h * 0.42f
        val reach = hypot(w, h) * 0.62f
        for (i in 0 until FLYERS) {
            val cycles = seconds / flyLife[i] + flyOffset[i]
            val cycle = floor(cycles)
            val progress = cycles - cycle
            val angle = flyAngle[i] + cycle * GOLDEN_ANGLE
            val dx = cos(angle)
            val dy = sin(angle)
            // Slow near the middle, quicker towards the edge, and bigger as it comes closer.
            val distance = reach * progress * progress
            val x = centreX + dx * distance
            val y = centreY + dy * distance
            val fade = min(1f, progress / 0.15f) * min(1f, (1f - progress) / 0.1f)
            val radius = density * (0.3f + 1.9f * progress)
            val alpha = (220 * fade).toInt()
            dot.color = flyColor[i]
            dot.alpha = alpha
            canvas.drawCircle(x, y, radius, dot)
            if (progress > 0.45f) {
                val streak = density * 18f * (progress - 0.45f)
                line.color = flyColor[i]
                line.alpha = alpha / 2
                line.strokeWidth = radius
                canvas.drawLine(x, y, x - dx * streak, y - dy * streak, line)
            }
        }
    }

    private fun drawTwinkles(canvas: Canvas, w: Float, h: Float, seconds: Float) {
        for (i in 0 until TWINKLES) {
            val wave = 0.5f + 0.5f * sin(TWO_PI * seconds / twinklePeriod[i] + twinklePhase[i])
            val brightness = twinkleMax[i] * (0.15f + 0.85f * wave * wave)
            val x = twinkleX[i] * w
            val y = twinkleY[i] * h
            dot.color = twinkleColor[i]
            dot.alpha = (255 * brightness).toInt()
            canvas.drawCircle(x, y, twinkleRadius[i], dot)
            if (i < SPARKLERS && wave > 0.75f) {
                val arm = twinkleRadius[i] * 16f * (wave - 0.75f)
                line.color = twinkleColor[i]
                line.alpha = (170 * brightness).toInt()
                line.strokeWidth = 0.8f * density
                canvas.drawLine(x - arm, y, x + arm, y, line)
                canvas.drawLine(x, y - arm, x, y + arm, line)
            }
        }
    }

    private fun drawMeteors(canvas: Canvas, w: Float, h: Float, now: Long) {
        if (now >= nextMeteorAt) {
            launchMeteor(w, h, now)
            nextMeteorAt = now + random.nextLong(METEOR_GAP_MIN_MS, METEOR_GAP_MAX_MS)
        }
        for (m in 0 until METEORS) {
            if (!meteorLive[m]) continue
            val progress = (now - meteorStart[m]).toFloat() / meteorDuration[m]
            if (progress >= 1f) {
                meteorLive[m] = false
                continue
            }
            val travelled = meteorTravel[m] * progress
            val headX = meteorX[m] + meteorDx[m] * travelled
            val headY = meteorY[m] + meteorDy[m] * travelled
            val fade = min(1f, progress / 0.12f) * min(1f, (1f - progress) / 0.3f)
            val tail = meteorTail[m] * min(1f, progress / 0.3f)
            for (s in 0 until TAIL_SEGMENTS) {
                val from = s.toFloat() / TAIL_SEGMENTS
                val to = (s + 1).toFloat() / TAIL_SEGMENTS
                val strength = 1f - from
                line.color = METEOR_TAIL
                line.alpha = (255 * fade * strength * strength).toInt()
                line.strokeWidth = density * 2.2f * strength
                canvas.drawLine(
                    headX - meteorDx[m] * tail * from, headY - meteorDy[m] * tail * from,
                    headX - meteorDx[m] * tail * to, headY - meteorDy[m] * tail * to,
                    line,
                )
            }
            dot.color = METEOR_GLOW
            dot.alpha = (90 * fade).toInt()
            canvas.drawCircle(headX, headY, density * 5f, dot)
            dot.color = METEOR_HEAD
            dot.alpha = (255 * fade).toInt()
            canvas.drawCircle(headX, headY, density * 1.6f, dot)
        }
    }

    /** Starts a shooting star in a free slot, from the upper part of the sky down towards the lower left. */
    private fun launchMeteor(w: Float, h: Float, now: Long) {
        var m = 0
        while (m < METEORS && meteorLive[m]) m++
        if (m == METEORS) return
        val angle = (135f + random.nextFloat() * 25f) * DEGREES
        meteorLive[m] = true
        meteorStart[m] = now
        meteorDuration[m] = random.nextLong(650L, 1_100L)
        meteorX[m] = w * (0.35f + random.nextFloat() * 0.7f)
        meteorY[m] = h * (0.05f + random.nextFloat() * 0.4f)
        meteorDx[m] = cos(angle)
        meteorDy[m] = sin(angle)
        meteorTravel[m] = w * (0.55f + random.nextFloat() * 0.3f)
        meteorTail[m] = density * (70f + random.nextFloat() * 60f)
    }

    private companion object {
        const val SEED = 20261018
        const val TWINKLES = 70
        const val SPARKLERS = 10
        const val FLYERS = 36
        const val METEORS = 3
        const val TAIL_SEGMENTS = 10
        const val FIRST_METEOR_MS = 1_500L
        const val METEOR_GAP_MIN_MS = 2_500L
        const val METEOR_GAP_MAX_MS = 6_500L
        /** About 40 frames a second: smooth enough for the meteors, light on the battery. */
        const val MIN_FRAME_NANOS = 25_000_000L
        const val TWO_PI = 6.2831855f
        const val DEGREES = 0.017453292f
        /** Turning each returning star by the golden angle keeps its new path away from its last one. */
        const val GOLDEN_ANGLE = 2.3999631f
        const val METEOR_TAIL = 0xFFF5F3FF.toInt()
        const val METEOR_GLOW = 0xFFC4B5FD.toInt()
        const val METEOR_HEAD = 0xFFFFFFFF.toInt()
        /** White, lavender, violet, cyan and pink, like the static starfield underneath. */
        val STAR_COLORS = intArrayOf(
            0xFFFFFFFF.toInt(), 0xFFE9E5FF.toInt(), 0xFFC4B5FD.toInt(), 0xFFA5F3FC.toInt(), 0xFFF9A8D4.toInt(),
        )
    }
}
