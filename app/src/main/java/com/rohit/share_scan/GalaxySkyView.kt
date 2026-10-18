package com.rohit.share_scan

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import androidx.annotation.RequiresApi
import java.nio.ByteBuffer
import kotlin.concurrent.thread
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * Every theme's sky, behind everything on the screen: a Milky Way photographed on a dark night, about a
 * million stars in all, with the animated stars on top of it (stars that twinkle, stars that drift out from the
 * middle as if the screen were flying through space, and a shooting star every couple of seconds).
 *
 * The Milky Way is baked once per view size on a background thread (see [SkyBaker]) and then costs five bitmap
 * draws a frame: an opaque half-resolution base (the band, its warm core, dust lanes, nebulae and ~900,000 faint
 * stars as grain), a quarter-resolution light-blue glow around the bright stars, and three full-resolution layers
 * of crisp stars whose brightness swells and fades out of step, so the stars seem to twinkle one by one.
 * Every halo is light blue. The hero, the cards and the sheets are see-through glass, so it shows behind them too.
 * It redraws about 30 times a second while the screen is on show (15 in battery saver), and holds still only when
 * the system's animations are switched off or Settings turns [animate] off.
 */
class GalaxySkyView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val density = resources.displayMetrics.density
    private val random = Random(SEED)
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    /** Flat ends, so the see-through tail segments do not overlap and brighten at every joint. */
    private val tail = Paint(Paint.ANTI_ALIAS_FLAG)
    private val power = context.getSystemService(PowerManager::class.java)
    private val startedAt = SystemClock.uptimeMillis()
    /** Stars fade out towards the top, under the opaque status-bar strip, instead of being cut off by it. */
    private val topFade = 56 * density

    /** The picked theme's colours for the Milky Way; set it before the first layout (a later change re-bakes). */
    var palette: SkyPalette = SkyPalette.GALAXY
        set(value) {
            if (field === value) return
            field = value
            if (bakeWidth > 0 && bakeHeight > 0) bake(bakeWidth, bakeHeight)
        }

    /**
     * Settings' sky animation switch. Off holds the sky still: the Milky Way stays at full brightness and the
     * twinkling and drifting stars are drawn once where they are; no shooting stars.
     */
    var animate: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            updateAnimating()
        }

    // The baked Milky Way: null until the first bake for this size lands. Bitmaps are scaled up with filtering.
    private var sky: Sky? = null
    private var skyShownAt = 0L
    private val basePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val glowPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { color = STAR_GLOW }
    private val layerPaints = Array(STAR_LAYERS) { i -> Paint(Paint.FILTER_BITMAP_FLAG).apply { color = LAYER_TINTS[i] } }
    /** Bumped for each new bake (on the main thread); an older bake still running notices, stops and is dropped. */
    @Volatile private var bakeGeneration = 0
    private var bakeWidth = 0
    private var bakeHeight = 0
    private val mainThread = Handler(Looper.getMainLooper())

    // Twinkling stars, at fixed places given as fractions of the view; the first SPARKLERS also glint.
    private val twinkleX = FloatArray(TWINKLES) { random.nextFloat() }
    private val twinkleY = FloatArray(TWINKLES) { 0.06f + 0.94f * random.nextFloat() }
    private val twinkleRadius = FloatArray(TWINKLES) { i -> density * if (i < SPARKLERS) 1.6f + random.nextFloat() * 0.8f else 0.8f + random.nextFloat() * 1.0f }
    private val twinklePeriod = FloatArray(TWINKLES) { 1.2f + random.nextFloat() * 2.4f }
    private val twinklePhase = FloatArray(TWINKLES) { random.nextFloat() * TWO_PI }
    private val twinkleMax = FloatArray(TWINKLES) { i -> if (i < SPARKLERS) 1f else 0.7f + random.nextFloat() * 0.3f }
    private val twinkleColor = IntArray(TWINKLES) { STAR_COLORS[random.nextInt(STAR_COLORS.size)] }

    // Stars flying out from the vanishing point; each comes back from the middle on a new heading.
    private val flyAngle = FloatArray(FLYERS) { random.nextFloat() * TWO_PI }
    private val flyLife = FloatArray(FLYERS) { 6f + random.nextFloat() * 5f }
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
            val gap = if (power?.isPowerSaveMode == true) SAVER_FRAME_NANOS else MIN_FRAME_NANOS
            if (frameTimeNanos - lastFrameNanos >= gap) {
                lastFrameNanos = frameTimeNanos
                invalidate()
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    /** Notices "Remove animations" being switched while the screen is open (the listener runs off the main thread). */
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

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // A keyboard or a split screen that only shortens the view keeps the sky baked (or baking) for the taller one.
        if (w == bakeWidth && h <= bakeHeight) return
        bake(w, h)
    }

    /** Bakes the Milky Way for a [w] x [h] view off the main thread; the window background shows until it lands. */
    private fun bake(w: Int, h: Int) {
        val generation = ++bakeGeneration
        bakeWidth = w
        bakeHeight = h
        if (w <= 0 || h <= 0) return
        val density = density
        val palette = palette
        thread(name = "GalaxySkyBake") {
            val baked = SkyBaker(w, h, density, palette) { generation != bakeGeneration }.bake() ?: return@thread
            mainThread.post {
                if (generation == bakeGeneration) {
                    sky = baked
                    skyShownAt = SystemClock.uptimeMillis()
                    invalidate()
                }
            }
        }
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
        // It keeps moving under the glass sheets and in battery saver (at half the frame rate); only "Remove
        // animations" or the Settings switch ([animate]) stills it.
        val run = animate && isAttachedToWindow && windowVisibility == VISIBLE && isShown &&
            ValueAnimator.areAnimatorsEnabled()
        if (run == animating) return
        if (run) {
            animating = true
            // No shooting star the instant the screen comes back; the next one follows after a short pause.
            nextMeteorAt = SystemClock.uptimeMillis() + FIRST_METEOR_MS
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
        // The frame's own time rather than the moment onDraw runs, so motion stays even from frame to frame.
        val now = drawingTime
        // Double: a Float of seconds loses whole frames of precision once a screen has been open for a day.
        val seconds = (now - startedAt) / 1000.0
        drawMilkyWay(canvas, now, seconds)
        drawFlyers(canvas, w, h, seconds)
        drawTwinkles(canvas, w, h, seconds)
        if (animating) drawMeteors(canvas, w, h, now)
    }

    /** The baked sky: five bitmap draws, each layer of crisp stars breathing on its own beat. */
    private fun drawMilkyWay(canvas: Canvas, now: Long, seconds: Double) {
        val sky = sky ?: return
        // Eases in over the plain window background instead of popping in when the bake lands.
        val shown = if (animating) ((now - skyShownAt) / SKY_FADE_IN_MS).coerceIn(0f, 1f) else 1f
        basePaint.alpha = (255 * shown).toInt()
        canvas.drawBitmap(sky.base, null, sky.baseRect, basePaint)
        glowPaint.alpha = (255 * shown * (0.85f + 0.15f * beat(seconds, GLOW_PERIOD, 0f))).toInt()
        canvas.drawBitmap(sky.glow, null, sky.glowRect, glowPaint)
        for (i in sky.stars.indices) {
            val wave = beat(seconds, LAYER_PERIODS[i], i * 2.1f)
            layerPaints[i].alpha = (255 * shown * (LAYER_DIMMEST + (1f - LAYER_DIMMEST) * wave)).toInt()
            canvas.drawBitmap(sky.stars[i], null, sky.starRect, layerPaints[i])
        }
    }

    /** 0..1 and back over each [period] seconds, smoothly. */
    private fun beat(seconds: Double, period: Float, phase: Float): Float =
        0.5f + 0.5f * sin(TWO_PI * ((seconds / period) % 1.0).toFloat() + phase)

    private fun drawFlyers(canvas: Canvas, w: Float, h: Float, seconds: Double) {
        val centreX = w * 0.5f
        val centreY = h * 0.42f
        val reach = hypot(w, h) * 0.62f
        for (i in 0 until FLYERS) {
            val cycles = seconds / flyLife[i] + flyOffset[i]
            val cycle = floor(cycles)
            val progress = (cycles - cycle).toFloat()
            val angle = flyAngle[i] + (cycle % TURNS_BEFORE_REPEAT).toFloat() * GOLDEN_ANGLE
            val dx = cos(angle)
            val dy = sin(angle)
            // Slow near the middle, quicker towards the edge, and bigger as it comes closer.
            val distance = reach * progress * progress
            val x = centreX + dx * distance
            val y = centreY + dy * distance
            val fade = FLYER_BRIGHTNESS * min(1f, progress / 0.15f) * min(1f, (1f - progress) / 0.1f) * fadeNearTop(y)
            val radius = density * (0.5f + 2.0f * progress)
            val alpha = (255 * fade).toInt()
            if (progress > 0.35f) {
                // Motion blur: a few flat-ended pieces, each narrower and fainter, so it tapers away to nothing.
                val streak = density * 40f * (progress - 0.35f)
                tail.color = STAR_GLOW
                for (s in 0 until STREAK_SEGMENTS) {
                    val from = s.toFloat() / STREAK_SEGMENTS
                    val to = (s + 1).toFloat() / STREAK_SEGMENTS
                    val strength = 1f - from
                    tail.alpha = (alpha * 0.4f * strength * strength).toInt()
                    tail.strokeWidth = radius * 1.4f * strength
                    canvas.drawLine(x - dx * streak * from, y - dy * streak * from, x - dx * streak * to, y - dy * streak * to, tail)
                }
            }
            if (progress > 0.4f) glow(canvas, x, y, radius, alpha / 4)
            dot.color = flyColor[i]
            dot.alpha = alpha
            canvas.drawCircle(x, y, radius, dot)
        }
    }

    private fun drawTwinkles(canvas: Canvas, w: Float, h: Float, seconds: Double) {
        for (i in 0 until TWINKLES) {
            val turn = ((seconds / twinklePeriod[i]) % 1.0).toFloat()
            val wave = 0.5f + 0.5f * sin(TWO_PI * turn + twinklePhase[i])
            val x = twinkleX[i] * w
            val y = twinkleY[i] * h
            // Nearly out at the bottom of each beat, swelling to full brightness and size at the top.
            val brightness = twinkleMax[i] * (0.04f + 0.96f * wave * wave) * fadeNearTop(y)
            val radius = twinkleRadius[i] * (0.6f + 0.6f * wave)
            if (wave > 0.5f) glow(canvas, x, y, radius, (150 * brightness * (wave - 0.5f) * 2f).toInt())
            if (i < SPARKLERS && wave > 0.6f) {
                // Thin diffraction spikes: a faint full-length cross with a brighter one half as long over it,
                // so each spike fades out towards its tip.
                val arm = twinkleRadius[i] * 10f * (wave - 0.6f)
                val spike = (90 * brightness * (wave - 0.6f) / 0.4f).toInt()
                tail.color = STAR_GLOW
                tail.strokeWidth = max(1f, 0.5f * density)
                tail.alpha = spike / 2
                canvas.drawLine(x - arm, y, x + arm, y, tail)
                canvas.drawLine(x, y - arm, x, y + arm, tail)
                tail.alpha = spike
                canvas.drawLine(x - arm / 2, y, x + arm / 2, y, tail)
                canvas.drawLine(x, y - arm / 2, x, y + arm / 2, tail)
            }
            dot.color = twinkleColor[i]
            dot.alpha = (255 * brightness).toInt()
            canvas.drawCircle(x, y, radius, dot)
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
            val fade = min(1f, progress / 0.12f) * min(1f, (1f - progress) / 0.3f) * fadeNearTop(headY)
            val length = meteorTail[m] * min(1f, progress / 0.3f)
            for (s in 0 until TAIL_SEGMENTS) {
                val from = s.toFloat() / TAIL_SEGMENTS
                val to = (s + 1).toFloat() / TAIL_SEGMENTS
                val strength = 1f - from
                tail.color = METEOR_TAIL
                tail.alpha = (255 * fade * strength * strength).toInt()
                tail.strokeWidth = density * 3.2f * strength
                canvas.drawLine(
                    headX - meteorDx[m] * length * from, headY - meteorDy[m] * length * from,
                    headX - meteorDx[m] * length * to, headY - meteorDy[m] * length * to,
                    tail,
                )
            }
            dot.color = STAR_GLOW
            dot.alpha = (60 * fade).toInt()
            canvas.drawCircle(headX, headY, density * 11f, dot)
            dot.alpha = (130 * fade).toInt()
            canvas.drawCircle(headX, headY, density * 5.5f, dot)
            dot.color = METEOR_HEAD
            dot.alpha = (255 * fade).toInt()
            canvas.drawCircle(headX, headY, density * 2.4f, dot)
        }
    }

    /** A soft light-blue halo: two faint rings stacked so the light falls off instead of ending in an edge. */
    private fun glow(canvas: Canvas, x: Float, y: Float, radius: Float, alpha: Int) {
        dot.color = STAR_GLOW
        dot.alpha = alpha / 3
        canvas.drawCircle(x, y, radius * 2.8f, dot)
        dot.alpha = alpha / 2
        canvas.drawCircle(x, y, radius * 1.8f, dot)
    }

    private fun fadeNearTop(y: Float): Float = (y / topFade).coerceIn(0f, 1f)

    /** Starts a shooting star in a free slot, from anywhere in the top two thirds of the sky down to the lower left. */
    private fun launchMeteor(w: Float, h: Float, now: Long) {
        var m = 0
        while (m < METEORS && meteorLive[m]) m++
        if (m == METEORS) return
        val angle = (130f + random.nextFloat() * 30f) * DEGREES
        meteorLive[m] = true
        meteorStart[m] = now
        meteorDuration[m] = random.nextLong(1_000L, 1_600L)
        meteorX[m] = w * (0.3f + random.nextFloat() * 0.85f)
        meteorY[m] = h * (0.04f + random.nextFloat() * 0.62f)
        meteorDx[m] = cos(angle)
        meteorDy[m] = sin(angle)
        meteorTravel[m] = w * (0.8f + random.nextFloat() * 0.5f)
        meteorTail[m] = density * (110f + random.nextFloat() * 90f)
    }

    private companion object {
        const val SEED = 20261018
        const val TWINKLES = 90
        const val SPARKLERS = 18
        const val FLYERS = 40
        const val METEORS = 4
        const val TAIL_SEGMENTS = 10
        const val STREAK_SEGMENTS = 4
        /** The flyers stay a quiet extra over the Milky Way rather than the main show. */
        const val FLYER_BRIGHTNESS = 0.75f
        const val FIRST_METEOR_MS = 800L
        const val METEOR_GAP_MIN_MS = 1_200L
        const val METEOR_GAP_MAX_MS = 3_500L
        /** About 30 frames a second, evenly paced on 60, 90 and 120 Hz screens. */
        const val MIN_FRAME_NANOS = 30_000_000L
        /** About 15 frames a second in battery saver. */
        const val SAVER_FRAME_NANOS = 62_000_000L
        const val TWO_PI = 6.2831855f
        const val DEGREES = 0.017453292f
        /** Turning each returning star by the golden angle keeps its new path away from its last one. */
        const val GOLDEN_ANGLE = 2.3999631f
        /** Keeps the turn count small so the heading stays precise as Float; the pattern never visibly repeats. */
        const val TURNS_BEFORE_REPEAT = 997.0
        const val SKY_FADE_IN_MS = 700f
        /** Seconds per beat of each layer of crisp stars, and of the glow; unrelated, so they never line up. */
        val LAYER_PERIODS = floatArrayOf(2.3f, 3.1f, 3.7f)
        const val GLOW_PERIOD = 5.3f
        /** How far a layer of crisp stars dims at the bottom of its beat. */
        const val LAYER_DIMMEST = 0.45f
        /** Every halo, glint, streak and meteor glow: light blue. */
        const val STAR_GLOW = 0xFF7DD3FC.toInt()
        const val METEOR_TAIL = 0xFFE0F2FE.toInt()
        const val METEOR_HEAD = 0xFFFFFFFF.toInt()
        /** White and blue-white cores; the light blue is in their halos. */
        val STAR_COLORS = intArrayOf(
            0xFFFFFFFF.toInt(), 0xFFF0F9FF.toInt(), 0xFFE0F2FE.toInt(), 0xFFDBEAFE.toInt(), 0xFFBAE6FD.toInt(),
        )
        /** The crisp star layers' tints: blue-white, light blue and warm white, like stars of different heat. */
        val LAYER_TINTS = intArrayOf(0xFFDCE9FF.toInt(), 0xFFA9DCFF.toInt(), 0xFFFFF0DA.toInt())
        val STAR_LAYERS = LAYER_TINTS.size
    }
}

