package com.rohit.share_scan

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.annotation.StyleRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.activity.addCallback
import androidx.core.view.isVisible
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.rohit.share_scan.databinding.ItemHelpBinding
import com.rohit.share_scan.databinding.ItemMenuBinding
import com.rohit.share_scan.databinding.ItemThemeBinding
import com.rohit.share_scan.databinding.SheetAboutBinding
import com.rohit.share_scan.databinding.SheetHelpBinding
import com.rohit.share_scan.databinding.SheetMenuBinding
import com.rohit.share_scan.databinding.SheetSettingsBinding
import com.rohit.share_scan.databinding.SheetThemeBinding
import com.rohit.share_scan.databinding.SheetTopBarBinding
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

@get:StyleRes
val ThemeChoice.style: Int
    get() = when (this) {
        ThemeChoice.GALAXY -> R.style.Theme_ShareScan
        ThemeChoice.NEBULA -> R.style.Theme_ShareScan_Nebula
        ThemeChoice.AURORA -> R.style.Theme_ShareScan_Aurora
        ThemeChoice.MIDNIGHT -> R.style.Theme_ShareScan_Midnight
        ThemeChoice.SUNSET -> R.style.Theme_ShareScan_Sunset
        ThemeChoice.OCEAN -> R.style.Theme_ShareScan_Ocean
        ThemeChoice.EMERALD -> R.style.Theme_ShareScan_Emerald
        ThemeChoice.MARS -> R.style.Theme_ShareScan_Mars
        ThemeChoice.ROYAL_GOLD -> R.style.Theme_ShareScan_RoyalGold
        ThemeChoice.FROST -> R.style.Theme_ShareScan_Frost
        ThemeChoice.SPACE_GLASS -> R.style.Theme_ShareScan_SpaceGlass
    }

/** The theme's colours for the moving Milky Way behind the cards ([GalaxySkyView]; [BackgroundChoice.GALAXY]). */
val ThemeChoice.skyPalette: SkyPalette
    get() = when (this) {
        ThemeChoice.GALAXY -> SkyPalette.GALAXY
        ThemeChoice.NEBULA -> SkyPalette.NEBULA
        ThemeChoice.AURORA -> SkyPalette.AURORA
        ThemeChoice.MIDNIGHT -> SkyPalette.MIDNIGHT
        ThemeChoice.SUNSET -> SkyPalette.SUNSET
        ThemeChoice.OCEAN -> SkyPalette.OCEAN
        ThemeChoice.EMERALD -> SkyPalette.EMERALD
        ThemeChoice.MARS -> SkyPalette.MARS
        ThemeChoice.ROYAL_GOLD -> SkyPalette.ROYAL_GOLD
        ThemeChoice.FROST -> SkyPalette.FROST
        ThemeChoice.SPACE_GLASS -> SkyPalette.SPACE_GLASS
    }

@get:StringRes
val BackgroundChoice.title: Int
    get() = when (this) {
        BackgroundChoice.GALAXY -> R.string.background_galaxy
        BackgroundChoice.SPACE_GLASS -> R.string.background_space_glass
    }

@get:StringRes
private val BackgroundChoice.body: Int
    get() = when (this) {
        BackgroundChoice.GALAXY -> R.string.background_galaxy_body
        BackgroundChoice.SPACE_GLASS -> R.string.background_space_glass_body
    }

@get:StringRes
val ListLayout.title: Int
    get() = when (this) {
        ListLayout.AUTO -> R.string.layout_auto
        ListLayout.ACCORDION -> R.string.layout_accordion
        ListLayout.BENTO -> R.string.layout_bento
        ListLayout.SWIPE -> R.string.layout_swipe
    }

@get:StringRes
private val ListLayout.body: Int
    get() = when (this) {
        ListLayout.AUTO -> R.string.layout_auto_now_cards
        ListLayout.ACCORDION -> R.string.layout_accordion_body
        ListLayout.BENTO -> R.string.layout_bento_body
        ListLayout.SWIPE -> R.string.layout_swipe_body
    }

/** A sketch of the layout's list in the theme's colours, for the picker. */
@get:DrawableRes
private val ListLayout.preview: Int
    get() = when (this) {
        ListLayout.AUTO -> R.drawable.preview_layout_auto
        ListLayout.ACCORDION -> R.drawable.preview_layout_accordion
        ListLayout.BENTO -> R.drawable.preview_layout_bento
        ListLayout.SWIPE -> R.drawable.preview_layout_swipe
    }

