package com.rohit.share_scan

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import androidx.core.view.ViewCompat
import com.google.android.material.slider.RangeSlider
import kotlin.math.roundToInt

/**
 * The filter sheet's min/max slider in the Filters chip's glass: a glass channel, the picked range as a checked chip's
 * accent glass and two glass beads for the thumbs, in the picked glass mode. RangeSlider still does the touch, values,
 * labels and accessibility; its own track and thumbs are made invisible, and only its press halo still shows.
 * [wearFlat] swaps the glass for the compact list's flat look.
 */
class GlassRangeSlider @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.sliderStyle,
) : RangeSlider(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private var channel: Drawable = context.chipGlass(radiusPx = null)
    private var range: Drawable = context.chipGlass(radiusPx = null, on = true)
    private var bead: Drawable =
        context.chipGlass(radiusPx = null, tintBoost = 1.4f, on = true, rimBoost = 3f, surface = BEAD_SURFACE)
    private var rangeInset = RANGE_INSET_DP

    init {
        val clear = ColorStateList.valueOf(Color.TRANSPARENT)
        setTrackActiveTintList(clear)
        setTrackInactiveTintList(clear)
        setTickActiveTintList(clear)
        setTickInactiveTintList(clear)
        setThumbTintList(clear)
        setThumbStrokeColor(clear)
        setThumbElevation(0f)
        // No gap: with one, RangeSlider narrows a pressed thumb, and the gap and end dots would draw nothing anyway.
        setThumbTrackGapSize(0)
        setTrackStopIndicatorSize(0)
        // The channel is the track and the beads are the thumbs, so RangeSlider leaves room for them at the ends.
        setTrackHeight(dp(CHANNEL_DP))
        setThumbWidth(dp(BEAD_DP))
        setThumbHeight(dp(BEAD_DP))
        setHaloRadius(dp(BEAD_DP / 2 + HALO_DP))
        setHaloTintList(ColorStateList.valueOf(HALO))
        addOnChangeListener { _, _, _ -> keepBeadsFromBackGesture() }
    }

    /**
     * The compact list's flat look: a thin grey track, the picked range in the accent, and white beads ringed in it.
     */
    fun wearFlat() {
        val accent = context.flatAccent()
        channel = context.flatCapsule(Color.WHITE.withAlpha(0.1f), Color.TRANSPARENT, 0f)
        range = context.flatCapsule(accent, Color.TRANSPARENT, 0f)
        bead = context.flatCapsule(Color.WHITE, accent, FLAT_BEAD_RIM_DP)
        rangeInset = 0f
        setTrackHeight(dp(FLAT_TRACK_DP))
        setThumbWidth(dp(FLAT_BEAD_DP))
        setThumbHeight(dp(FLAT_BEAD_DP))
        setHaloRadius(dp(FLAT_BEAD_DP / 2 + HALO_DP))
        setHaloTintList(ColorStateList.valueOf(accent.withAlpha(0.2f)))
        invalidate()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        keepBeadsFromBackGesture()
    }

    /**
     * The beads at the ends sit near the screen's edges, where a sideways drag would be taken for the system's back
     * gesture and close the sheet instead of moving the bead: each bead's spot is kept out of that gesture. The
     * spots stay small, since the system honours only 200dp of them along each edge.
     */
    private fun keepBeadsFromBackGesture() {
        val reach = dp(BEAD_DP / 2 + GESTURE_GUARD_DP)
        val y = height / 2
        val spots = values.map { value ->
            val x = xOf(value).roundToInt()
            Rect(x - reach, y - reach, x + reach, y + reach)
        }
        ViewCompat.setSystemGestureExclusionRects(this, spots)
    }

    override fun onDraw(canvas: Canvas) {
        // RangeSlider centres its track: its value labels float above the view and add no height.
        val y = height / 2f
        val half = trackHeight / 2f
        val start = trackSidePadding.toFloat()
        channel.setBounds(start - half, y - half, start + trackWidth + half, y + half)
        channel.draw(canvas)

        val xs = values.map(::xOf)
        val inset = rangeInset * density
        range.setBounds(xs.min() - half + inset, y - half + inset, xs.max() + half - inset, y + half - inset)
        range.draw(canvas)

        super.onDraw(canvas)

        val r = thumbWidth / 2f
        for (x in xs) {
            bead.setBounds(x - r, y - r, x + r, y + r)
            bead.draw(canvas)
        }
    }

    /** Where RangeSlider draws the thumb for [value]. */
    private fun xOf(value: Float): Float {
        val along = (value - valueFrom) / (valueTo - valueFrom)
        val fromLeft = if (layoutDirection == LAYOUT_DIRECTION_RTL) 1 - along else along
        return trackSidePadding + (fromLeft * trackWidth).toInt().toFloat()
    }

    private fun dp(value: Float): Int = (value * density).roundToInt()

    private fun Drawable.setBounds(left: Float, top: Float, right: Float, bottom: Float) =
        setBounds(left.roundToInt(), top.roundToInt(), right.roundToInt(), bottom.roundToInt())

    private companion object {
        /** The glass channel's height: a slim rod. */
        const val CHANNEL_DP = 12f

        /** How far the range's glass sits inside the channel. */
        const val RANGE_INSET_DP = 2f

        /** The flat look's track, beads and the accent ring round each bead ([wearFlat]). */
        const val FLAT_TRACK_DP = 5f
        const val FLAT_BEAD_DP = 20f
        const val FLAT_BEAD_RIM_DP = 2.5f

        /** The beads' size: bigger than the channel, so they read as handles. */
        const val BEAD_DP = 22f

        /** How far the press halo reaches past a bead. */
        const val HALO_DP = 6f

        /** How far past a bead a drag is still kept from the back gesture. */
        const val GESTURE_GUARD_DP = 8f

        /** A bead's base: a little white under its glass, so it stands out from the accent range it sits on. */
        const val BEAD_SURFACE = 0x40FFFFFF

        /** The press halo: a faint white glow. */
        const val HALO = 0x26FFFFFF
    }
}