/** One bake, for one view size. The rects scale each bitmap back up to that size. */
private class Sky(
    val base: Bitmap,
    val glow: Bitmap,
    val stars: Array<Bitmap>,
    val baseRect: RectF,
    val glowRect: RectF,
    val starRect: RectF,
)

/**
 * Paints the Milky Way for a [w] x [h] view, once, on a background thread: pixels are filled straight into arrays
 * (no per-star Canvas calls) and copied into immutable-in-practice bitmaps. Fixed seed, so it is the same sky on
 * every launch.
 *
 * The band runs from the lower left to the upper right with its warm core in the upper middle. Its light is
 * shaped by layered noise in coordinates along and across the band, so the star clouds, the dark dust lanes and
 * the rift down its middle are stretched along it as in a real photo. Pink hydrogen, blue reflection and teal
 * nebulae sit on and beside it. Star density follows the band and thins behind the dust, and brightness follows
 * a power law: mostly faint dust, fewer bright stars, a few hundred big ones that get a light-blue glow.
 *
 * Memory, about 10.5 MB on a 1080 x 2400 screen: the base at half resolution (ARGB, 2.6 MB), three star layers at
 * full resolution (ALPHA_8, 2.6 MB each) and the glow at quarter resolution (0.16 MB). Bigger screens drop to two
 * star layers, or to half-resolution star layers, to stay under [MEMORY_BUDGET]; so does running out of memory.
 */