@get:StringRes
val ThemeChoice.title: Int
    get() = when (this) {
        ThemeChoice.GALAXY -> R.string.theme_galaxy
        ThemeChoice.NEBULA -> R.string.theme_nebula
        ThemeChoice.AURORA -> R.string.theme_aurora
        ThemeChoice.MIDNIGHT -> R.string.theme_midnight
        ThemeChoice.SUNSET -> R.string.theme_sunset
        ThemeChoice.OCEAN -> R.string.theme_ocean
        ThemeChoice.EMERALD -> R.string.theme_emerald
        ThemeChoice.MARS -> R.string.theme_mars
        ThemeChoice.ROYAL_GOLD -> R.string.theme_royal_gold
        ThemeChoice.FROST -> R.string.theme_frost
        ThemeChoice.SPACE_GLASS -> R.string.theme_space_glass
    }

@get:StringRes
private val ThemeChoice.body: Int
    get() = when (this) {
        ThemeChoice.GALAXY -> R.string.theme_galaxy_body
        ThemeChoice.NEBULA -> R.string.theme_nebula_body
        ThemeChoice.AURORA -> R.string.theme_aurora_body
        ThemeChoice.MIDNIGHT -> R.string.theme_midnight_body
        ThemeChoice.SUNSET -> R.string.theme_sunset_body
        ThemeChoice.OCEAN -> R.string.theme_ocean_body
        ThemeChoice.EMERALD -> R.string.theme_emerald_body
        ThemeChoice.MARS -> R.string.theme_mars_body
        ThemeChoice.ROYAL_GOLD -> R.string.theme_royal_gold_body
        ThemeChoice.FROST -> R.string.theme_frost_body
        ThemeChoice.SPACE_GLASS -> R.string.theme_space_glass_body
    }

/** The theme's hero card, logo tile and accent, so the picker previews a theme without applying it. */
private val ThemeChoice.preview: Triple<Int, Int, Int>
    get() = when (this) {
        ThemeChoice.GALAXY -> Triple(R.drawable.bg_hero, R.drawable.bg_logo, R.color.galaxy_accent)
        ThemeChoice.NEBULA -> Triple(R.drawable.bg_hero_nebula, R.drawable.bg_logo_nebula, R.color.nebula_accent)
        ThemeChoice.AURORA -> Triple(R.drawable.bg_hero_aurora, R.drawable.bg_logo_aurora, R.color.aurora_accent)
        ThemeChoice.MIDNIGHT -> Triple(R.drawable.bg_hero_midnight, R.drawable.bg_logo_midnight, R.color.midnight_accent)
        ThemeChoice.SUNSET -> Triple(R.drawable.bg_hero_sunset, R.drawable.bg_logo_sunset, R.color.sunset_accent)
        ThemeChoice.OCEAN -> Triple(R.drawable.bg_hero_ocean, R.drawable.bg_logo_ocean, R.color.ocean_accent)
        ThemeChoice.EMERALD -> Triple(R.drawable.bg_hero_emerald, R.drawable.bg_logo_emerald, R.color.emerald_accent)
        ThemeChoice.MARS -> Triple(R.drawable.bg_hero_mars, R.drawable.bg_logo_mars, R.color.mars_accent)
        ThemeChoice.ROYAL_GOLD -> Triple(R.drawable.bg_hero_royal_gold, R.drawable.bg_logo_royal_gold, R.color.royal_gold_accent)
        ThemeChoice.FROST -> Triple(R.drawable.bg_hero_frost, R.drawable.bg_logo_frost, R.color.frost_accent)
        ThemeChoice.SPACE_GLASS -> Triple(R.drawable.bg_hero_space_glass, R.drawable.bg_logo_space_glass, R.color.space_glass_accent)
    }

@get:StringRes
val ChartSite.label: Int
    get() = when (this) {
        ChartSite.TRADINGVIEW -> R.string.chart_site_tradingview
        ChartSite.CHARTINK -> R.string.chart_site_chartink
        ChartSite.GOOGLE_FINANCE -> R.string.chart_site_google
        ChartSite.SCREENER -> R.string.chart_site_screener
        ChartSite.GROWW -> R.string.broker_groww
        ChartSite.ANGEL_ONE -> R.string.broker_angel_one
        ChartSite.ZERODHA -> R.string.broker_zerodha
    }

