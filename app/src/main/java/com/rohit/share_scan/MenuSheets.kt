package com.rohit.share_scan

import android.content.res.ColorStateList
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.annotation.StyleRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.rohit.share_scan.databinding.ItemHelpBinding
import com.rohit.share_scan.databinding.ItemMenuBinding
import com.rohit.share_scan.databinding.ItemThemeBinding
import com.rohit.share_scan.databinding.SheetAboutBinding
import com.rohit.share_scan.databinding.SheetHelpBinding
import com.rohit.share_scan.databinding.SheetMenuBinding
import com.rohit.share_scan.databinding.SheetSettingsBinding
import com.rohit.share_scan.databinding.SheetThemeBinding

@get:StyleRes
val ThemeChoice.style: Int
    get() = when (this) {
        ThemeChoice.GALAXY -> R.style.Theme_ShareScan
        ThemeChoice.NEBULA -> R.style.Theme_ShareScan_Nebula
        ThemeChoice.AURORA -> R.style.Theme_ShareScan_Aurora
        ThemeChoice.MIDNIGHT -> R.style.Theme_ShareScan_Midnight
    }

@get:StringRes
val ThemeChoice.title: Int
    get() = when (this) {
        ThemeChoice.GALAXY -> R.string.theme_galaxy
        ThemeChoice.NEBULA -> R.string.theme_nebula
        ThemeChoice.AURORA -> R.string.theme_aurora
        ThemeChoice.MIDNIGHT -> R.string.theme_midnight
    }

@get:StringRes
private val ThemeChoice.body: Int
    get() = when (this) {
        ThemeChoice.GALAXY -> R.string.theme_galaxy_body
        ThemeChoice.NEBULA -> R.string.theme_nebula_body
        ThemeChoice.AURORA -> R.string.theme_aurora_body
        ThemeChoice.MIDNIGHT -> R.string.theme_midnight_body
    }

/** The theme's hero card, logo tile and accent, so the picker previews a theme without applying it. */
private val ThemeChoice.preview: Triple<Int, Int, Int>
    get() = when (this) {
        ThemeChoice.GALAXY -> Triple(R.drawable.bg_hero, R.drawable.bg_logo, R.color.galaxy_accent)
        ThemeChoice.NEBULA -> Triple(R.drawable.bg_hero_nebula, R.drawable.bg_logo_nebula, R.color.nebula_accent)
        ThemeChoice.AURORA -> Triple(R.drawable.bg_hero_aurora, R.drawable.bg_logo_aurora, R.color.aurora_accent)
        ThemeChoice.MIDNIGHT -> Triple(R.drawable.bg_hero_midnight, R.drawable.bg_logo_midnight, R.color.midnight_accent)
    }

@get:StringRes
val ChartSite.label: Int
    get() = when (this) {
        ChartSite.TRADINGVIEW -> R.string.chart_site_tradingview
        ChartSite.CHARTINK -> R.string.chart_site_chartink
        ChartSite.GOOGLE_FINANCE -> R.string.chart_site_google
        ChartSite.SCREENER -> R.string.chart_site_screener
    }

/** Shared shape of the menu's sheets: content-height, fully expanded, no half-open state. */
private fun AppCompatActivity.showSheet(root: android.view.View, fullHeight: Boolean = false): BottomSheetDialog {
    val dialog = BottomSheetDialog(this)
    if (fullHeight) {
        val height = (resources.displayMetrics.heightPixels * 0.9f).toInt()
        dialog.setContentView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height))
    } else {
        dialog.setContentView(root)
    }
    dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
    dialog.behavior.skipCollapsed = true
    dialog.show()
    return dialog
}

/** What the header's menu button offers; each entry hands control back to [MainActivity]. */
class MenuSheet(
    private val activity: AppCompatActivity,
    private val version: String,
    private val theme: ThemeChoice,
    private val onPick: (Entry) -> Unit,
) {
    enum class Entry { SETTINGS, THEME, UPDATE, HELP, SHARE, ABOUT }

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
        add(Entry.SETTINGS, R.drawable.ic_settings, R.string.menu_settings, activity.getString(R.string.menu_settings_body))
        add(Entry.THEME, R.drawable.ic_palette, R.string.menu_theme, activity.getString(R.string.menu_theme_body, activity.getString(theme.title)))
        add(Entry.UPDATE, R.drawable.ic_system_update, R.string.menu_update, activity.getString(R.string.menu_update_body, version))
        add(Entry.HELP, R.drawable.ic_help, R.string.menu_help, activity.getString(R.string.menu_help_body))
        add(Entry.SHARE, R.drawable.ic_share, R.string.menu_share, activity.getString(R.string.menu_share_body))
        add(Entry.ABOUT, R.drawable.ic_info, R.string.menu_about, activity.getString(R.string.menu_about_body))
        dialog = activity.showSheet(ui.root)
        return dialog
    }
}

/** Settings that apply as soon as they change; the data actions ask first. */
class SettingsSheet(
    private val activity: AppCompatActivity,
    private val settings: AppSettings,
    private val watchlistSize: Int,
    private val actions: Actions,
) {
    interface Actions {
        fun keepScreenOnChanged(on: Boolean)
        fun clearWatchlist()
        fun resetFilters()
        fun clearRankHistory()
    }

    private val ui = SheetSettingsBinding.inflate(activity.layoutInflater)

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

        val current = settings.chartSite
        ChartSite.entries.forEach { site ->
            val chip = activity.layoutInflater.inflate(R.layout.chip_choice, ui.settingChart, false) as Chip
            chip.id = android.view.View.generateViewId()
            chip.setText(site.label)
            chip.isCheckable = true
            chip.isChecked = site == current
            chip.setOnCheckedChangeListener { _, checked -> if (checked) settings.chartSite = site }
            ui.settingChart.addView(chip)
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
        return activity.showSheet(ui.root)
    }

    private fun confirm(message: String, action: () -> Unit) {
        MaterialAlertDialogBuilder(activity)
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
                if (selected) card.root.strokeColor = color(accent)
                card.root.contentDescription = activity.getString(choice.title) + ", " + activity.getString(choice.body)
                card.root.setOnClickListener {
                    dialog.dismiss()
                    if (choice != current) onPick(choice)
                }
            }
        }
        dialog = activity.showSheet(ui.root)
        return dialog
    }

    private fun color(@ColorRes id: Int) = ContextCompat.getColor(activity, id)
}

/** How the score, tiers, badges and trade plan work, in the same words the cards and detail sheet use. */
class HelpSheet(private val activity: AppCompatActivity) {
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
            val weight = ((BreakoutSignals.WEIGHTS[key] ?: 0.0) * 100).toInt()
            add(activity.getString(title), activity.getString(body), weight)
        }
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
        add(activity.getString(R.string.help_badges_title), activity.getString(R.string.help_badges_body))
        add(activity.getString(R.string.about_disclaimer_title), activity.getString(R.string.help_disclaimer))
        return activity.showSheet(ui.root, fullHeight = true)
    }
}

/** Version, data sources, disclaimer and links. */
class AboutSheet(
    private val activity: AppCompatActivity,
    private val version: String,
    private val build: Long,
    private val onShare: () -> Unit,
    private val onReleases: () -> Unit,
) {
    fun show(): BottomSheetDialog {
        val ui = SheetAboutBinding.inflate(activity.layoutInflater)
        ui.aboutVersion.text = activity.getString(R.string.about_version, version, build.toInt())
        ui.aboutShare.setOnClickListener { onShare() }
        ui.aboutReleases.setOnClickListener { onReleases() }
        return activity.showSheet(ui.root)
    }
}