private class SkyBaker(
    private val w: Int,
    private val h: Int,
    private val density: Float,
    private val palette: SkyPalette,
    private val stale: () -> Boolean,
) {
    private val random = Random(SKY_SEED)
    /** Ken Perlin's permutation table, shuffled by the seed and doubled so lookups never wrap. */
    private val perm = IntArray(512).also { table ->
        val order = IntArray(256) { it }
        order.shuffle(random)
        for (i in 0 until 512) table[i] = order[i and 255]
    }

    // The band's centre line, in view pixels, and the core's place on it. Distances are in units of the view's
    // shorter side, so the band looks the same in portrait and landscape.
    private val unit = min(w, h).toFloat()
    private val alongX: Float
    private val alongY: Float
    private val coreX: Float
    private val coreY: Float
    private val topFade = 56 * density

    init {
        val startX = -0.08f * w
        val startY = 0.94f * h
        val endX = 1.08f * w
        val endY = 0.08f * h
        val length = hypot(endX - startX, endY - startY)
        alongX = (endX - startX) / length
        alongY = (endY - startY) / length
        coreX = startX + (endX - startX) * CORE_AT
        coreY = startY + (endY - startY) * CORE_AT
    }

    // What field() found at the last point it was asked about.
    private var lightR = 0f
    private var lightG = 0f
    private var lightB = 0f
    private var starDensity = 0f
    private var warmth = 0f

    fun bake(): Sky? = try {
        bakeOrThrow()
    } catch (e: OutOfMemoryError) {
        // Not even the base fits: the plain window background shows, with the animated stars over it.
        null
    }

    private fun bakeOrThrow(): Sky? {
        val bw = (w + 1) / 2
        val bh = (h + 1) / 2
        val pixels = IntArray(bw * bh)
        val crowd = FloatArray(bw * bh)
        if (!paintBase(bw, bh, pixels, crowd)) return null
        val base = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        base.setPixels(pixels, 0, bw, 0, 0, bw, bh)
        base.setHasAlpha(false)
        base.prepareToDraw()

        val gw = (w + 3) / 4
        val gh = (h + 3) / 4
        val glowBytes = ByteArray(gw * gh)
        val fixedBytes = 4L * bw * bh + gw.toLong() * gh
        for (plan in PLANS) {
            val scale = plan[1]
            val sw = (w + scale - 1) / scale
            val sh = (h + scale - 1) / scale
            if (fixedBytes + plan[0].toLong() * sw * sh > MEMORY_BUDGET) continue
            try {
                glowBytes.fill(0)
                val stars = paintStars(plan[0], scale, sw, sh, bw, bh, crowd, glowBytes, gw, gh) ?: return null
                val glow = alphaBitmap(glowBytes, gw, gh, gw)
                val full = RectF(0f, 0f, sw.toFloat() * scale, sh.toFloat() * scale)
                return Sky(base, glow, stars, RectF(0f, 0f, bw * 2f, bh * 2f), RectF(0f, 0f, gw * 4f, gh * 4f), full)
            } catch (e: OutOfMemoryError) {
                // Try the next, smaller plan.
            }
        }
        return null
    }

    /**
     * The base: deep space, the band's glow, the core, dust and nebulae, plus ~[FAINT_STARS] faint stars summed
     * into each pixel as grain. Also leaves each pixel's relative star density in [crowd] for the crisp stars.
     */
    private fun paintBase(bw: Int, bh: Int, pixels: IntArray, crowd: FloatArray): Boolean {
        // The smooth light is worked out on a coarser grid, one point every GRID view pixels, and blended between
        // points; only the stars are drawn pixel by pixel.
        val gw = w / GRID + 2
        val gh = h / GRID + 2
        val gridR = FloatArray(gw * gh)
        val gridG = FloatArray(gw * gh)
        val gridB = FloatArray(gw * gh)
        val gridCrowd = FloatArray(gw * gh)
        val gridWarmth = FloatArray(gw * gh)
        var total = 0f
        for (gy in 0 until gh) {
            if (gy % 32 == 0 && stale()) return false
            for (gx in 0 until gw) {
                field(gx * GRID.toFloat(), gy * GRID.toFloat())
                val g = gy * gw + gx
                gridR[g] = lightR
                gridG[g] = lightG
                gridB[g] = lightB
                gridCrowd[g] = starDensity
                gridWarmth[g] = warmth
                total += starDensity
            }
        }
        // So the faint stars come to about FAINT_STARS in all. Poisson sampling stops at e^-(expected count).
        val perUnit = FAINT_STARS / (total / (gw * gh) * bw * bh)
        val gridLimit = FloatArray(gw * gh) { exp(-perUnit * gridCrowd[it]) }
        // The faint stars' brightness, from a power law: the flux at each of FLUX_STEPS evenly spaced quantiles.
        val fluxes = FloatArray(FLUX_STEPS) { FAINT_FLUX * ((it + 0.5f) / FLUX_STEPS).coerceAtLeast(0.002f).pow(-1f / FAINT_SLOPE) }
        for (y in 0 until bh) {
            if (y % 64 == 0 && stale()) return false
            val sy = y * 2f + 1f
            val fade = smoothstep(0f, topFade, sy)
            val down = sy / h
            val spaceR = palette.spaceTop[0] + (palette.spaceBottom[0] - palette.spaceTop[0]) * down
            val spaceG = palette.spaceTop[1] + (palette.spaceBottom[1] - palette.spaceTop[1]) * down
            val spaceB = palette.spaceTop[2] + (palette.spaceBottom[2] - palette.spaceTop[2]) * down
            val gyf = sy / GRID
            val row = gyf.toInt()
            val fy = gyf - row
            for (x in 0 until bw) {
                val gxf = (x * 2f + 1f) / GRID
                val col = gxf.toInt()
                val fx = gxf - col
                val g = row * gw + col
                // Bilinear weights of the four grid points around this pixel.
                val w00 = (1f - fx) * (1f - fy)
                val w10 = fx * (1f - fy)
                val w01 = (1f - fx) * fy
                val w11 = fx * fy
                var r = w00 * gridR[g] + w10 * gridR[g + 1] + w01 * gridR[g + gw] + w11 * gridR[g + gw + 1]
                var gr = w00 * gridG[g] + w10 * gridG[g + 1] + w01 * gridG[g + gw] + w11 * gridG[g + gw + 1]
                var b = w00 * gridB[g] + w10 * gridB[g + 1] + w01 * gridB[g + gw] + w11 * gridB[g + gw + 1]
                val here = w00 * gridCrowd[g] + w10 * gridCrowd[g + 1] + w01 * gridCrowd[g + gw] + w11 * gridCrowd[g + gw + 1]
                val warm = w00 * gridWarmth[g] + w10 * gridWarmth[g + 1] + w01 * gridWarmth[g + gw] + w11 * gridWarmth[g + gw + 1]
                val limit = w00 * gridLimit[g] + w10 * gridLimit[g + 1] + w01 * gridLimit[g + gw] + w11 * gridLimit[g + gw + 1]
                val i = y * bw + x
                crowd[i] = here * fade
                // The unresolved stars: a Poisson number of them in this pixel, each from a power law.
                var chance = random.nextFloat()
                while (chance > limit) {
                    val flux = fluxes[random.nextInt(FLUX_STEPS)]
                    val kind = random.nextFloat() - warm * 0.3f
                    when {
                        kind < 0.06f -> { r += flux * 1.0f; gr += flux * 0.78f; b += flux * 0.58f }
                        kind < 0.30f -> { r += flux * 1.0f; gr += flux * 0.90f; b += flux * 0.76f }
                        kind < 0.70f -> { r += flux * 0.98f; gr += flux * 0.97f; b += flux * 0.95f }
                        else -> { r += flux * 0.80f; gr += flux * 0.88f; b += flux * 1.0f }
                    }
                    chance *= random.nextFloat()
                }
                // A soft shoulder instead of clipping, then the dark sky under it and a touch of dither.
                val dither = random.nextFloat() - 0.5f
                val outR = (255f * (spaceR + fade * r / (1f + r)) + dither).toInt().coerceIn(0, 255)
                val outG = (255f * (spaceG + fade * gr / (1f + gr)) + dither).toInt().coerceIn(0, 255)
                val outB = (255f * (spaceB + fade * b / (1f + b)) + dither).toInt().coerceIn(0, 255)
                pixels[i] = (0xFF shl 24) or (outR shl 16) or (outG shl 8) or outB
            }
        }
        return true
    }

    /**
     * The Milky Way's light and star density at view pixel ([x], [y]), into [lightR], [lightG], [lightB],
     * [starDensity] and [warmth]. u runs along the band from the core, v across it.
     */
    private fun field(x: Float, y: Float) {
        val px = (x - coreX) / unit
        val py = (y - coreY) / unit
        val u = px * alongX + py * alongY
        // A slight arch, and edges that wander instead of running ruler-straight.
        val v = -px * alongY + py * alongX + BAND_ARCH * u * u + 0.05f * noise(u * 1.4f + 3.1f, 0.7f)
        val across = v * v
        val band = exp(-across / (2 * BAND_WIDTH * BAND_WIDTH))
        val halo = exp(-across / (2 * HALO_WIDTH * HALO_WIDTH))
        val along = 0.4f + 0.6f * exp(-u * u / (2 * 0.8f * 0.8f))
        val bulge = exp(-(u * u / (2 * 0.2f * 0.2f) + across / (2 * 0.13f * 0.13f)))
        // Star clouds: lumpy, stretched along the band.
        val clouds = (0.5f + 1.6f * fbm(u * 3f, v * 6f, 4)).coerceIn(0.05f, 1.4f)
        // Dust: warped noise makes ragged filaments; a rift wanders down the middle of the band.
        val warpX = fbm(u * 2f + 11f, v * 5f, 2)
        val warpY = fbm(u * 2f - 7f, v * 5f + 3f, 2)
        val dust = fbm(u * 3.5f + 1.8f * warpX, v * 9f + 1.8f * warpY, 4)
        val riftCentre = 0.02f + 0.06f * noise(u * 1.5f + 31.5f, 0.5f)
        val riftWidth = 0.022f + 0.03f * (0.5f + noise(u * 2.3f + 17.5f, 3.3f)).coerceIn(0f, 1f)
        val off = (v - riftCentre) / riftWidth
        val rift = exp(-0.5f * off * off) * smoothstep(-0.25f, 0.2f, dust + 0.12f * noise(u * 9f, v * 9f))
        val patches = smoothstep(0f, 0.28f, dust) * exp(-across / (2 * 0.22f * 0.22f))
        val clear = (1f - 0.94f * rift) * (1f - 0.88f * patches)

        // The palette's arm colour out along the arms, its core colour towards the core.
        warmth = (bulge + 0.6f * exp(-u * u / (2 * 0.45f * 0.45f)) * band).coerceIn(0f, 1f)
        val glow = palette.brightness * (along * (BAND_GLOW * band * (0.25f + 0.75f * clouds) + HALO_GLOW * halo) +
            CORE_GLOW * bulge * (0.75f + 0.35f * clouds))
        // Dust dims blue light more than red, so what shows through it turns brown.
        val clearR = clear
        val clearG = clear * (0.75f + 0.25f * clear)
        val clearB = clear * (0.5f + 0.5f * clear)
        val arm = palette.arm
        val core = palette.core
        lightR = glow * (arm[0] + (core[0] - arm[0]) * warmth) * clearR
        lightG = glow * (arm[1] + (core[1] - arm[1]) * warmth) * clearG
        lightB = glow * (arm[2] + (core[2] - arm[2]) * warmth) * clearB
        starDensity = (0.22f + 2.8f * band * clouds * along + 2.5f * bulge) * clear

        // Nebulae, each a soft patch broken up by wisps; only near one is the extra noise worked out.
        var wisps = -1f
        val nebulae = palette.nebulae
        for (n in nebulae.indices step 7) {
            val du = u - nebulae[n]
            val dv = v - nebulae[n + 1]
            val size = nebulae[n + 2]
            val d = (du * du + dv * dv) / (size * size)
            if (d > 9f) continue
            if (wisps < 0f) wisps = (0.45f + 1.8f * fbm(u * 7f + 40f, v * 7f - 20f, 4)).coerceIn(0f, 1.5f)
            val strength = nebulae[n + 6] * exp(-0.5f * d) * wisps * (0.4f + 0.6f * clear)
            lightR += strength * nebulae[n + 3]
            lightG += strength * nebulae[n + 4]
            lightB += strength * nebulae[n + 5]
        }
        // Faint gas glow scattered along the band, in the palette's colour.
        val gas = palette.gas
        val glowing = smoothstep(0.08f, 0.35f, fbm(u * 2.4f + 50f, v * 4f, 3)) * band * along * gas[3] * clear
        lightR += glowing * gas[0]
        lightG += glowing * gas[1]
        lightB += glowing * gas[2]
        if (palette.aurora > 0f) addAurora(x, y)
    }

    /**
     * Aurora curtains low in the sky: a wavy green hem with rays rising from it, turning teal and then violet as
     * they fade upwards, and a second, fainter curtain folded behind the first.
     */
    private fun addAurora(x: Float, y: Float) {
        var total = 0f
        var topness = 0f
        val nx = x / unit
        for (curtain in 0..1) {
            val s = curtain * 37f
            // The hem: a slow wave plus a wander, higher for the curtain behind.
            val hem = h * (AURORA_HEM - 0.1f * curtain) +
                unit * (0.09f * sin(nx * 5.5f + s) + 0.05f * sin(nx * 11f + 2f * s) + 0.06f * noise(nx * 3f + s, 2.3f))
            val above = (hem - y) / (unit * AURORA_HEIGHT)
            if (above < -0.3f || above > 1.6f) continue
            // Soft rays across x, brighter and dimmer patches along the curtain, and a gentle fold.
            val rays = (0.55f + 0.9f * fbm(nx * 13f + s, 0.4f * curtain, 3)).coerceIn(0.15f, 1.3f)
            val patches = 0.3f + 0.7f * smoothstep(-0.25f, 0.35f, fbm(nx * 2.1f + s, 1.3f + curtain, 2))
            // Brightest just above the hem, a quick fall-off below it, a long soft fade up the rays.
            val profile = if (above < 0f) exp(-(above * above) / (2 * 0.07f * 0.07f))
            else exp(-above * 1.7f) * (1f - smoothstep(0.55f, 1.5f, above))
            val strength = palette.aurora * profile * rays * patches * (1f - 0.45f * curtain)
            total += strength
            topness += strength * above.coerceIn(0f, 1f)
        }
        if (total <= 0f) return
        // Green at the hem, teal above it, violet at the top of the rays.
        val t = (topness / total).coerceIn(0f, 1f)
        lightR += total * (0.16f + 0.6f * smoothstep(0.4f, 1f, t))
        lightG += total * (1.0f - 0.7f * t)
        lightB += total * (0.42f + 0.55f * t)
        starDensity *= 1f - 0.25f * min(1f, total)
    }

    /**
     * The crisp stars, [CRISP_STARS] of them split between [layers] bitmaps at 1/[scale] resolution, placed by
     * [crowd]; the brightest also get a soft blob in the glow. Null if a newer bake has taken over.
     */
    private fun paintStars(
        layers: Int, scale: Int, sw: Int, sh: Int, bw: Int, bh: Int,
        crowd: FloatArray, glowBytes: ByteArray, gw: Int, gh: Int,
    ): Array<Bitmap>? {
        var most = 0f
        for (c in crowd) most = max(most, c)
        if (most <= 0f) most = 1f
        val stars = arrayOfNulls<Bitmap>(layers)
        val bitmap = Bitmap.createBitmap(sw, sh, Bitmap.Config.ALPHA_8)
        val stride = bitmap.rowBytes
        val bytes = ByteArray(stride * sh)
        for (layer in 0 until layers) {
            if (stale()) return null
            val target = if (layer == 0) bitmap else Bitmap.createBitmap(sw, sh, Bitmap.Config.ALPHA_8)
            if (layer > 0) bytes.fill(0)
            var placed = 0
            while (placed < CRISP_STARS / layers) {
                // Rejection sampling: anywhere on the screen, kept as often as the sky is crowded there.
                val cell = random.nextInt(bw * bh)
                if (random.nextFloat() * most > crowd[cell]) continue
                placed++
                val x = ((cell % bw) + random.nextFloat()) * 2f
                val y = ((cell / bw) + random.nextFloat()) * 2f
                val fade = smoothstep(0f, topFade, y)
                val magnitude = min(CRISP_BRIGHTEST, random.nextFloat().coerceAtLeast(1e-6f).pow(-1f / CRISP_SLOPE))
                val sigma = (0.42f + 0.14f * ln(magnitude)) / scale
                stamp(bytes, stride, sw, sh, x / scale, y / scale, sigma, 0.2f * magnitude.pow(0.6f) * fade)
                if (magnitude > GLOW_FROM) {
                    val strength = ln(magnitude / GLOW_FROM)
                    val reach = density * (1.1f + 0.9f * strength) / 4f
                    stamp(glowBytes, gw, gw, gh, x / 4f, y / 4f, reach, (0.16f + 0.1f * strength).coerceAtMost(0.6f) * fade)
                }
            }
            target.copyPixelsFromBuffer(ByteBuffer.wrap(bytes))
            target.prepareToDraw()
            stars[layer] = target
        }
        return Array(layers) { stars[it]!! }
    }

    private fun alphaBitmap(bytes: ByteArray, width: Int, height: Int, stride: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
        // ALPHA_8 rows may be padded; repack if the bitmap's stride differs from the array's.
        val rows = if (bitmap.rowBytes == stride) bytes else ByteArray(bitmap.rowBytes * height).also { out ->
            for (y in 0 until height) System.arraycopy(bytes, y * stride, out, y * bitmap.rowBytes, width)
        }
        bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(rows))
        bitmap.prepareToDraw()
        return bitmap
    }

    /** Adds a round Gaussian spot of peak [amount] (1 = opaque) at ([cx], [cy]), saturating at opaque. */
    private fun stamp(bytes: ByteArray, stride: Int, width: Int, height: Int, cx: Float, cy: Float, sigma: Float, amount: Float) {
        if (amount <= 0f) return
        val reach = sigma * 2.8f
        val x0 = max(0, (cx - reach).toInt())
        val x1 = min(width - 1, (cx + reach).toInt())
        val y0 = max(0, (cy - reach).toInt())
        val y1 = min(height - 1, (cy + reach).toInt())
        // Index into FALLOFF: GAUSS_STEPS per unit of (distance / sigma)^2 / 2.
        val spread = GAUSS_STEPS * 0.5f / (sigma * sigma)
        val peak = 255f * amount
        for (y in y0..y1) {
            val dy = y + 0.5f - cy
            for (x in x0..x1) {
                val dx = x + 0.5f - cx
                val step = ((dx * dx + dy * dy) * spread).toInt()
                if (step >= FALLOFF.size) continue
                val add = (peak * FALLOFF[step]).toInt()
                if (add <= 0) continue
                val i = y * stride + x
                bytes[i] = min(255, (bytes[i].toInt() and 255) + add).toByte()
            }
        }
    }

    /** 2D gradient noise, about -0.7..0.7. */
    private fun noise(x: Float, y: Float): Float {
        val fx = floor(x)
        val fy = floor(y)
        val xi = fx.toInt() and 255
        val yi = fy.toInt() and 255
        val xf = x - fx
        val yf = y - fy
        val sx = xf * xf * xf * (xf * (xf * 6f - 15f) + 10f)
        val sy = yf * yf * yf * (yf * (yf * 6f - 15f) + 10f)
        val a = perm[xi] + yi
        val b = perm[xi + 1] + yi
        val n00 = grad(perm[a], xf, yf)
        val n10 = grad(perm[b], xf - 1f, yf)
        val n01 = grad(perm[a + 1], xf, yf - 1f)
        val n11 = grad(perm[b + 1], xf - 1f, yf - 1f)
        val low = n00 + sx * (n10 - n00)
        val high = n01 + sx * (n11 - n01)
        return low + sy * (high - low)
    }

    private fun grad(hash: Int, x: Float, y: Float): Float = when (hash and 7) {
        0 -> x + y
        1 -> y - x
        2 -> x - y
        3 -> -x - y
        4 -> x
        5 -> -x
        6 -> y
        else -> -y
    }

    /** [octaves] of [noise], each twice as fine and half as strong, turned so the lattice never lines up. */
    private fun fbm(x: Float, y: Float, octaves: Int): Float {
        var sum = 0f
        var strength = 0.5f
        var fx = x
        var fy = y
        for (o in 0 until octaves) {
            sum += strength * noise(fx, fy)
            val nx = fx * 1.6f - fy * 1.2f + 5.3f
            fy = fx * 1.2f + fy * 1.6f + 1.7f
            fx = nx
            strength *= 0.5f
        }
        return sum
    }

    private fun smoothstep(from: Float, to: Float, x: Float): Float {
        val t = ((x - from) / (to - from)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private companion object {
        const val SKY_SEED = 1_000_003
        /** View pixels between the points where the Milky Way's smooth light is worked out. */
        const val GRID = 6
        const val FAINT_STARS = 900_000
        const val CRISP_STARS = 100_000
        /** Extra native memory the bitmaps may take. */
        const val MEMORY_BUDGET = 16L * 1024 * 1024
        /** Star layer count and down-scale, best first. */
        val PLANS = arrayOf(intArrayOf(3, 1), intArrayOf(2, 1), intArrayOf(3, 2))
        /** How far along the band, from the lower left, its core sits. */
        const val CORE_AT = 0.64f
        const val BAND_ARCH = 0.05f
        const val BAND_WIDTH = 0.14f
        const val HALO_WIDTH = 0.42f
        const val BAND_GLOW = 0.2f
        const val HALO_GLOW = 0.028f
        const val CORE_GLOW = 1.25f
        const val FAINT_FLUX = 0.025f
        const val FAINT_SLOPE = 1.8f
        const val FLUX_STEPS = 1024
        /** e^-t for t from 0 to 4 (as far as a star's spot reaches), GAUSS_STEPS steps per unit. */
        const val GAUSS_STEPS = 64
        val FALLOFF = FloatArray(4 * GAUSS_STEPS) { exp(-(it + 0.5f) / GAUSS_STEPS) }
        /** Where the aurora's hem sits, as a fraction of the height, and how far its rays reach (shorter-side units). */
        const val AURORA_HEM = 0.86f
        const val AURORA_HEIGHT = 0.42f
        const val CRISP_SLOPE = 1.6f
        const val CRISP_BRIGHTEST = 400f
        /** Crisp stars brighter than this many times the faintest get a light-blue glow. */
        const val GLOW_FROM = 45f
    }
}