/**
 * Shared shape of the menu's sheets: content-height, fully expanded, no half-open state. Its [bar]'s ✕ closes it; a
 * sheet opened from the menu gets [onBack], which ← and the system back button run to return to the menu.
 */
internal fun AppCompatActivity.showSheet(
    root: android.view.View,
    bar: SheetTopBarBinding,
    onBack: (() -> Unit)? = null,
    fullHeight: Boolean = false,
): BottomSheetDialog {
    val dialog = BottomSheetDialog(this)
    if (fullHeight) {
        val height = (resources.displayMetrics.heightPixels * 0.9f).toInt()
        dialog.setContentView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height))
    } else {
        dialog.setContentView(root)
    }
    dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
    dialog.behavior.skipCollapsed = true
    bar.sheetClose.setOnClickListener { dialog.dismiss() }
    if (onBack != null) {
        val back = {
            dialog.dismiss()
            onBack()
        }
        bar.sheetBack.isVisible = true
        bar.sheetBack.setOnClickListener { back() }
        dialog.onBackPressedDispatcher.addCallback(dialog) { back() }
    }
    dialog.show()
    return dialog
}

/** What the header's menu button offers; each entry hands control back to [MainActivity]. */
class MenuSheet(
    private val activity: AppCompatActivity,
    private val version: String,
    private val settings: AppSettings,
    private val onPick: (Entry) -> Unit,
) {
    enum class Entry { ACCOUNT, USAGE, SCAN, TRACK_RECORD, SECTORS, SETTINGS, TEXT, THEME, BACKGROUND, LAYOUT, UPDATE, HELP, SHARE, ABOUT }

    fun show(): BottomSheetDialog {
        val ui = SheetMenuBinding.inflate(activity.layoutInflater)
        ui.menuVersion.text = activity.getString(R.string.menu_version, version)
        lateinit var dialog: BottomSheetDialog
        fun add(entry: Entry, @DrawableRes icon: Int, @StringRes title: Int, body: String) {
            val row = ItemMenuBinding.inflate(activity.layoutInflater, ui.menuItems, true)
            row.menuIcon.setImageResource(icon)
            row.menuTitle.setText(title)
            row.menuBody.text = body
            row.root.setOnClickListener {
                dialog.dismiss()
                onPick(entry)
            }
        }
        val account = AccountManager.summary(activity) ?: activity.getString(R.string.menu_account_signed_out)
        add(Entry.ACCOUNT, R.drawable.ic_account, R.string.menu_account, account)
        // Only the app's maker, signed in with their GitHub account, sees how many phones use it.
        if (UsageCounter.isOwner(activity)) {
            add(Entry.USAGE, R.drawable.ic_trending_up, R.string.menu_usage, activity.getString(R.string.menu_usage_body))
        }
        add(Entry.SCAN, R.drawable.ic_radar, R.string.menu_scan, activity.getString(R.string.menu_scan_body))
        add(Entry.TRACK_RECORD, R.drawable.ic_history, R.string.menu_track_record, activity.getString(R.string.menu_track_record_body))
        add(Entry.SECTORS, R.drawable.ic_sector, R.string.menu_sectors, activity.getString(R.string.menu_sectors_body))
        add(Entry.SETTINGS, R.drawable.ic_settings, R.string.menu_settings, activity.getString(R.string.menu_settings_body))
        add(
            Entry.TEXT,
            R.drawable.ic_translate,
            R.string.menu_text,
            activity.getString(R.string.menu_text_body, Languages.current(activity).nativeName, settings.font.label(activity)),
        )
        add(Entry.THEME, R.drawable.ic_palette, R.string.menu_theme, activity.getString(R.string.menu_theme_body, activity.getString(settings.theme.title)))
        add(
            Entry.BACKGROUND,
            R.drawable.ic_wallpaper,
            R.string.menu_background,
            activity.getString(R.string.menu_theme_body, activity.getString(settings.background.title)),
        )
        add(
            Entry.LAYOUT,
            R.drawable.ic_view_agenda,
            R.string.menu_layout,
            activity.getString(R.string.menu_theme_body, activity.getString(settings.listLayout.title)),
        )
        add(Entry.UPDATE, R.drawable.ic_system_update, R.string.menu_update, activity.getString(R.string.menu_update_body, version))
        add(Entry.HELP, R.drawable.ic_help, R.string.menu_help, activity.getString(R.string.menu_help_body))
        add(Entry.SHARE, R.drawable.ic_share, R.string.menu_share, activity.getString(R.string.menu_share_body))
        add(Entry.ABOUT, R.drawable.ic_info, R.string.menu_about, activity.getString(R.string.menu_about_body))
        dialog = activity.showSheet(ui.root, ui.sheetTopBar)
        return dialog
    }
}

