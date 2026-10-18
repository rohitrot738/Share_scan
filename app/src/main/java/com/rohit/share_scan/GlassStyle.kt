package com.rohit.share_scan

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.View
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import androidx.annotation.StringRes
import androidx.annotation.StyleRes
import androidx.core.graphics.ColorUtils
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import kotlin.math.roundToInt

/**
 * How much glass the screens are made of (Settings → ग्लास मोड); [key] is what the settings store.
 * Each is a theme overlay ([overlay], values/styles_glass.xml) that MainActivity lays over the picked theme, so every
 * style works in every theme: cards, sheets, chips and switches read their glass from its attributes, and the scan
 * board, its button, the stat and stock cards, the logo, the filter sheet's sliders and button and the score bars
 * wear the chips' glass ([wearChipGlass], [chipGlass]).
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

/**
 * How much the sky behind the scan board is frosted (GalaxySkyView.frost): 0 shows it sharp, 1 only blurred. Solid
 * has no pane; its board is opaque anyway.
 */
val GlassStyle.heroFrost: Float
    get() = when (this) {
        GlassStyle.FROSTED -> 0.9f
        GlassStyle.CLEAR -> 0.55f
        GlassStyle.SMOKY -> 0.95f
        GlassStyle.TINTED -> 0.9f
        GlassStyle.LIQUID -> 0.75f
        GlassStyle.SOLID -> 0f
    }

/**
 * How much the frosted pane is darkened, so the board's white text stays easy to read over a bright sky. Light,
 * because the board's own chip glass already smokes it.
 */
val GlassStyle.heroShade: Float
    get() = when (this) {
        GlassStyle.FROSTED -> 0.12f
        GlassStyle.CLEAR -> 0.05f
        GlassStyle.SMOKY -> 0.3f
        GlassStyle.TINTED -> 0.1f
        GlassStyle.LIQUID -> 0.08f
        GlassStyle.SOLID -> 0f
    }

/** The corners of every bg_hero*.xml. */
const val HERO_CORNER_DP = 28f

/** The SCAN button's corners (activity_main.xml, app:cornerRadius). */
const val START_BUTTON_CORNER_DP = 18f

/** The header logo tile's corners, as bg_logo*.xml draws them. */
const val LOGO_CORNER_DP = 14f

/** ShareScanCard's corners (cardCornerRadius): the stat cards and the stock cards. */
const val CARD_CORNER_DP = 20f

/**
 * Dresses this view in the same glass as the Filters chip, in the picked glass mode: smoked see-through glass
 * (glassChipSurface) stained with the accent (glassChipTintAlpha, times [tintBoost]), a bright line along the top,
 * a crisp shine under it, light gathering along the bottom and a thin rim (glassShine*, glassChipRim*). [radiusDp]
 * rounds the corners; null makes it round. [accent] stains it another colour than the theme's (a tier's green, say).
 * The glass is the background, so the view's text and icons stay on top, fully opaque. With [ripple], presses flash
 * white over it.
 */
fun View.wearChipGlass(
    radiusDp: Float?,
    tintBoost: Float = 1f,
    ripple: Boolean = false,
    @ColorInt accent: Int? = null,
) {
    val glass = context.chipGlass(radiusDp?.let { it * resources.displayMetrics.density }, tintBoost, accent)
    // Off the view first, so the view does not clear the new drawable's callback when it drops the old one.
    background = null
    background = if (ripple) RippleDrawable(ColorStateList.valueOf(PRESS_FLASH), glass, glass) else glass
    backgroundTintList = null
}

/** A ShareScanCard in chip glass: the card's own fill and rim off, its content wearing the glass. */
fun MaterialCardView.wearCardGlass() {
    setCardBackgroundColor(Color.TRANSPARENT)
    strokeWidth = 0
    getChildAt(0).wearChipGlass(CARD_CORNER_DP)
}

/**
 * The Filters chip's glass as a drawable, for views that draw it themselves (GlassRangeSlider, GlassMeter); see
 * [wearChipGlass]. [on] gives a checked chip's glass: more accent, brighter light. [rimBoost] brightens the rim and
 * [surface] replaces the smoked base.
 */
