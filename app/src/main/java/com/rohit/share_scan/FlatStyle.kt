package com.rohit.share_scan

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.view.ViewGroup
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.color.MaterialColors
import kotlin.math.roundToInt

/*
 * The compact list's flat look, after the redesign's mockup (the Space Glass background, Menu → Background): outlined capsules for chips and
 * buttons and plain cards with a hairline rim, in place of the glass. The home screen's floating bar and scan panel
 * wear it, and on the Space Glass background so does every sheet's chips and toggles ([keepFlat]), and the Filters and
 * sector sheets' sliders and cards.
 */

/** Whether the app wears the flat look: the Space Glass background (Menu → Background); the Milky Way's is glass. */
internal val Context.wearsFlat: Boolean get() = AppSettings(this).background.compact

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
    setTag(R.id.flat_dressed, true)
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

/**
 * A button of a toggle group (BUY/SELL, MIS/CNC) in the flat chips' colours: grey until picked, then ringed and lit
 * in the accent. Only tints, so the group keeps shaping its segments.
 */
internal fun MaterialButton.wearFlatToggle() {
    setTag(R.id.flat_dressed, true)
    val accent = context.flatAccent()
    val muted = context.flatMuted()
    backgroundTintList = checkedOr(accent.withAlpha(0.18f), Color.WHITE.withAlpha(0.04f))
    strokeColor = checkedOr(accent.withAlpha(0.85f), muted.withAlpha(0.45f))
    strokeWidth = (1.2f * resources.displayMetrics.density).roundToInt()
    setTextColor(checkedOr(ContextCompat.getColor(context, R.color.text_primary), muted))
    iconTint = checkedOr(accent, muted)
}

/**
 * Every chip and toggle button under this view in the flat look, and those a sheet adds later (a tab's rows, a
 * re-drawn list) as they are laid out: so on the Space Glass background Settings, Paper Trading, the trade window and
 * every other sheet match the home screen. An action chip (not checkable: Share, Filters) is ringed in the accent, a
 * choice is grey until picked. A chip or button already dressed is left as it is.
 */
internal fun View.keepFlat() {
    fun dress(view: View) {
        if (view.getTag(R.id.flat_dressed) == null) {
            when (view) {
                is Chip -> view.wearFlat(accented = !view.isCheckable)
                is MaterialButton -> if (view.parent is MaterialButtonToggleGroup) view.wearFlatToggle()
            }
        }
        if (view is ViewGroup) for (i in 0 until view.childCount) dress(view.getChildAt(i))
    }
    dress(this)
    viewTreeObserver.addOnGlobalLayoutListener { dress(this) }
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