/** Settings that apply as soon as they change; the data actions ask first. */
class SettingsSheet(
    private val activity: AppCompatActivity,
    private val settings: AppSettings,
    private val watchlistSize: Int,
    private val actions: Actions,
    private val onBack: () -> Unit,
) {
    interface Actions {
        fun keepScreenOnChanged(on: Boolean)
        fun skyAnimationChanged(on: Boolean)
        /** Saves [style] and re-creates the screen in it. */
        fun glassStyleChanged(style: GlassStyle)
        /** Saves whether the glass is on and re-creates the screen with it on, or flat and solid. */
        fun glassEnabledChanged(on: Boolean)
        fun clearWatchlist()
        fun resetFilters()
        fun clearRankHistory()
    }

    private val ui = SheetSettingsBinding.inflate(activity.layoutInflater)
    private var confirmDialog: AlertDialog? = null

    fun show(): BottomSheetDialog {
        ui.settingUpdates.isChecked = settings.checkUpdates
        ui.settingUpdates.setOnCheckedChangeListener { _, on -> settings.checkUpdates = on }
        ui.settingUpdatesRow.setOnClickListener { ui.settingUpdates.toggle() }
        ui.settingScreen.isChecked = settings.keepScreenOn
        ui.settingScreen.setOnCheckedChangeListener { _, on ->
            settings.keepScreenOn = on
            actions.keepScreenOnChanged(on)
        }
        ui.settingScreenRow.setOnClickListener { ui.settingScreen.toggle() }
        ui.settingSky.isChecked = settings.skyAnimation
        ui.settingSky.setOnCheckedChangeListener { _, on ->
            settings.skyAnimation = on
            actions.skyAnimationChanged(on)
        }
        ui.settingSkyRow.setOnClickListener { ui.settingSky.toggle() }

        val current = settings.chartSite
        ChartSite.entries.forEach { site ->
            val chip = activity.layoutInflater.inflate(R.layout.chip_choice, ui.settingChart, false) as Chip
            chip.id = android.view.View.generateViewId()
            chip.setText(site.label)
            site.broker?.let { broker ->
                chip.chipIcon = broker.icon(activity)
                chip.chipIconTint = null
                chip.chipIconSize = activity.resources.getDimension(R.dimen.broker_icon)
                chip.isChipIconVisible = true
            }
            chip.isCheckable = true
            chip.isChecked = site == current
            chip.setOnCheckedChangeListener { _, checked -> if (checked) settings.chartSite = site }
            ui.settingChart.addView(chip)
        }

        val glassOn = settings.glassEnabled
        ui.settingGlassOn.isChecked = glassOn
        ui.settingGlassOn.setOnCheckedChangeListener { _, on -> actions.glassEnabledChanged(on) }
        ui.settingGlassRow.setOnClickListener { ui.settingGlassOn.toggle() }
        val glass = settings.glassStyle
        ui.settingGlassCurrent.setText(if (glassOn) glass.body else R.string.glass_off_body)
        GlassStyle.entries.forEach { style ->
            val chip = activity.layoutInflater.inflate(R.layout.chip_choice, ui.settingGlass, false) as Chip
            chip.id = android.view.View.generateViewId()
            chip.setText(style.label)
            chip.isCheckable = true
            chip.isChecked = style == glass
            // With the glass off the modes wait, greyed out, for it to come back on.
            chip.isEnabled = glassOn
            chip.setOnCheckedChangeListener { _, checked -> if (checked && style != glass) actions.glassStyleChanged(style) }
            ui.settingGlass.addView(chip)
        }

        ui.settingClearWatchlist.text = activity.getString(R.string.settings_clear_watchlist, watchlistSize)
        ui.settingClearWatchlist.isEnabled = watchlistSize > 0
        ui.settingClearWatchlist.setOnClickListener {
            confirm(activity.getString(R.string.settings_confirm_watchlist, watchlistSize)) {
                actions.clearWatchlist()
                ui.settingClearWatchlist.text = activity.getString(R.string.settings_clear_watchlist, 0)
                ui.settingClearWatchlist.isEnabled = false
            }
        }
        ui.settingResetFilters.setOnClickListener {
            confirm(activity.getString(R.string.settings_confirm_filters)) { actions.resetFilters() }
        }
        ui.settingClearRanks.setOnClickListener {
            confirm(activity.getString(R.string.settings_confirm_ranks)) { actions.clearRankHistory() }
        }
        val sheet = activity.showSheet(ui.root, ui.sheetTopBar, onBack)
        // Closing the sheet (or the screen being re-created) takes an open "पक्का?" dialog with it.
        sheet.setOnDismissListener { confirmDialog?.dismiss() }
        return sheet
    }

    private fun confirm(message: String, action: () -> Unit) {
        confirmDialog?.dismiss()
        confirmDialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.settings_confirm_title)
            .setMessage(message)
            .setNegativeButton(R.string.settings_confirm_no, null)
            .setPositiveButton(R.string.settings_confirm_yes) { _, _ ->
                action()
                android.widget.Toast.makeText(activity, R.string.settings_done, android.widget.Toast.LENGTH_SHORT).show()
            }
            .show()
    }
}

