package com.rohit.share_scan

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.util.TypedValue
import android.view.View
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import androidx.annotation.StringRes
import androidx.annotation.StyleRes
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors
import kotlin.math.roundToInt

/**
 * How much glass the screens are made of (Settings → ग्लास मोड); [key] is what the settings store.
 * Each is a theme overlay ([overlay], values/styles_glass.xml) that MainActivity lays over the picked theme, so every
 * style works in every theme: cards, sheets, chips and switches read their glass from its attributes, and the hero
 * (scan board) is dressed from code by [applyGlassHero].
 */
enum class GlassStyle(val key: String) {
    /** The default: soft frosted glass the sky shows through. */
    FROSTED("frosted"),

    /** Very see-through, with bright rims. */
    CLEAR("clear"),

    /** Dark smoked glass. */
    SMOKY("smoky"),

    /** Glass stained with the theme's accent. */
    TINTED("tinted"),

    /** Iridescent: edges that shift from cyan to magenta. */
    LIQUID("liquid"),

    /** Glass off: opaque surfaces, the easiest to read and the lightest on the GPU (no blur behind sheets). */
    SOLID("solid");

    companion object {
        fun fromKey(key: String?): GlassStyle = entries.firstOrNull { it.key == key } ?: FROSTED
    }
}

@get:StyleRes
val GlassStyle.overlay: Int
    get() = when (this) {
        GlassStyle.FROSTED -> R.style.ThemeOverlay_ShareScan_Glass_Frosted
        GlassStyle.CLEAR -> R.style.ThemeOverlay_ShareScan_Glass_Clear
        GlassStyle.SMOKY -> R.style.ThemeOverlay_ShareScan_Glass_Smoky
        GlassStyle.TINTED -> R.style.ThemeOverlay_ShareScan_Glass_Tinted
        GlassStyle.LIQUID -> R.style.ThemeOverlay_ShareScan_Glass_Liquid
        GlassStyle.SOLID -> R.style.ThemeOverlay_ShareScan_Glass_Solid
    }

@get:StringRes
val GlassStyle.label: Int
    get() = when (this) {
        GlassStyle.FROSTED -> R.string.glass_frosted
        GlassStyle.CLEAR -> R.string.glass_clear
        GlassStyle.SMOKY -> R.string.glass_smoky
        GlassStyle.TINTED -> R.string.glass_tinted
        GlassStyle.LIQUID -> R.string.glass_liquid
        GlassStyle.SOLID -> R.string.glass_solid
    }

@get:StringRes
val GlassStyle.body: Int
    get() = when (this) {
        GlassStyle.FROSTED -> R.string.glass_frosted_body
        GlassStyle.CLEAR -> R.string.glass_clear_body
        GlassStyle.SMOKY -> R.string.glass_smoky_body
        GlassStyle.TINTED -> R.string.glass_tinted_body
        GlassStyle.LIQUID -> R.string.glass_liquid_body
        GlassStyle.SOLID -> R.string.glass_solid_body
    }

/** The corners of every bg_hero*.xml. */
private const val HERO_CORNER_DP = 28f

/**
 * Dresses the hero (scan board) in the picked glass mode. Its theme drawable (?attr/scanHeroBackground) is already
 * glass, but a drawable cannot take its strength from the theme, so it is done here: the drawable at glassHeroAlpha,
 * over a glassHeroBase fill (opaque in Solid) and under a glassHeroWash, with a glassHeroSheen across the top and a
 * rim, iridescent when the mode sets glassIrisTop. It all stays in the background, so the hero's text, ring and
 * button stay fully opaque. Frosted leaves the drawable as it is.
 */
