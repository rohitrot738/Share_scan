package com.rohit.share_scan

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import androidx.annotation.ColorInt

/**
 * A score bar in the Filters chip's glass: a glass channel (in the picked glass mode) filled to [fraction] with
 * [color], the fill glossy along its top like liquid in a glass tube.
 */
class GlassMeter @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    /** How full the bar is, 0 to 1. */
    var fraction = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    @ColorInt
    var color = Color.WHITE
        set(value) {
            field = value
            fillPaint.color = value
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val channel = context.chipGlass(radiusPx = null)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = this@GlassMeter.color }
    private val glossPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fill = RectF()

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        channel.setBounds(0, 0, w, h)
        val inset = INSET_DP * density
        glossPaint.shader = LinearGradient(0f, inset, 0f, h / 2f, GLOSS, GLOSS and 0xFFFFFF, Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        channel.draw(canvas)
        val inset = INSET_DP * density
        val inner = width - 2 * inset
        val thickness = height - 2 * inset
        if (fraction <= 0f || inner <= 0f || thickness <= 0f) return
        // Never shorter than round, so a tiny score still shows as a dot.
        val length = maxOf(thickness, inner * fraction)
        if (layoutDirection == LAYOUT_DIRECTION_RTL) {
            fill.set(width - inset - length, inset, width - inset, inset + thickness)
        } else {
            fill.set(inset, inset, inset + length, inset + thickness)
        }
        val r = thickness / 2
        canvas.drawRoundRect(fill, r, r, fillPaint)
        canvas.drawRoundRect(fill, r, r, glossPaint)
    }

    private companion object {
        /** How far the fill sits inside the glass. */
        const val INSET_DP = 3.5f

        /** The shine along the top of the fill. */
        const val GLOSS = 0x73FFFFFF
    }
}