/** Two-column grid of theme previews; picking one hands it to [onPick], which re-creates the screen. */
class ThemeSheet(
    private val activity: AppCompatActivity,
    private val current: ThemeChoice,
    private val onPick: (ThemeChoice) -> Unit,
    private val onBack: () -> Unit,
) {
    fun show(): BottomSheetDialog {
        val ui = SheetThemeBinding.inflate(activity.layoutInflater)
        lateinit var dialog: BottomSheetDialog
        ThemeChoice.entries.chunked(2).forEach { pair ->
            val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            ui.themeGrid.addView(row)
            pair.forEach { choice ->
                val card = ItemThemeBinding.inflate(activity.layoutInflater, row, true)
                val (hero, logo, accent) = choice.preview
                card.themePreview.setBackgroundResource(hero)
                card.themePreview.clipToOutline = true
                card.themeLogo.setBackgroundResource(logo)
                card.themeAccent.backgroundTintList = ColorStateList.valueOf(color(accent))
                card.themeName.setText(choice.title)
                card.themeBody.setText(choice.body)
                val selected = choice == current
                card.themeCheck.isVisible = selected
                card.root.strokeWidth = activity.resources.getDimensionPixelSize(
                    if (selected) R.dimen.theme_card_stroke_selected else R.dimen.theme_card_stroke,
                )
                if (selected) card.root.setStrokeColor(ColorStateList.valueOf(color(accent)))
                card.root.contentDescription = activity.getString(choice.title) + ", " + activity.getString(choice.body)
                card.root.setOnClickListener {
                    dialog.dismiss()
                    if (choice != current) onPick(choice)
                }
            }
        }
        dialog = activity.showSheet(ui.root, ui.sheetTopBar, onBack)
        return dialog
    }

    private fun color(@ColorRes id: Int) = ContextCompat.getColor(activity, id)
}

/**
 * The two backgrounds side by side, each a still of itself in the theme's colours: the Milky Way (with the cards) and
 * Space Glass (with the compact table). Picking one hands it to [onPick], which re-creates the screen; the theme
 * stays.
 */
