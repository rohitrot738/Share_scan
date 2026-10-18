package com.rohit.share_scan

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import com.google.android.material.slider.RangeSlider
import kotlin.math.roundToInt

/**
 * The filter sheet's min/max slider in the Filters chip's glass: a glass channel, the picked range as a checked chip's
 * accent glass and two glass beads for the thumbs, in the picked glass mode. RangeSlider still does the touch, values,
 * labels and accessibility; its own track and thumbs are made invisible, and only its press halo still shows.
 */
class GlassRangeSlider @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.sliderStyle,
) : RangeSlider(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val channel = context.chipGlass(radiusPx = null)
    private val range = context.chipGlass(radiusPx = null, on = true)
    private val bead =
        context.chipGlass(radiusPx = null, tintBoost = 1.4f, on = true, rimBoost = 3f, surface = BEAD_SURFACE)

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
    }

    override fun onDraw(canvas: Canvas) {
        // RangeSlider centres its track: its value labels float above the view and add no height.
        val y = height / 2f
        val half = trackHeight / 2f
        val start = trackSidePadding.toFloat()
        channel.setBounds(start - half, y - half, start + trackWidth + half, y + half)
        channel.draw(canvas)

        val xs = values.map(::xOf)
        val inset = RANGE_INSET_DP * density
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
        /** The glass channel's height. */
        const val CHANNEL_DP = 26f

        /** How far the range's glass sits inside the channel. */
        const val RANGE_INSET_DP = 3f

        /** The beads' size: bigger than the channel, so they read as handles. */
        const val BEAD_DP = 32f

        /** How far the press halo reaches past a bead. */
        const val HALO_DP = 6f

        /** A bead's base: a little white under its glass, so it stands out from the accent range it sits on. */
        const val BEAD_SURFACE = 0x40FFFFFF

        /** The press halo: a faint white glow. */
        const val HALO = 0x26FFFFFF
    }
}
