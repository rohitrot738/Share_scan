package com.rohit.share_scan

import android.os.PowerManager
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.rohit.share_scan.databinding.ItemScanMarketBinding
import com.rohit.share_scan.databinding.SheetScanBinding

/**
 * Menu → स्कैन: live mode (auto-scan, its interval and its alerts), and which exchanges the scan covers and, on each,
 * which industries. Every change is saved at once and the next scan, the user's or live mode's, follows it.
 */
class ScanSheet(
    private val activity: AppCompatActivity,
    private val settings: AppSettings,
    private val sectors: SectorBook,
    private val actions: Actions,
    private val onBack: () -> Unit,
) {
    interface Actions {
        /** Live mode or one of its alerts changed: reschedule the background work, ask to notify if needed. */
        fun liveChanged(notifying: Boolean)

        /** Opens the system's notification settings for the app. */
        fun openNotificationSettings()

        /**
         * Asks Android to let the app run in the background without battery limits, or, when it already does, opens
         * the app's info page (battery, and autostart on some phones).
         */
        fun openBackgroundSettings(allowed: Boolean)
    }

    private val ui = SheetScanBinding.inflate(activity.layoutInflater)
    private val markets = LinkedHashMap<Market, ItemScanMarketBinding>()

    fun show(): BottomSheetDialog {
        bindLive()
        MARKETS.forEach(::addMarket)
        ui.scanSectorNote.text = if (sectors.size > 0) {
            activity.getString(R.string.scan_sector_note, sectors.updated)
        } else {
            activity.getString(R.string.scan_sector_note_missing)
        }
        val sheet = activity.showSheet(ui.root, ui.sheetTopBar, onBack, fullHeight = true)
        // Back from Android's battery dialog or settings, the background line says how it stands now.
        val back = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refreshBackground() }
        activity.lifecycle.addObserver(back)
        sheet.lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_DESTROY) activity.lifecycle.removeObserver(back)
        })
        return sheet
    }

    /** Whether Android lets the app run in the background unhindered; live mode's scans and alerts need it. */
    private fun refreshBackground() {
        ui.settingBackgroundRow.isVisible = settings.liveScan || settings.watchAlerts
        val allowed = activity.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(activity.packageName) == true
        ui.settingBackgroundBody.setText(if (allowed) R.string.settings_background_on else R.string.settings_background_off)
        ui.settingBackgroundBody.setTextColor(ContextCompat.getColorStateList(activity, if (allowed) R.color.text_secondary else R.color.warn))
        ui.settingBackgroundRow.setOnClickListener { actions.openBackgroundSettings(allowed) }
    }

    /** Live mode's switch and interval, and the two kinds of alerts. */
    private fun bindLive() {
        fun refresh() {
            val live = settings.liveScan
            for (index in 0 until ui.settingLiveInterval.childCount) ui.settingLiveInterval.getChildAt(index).isEnabled = live
            ui.settingLiveAlerts.isEnabled = live
            ui.settingLiveAlertsRow.isEnabled = live
            ui.settingLiveAlertsRow.alpha = if (live) 1f else DISABLED_ALPHA
            val notifying = (live && settings.liveAlerts) || settings.watchAlerts
            ui.settingNotifyBlocked.isVisible = notifying && !Notifier.canPost(activity)
            refreshBackground()
        }
        fun changed() {
            refresh()
            actions.liveChanged((settings.liveScan && settings.liveAlerts) || settings.watchAlerts)
        }
        ui.settingLive.isChecked = settings.liveScan
        ui.settingLive.setOnCheckedChangeListener { _, on ->
            settings.liveScan = on
            changed()
        }
        ui.settingLiveRow.setOnClickListener { ui.settingLive.toggle() }
        val interval = settings.liveInterval
        AppSettings.LIVE_INTERVALS.forEach { minutes ->
            val chip = activity.layoutInflater.inflate(R.layout.chip_choice, ui.settingLiveInterval, false) as Chip
            chip.id = View.generateViewId()
            chip.text = if (minutes % 60 == 0) {
                activity.getString(R.string.settings_live_hours, minutes / 60)
            } else {
                activity.getString(R.string.settings_live_minutes, minutes)
            }
            chip.isCheckable = true
            chip.isChecked = minutes == interval
            chip.setOnCheckedChangeListener { _, checked -> if (checked) settings.liveInterval = minutes }
            ui.settingLiveInterval.addView(chip)
        }
        ui.settingLiveAlerts.isChecked = settings.liveAlerts
        ui.settingLiveAlerts.setOnCheckedChangeListener { _, on ->
            settings.liveAlerts = on
            changed()
        }
        ui.settingLiveAlertsRow.setOnClickListener { if (ui.settingLiveAlerts.isEnabled) ui.settingLiveAlerts.toggle() }
        ui.settingWatchAlerts.isChecked = settings.watchAlerts
        ui.settingWatchAlerts.setOnCheckedChangeListener { _, on ->
            settings.watchAlerts = on
            changed()
        }
        ui.settingWatchAlertsRow.setOnClickListener { ui.settingWatchAlerts.toggle() }
        ui.settingNotifyBlocked.setOnClickListener { actions.openNotificationSettings() }
        refresh()
    }

    /** One exchange's card: its switch, then its industries, each on or off. */
    private fun addMarket(market: Market) {
        val card = ItemScanMarketBinding.inflate(activity.layoutInflater, ui.scanMarkets, true)
        markets[market] = card
        val (title, body) = when (market) {
            Market.NSE -> R.string.settings_nse to R.string.settings_nse_body
            Market.NSE_SME -> R.string.settings_sme to R.string.settings_sme_body
            Market.BSE -> R.string.settings_bse to R.string.settings_bse_body
        }
        card.marketName.setText(title)
        card.marketBody.setText(body)
        card.marketSwitch.contentDescription = activity.getString(title)
        card.marketSwitch.isChecked = included(market)
        card.marketSwitch.setOnCheckedChangeListener { switch, on ->
            if (!on && MARKETS.none { it != market && included(it) }) {
                // The scan would have nothing to scan.
                switch.isChecked = true
                Toast.makeText(activity, R.string.settings_market_needed, Toast.LENGTH_SHORT).show()
                return@setOnCheckedChangeListener
            }
            include(market, on)
            refreshMarket(market)
        }
        card.marketRow.setOnClickListener { card.marketSwitch.toggle() }
        card.marketExpand.setOnClickListener {
            val open = !card.marketSectorsBox.isVisible
            card.marketSectorsBox.isVisible = open
            card.marketExpand.setText(if (open) R.string.scan_sectors_hide else R.string.scan_sectors_show)
        }
        card.marketAll.setOnClickListener {
            settings.scanSectors = settings.scanSectors.with(market, null)
            refreshMarket(market)
        }
        card.marketNone.setOnClickListener {
            settings.scanSectors = settings.scanSectors.with(market, emptySet())
            refreshMarket(market)
        }
        for ((sector, count) in sectors.counts(market)) {
            val chip = activity.layoutInflater.inflate(R.layout.chip_choice, card.marketSectors, false) as Chip
            chip.id = View.generateViewId()
            chip.tag = sector
            val name = if (sector == SectorBook.OTHER) activity.getString(R.string.scan_other) else sector
            chip.text = activity.getString(R.string.scan_sector_chip, name, StockFormat.count(count))
            chip.isCheckable = true
            chip.setOnClickListener { toggle(market, sector, chip.isChecked) }
            card.marketSectors.addView(chip)
        }
        // Without a sector file every stock counts as "other"; there is nothing to pick from.
        card.marketExpand.isVisible = card.marketSectors.childCount > 1
        refreshMarket(market)
    }

    /** Switches [sector] of [market] on or off; picking every industry again goes back to "all". */
    private fun toggle(market: Market, sector: String, on: Boolean) {
        val every = sectors.counts(market).map { it.first }.toSet()
        val current = settings.scanSectors.picked[market] ?: every
        val next = if (on) current + sector else current - sector
        settings.scanSectors = settings.scanSectors.with(market, if (next.containsAll(every)) null else next)
        refreshMarket(market)
    }

    private fun refreshMarket(market: Market) {
        val card = markets[market] ?: return
        val on = included(market)
        val scope = settings.scanSectors
        val counts = sectors.counts(market)
        val picked = counts.filter { (sector, _) -> scope.includes(market, sector) }
        for (index in 0 until card.marketSectors.childCount) {
            val chip = card.marketSectors.getChildAt(index) as Chip
            chip.isChecked = scope.includes(market, chip.tag as String)
            chip.isEnabled = on
        }
        card.marketAll.isEnabled = on
        card.marketNone.isEnabled = on
        val named = counts.count { it.first != SectorBook.OTHER }
        val pickedNamed = picked.count { it.first != SectorBook.OTHER }
        val stocks = StockFormat.count(picked.sumOf { it.second })
        card.marketSummary.text = when {
            !on -> activity.getString(R.string.scan_market_off)
            counts.isEmpty() -> activity.getString(R.string.scan_market_no_data)
            picked.isEmpty() -> activity.getString(R.string.scan_market_none)
            scope.all(market) -> activity.getString(R.string.scan_market_all, named, stocks)
            else -> activity.getString(R.string.scan_market_some, pickedNamed, named, stocks)
        }
        card.root.alpha = if (on) 1f else DISABLED_ALPHA
    }

    private fun included(market: Market): Boolean = when (market) {
        Market.NSE -> settings.includeNse
        Market.NSE_SME -> settings.includeSme
        Market.BSE -> settings.includeBse
    }

    private fun include(market: Market, on: Boolean) {
        when (market) {
            Market.NSE -> settings.includeNse = on
            Market.NSE_SME -> settings.includeSme = on
            Market.BSE -> settings.includeBse = on
        }
    }

    private companion object {
        val MARKETS = listOf(Market.NSE, Market.NSE_SME, Market.BSE)
        const val DISABLED_ALPHA = 0.5f
    }
}