class BackgroundSheet(
    private val activity: AppCompatActivity,
    private val theme: ThemeChoice,
    private val current: BackgroundChoice,
    private val onPick: (BackgroundChoice) -> Unit,
    private val onBack: () -> Unit,
) {
    private val previewHeight = activity.resources.getDimensionPixelSize(R.dimen.background_preview_height)

    fun show(): BottomSheetDialog {
        val ui = SheetThemeBinding.inflate(activity.layoutInflater)
        ui.themeTitle.setText(R.string.background_title)
        ui.themeSubtitle.setText(R.string.background_subtitle)
        val corner = activity.resources.getDimension(R.dimen.background_preview_corner)
        val accent = color(theme.preview.third)
        lateinit var dialog: BottomSheetDialog
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        ui.themeGrid.addView(row)
        BackgroundChoice.entries.forEach { choice ->
            val card = ItemThemeBinding.inflate(activity.layoutInflater, row, true)
            card.themePreview.background = preview(choice)
            card.themePreview.layoutParams.height = previewHeight
            card.themePreview.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, corner)
            }
            card.themePreview.clipToOutline = true
            card.themeLogo.isVisible = false
            card.themeAccent.backgroundTintList = ColorStateList.valueOf(accent)
            card.themeName.setText(choice.title)
            card.themeBody.setText(choice.body)
            val selected = choice == current
            card.themeCheck.isVisible = selected
            card.root.strokeWidth = activity.resources.getDimensionPixelSize(
                if (selected) R.dimen.theme_card_stroke_selected else R.dimen.theme_card_stroke,
            )
            if (selected) card.root.setStrokeColor(ColorStateList.valueOf(accent))
            card.root.contentDescription = activity.getString(choice.title) + ", " + activity.getString(choice.body)
            card.root.setOnClickListener {
                dialog.dismiss()
                if (choice != current) onPick(choice)
            }
        }
        dialog = activity.showSheet(ui.root, ui.sheetTopBar, onBack)
        return dialog
    }

    /**
     * A still picture of the background in the theme's colours, made of plain gradients and a vector: the sky views
     * themselves, even held still, took the test emulator down inside the sheet.
     */
    private fun preview(choice: BackgroundChoice): Drawable = when (choice) {
        BackgroundChoice.GALAXY -> galaxyPreview()
        BackgroundChoice.SPACE_GLASS -> spaceGlassPreview()
    }

    /** The Milky Way in the theme's sky: deep space, the band from lower left to upper right, a nebula, its core, stars. */
    private fun galaxyPreview(): Drawable {
        val sky = theme.skyPalette
        val clear = sky.arm.color(0)
        val arm = sky.arm.color(0x30)
        // Across the band (top left to bottom right): clear, the arms' light, the core's colour along its middle.
        val band = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(clear, clear, clear, clear, arm, sky.core.color(0x50), arm, clear, clear, clear, clear),
        )
        val nebula = sky.nebulae.let { n -> floatArrayOf(n[3], n[4], n[5]) }
        return LayerDrawable(
            arrayOf(
                GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(sky.spaceTop.color(), sky.spaceBottom.color())),
                band,
                glow(nebula.color(0x48), 0.6f, 0.5f, 0.2f),
                glow(sky.core.color(0x80), 0.66f, 0.39f, 0.24f),
                AppCompatResources.getDrawable(activity, R.drawable.galaxy_hero_stars)!!,
            ),
        )
    }

    /**
     * Space Glass's sky in the theme's colours: its space, a light in the accent up right and one in the second colour
     * low left, then the grid and the price line.
     */
    private fun spaceGlassPreview(): Drawable {
        val accent = activity.flatAccent()
        val second = MaterialColors.getColor(activity, com.google.android.material.R.attr.colorSecondary, accent)
        val background = MaterialColors.getColor(activity, android.R.attr.colorBackground, Color.BLACK)
        return LayerDrawable(
            arrayOf(
                GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, spaceGlassSpace(background, accent, second)),
                glow(accent.withAlpha(0.22f), 0.82f, 0.16f, 0.55f),
                glow(second.withAlpha(0.16f), 0.12f, 0.78f, 0.6f),
                AppCompatResources.getDrawable(activity, R.drawable.preview_space_glass)!!,
            ),
        )
    }

    /** A soft round light of [color] round ([x], [y]), as fractions of the preview, fading out by [radius] heights. */
    private fun glow(@ColorInt color: Int, x: Float, y: Float, radius: Float) = GradientDrawable().apply {
        gradientType = GradientDrawable.RADIAL_GRADIENT
        colors = intArrayOf(color, ColorUtils.setAlphaComponent(color, Color.alpha(color) * 2 / 5), ColorUtils.setAlphaComponent(color, 0))
        setGradientCenter(x, y)
        gradientRadius = radius * previewHeight
    }

    /** A sky colour (0..1 RGB) as a colour int with [alpha]. */
    @ColorInt
    private fun FloatArray.color(alpha: Int = 0xFF): Int =
        Color.argb(alpha, (this[0] * 255).roundToInt(), (this[1] * 255).roundToInt(), (this[2] * 255).roundToInt())

    private fun color(@ColorRes id: Int) = ContextCompat.getColor(activity, id)
}

/**
 * The list's four layouts in a two-by-two grid, each a sketch in the theme's colours: the background's own, accordion
 * cards, the bento grid and swipe cards. Picking one hands it to [onPick], which re-creates the screen; the background
 * and the theme stay.
 */
