package com.rohit.share_scan

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.color.MaterialColors
import kotlin.math.roundToInt

/*
 * The compact list's flat look, after the redesign's mockup (Settings → Compact list): outlined capsules for chips and
 * buttons and plain cards with a hairline rim, in place of the glass. The home screen's floating bar and scan panel
 * wear it, and so do the Filters and sector sheets while the compact list is on.
 */

@ColorInt
internal fun Int.withAlpha(fraction: Float): Int = ColorUtils.setAlphaComponent(this, (fraction * 255).roundToInt())

/** [checked] for a checked view, [otherwise] for the rest. */
internal fun checkedOr(@ColorInt checked: Int, @ColorInt otherwise: Int) = ColorStateList(
    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
    intArrayOf(checked, otherwise),
)

@ColorInt
internal fun Context.flatAccent(): Int = attrColor(androidx.appcompat.R.attr.colorPrimary)

@ColorInt
internal fun Context.flatMuted(): Int = attrColor(com.google.android.material.R.attr.colorOnSurfaceVariant)

@ColorInt
private fun Context.attrColor(@AttrRes attr: Int): Int = MaterialColors.getColor(this, attr, Color.WHITE)

/** A capsule (the corners clamp to half the height) filled with [fill] and ringed in [rim]. */
internal fun Context.flatCapsule(@ColorInt fill: Int, @ColorInt rim: Int, rimDp: Float): GradientDrawable =
    GradientDrawable().apply {
        val dp = resources.displayMetrics.density
        cornerRadius = 100 * dp
        setColor(fill)
        setStroke((rimDp * dp).roundToInt(), rim)
    }

/** A plain card: the theme's container colour with a hairline rim. */
internal fun Context.flatCard(radiusDp: Float = 16f): Drawable = GradientDrawable().apply {
    val dp = resources.displayMetrics.density
    cornerRadius = radiusDp * dp
    setColor(attrColor(com.google.android.material.R.attr.colorSurfaceContainer))
    setStroke(dp.roundToInt(), Color.WHITE.withAlpha(FLAT_RIM))
}

/**
 * An outlined capsule chip without the glass's shine or tick. An [accented] chip (an action: Filters, Watchlist,
 * the sector, sharing) is ringed in the accent; a choice (a sort, a reach, a preset) is grey until it is picked.
 */
internal fun Chip.wearFlat(accented: Boolean) {
    val accent = context.flatAccent()
    val muted = context.flatMuted()
    val text = ContextCompat.getColor(context, R.color.text_primary)
    foreground = null
    chipBackgroundColor = checkedOr(accent.withAlpha(0.18f), Color.WHITE.withAlpha(0.04f))
    chipStrokeWidth = 1.2f * resources.displayMetrics.density
    isCheckedIconVisible = false
    if (accented) {
        chipStrokeColor = ColorStateList.valueOf(accent.withAlpha(0.85f))
        setTextColor(text)
        chipIconTint = ColorStateList.valueOf(accent)
    } else {
        chipStrokeColor = checkedOr(accent.withAlpha(0.85f), muted.withAlpha(0.45f))
        setTextColor(checkedOr(text, muted))
        chipIconTint = checkedOr(accent, muted)
    }
}

/** A card as a plain one with a hairline rim, in place of its see-through glass. */
internal fun MaterialCardView.wearFlat() {
    setCardBackgroundColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurfaceContainer))
    strokeColor = Color.WHITE.withAlpha(FLAT_RIM)
    strokeWidth = resources.displayMetrics.density.roundToInt()
    radius = 16 * resources.displayMetrics.density
}

/** A screen's main action (SCAN, लागू करें) as a capsule ringed and lit in the accent. */
internal fun MaterialButton.wearFlatAction() {
    val accent = context.flatAccent()
    val capsule = context.flatCapsule(accent.withAlpha(0.2f), accent.withAlpha(0.9f), 1.5f)
    background = RippleDrawable(ColorStateList.valueOf(accent.withAlpha(0.3f)), capsule, null)
    backgroundTintList = null
    setTextColor(ContextCompat.getColor(context, R.color.text_primary))
    iconTint = ColorStateList.valueOf(accent)
}

/** The hairline round a flat card: white at this strength. */
private const val FLAT_RIM = 0.08f