fun Context.chipGlass(
    radiusPx: Float?,
    tintBoost: Float = 1f,
    @ColorInt accent: Int? = null,
    on: Boolean = false,
    rimBoost: Float = 1f,
    @ColorInt surface: Int? = null,
): Drawable {
    val tintAlpha = (themeFloat(if (on) R.attr.glassChipTintAlphaOn else R.attr.glassChipTintAlpha) * tintBoost)
        .coerceIn(0f, 1f)
    val rimAlpha = (themeFloat(R.attr.glassChipRimAlpha) * rimBoost).coerceIn(0f, 1f)
    return ChipGlass(
        radius = radiusPx,
        density = resources.displayMetrics.density,
        surface = surface ?: themeColor(if (on) R.attr.glassChipSurfaceOn else R.attr.glassChipSurface),
        tint = ColorUtils.setAlphaComponent(
            accent ?: themeColor(androidx.appcompat.R.attr.colorPrimary),
            (tintAlpha * 255).roundToInt(),
        ),
        rim = ColorUtils.setAlphaComponent(themeColor(R.attr.glassChipRimTint), (rimAlpha * 255).roundToInt()),
        shineRim = themeColor(if (on) R.attr.glassShineRimOn else R.attr.glassShineRim),
        specular = themeColor(if (on) R.attr.glassShineSpecularOn else R.attr.glassShineSpecular),
        glint = themeColor(R.attr.glassShineGlint),
    )
}

/** A white flash for presses on chip glass. */
private const val PRESS_FLASH = 0x3DFFFFFF

/** A float theme attribute, or 1 (full strength) when the theme does not set it. */
private fun Context.themeFloat(@AttrRes attr: Int): Float {
    val value = TypedValue()
    val found = theme.resolveAttribute(attr, value, true) && value.type == TypedValue.TYPE_FLOAT
    return if (found) value.float else 1f
}

/** A colour theme attribute (a colour state list gives its default colour), or transparent when it is not set. */
@ColorInt
private fun Context.themeColor(@AttrRes attr: Int): Int = MaterialColors.getColor(this, attr, Color.TRANSPARENT)

/**
 * The Filters chip's glass at any size: the body ([surface] under [tint]), a bright line along the top
 * ([shineRim]), a crisp shine under it ([specular]), light along the bottom ([glint]) and a 1dp [rim]. The bands keep
 * the chip's proportions on small views and stop growing on big ones, so a board gets a thin bright edge, not a
 * wash over its text. [radius] null makes it round.
 */
private class ChipGlass(
    private val radius: Float?,
    private val density: Float,
    @ColorInt private val surface: Int,
    @ColorInt private val tint: Int,
    @ColorInt private val rim: Int,
    @ColorInt private val shineRim: Int,
    @ColorInt private val specular: Int,
    @ColorInt private val glint: Int,
) : Drawable() {
    private val surfacePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = surface }
    private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = tint }
    private val topPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val specularPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glintPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density
        color = rim
    }
    private val box = RectF()
    private val edge = RectF()
    private val band = RectF()
    private var corner = 0f
    private var bandCorner = 0f

    override fun onBoundsChange(bounds: Rect) {
        box.set(bounds)
        val h = box.height()
        corner = radius ?: (minOf(box.width(), h) / 2)
        // A stroke straddles its path, so the rim's path runs half its width inside the edge.
        edge.set(box)
        edge.inset(density / 2, density / 2)
        // Each colour fades to itself at zero alpha: fading to transparent black would leave a grey band.
        val top = (h * 0.16f).coerceIn(4 * density, 14 * density)
        topPaint.shader = LinearGradient(0f, box.top, 0f, box.top + top, shineRim, shineRim and 0xFFFFFF, Shader.TileMode.CLAMP)
        val bandHeight = (h * 0.4f).coerceIn(8 * density, 26 * density)
        val side = maxOf(6 * density, corner * 0.45f)
        band.set(box.left + side, box.top + density, box.right - side, box.top + density + bandHeight)
        bandCorner = minOf(bandHeight / 2, corner)
        specularPaint.shader = LinearGradient(
            0f, band.top, 0f, band.bottom, specular, specular and 0xFFFFFF, Shader.TileMode.CLAMP,
        )
        val bottom = (h * 0.3f).coerceIn(6 * density, 24 * density)
        glintPaint.shader = LinearGradient(
            0f, box.bottom - bottom, 0f, box.bottom, glint and 0xFFFFFF, glint, Shader.TileMode.CLAMP,
        )
    }

    override fun draw(canvas: Canvas) {
        canvas.drawRoundRect(box, corner, corner, surfacePaint)
        canvas.drawRoundRect(box, corner, corner, tintPaint)
        canvas.drawRoundRect(box, corner, corner, topPaint)
        canvas.drawRoundRect(band, bandCorner, bandCorner, specularPaint)
        canvas.drawRoundRect(box, corner, corner, glintPaint)
        canvas.drawRoundRect(edge, corner - density / 2, corner - density / 2, rimPaint)
    }

    // The rounded shape, so ripples and clipToOutline follow the glass's corners.
    override fun getOutline(outline: Outline) {
        outline.setRoundRect(bounds, corner)
    }

    override fun setAlpha(alpha: Int) = Unit

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