class LayoutSheet(
    private val activity: AppCompatActivity,
    private val theme: ThemeChoice,
    private val background: BackgroundChoice,
    private val current: ListLayout,
    private val onPick: (ListLayout) -> Unit,
    private val onBack: () -> Unit,
) {
    fun show(): BottomSheetDialog {
        val ui = SheetThemeBinding.inflate(activity.layoutInflater)
        ui.themeTitle.setText(R.string.layout_title)
        ui.themeSubtitle.setText(R.string.layout_subtitle)
        val corner = activity.resources.getDimension(R.dimen.background_preview_corner)
        val accent = ContextCompat.getColor(activity, theme.preview.third)
        lateinit var dialog: BottomSheetDialog
        ListLayout.entries.chunked(2).forEach { pair ->
            val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            ui.themeGrid.addView(row)
            pair.forEach { choice ->
                val card = ItemThemeBinding.inflate(activity.layoutInflater, row, true)
                card.themePreview.background = AppCompatResources.getDrawable(activity, choice.preview)
                card.themePreview.layoutParams.height = activity.resources.getDimensionPixelSize(R.dimen.layout_preview_height)
                card.themePreview.outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, corner)
                }
                card.themePreview.clipToOutline = true
                card.themeLogo.isVisible = false
                card.themeAccent.backgroundTintList = ColorStateList.valueOf(accent)
                card.themeName.setText(choice.title)
                // The background's own says which it is now: cards or the table.
                card.themeBody.text = if (choice == ListLayout.AUTO) {
                    activity.getString(if (background.compact) R.string.layout_auto_now_table else R.string.layout_auto_now_cards)
                } else {
                    activity.getString(choice.body)
                }
                val selected = choice == current
                card.themeCheck.isVisible = selected
                card.root.strokeWidth = activity.resources.getDimensionPixelSize(
                    if (selected) R.dimen.theme_card_stroke_selected else R.dimen.theme_card_stroke,
                )
                if (selected) card.root.setStrokeColor(ColorStateList.valueOf(accent))
                card.root.contentDescription = activity.getString(choice.title) + ", " + card.themeBody.text
                card.root.setOnClickListener {
                    dialog.dismiss()
                    if (choice != current) onPick(choice)
                }
            }
        }
        dialog = activity.showSheet(ui.root, ui.sheetTopBar, onBack)
        return dialog
    }
}

/** How the score, tiers, badges and trade plan work, in the same words the cards and detail sheet use. */
class HelpSheet(private val activity: AppCompatActivity, private val onBack: () -> Unit) {
    fun show(): BottomSheetDialog {
        val ui = SheetHelpBinding.inflate(activity.layoutInflater)
        fun add(title: String, body: String, weight: Int = 0) {
            val card = ItemHelpBinding.inflate(activity.layoutInflater, ui.helpSections, true)
            card.helpTitle.text = title
            card.helpBody.text = body
            card.helpWeight.isVisible = weight > 0
            card.helpWeight.progress = weight
        }
        val parts = listOf(
            Triple(R.string.help_part_breakout, R.string.help_part_breakout_body, ScoreKeys.BREAKOUT),
            Triple(R.string.help_part_trend, R.string.help_part_trend_body, ScoreKeys.TREND),
            Triple(R.string.help_part_volume, R.string.help_part_volume_body, ScoreKeys.VOLUME),
            Triple(R.string.help_part_liquidity, R.string.help_part_liquidity_body, ScoreKeys.LIQUIDITY),
            Triple(R.string.help_part_momentum, R.string.help_part_momentum_body, ScoreKeys.MOMENTUM),
        )
        parts.forEach { (title, body, key) ->
            val weight = ((BreakoutSignals.WEIGHTS[key] ?: 0.0) * 100).roundToInt()
            add(activity.getString(title), activity.getString(body), weight)
        }
        add(
            activity.getString(R.string.help_part_support, (BreakoutSignals.SUPPORT_BONUS * 100).roundToInt()),
            activity.getString(
                R.string.help_part_support_body,
                SupportFloors.LONG_AGE / SupportFloors.SESSIONS_PER_MONTH,
            ),
        )
        add(
            activity.getString(R.string.help_tiers_title),
            activity.getString(
                R.string.help_tiers_body,
                StockFormat.STRONG_SCORE.toInt().toString(),
                StockFormat.WATCH_SCORE.toInt().toString(),
            ),
        )
        add(
            activity.getString(R.string.help_rvol_title),
            activity.getString(R.string.help_rvol_body, StockFormat.SPIKE_RVOL.toInt().toString()),
        )
        add(activity.getString(R.string.help_plan_title), activity.getString(R.string.help_plan_body))
        add(activity.getString(R.string.help_indicators_title), activity.getString(R.string.help_indicators_body))
        add(activity.getString(R.string.help_setups_title), activity.getString(R.string.help_setups_body))
        add(activity.getString(R.string.help_cr360_title), activity.getString(R.string.help_cr360_body))
        add(activity.getString(R.string.help_framework_title), activity.getString(R.string.help_framework_body))
        add(activity.getString(R.string.help_badges_title), activity.getString(R.string.help_badges_body))
        add(activity.getString(R.string.help_live_title), activity.getString(R.string.help_live_body))
        add(activity.getString(R.string.help_background_title), activity.getString(R.string.help_background_body))
        add(activity.getString(R.string.help_news_title), activity.getString(R.string.help_news_body))
        add(activity.getString(R.string.help_alerts_title), activity.getString(R.string.help_alerts_body))
        add(activity.getString(R.string.help_record_title), activity.getString(R.string.help_record_body))
        add(activity.getString(R.string.help_sector_title), activity.getString(R.string.help_sector_body))
        add(activity.getString(R.string.help_cloud_title), activity.getString(R.string.help_cloud_body))
        add(activity.getString(R.string.help_compact_title), activity.getString(R.string.help_compact_body))
        add(activity.getString(R.string.help_sky_title), activity.getString(R.string.help_sky_body))
        add(activity.getString(R.string.help_layout_title), activity.getString(R.string.help_layout_body))
        add(activity.getString(R.string.help_watchlist_title), activity.getString(R.string.help_watchlist_body))
        add(activity.getString(R.string.about_disclaimer_title), activity.getString(R.string.help_disclaimer))
        return activity.showSheet(ui.root, ui.sheetTopBar, onBack, fullHeight = true)
    }
}