fun View.applyGlassHero() {
    val body = background ?: return
    val alpha = themeFloat(R.attr.glassHeroAlpha)
    val base = themeColor(R.attr.glassHeroBase)
    val wash = themeColor(R.attr.glassHeroWash)
    val sheen = themeColor(R.attr.glassHeroSheen)
    val irisTop = themeColor(R.attr.glassIrisTop)
    val irisBottom = themeColor(R.attr.glassIrisBottom)
    val rim = themeColor(R.attr.glassHeroRim)
    val rimColors = when {
        Color.alpha(irisTop) > 0 -> intArrayOf(irisTop, ColorUtils.blendARGB(irisTop, irisBottom, 0.5f), irisBottom)
        Color.alpha(rim) > 0 -> intArrayOf(rim, rim)
        else -> null
    }
    val clear = Color.alpha(base) == 0 && Color.alpha(wash) == 0 && Color.alpha(sheen) == 0 && rimColors == null
    if (alpha >= 1f && clear) return

    val density = resources.displayMetrics.density
    val radius = HERO_CORNER_DP * density
    // Mutated, so the faded copy never reaches the theme picker's previews of the same drawable.
    if (alpha < 1f) body.mutate().alpha = (alpha.coerceIn(0f, 1f) * 255).roundToInt()
    val under = GradientDrawable().apply {
        cornerRadius = radius
        setColor(base)
    }
    val rimWidth = density * if (Color.alpha(irisTop) > 0) 1.5f else 1f
    // Off the view first, so the view does not clear the drawable's callback after the layers have taken it.
    background = null
    // The first layer is a rounded rectangle, so the hero's outline (clipToOutline) keeps its corners.
    background = LayerDrawable(arrayOf(under, body, GlassFinish(radius, wash, sheen, rimColors, rimWidth)))
}

/** A float theme attribute, or 1 (full strength) when the theme does not set it. */
private fun View.themeFloat(@AttrRes attr: Int): Float {
    val value = TypedValue()
    val found = context.theme.resolveAttribute(attr, value, true) && value.type == TypedValue.TYPE_FLOAT
    return if (found) value.float else 1f
}

/** A colour theme attribute (a colour state list gives its default colour), or transparent when it is not set. */
@ColorInt
private fun View.themeColor(@AttrRes attr: Int): Int = MaterialColors.getColor(context, attr, Color.TRANSPARENT)

/**
 * What a glass mode adds over the hero's drawable: a [wash] over the whole card, a [sheen] fading out over the top
 * third and a rim, one colour or a diagonal gradient through [rim] (top left to bottom right). Every colour carries
 * its own alpha, so the finish as a whole is never faded.
 */
private class GlassFinish(
    private val radius: Float,
    @ColorInt private val wash: Int,
    @ColorInt private val sheen: Int,
    private val rim: IntArray?,
    private val rimWidth: Float,
) : Drawable() {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = rimWidth
    }
    private val box = RectF()
    private val edge = RectF()

    override fun onBoundsChange(bounds: Rect) {
        box.set(bounds)
        // A stroke straddles its path, so the rim's path runs half its width inside the edge.
        edge.set(box)
        edge.inset(rimWidth / 2, rimWidth / 2)
        fill.color = wash
        // Fades to the sheen's own colour at zero alpha: a gradient to transparent black would leave a grey band.
        sheenPaint.shader = LinearGradient(
            0f, box.top, 0f, box.top + box.height() * SHEEN_DEPTH,
            sheen, sheen and 0x00FFFFFF, Shader.TileMode.CLAMP,
        )
        if (rim != null) {
            stroke.shader = LinearGradient(box.left, box.top, box.right, box.bottom, rim, null, Shader.TileMode.CLAMP)
        }
    }

    override fun draw(canvas: Canvas) {
        if (Color.alpha(wash) > 0) canvas.drawRoundRect(box, radius, radius, fill)
        if (Color.alpha(sheen) > 0) canvas.drawRoundRect(box, radius, radius, sheenPaint)
        if (rim != null) canvas.drawRoundRect(edge, radius - rimWidth / 2, radius - rimWidth / 2, stroke)
    }

    override fun setAlpha(alpha: Int) = Unit

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private companion object {
        /** Like glass_sheen_28: the shine is gone 35% of the way down. */
        const val SHEEN_DEPTH = 0.35f
    }
}
