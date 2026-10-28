package com.rohit.share_scan

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.animation.LinearInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.withStarted
import androidx.recyclerview.widget.SimpleItemAnimator
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDragHandleView
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.rohit.share_scan.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {
    private val scan: ScanViewModel by viewModels()
    private val updates: UpdateViewModel by viewModels()
    private lateinit var settings: AppSettings
    private lateinit var ui: ActivityMainBinding
    private val adapter = StockAdapter(
        onOpen = { openChart(it) },
        onStar = { scan.toggleStar(it.symbol) },
        onNews = { openChart(it, onNews = true) },
    )
    private var shownResults: List<ScanResult>? = null
    private var shownFilters: ScanFilters? = null
    private var shownSort: SortMode? = null
    private var shownAscending: Boolean? = null
    private var shownWatchlistOnly: Boolean? = null
    private var shownScanMode: ScanMode? = null
    private var shownSupportReach: Double? = null
    private var shownQuery: String? = null
    private var shownWatchlist: Set<String>? = null
    private var shownCr360: Cr360Book? = null
    private val closeSearchOnBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = closeSearch()
    }
    /** In the watchlist, Back goes to the full list rather than out of the app. */
    private val leaveWatchlistOnBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = scan.setWatchlistOnly(false)
    }
    private var filterSheet: BottomSheetDialog? = null
    private var detailSheet: BottomSheetDialog? = null
    /** The menu or whichever of its sheets is open; only one shows at a time. */
    private var menuSheet: BottomSheetDialog? = null
    private var shownRing = -1
    /** The list as a compact table (Settings → Compact list), read when the screen is created. */
    private var compact = false
    /** The compact list's scan controls (the Breakout/Support chips and the scan board), shown in [scanPanel]. */
    private var panelContent: LinearLayout? = null
    private var scanPanel: BottomSheetDialog? = null
    private var compactShare: Chip? = null
    private var radarSpin: ObjectAnimator? = null
    /** A notification's stock, opened once the list holds it. */
    private var pendingSymbol: String? = null
    /** The pending stock opens on its खबरें tab (a news notification's). */
    private var pendingNews = false
    private var sectorSheet: BottomSheetDialog? = null
    private var clearDialog: AlertDialog? = null
    private val notifyPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        // The picked theme has to be in place before any view (or the window background) is created.
        settings = AppSettings(this)
        setTheme(settings.theme.style)
        // The glass mode goes over the theme: cards, sheets, chips and switches read their glass from it. Glass
        // switched off lays flat, solid surfaces over it instead.
        theme.applyStyle(if (settings.glassEnabled) settings.glassStyle.overlay else GLASS_OFF_OVERLAY, true)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ui = ActivityMainBinding.inflate(layoutInflater)
        setContentView(ui.root)
        // Space Glass paints its own background; every other theme its Milky Way (the hidden view never bakes).
        val ownSky = settings.theme.ownSky
        ui.galaxySky.isVisible = !ownSky
        ui.spaceSky.isVisible = ownSky
        compact = settings.compactList
        adapter.compact = compact
        ui.galaxySky.palette = settings.theme.skyPalette
        ui.galaxySky.animate = settings.skyAnimation
        ui.spaceSky.animate = settings.skyAnimation
        // The version running, beside the name.
        updates.installedName.takeIf { it.isNotEmpty() }?.let {
            ui.appVersion.text = getString(R.string.header_version, it)
            ui.appVersion.isVisible = true
        }
        ui.hero.clipToOutline = true
        // The scan board, its button, the stat cards and the empty-state circle wear the Filters chip's glass;
        // so do the stock cards (StockAdapter) and the filter sheet's sliders and button (FilterSheet).
        ui.hero.wearChipGlass(HERO_CORNER_DP, tintBoost = 1.5f)
        ui.startButton.wearChipGlass(START_BUTTON_CORNER_DP, tintBoost = 1.8f, ripple = true)
        ui.emptyCircle.wearChipGlass(null, tintBoost = 2f)
        listOf(ui.statCardScanned, ui.statCardTop, ui.statCardSpikes).forEach { it.wearCardGlass() }
        // Frosted glass: the sky behind the scan board is drawn blurred, and follows the board as the bar scrolls.
        // The compact list keeps the board in its scan panel, off the screen behind it.
        if (settings.glassEnabled && !compact) {
            val glass = settings.glassStyle
            val corner = HERO_CORNER_DP * resources.displayMetrics.density
            if (ownSky) ui.spaceSky.frost(ui.hero, glass.heroFrost, glass.heroShade, corner)
            else ui.galaxySky.frost(ui.hero, glass.heroFrost, glass.heroShade, corner)
        }
        ui.appBar.addOnOffsetChangedListener { _, _ -> (if (ownSky) ui.spaceSky else ui.galaxySky).invalidate() }
        ui.results.adapter = adapter
        if (compact) setUpCompact()
        applyWindowInsets()
        setUpListTools()
        ui.updateAction.setOnClickListener { updates.proceed() }
        ui.updateLater.setOnClickListener { updates.dismiss() }
        ui.menuButton.setOnClickListener { openMenu() }
        ui.sectorChip.setOnClickListener { openSectors(fromMenu = false) }
        if (savedInstanceState == null) {
            intent.getStringExtra(Notifier.EXTRA_SYMBOL)?.let { openFromNotification(it, intent.getBooleanExtra(Notifier.EXTRA_NEWS, false)) }
            if (intent.getBooleanExtra(Notifier.EXTRA_INSTALL, false)) updates.installWhenReady()
        }
        if ((settings.liveScan && settings.liveAlerts) || settings.watchAlerts || settings.newsAlerts) askToNotify(again = false)

        ui.startButton.setOnClickListener {
            // While a scan runs the same button stops it.
            if (scan.uiState.value.isRunning) {
                scan.stopScan()
            } else {
                ui.appBar.setExpanded(true, true)
                scan.startScan()
            }
        }
        ui.clearScanButton.setOnClickListener { askToClear() }
        ui.filterChip.setOnClickListener {
            filterSheet?.dismiss()
            val state = scan.uiState.value
            // Each scan keeps its own filters: the Support scan's count among the stocks on a long floor.
            filterSheet = if (state.scanMode == ScanMode.SUPPORT) {
                FilterSheet(
                    this,
                    state,
                    state.supportFilters,
                    state.supportReached.map { it.stock },
                    ScanFilters(limit = 0),
                    scan::setSupportFilters,
                )
            } else {
                FilterSheet(this, state, state.filters, state.results, ScanFilters(), scan::setFilters)
            }.show()
        }
        // A tap on another sort picks it, highest first; a tap on the one in use turns its order round.
        for (mode in SortMode.entries) {
            sortChip(mode).setOnClickListener {
                val turning = scan.uiState.value.sort == mode
                scan.pickSort(mode)
                if (turning) Toast.makeText(this, sortName(scan.uiState.value), Toast.LENGTH_SHORT).show()
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { scan.uiState.collect { render(it) } }
                launch { updates.offer.collect { renderUpdate(it) } }
                launch { updates.installRequests.collect { install(it) } }
                launch { updates.checkResults.collect { showUpdateCheck(it) } }
                launch {
                    // Keeps the elapsed-time clock in the hero card ticking during a scan, and the market's state
                    // (LIVE or closed) current while the screen is up.
                    var tick = 0
                    while (true) {
                        delay(1_000)
                        val state = scan.uiState.value
                        if (state.isRunning || ++tick % MARKET_REFRESH_S == 0) renderHero(state)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(Notifier.EXTRA_SYMBOL)?.let { openFromNotification(it, intent.getBooleanExtra(Notifier.EXTRA_NEWS, false)) }
        if (intent.getBooleanExtra(Notifier.EXTRA_INSTALL, false)) updates.installWhenReady()
    }

    /**
     * A notification's stock: opened at once when the list holds it, or as soon as the kept scan is read from disk; if it
     * is not there by then (or a scan under way does not bring it), the user is told rather than left with the bare list.
     */
    private fun openFromNotification(symbol: String, news: Boolean) {
        pendingSymbol = symbol
        pendingNews = news
        // Until the screen is up the list's first render opens it.
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) openPending(scan.uiState.value)
        lifecycleScope.launch {
            // On a fresh start the whole list takes seconds to read; the stock's own row is found first, and opens
            // once the R/S and learned-setup files are in (or a moment has passed without them).
            if (!ScanEngine.read.value) {
                val row = withContext(Dispatchers.IO) { ScanEngine.peek(this@MainActivity, symbol) }
                if (row != null && pendingSymbol == symbol) {
                    withTimeoutOrNull(BOOKS_WAIT_MS) {
                        scan.uiState.first { it.cr360 !== Cr360Book.EMPTY && it.setups !== SetupBook.EMPTY }
                    }
                    lifecycle.withStarted {
                        if (pendingSymbol == symbol) {
                            pendingSymbol = null
                            openChart(row, onNews = news)
                        }
                    }
                }
            }
            ScanEngine.read.first { it }
            scan.uiState.first { !it.isRunning }
            // The list renders the kept or finished scan a moment later.
            delay(PENDING_GRACE_MS)
            if (pendingSymbol == symbol) {
                pendingSymbol = null
                Toast.makeText(this@MainActivity, getString(R.string.notify_open_missing, symbol), Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        AppScreen.open = true
        // The scan's result is on the screen now.
        Notifier.cancelScanDone(this)
        // Signed in, the watchlist follows the account; a change from another phone shows here at once.
        CloudWatchlist.start(this) { scan.showWatchlist(it) }
        // Once a day, this phone counts towards how many use the app (seen by its maker alone).
        UsageCounter.checkIn(this, updates.installedName)
    }

    override fun onStop() {
        AppScreen.open = false
        CloudWatchlist.stop(this)
        super.onStop()
    }

    override fun onDestroy() {
        radarSpin?.cancel()
        filterSheet?.dismiss()
        detailSheet?.dismiss()
        menuSheet?.dismiss()
        sectorSheet?.dismiss()
        clearDialog?.dismiss()
        scanPanel?.dismiss()
        super.onDestroy()
    }

    private fun render(state: ScanUiState) {
        renderHero(state)
        renderStats(state)
        renderResults(state)
        // Only the user's own scan keeps the screen awake; live mode's run on their own.
        ui.root.keepScreenOn = state.isRunning && !state.runningAuto && settings.keepScreenOn
        openPending(state)
    }

    /** Opens the stock a notification named, once the list (or the watchlist's stocks outside it) holds it. */
    private fun openPending(state: ScanUiState) {
        val symbol = pendingSymbol ?: return
        val stock = state.results.firstOrNull { it.symbol == symbol }
            ?: state.watchExtras.firstOrNull { it.symbol == symbol }
            ?: return
        pendingSymbol = null
        openChart(stock, onNews = pendingNews)
    }

    /**
     * Asks Android (13 and later) for permission to post the alerts. On its own the app asks once; [again] asks
     * whenever the user turns an alert on, and Android itself stops asking after the user said no twice.
     */
    private fun askToNotify(again: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || Notifier.canPost(this)) return
        val prefs = getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE)
        if (!again && prefs.getBoolean(Prefs.NOTIFY_ASKED, false)) return
        prefs.edit { putBoolean(Prefs.NOTIFY_ASKED, true) }
        notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /** The hero's top line: whether the market trades now and how fresh the list is, and whether live mode is on. */
    private fun renderMarket(state: ScanUiState) {
        val now = MarketClock.now()
        val phase = MarketClock.phase(now)
        val open = phase == MarketClock.Phase.OPEN
        val today = state.savedAt > 0 && MarketClock.now(state.savedAt).toLocalDate() == now.toLocalDate()
        val market = when {
            open && state.live && today -> getString(R.string.hero_live, clockTime(state.savedAt))
            open -> getString(R.string.hero_open)
            phase == MarketClock.Phase.BEFORE_OPEN -> getString(R.string.hero_before_open)
            else -> getString(R.string.hero_closed)
        }
        ui.heroLabel.text = if (settings.liveScan) getString(R.string.hero_auto, market, settings.liveInterval) else market
        ui.heroLabel.setTextColor(ContextCompat.getColor(this, if (open) R.color.bull else R.color.hero_text_muted))
    }

    /** "आज 11:42" for today, otherwise the day as well: "शुक्र 16 अक्टू 15:30". */
    private fun whenScanned(millis: Long): String {
        val at = MarketClock.now(millis)
        return if (at.toLocalDate() == MarketClock.now().toLocalDate()) {
            getString(R.string.time_today, clockTime(millis))
        } else {
            DAY_TIME.format(at)
        }
    }

    private fun clockTime(millis: Long): String = CLOCK.format(Instant.ofEpochMilli(millis).atZone(MarketClock.INDIA))

    private fun renderHero(state: ScanUiState) {
        renderMarket(state)
        val support = state.scanMode == ScanMode.SUPPORT
        val clock = StockFormat.duration(state.elapsedAt(SystemClock.elapsedRealtime()))
        when (state.phase) {
            // A fresh start: the kept scan is on its way from disk, not missing.
            ScanPhase.IDLE -> if (state.reading) {
                showStatus(
                    getString(R.string.status_reading_title),
                    getString(R.string.status_reading_body),
                    R.drawable.ic_radar,
                    0,
                )
            } else {
                showStatus(
                    getString(if (support) R.string.support_ready_title else R.string.status_ready_title),
                    getString(if (support) R.string.support_ready_body else R.string.status_ready_body),
                    if (support) R.drawable.ic_support else R.drawable.ic_radar,
                    0,
                )
            }
            ScanPhase.LOADING -> showStatus(
                getString(if (state.runningAuto) R.string.status_auto_title else R.string.status_loading_title),
                getString(R.string.status_loading_body, clock),
                R.drawable.ic_radar,
                0,
            )
            ScanPhase.SCANNING -> showStatus(
                getString(if (state.runningAuto) R.string.status_auto_title else R.string.status_scanning_title),
                getString(
                    R.string.status_scanning_body,
                    StockFormat.count(state.done),
                    StockFormat.count(state.total),
                    clock,
                ),
                null,
                state.percent,
            )
            // A stopped scan keeps its ring where it stopped, so the share of stocks checked shows at a glance.
            ScanPhase.DONE -> if (state.stopped) {
                showStatus(
                    getString(R.string.status_stopped_title),
                    if (state.total > 0 && !state.earlyStop) {
                        getString(
                            R.string.status_stopped_body,
                            StockFormat.count(state.done),
                            StockFormat.count(state.total),
                            clock,
                        )
                    } else {
                        getString(R.string.status_stopped_early_body)
                    },
                    R.drawable.ic_stop,
                    state.percent,
                )
            } else if (support) {
                showStatus(
                    getString(R.string.support_done_title),
                    getString(R.string.support_done_body, state.supportStocks.size, whenScanned(state.savedAt)),
                    R.drawable.ic_support,
                    100,
                )
            } else {
                showStatus(
                    getString(if (state.autoResults) R.string.status_auto_done_title else R.string.status_done_title),
                    getString(
                        if (state.narrowed) R.string.status_done_sectors_body else R.string.status_done_saved_body,
                        state.visible.size,
                        whenScanned(state.savedAt),
                    ),
                    R.drawable.ic_check,
                    100,
                )
            }
            ScanPhase.FAILED -> showStatus(
                getString(R.string.status_failed_title),
                state.error?.let { getString(R.string.status_failed_detail, it) }
                    ?: getString(R.string.status_failed_body),
                R.drawable.ic_error,
                0,
            )
        }

        val start = if (support) R.string.support_action_start else R.string.action_start
        val (label, icon) = when (state.phase) {
            ScanPhase.IDLE -> start to R.drawable.ic_play
            ScanPhase.LOADING, ScanPhase.SCANNING ->
                (if (state.stopping) R.string.action_stopping else R.string.action_stop) to R.drawable.ic_stop
            ScanPhase.DONE -> if (state.stopped) {
                start to R.drawable.ic_play
            } else {
                (if (support) R.string.support_action_rescan else R.string.action_rescan) to R.drawable.ic_refresh
            }
            ScanPhase.FAILED -> R.string.action_retry to R.drawable.ic_refresh
        }
        ui.startButton.setText(label)
        ui.startButton.setIconResource(icon)
        // A running scan's button stops it; it rests only while a stop finishes the requests under way.
        ui.startButton.isEnabled = !state.stopping
        // Nothing to clear without a scan, nor while one runs: it would put a scan straight back.
        ui.clearScanButton.isVisible = state.results.isNotEmpty() && !state.isRunning
        if (compact) renderPill(state)
    }

    /** The compact header's status pill: what the scan is doing, with a dot green while live or scanning. */
    private fun renderPill(state: ScanUiState) {
        val count = StockFormat::count
        val now = MarketClock.now()
        val open = MarketClock.phase(now) == MarketClock.Phase.OPEN
        val today = state.savedAt > 0 && MarketClock.now(state.savedAt).toLocalDate() == now.toLocalDate()
        val (text, dot) = when (state.phase) {
            ScanPhase.IDLE -> getString(if (state.reading) R.string.pill_reading else R.string.pill_idle) to R.color.neutral
            ScanPhase.LOADING -> getString(R.string.pill_loading) to R.color.bull
            ScanPhase.SCANNING ->
                getString(R.string.pill_scanning, state.percent, count(state.done), count(state.total)) to R.color.bull
            ScanPhase.DONE -> if (state.stopped) {
                getString(R.string.pill_stopped, state.percent, count(state.done), count(state.total)) to R.color.warn
            } else {
                val stocks = count(if (state.total > 0) state.total else state.results.size)
                when {
                    state.scanMode == ScanMode.SUPPORT -> getString(R.string.pill_support_done, stocks)
                    open && state.live && today -> getString(R.string.pill_live_done, stocks)
                    else -> getString(R.string.pill_done, stocks)
                } to (if (open) R.color.bull else R.color.warn)
            }
            ScanPhase.FAILED -> getString(R.string.pill_failed) to R.color.bear
        }
        ui.statusPillText.text = text
        ui.statusDot.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, dot))
        ui.statusPill.contentDescription = getString(R.string.pill_description, text)
    }

    /**
     * The compact list (Settings → Compact list): a slim pinned header with the scan's status pill in place of the
     * brand header, scan board and stats, the table's column names, and the list's tools in a floating bar at the
     * bottom. The scan board and the Breakout/Support chips move into a panel the pill opens ([openScanPanel]).
     */
    private fun setUpCompact() {
        val dp = resources.displayMetrics.density
        // The slim header becomes the app bar's first child: the bar keeps its children clear of the status bar only
        // while the first one shows, and the brand header it takes the place of is hidden.
        val headerHeight = ui.compactHeader.layoutParams.height
        ui.resultsSection.removeView(ui.compactHeader)
        ui.appBar.addView(ui.compactHeader, 0, AppBarLayout.LayoutParams(MATCH_PARENT, headerHeight))
        ui.brandHeader.isVisible = false
        ui.statsRow.isVisible = false
        ui.resultsHeading.isVisible = false
        ui.resultsSection.updatePadding(top = 0, bottom = 0)
        ui.compactHeader.isVisible = true
        ui.compactColumns.isVisible = true
        updates.installedName.takeIf { it.isNotEmpty() }?.let { ui.compactVersion.text = getString(R.string.header_version, it) }
        ui.statusPill.setOnClickListener { openScanPanel() }
        ui.compactSearch.setOnClickListener { openSearch() }
        ui.compactMenu.setOnClickListener { openMenu() }

        // The tools into the floating bar, with sharing at their end; the list keeps room under its last line.
        ui.resultsSection.removeView(ui.listToolsScroll)
        ui.listToolsScroll.updatePadding(left = (10 * dp).roundToInt(), right = (10 * dp).roundToInt())
        ui.bottomBarTools.addView(ui.listToolsScroll, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        compactShare = (layoutInflater.inflate(R.layout.chip_share, ui.listTools, false) as Chip).also {
            it.setOnClickListener { shareShown() }
            ui.listTools.addView(it)
        }
        ui.bottomBar.isVisible = true
        ui.results.updatePadding(bottom = resources.getDimensionPixelSize(R.dimen.compact_list_bottom))

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            updatePadding(bottom = (24 * dp).roundToInt())
        }
        for (view in listOf(ui.modeScroll, ui.hero)) {
            (view.parent as ViewGroup).removeView(view)
            panel.addView(
                view,
                LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                    if (view === ui.hero) {
                        marginStart = (16 * dp).roundToInt()
                        marginEnd = (16 * dp).roundToInt()
                    } else {
                        bottomMargin = (6 * dp).roundToInt()
                    }
                },
            )
        }
        panelContent = panel
    }

    /** The scan's controls in a sheet: Breakout or Support, the scan board with its SCAN / Stop and Clear buttons. */
    private fun openScanPanel() {
        val content = panelContent ?: return
        scanPanel?.dismiss()
        (content.parent as? ViewGroup)?.removeView(content)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(BottomSheetDragHandleView(this@MainActivity))
            addView(content)
        }
        scanPanel = BottomSheetDialog(this).apply {
            setContentView(root)
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
            behavior.skipCollapsed = true
            // The board goes back to waiting for the next time the pill is tapped.
            setOnDismissListener { root.removeView(content) }
            show()
        }
    }

    /** [icon] replaces the percentage in the middle of the progress ring when it is not null. */
    private fun showStatus(title: String, body: String, @DrawableRes icon: Int?, ring: Int) {
        ui.statusTitle.text = title
        ui.statusBody.text = body
        ui.ringIcon.isVisible = icon != null
        if (icon != null) ui.ringIcon.setImageResource(icon)
        ui.ringPercent.isVisible = icon == null
        ui.ringPercent.text = getString(R.string.percent, ring)
        if (ring != shownRing) {
            ui.progressRing.setProgressCompat(ring, shownRing >= 0)
            shownRing = ring
        }
    }

    private fun renderStats(state: ScanUiState) {
        val empty = getString(R.string.stat_empty)
        ui.statScannedValue.text = if (state.total > 0) StockFormat.count(state.done) else empty
        if (state.scanMode == ScanMode.SUPPORT) {
            // How many stand on a six-month floor, and how many of them sit within 5% of it.
            val floors = state.supportStocks
            val near = floors.count { it.stock.support!!.distancePct(it.stock.price) <= NEAR_SUPPORT_PCT }
            ui.statTopLabel.setText(R.string.support_stat_long)
            ui.statSpikesLabel.setText(R.string.support_stat_near)
            ui.statTopValue.text = if (state.results.isEmpty()) empty else floors.size.toString()
            ui.statSpikesValue.text = if (state.results.isEmpty()) empty else near.toString()
        } else {
            ui.statTopLabel.setText(R.string.stat_top_score)
            ui.statSpikesLabel.setText(R.string.stat_spikes)
            ui.statTopValue.text = state.visible.firstOrNull()?.let { StockFormat.score(it.stock.score) } ?: empty
            ui.statSpikesValue.text = if (state.visible.isEmpty()) empty else state.volumeSpikes.toString()
        }
    }

    private fun renderResults(state: ScanUiState) {
        val shown = state.sortedResults
        val hasResults = shown.isNotEmpty()
        ui.resultCount.isVisible = hasResults
        ui.resultCount.text = StockFormat.count(shown.size)
        renderFilterChip(state.activeFilters)
        renderListTools(state, hasResults)
        // Invisible rather than gone: the hint also pushes the search and share buttons to the end of the row.
        ui.resultsHint.isInvisible = !hasResults
        for (index in 0 until ui.sortChips.childCount) ui.sortChips.getChildAt(index).isEnabled = hasResults
        renderSort(state)

        ui.results.isVisible = hasResults
        ui.emptyScroll.isVisible = !hasResults
        renderEmpty(state)

        // A star toggle only rebinds its card; anything that changes which stocks show, or their order, jumps to the top.
        // A fresh 360CR file redraws the cards' scores, and reorders a list sorted by them.
        val freshCr360 = state.cr360 !== shownCr360
        if (freshCr360 || state.setups !== adapter.setups || state.news !== adapter.news) {
            shownCr360 = state.cr360
            adapter.cr360 = state.cr360
            adapter.setups = state.setups
            adapter.news = state.news
            adapter.notifyItemRangeChanged(0, adapter.itemCount)
        }
        val reorder = state.results !== shownResults || state.activeFilters != shownFilters || state.sort != shownSort ||
            state.ascending != shownAscending || state.watchlistOnly != shownWatchlistOnly ||
            state.scanMode != shownScanMode || state.supportReach != shownSupportReach || state.query != shownQuery ||
            (freshCr360 && state.sort == SortMode.CR360)
        if (reorder || state.watchlist != shownWatchlist) {
            val freshResults = state.results !== shownResults
            // Live mode's fresh prices arrive while the user may be reading: the list keeps its place for them.
            val quiet = freshResults && shownResults != null && state.autoResults &&
                state.activeFilters == shownFilters && state.sort == shownSort && state.ascending == shownAscending &&
                state.watchlistOnly == shownWatchlistOnly && state.scanMode == shownScanMode &&
                state.supportReach == shownSupportReach && state.query == shownQuery
            shownResults = state.results
            shownFilters = state.activeFilters
            shownSort = state.sort
            shownAscending = state.ascending
            shownWatchlistOnly = state.watchlistOnly
            shownScanMode = state.scanMode
            shownSupportReach = state.supportReach
            shownQuery = state.query
            shownWatchlist = state.watchlist
            adapter.submitList(shown) {
                if (reorder && !quiet) ui.results.scrollToPosition(0)
                if (freshResults && hasResults && !quiet) ui.results.scheduleLayoutAnimation()
            }
        }
    }

    private fun renderEmpty(state: ScanUiState) {
        if (state.phase == ScanPhase.DONE && state.results.isNotEmpty()) {
            if (!renderListEmpty(state)) {
                ui.emptyTitle.setText(R.string.empty_filtered_title)
                ui.emptyBody.text = getString(R.string.empty_filtered_body, StockFormat.count(state.results.size))
                ui.emptyIcon.setImageResource(R.drawable.ic_tune)
            }
        } else {
            val (title, body, icon) = when {
                state.isRunning -> Triple(
                    R.string.empty_scanning_title,
                    if (settings.keepScreenOn) R.string.empty_scanning_body else R.string.empty_scanning_body_screen_off,
                    R.drawable.ic_radar,
                )
                state.reading -> Triple(R.string.status_reading_title, R.string.empty_reading_body, R.drawable.ic_radar)
                state.phase == ScanPhase.IDLE ->
                    Triple(R.string.empty_title, if (compact) R.string.empty_body_compact else R.string.empty_body, R.drawable.ic_trending_up)
                state.stopped -> Triple(R.string.empty_stopped_title, R.string.empty_stopped_body, R.drawable.ic_stop)
                else -> Triple(R.string.empty_failed_title, R.string.empty_failed_body, R.drawable.ic_error)
            }
            ui.emptyTitle.setText(title)
            ui.emptyBody.setText(body)
            ui.emptyIcon.setImageResource(icon)
        }

        val spin = (state.isRunning || state.reading) && ui.emptyScroll.isVisible
        if (spin && radarSpin == null) {
            radarSpin = ObjectAnimator.ofFloat(ui.emptyIcon, View.ROTATION, 0f, 360f).apply {
                duration = 2_400
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                start()
            }
        } else if (!spin && radarSpin != null) {
            radarSpin?.cancel()
            radarSpin = null
            ui.emptyIcon.rotation = 0f
        }
    }

    /** The chip lights up with the accent (activated) and shows a count while any limit is set. */
    private fun renderFilterChip(filters: ScanFilters) {
        val active = filters.activeCount
        ui.filterChip.text = if (active > 0) getString(R.string.filters_active, active) else getString(R.string.filters)
        ui.filterChip.isActivated = active > 0
    }

    /** The chosen sort is checked and carries its direction: "RVOL ↓" highest first, "RVOL ↑" lowest first. */
    private fun renderSort(state: ScanUiState) {
        for (mode in SortMode.entries) {
            val chip = sortChip(mode)
            val label = getString(sortLabel(mode))
            val chosen = mode == state.sort
            val text = if (!chosen) label else getString(if (state.ascending) R.string.sort_up else R.string.sort_down, label)
            // Every progress tick renders; leave the chip alone unless its text changes, so it does not lay out again.
            if (chip.text.toString() != text) chip.text = text
            chip.contentDescription = if (chosen) sortName(state) else null
        }
        val checked = sortChip(state.sort).id
        if (ui.sortChips.checkedChipId != checked) ui.sortChips.check(checked)
    }

    /** "RVOL: कम से ज़्यादा". */
    private fun sortName(state: ScanUiState): String = getString(
        R.string.sort_named,
        getString(sortLabel(state.sort)),
        getString(if (state.ascending) R.string.sort_low_first else R.string.sort_high_first),
    )

    private fun sortChip(mode: SortMode): Chip = when (mode) {
        SortMode.SCORE -> ui.sortScore
        SortMode.RVOL -> ui.sortRvol
        SortMode.CHANGE -> ui.sortChange
        SortMode.PRICE -> ui.sortPrice
        SortMode.SUPPORT -> ui.sortSupport
        SortMode.CR360 -> ui.sortCr360
    }

    private fun sortLabel(mode: SortMode): Int = when (mode) {
        SortMode.SCORE -> R.string.sort_score
        SortMode.RVOL -> R.string.sort_rvol
        SortMode.CHANGE -> R.string.sort_change
        SortMode.PRICE -> R.string.sort_price
        SortMode.SUPPORT -> R.string.sort_support
        SortMode.CR360 -> R.string.sort_cr360
    }

    private fun applyWindowInsets() {
        val listBottom = ui.results.paddingBottom
        val emptyBottom = ui.emptyState.paddingBottom
        val bars = WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        // The app bar handles the status bar itself (fitsSystemWindows + statusBarForeground in the layout),
        // so content scrolled under the status bar stays hidden.
        val barBottom = (ui.bottomBar.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin
        ViewCompat.setOnApplyWindowInsetsListener(ui.listContainer) { _, insets ->
            val inset = insets.getInsets(bars)
            // The compact list's floating bar stays clear of the navigation bar.
            ui.bottomBar.updateLayoutParams<ViewGroup.MarginLayoutParams> { bottomMargin = barBottom + inset.bottom }
            // While the search keyboard is up, the last cards can still scroll above it.
            val bottom = maxOf(inset.bottom, insets.getInsets(WindowInsetsCompat.Type.ime()).bottom)
            ui.results.updatePadding(left = inset.left, right = inset.right, bottom = listBottom + bottom)
            ui.emptyState.updatePadding(bottom = emptyBottom + bottom)
            insets
        }
    }

    /** Watchlist chip, symbol search and share button in the pinned results header. */
    private fun setUpListTools() {
        // A star toggle rebinds its card in place instead of cross-fading it.
        (ui.results.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        // Added last, the search's callback runs first: Back closes the search, then leaves the watchlist.
        onBackPressedDispatcher.addCallback(this, leaveWatchlistOnBack)
        onBackPressedDispatcher.addCallback(this, closeSearchOnBack)
        ui.watchlistChip.setOnCheckedChangeListener { _, checked -> scan.setWatchlistOnly(checked) }
        ui.watchlistChip.setOnCloseIconClickListener { scan.setWatchlistOnly(false) }
        ui.watchlistChip.closeIconContentDescription = getString(R.string.list_watchlist_leave)
        ui.modeBreakout.setOnClickListener { scan.setScanMode(ScanMode.BREAKOUT) }
        ui.modeSupport.setOnClickListener { scan.setScanMode(ScanMode.SUPPORT) }
        // The Support scan's reach: any distance above the floor, or within a few percent of it.
        for (reach in SUPPORT_REACHES) {
            val chip = layoutInflater.inflate(R.layout.chip_choice, ui.supportReachChips, false) as Chip
            chip.id = View.generateViewId()
            chip.tag = reach
            chip.text = reach?.let { getString(R.string.support_reach_within, it.toInt()) } ?: getString(R.string.support_reach_any)
            chip.isCheckable = true
            chip.setOnClickListener { scan.setSupportReach(reach) }
            ui.supportReachChips.addView(chip)
        }
        ui.searchButton.setOnClickListener { openSearch() }
        ui.searchClose.setOnClickListener { closeSearch() }
        ui.searchField.doAfterTextChanged { scan.setQuery(it?.toString().orEmpty()) }
        ui.searchField.setOnEditorActionListener { _, actionId, _ ->
            val search = actionId == EditorInfo.IME_ACTION_SEARCH
            if (search) keyboard().hide(WindowInsetsCompat.Type.ime())
            search
        }
        ui.shareButton.setOnClickListener { shareShown() }
    }

    private fun renderListTools(state: ScanUiState, hasResults: Boolean) {
        val stars = state.watchlist.size
        val watchlistLabel =
            if (stars > 0) getString(R.string.list_watchlist_count, stars) else getString(R.string.list_watchlist)
        if (ui.watchlistChip.text.toString() != watchlistLabel) ui.watchlistChip.text = watchlistLabel
        if (ui.watchlistChip.isChecked != state.watchlistOnly) ui.watchlistChip.isChecked = state.watchlistOnly
        // The ✕ on the chip shows the way back to the full list.
        ui.watchlistChip.isCloseIconVisible = state.watchlistOnly
        leaveWatchlistOnBack.isEnabled = state.watchlistOnly

        // The Support scan has its own list and filters, a reach to pick and no watchlist view.
        val support = state.scanMode == ScanMode.SUPPORT
        val modeChip = if (support) ui.modeSupport.id else ui.modeBreakout.id
        if (ui.scanModeChips.checkedChipId != modeChip) ui.scanModeChips.check(modeChip)
        ui.watchlistChip.isVisible = !support
        ui.supportReachChips.isVisible = support
        ui.sortSupport.isVisible = support
        // Checked through the group, which unchecks the others; unchecking one by hand would fight selectionRequired.
        val reachChip = (0 until ui.supportReachChips.childCount).map(ui.supportReachChips::getChildAt)
            .firstOrNull { it.tag == state.supportReach }
        if (reachChip != null && ui.supportReachChips.checkedChipId != reachChip.id) ui.supportReachChips.check(reachChip.id)

        val scanned = state.results.isNotEmpty()
        ui.searchButton.isVisible = scanned
        ui.shareButton.isVisible = scanned
        ui.shareButton.isEnabled = hasResults
        ui.shareButton.alpha = if (hasResults) 1f else 0.38f
        compactShare?.let {
            it.isVisible = scanned
            it.isEnabled = hasResults
        }
        ui.compactSearch.isEnabled = scanned
        ui.compactSearch.alpha = if (scanned) 1f else 0.38f

        ui.searchBar.isVisible = state.searchOpen
        closeSearchOnBack.isEnabled = state.searchOpen
        if (ui.searchField.text.toString() != state.query) {
            ui.searchField.setText(state.query)
            ui.searchField.setSelection(state.query.length)
        }
    }

    /** Empty states for the watchlist and the search; false when the filters alone emptied the list. */
    private fun renderListEmpty(state: ScanUiState): Boolean {
        val (title, body, icon) = when {
            state.listed.isNotEmpty() -> Triple(
                getString(R.string.list_empty_search_title),
                getString(R.string.list_empty_search_body, state.query.trim()),
                R.drawable.ic_search,
            )
            state.scanMode == ScanMode.SUPPORT -> Triple(
                getString(R.string.support_list_empty_title),
                getString(R.string.support_list_empty_body),
                R.drawable.ic_support,
            )
            !state.watchlistOnly -> return false
            state.watchlist.isEmpty() -> Triple(
                getString(R.string.list_empty_watchlist_title),
                getString(R.string.list_empty_watchlist_body),
                R.drawable.ic_star_outline,
            )
            // Stars with no chart yet, such as ones synced from another phone before any scan here.
            else -> Triple(
                getString(R.string.list_empty_watchlist_waiting_title),
                getString(R.string.list_empty_watchlist_waiting_body),
                R.drawable.ic_star,
            )
        }
        ui.emptyTitle.text = title
        ui.emptyBody.text = body
        ui.emptyIcon.setImageResource(icon)
        return true
    }

    private fun openSearch() {
        scan.openSearch()
        // Slide the header, hero and stats away so the search bar sits at the top, clear of the keyboard.
        ui.appBar.setExpanded(false, true)
        ui.searchBar.isVisible = true
        ui.searchField.requestFocus()
        keyboard().show(WindowInsetsCompat.Type.ime())
    }

    private fun closeSearch() {
        keyboard().hide(WindowInsetsCompat.Type.ime())
        // Hiding the bar also drops the field's focus, so it cannot pull the keyboard back.
        ui.searchBar.isVisible = false
        scan.closeSearch()
        // Bring back the header that opening search slid away, with its menu and SCAN button.
        ui.appBar.setExpanded(true, true)
    }

    private fun keyboard() = WindowCompat.getInsetsController(window, ui.searchField)

    /** Asks first, then clears the kept scan and its ranks, so the screen is back to before any scan. */
    private fun askToClear() {
        clearDialog?.dismiss()
        clearDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.clear_scan_title)
            .setMessage(R.string.clear_scan_body)
            .setNegativeButton(R.string.settings_confirm_no, null)
            .setPositiveButton(R.string.action_clear_scan) { _, _ ->
                // A live scan may have started while the question was open.
                if (scan.clearScans()) {
                    if (scan.uiState.value.searchOpen) closeSearch()
                    Toast.makeText(this, R.string.clear_scan_done, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, R.string.clear_scan_running, Toast.LENGTH_LONG).show()
                }
            }
            .show()
    }

    /** Shares the top of the list exactly as shown, so filters, watchlist, search and sort all apply. */
    private fun shareShown() {
        val state = scan.uiState.value
        val stocks = state.sortedResults.map { it.stock }.filter { it.charted }.take(ShareText.COUNT)
        if (stocks.isEmpty()) return
        val active = state.activeFilters.activeCount
        val filters =
            if (active > 0) getString(R.string.list_share_filters, active) else getString(R.string.list_share_no_filters)
        val header = ShareText.header(getString(R.string.app_name), LocalDate.now(), filters)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, header)
            .putExtra(Intent.EXTRA_TEXT, ShareText.build(header, getString(R.string.list_share_line), stocks))
        try {
            startActivity(Intent.createChooser(send, getString(R.string.list_share_open)))
        } catch (error: ActivityNotFoundException) {
            Toast.makeText(this, R.string.list_share_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun renderUpdate(offer: UpdateOffer?) {
        ui.updateBanner.isVisible = offer != null
        if (offer == null) return
        val ready = offer.step == UpdateStep.READY
        ui.updateTitle.setText(if (ready) R.string.update_title_ready else R.string.update_title)
        ui.updateVersions.text = getString(R.string.update_versions, offer.release.versionName, offer.installedName)
        ui.updateBody.text = when (offer.step) {
            UpdateStep.AVAILABLE -> getString(R.string.update_available_body)
            UpdateStep.DOWNLOADING -> getString(R.string.update_downloading_body, offer.percent)
            UpdateStep.READY -> getString(
                if (packageManager.canRequestPackageInstalls()) R.string.update_ready_body
                else R.string.update_ready_permission_body,
            )
            UpdateStep.FAILED -> getString(R.string.update_failed_body)
        }
        val downloading = offer.step == UpdateStep.DOWNLOADING
        ui.updateProgress.isVisible = downloading
        if (downloading) ui.updateProgress.setProgressCompat(offer.percent, true)
        ui.updateActions.isVisible = !downloading
        ui.updateAction.setText(
            when (offer.step) {
                UpdateStep.READY -> R.string.update_action_install
                UpdateStep.FAILED -> R.string.update_action_retry
                else -> R.string.update_action_download
            },
        )
    }

    /**
     * Hands a downloaded release to the system installer. Without the "install unknown apps" permission the
     * installer itself offers the setting and carries on with the install when the user comes back.
     */
    private fun install(apk: File) {
        // Its "ready to install" notification has done its job.
        Notifier.cancelUpdateReady(this)
        val uri = FileProvider.getUriForFile(this, "$packageName.${UpdateViewModel.UPDATE_FOLDER}", apk)
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, AppUpdate.APK_MIME)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(view)
        } catch (error: ActivityNotFoundException) {
            Toast.makeText(this, R.string.update_install_failed, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Opens the stock's detail sheet, on its जानकारी tab or ([onNews]) its खबरें tab; its button leads on to the chart
     * site picked in Settings.
     */
    private fun openChart(stock: ScanResult, onNews: Boolean = false) {
        if (!stock.charted) {
            Toast.makeText(this, getString(R.string.item_no_data_toast, stock.symbol), Toast.LENGTH_SHORT).show()
            return
        }
        detailSheet?.dismiss()
        val state = scan.uiState.value
        val alert = ScanEngine.watchStates(this)[stock.symbol]
        detailSheet = StockDetailSheet(
            this,
            stock,
            settings.chartSite,
            starred = stock.symbol in state.watchlist,
            alerts = settings.watchAlerts,
            alert = alert,
            cr360 = state.cr360.of(stock.symbol),
            setups = state.setups,
            openOnNews = onNews,
        ).show()
    }

    /** Industries by strength; picking one filters the list to it, the reset row clears the industry filter. */
    private fun openSectors(fromMenu: Boolean) {
        val state = scan.uiState.value
        val open = {
            SectorSheet(this, state.results, state.activeFilters.sectors, ::pickSector, if (fromMenu) ::openMenu else null).show()
        }
        if (fromMenu) {
            showMenuSheet(open)
        } else {
            sectorSheet?.dismiss()
            sectorSheet = open()
        }
    }

    private fun pickSector(sector: String?) {
        val state = scan.uiState.value
        val sectors = sector?.let(::setOf).orEmpty()
        if (state.scanMode == ScanMode.SUPPORT) {
            scan.setSupportFilters(state.supportFilters.copy(sectors = sectors))
        } else {
            scan.setFilters(state.filters.copy(sectors = sectors))
        }
        if (sector != null) Toast.makeText(this, getString(R.string.sector_applied, sector), Toast.LENGTH_SHORT).show()
    }

    private fun openMenu() {
        showMenuSheet { MenuSheet(this, updates.installedName, settings.theme, ::onMenuPick).show() }
    }

    private fun onMenuPick(entry: MenuSheet.Entry) {
        when (entry) {
            MenuSheet.Entry.ACCOUNT -> showMenuSheet { AccountSheet(this, ::openMenu).show() }
            MenuSheet.Entry.USAGE -> showMenuSheet { UsageSheet(this, ::openMenu).show() }
            MenuSheet.Entry.SCAN -> showMenuSheet {
                ScanSheet(this, settings, SectorRepository.book(this), scanActions, ::openMenu).show()
            }
            MenuSheet.Entry.TRACK_RECORD -> showMenuSheet { TrackRecordSheet(this, ScanEngine.journal(this), ::openMenu).show() }
            MenuSheet.Entry.SECTORS -> openSectors(fromMenu = true)
            MenuSheet.Entry.SETTINGS -> showMenuSheet {
                SettingsSheet(this, settings, scan.uiState.value.watchlist.size, settingsActions, ::openMenu).show()
            }
            MenuSheet.Entry.THEME -> showMenuSheet { ThemeSheet(this, settings.theme, ::applyTheme, ::openMenu).show() }
            MenuSheet.Entry.UPDATE -> {
                if (updates.canCheck) Toast.makeText(this, R.string.update_checking, Toast.LENGTH_SHORT).show()
                updates.checkNow()
            }
            MenuSheet.Entry.HELP -> showMenuSheet { HelpSheet(this, ::openMenu).show() }
            MenuSheet.Entry.SHARE -> shareApp()
            MenuSheet.Entry.ABOUT -> showMenuSheet {
                AboutSheet(this, updates.installedName, updates.installedCode, ::shareApp, ::openReleases, ::openMenu).show()
            }
        }
    }

    private fun showMenuSheet(open: () -> BottomSheetDialog) {
        menuSheet?.dismiss()
        menuSheet = open()
    }

    private val settingsActions = object : SettingsSheet.Actions {
        override fun keepScreenOnChanged(on: Boolean) {
            ui.root.keepScreenOn = scan.uiState.value.isRunning && on
            renderEmpty(scan.uiState.value)
        }

        override fun compactListChanged(on: Boolean) {
            settings.compactList = on
            recreate()
        }

        override fun skyAnimationChanged(on: Boolean) {
            ui.galaxySky.animate = on
            ui.spaceSky.animate = on
        }

        /** Like [applyTheme]: the scan and its results live on in the view model. */
        override fun glassStyleChanged(style: GlassStyle) {
            settings.glassStyle = style
            recreate()
        }

        /** Like [glassStyleChanged]: the screen is rebuilt with the glass on, or flat and solid. */
        override fun glassEnabledChanged(on: Boolean) {
            settings.glassEnabled = on
            recreate()
        }

        override fun clearWatchlist() = scan.clearWatchlist()

        override fun resetFilters() = scan.setFilters(ScanFilters())

        override fun clearRankHistory() = scan.clearRankHistory()
    }

    private val scanActions = object : ScanSheet.Actions {
        override fun liveChanged(notifying: Boolean) {
            LiveSchedule.sync(this@MainActivity)
            if (notifying) askToNotify(again = true)
            renderHero(scan.uiState.value)
        }

        override fun openNotificationSettings() {
            val open = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            try {
                startActivity(open)
            } catch (error: ActivityNotFoundException) {
                Toast.makeText(this@MainActivity, R.string.about_open_failed, Toast.LENGTH_SHORT).show()
            }
        }

        /** Android's own "let it run in the background?" dialog; App info (battery, autostart) once allowed or without it. */
        override fun openBackgroundSettings(allowed: Boolean) {
            val app = "package:$packageName".toUri()
            val tries = listOfNotNull(
                if (allowed) null else Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, app),
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, app),
            )
            for (open in tries) {
                try {
                    startActivity(open)
                    return
                } catch (error: ActivityNotFoundException) {
                    // Some phones leave the dialog out; App info comes next.
                }
            }
            Toast.makeText(this@MainActivity, R.string.about_open_failed, Toast.LENGTH_SHORT).show()
        }
    }

    /** Saves the theme and rebuilds the screen in it; the scan and its results live on in the view model. */
    private fun applyTheme(theme: ThemeChoice) {
        // Space Glass comes with the compact table it was designed with; Settings can turn the cards back on.
        if (theme == ThemeChoice.SPACE_GLASS && settings.theme != theme) settings.compactList = true
        settings.theme = theme
        recreate()
    }

    private fun showUpdateCheck(result: UpdateCheck) {
        val message = when (result) {
            UpdateCheck.Found -> {
                ui.appBar.setExpanded(true, true)
                getString(R.string.update_found)
            }
            is UpdateCheck.UpToDate -> getString(R.string.update_latest, result.version)
            UpdateCheck.Failed -> getString(R.string.update_check_failed)
            UpdateCheck.Disabled -> getString(R.string.update_check_debug)
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun shareApp() {
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, getString(R.string.share_app_subject))
            .putExtra(Intent.EXTRA_TEXT, getString(R.string.share_app_text, AppUpdate.DOWNLOAD_LINK))
        try {
            startActivity(Intent.createChooser(send, getString(R.string.share_app_open)))
        } catch (error: ActivityNotFoundException) {
            Toast.makeText(this, R.string.list_share_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private companion object {
        /** How often, in seconds, the hero's market line is brought up to date when no scan runs. */
        const val MARKET_REFRESH_S = 30
        val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
        val DAY_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM HH:mm", Locale.forLanguageTag("hi-IN"))

        /** The Support scan's reach choices, percent above the floor; null is any distance. */
        val SUPPORT_REACHES = listOf(null, 5.0, 10.0, 20.0)

        /** The Support scan's stat counts floors this close, percent above. */
        const val NEAR_SUPPORT_PCT = 5.0

        /**
         * How long after the kept scan is read a notification's stock may take to reach the list, before the user hears
         * it is not there.
         */
        const val PENDING_GRACE_MS = 2_000L

        /** How long a notification's stock, found on its own, waits for the R/S and learned-setup files. */
        const val BOOKS_WAIT_MS = 3_000L
    }

    private fun openReleases() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, AppUpdate.RELEASES_PAGE.toUri()))
        } catch (error: ActivityNotFoundException) {
            Toast.makeText(this, R.string.about_open_failed, Toast.LENGTH_SHORT).show()
        }
    }
}