/** Version, data sources, disclaimer and links. */
class AboutSheet(
    private val activity: AppCompatActivity,
    private val version: String,
    private val build: Long,
    private val onShare: () -> Unit,
    private val onReleases: () -> Unit,
    private val onBack: () -> Unit,
) {
    fun show(): BottomSheetDialog {
        val ui = SheetAboutBinding.inflate(activity.layoutInflater)
        ui.aboutVersion.text = activity.getString(R.string.about_version, version, build.toInt())
        ui.aboutShare.setOnClickListener { onShare() }
        ui.aboutReleases.setOnClickListener { onReleases() }
        bindUpdates(ui)
        return activity.showSheet(ui.root, ui.sheetTopBar, onBack)
    }

    /** The newest [SHOWN_UPDATES] updates, and a button that adds the older ones. */
    private fun bindUpdates(ui: SheetAboutBinding) {
        val notes = runCatching {
            activity.assets.open(Changelog.ASSET).bufferedReader().use { Changelog.parse(it.readText()) }
        }.getOrDefault(emptyList())
        ui.aboutUpdatesCard.isVisible = notes.isNotEmpty()
        notes.take(SHOWN_UPDATES).forEach { addUpdate(ui.aboutUpdates, it) }
        val older = notes.drop(SHOWN_UPDATES)
        ui.aboutUpdatesMore.isVisible = older.isNotEmpty()
        ui.aboutUpdatesMore.text = activity.getString(R.string.about_updates_more, older.size)
        ui.aboutUpdatesMore.setOnClickListener {
            older.forEach { addUpdate(ui.aboutUpdates, it) }
            ui.aboutUpdatesMore.isVisible = false
        }
    }

    private fun addUpdate(list: LinearLayout, note: ReleaseNote) {
        val shown = Changelog.versionOf(note, version)
        val day = note.date.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.forLanguageTag("en-IN")))
        list.addView(
            TextView(activity).apply {
                setTextAppearance(R.style.SettingsRowTitle)
                textSize = 13f
                text = activity.getString(
                    if (shown == version) R.string.about_update_head_installed else R.string.about_update_head,
                    shown,
                    day,
                )
                setPadding(0, (12 * resources.displayMetrics.density).toInt(), 0, 0)
            },
        )
        list.addView(
            TextView(activity).apply {
                setTextAppearance(R.style.SettingsRowBody)
                text = note.items.joinToString("\n") { "• $it" }
                setLineSpacing((2 * resources.displayMetrics.density), 1f)
                setPadding(0, (2 * resources.displayMetrics.density).toInt(), 0, 0)
            },
        )
    }

    private companion object {
        /** Updates listed before "पुराने … भी देखें". */
        const val SHOWN_UPDATES = 5
    }
}
