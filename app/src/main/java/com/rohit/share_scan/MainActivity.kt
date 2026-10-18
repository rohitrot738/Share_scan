package com.rohit.share_scan

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.SimpleItemAnimator
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.rohit.share_scan.databinding.ActivityMainBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate

class MainActivity : AppCompatActivity() {
    private val scan: ScanViewModel by viewModels()
    private val updates: UpdateViewModel by viewModels()
    private lateinit var settings: AppSettings
    private lateinit var ui: ActivityMainBinding
    private val adapter = StockAdapter(::openChart) { stock -> scan.toggleStar(stock.symbol) }
    private var shownResults: List<ScanResult>? = null
    private var shownFilters: ScanFilters? = null
    private var shownSort: SortMode? = null
    private var shownWatchlistOnly: Boolean? = null
    private var shownQuery: String? = null
    private var shownWatchlist: Set<String>? = null
    private val closeSearchOnBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = closeSearch()
    }
    private var filterSheet: BottomSheetDialog? = null
    private var detailSheet: BottomSheetDialog? = null
    /** The menu or whichever of its sheets is open; only one shows at a time. */
    private var menuSheet: BottomSheetDialog? = null
    private var shownRing = -1
    private var radarSpin: ObjectAnimator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        // The picked theme has to be in place before any view (or the window background) is created.
        settings = AppSettings(this)
        setTheme(settings.theme.style)
        // The glass mode goes over the theme: cards, sheets, chips and switches read their glass from it.
        theme.applyStyle(settings.glassStyle.overlay, true)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ui = ActivityMainBinding.inflate(layoutInflater)
        setContentView(ui.root)
        ui.galaxySky.palette = settings.theme.skyPalette
        ui.galaxySky.animate = settings.skyAnimation
        ui.hero.clipToOutline = true
        ui.hero.applyGlassHero()
        ui.results.adapter = adapter
        applyWindowInsets()
        setUpListTools()
        ui.updateAction.setOnClickListener { updates.proceed() }
        ui.updateLater.setOnClickListener { updates.dismiss() }
        ui.menuButton.setOnClickListener { openMenu() }

        ui.startButton.setOnClickListener {
            // While a scan runs the same button stops it.
            if (scan.uiState.value.isRunning) {
                scan.stopScan()
            } else {
                ui.appBar.setExpanded(true, true)
                scan.startScan()
            }
        }
        ui.filterChip.setOnClickListener {
            filterSheet?.dismiss()
            filterSheet = FilterSheet(this, scan.uiState.value, scan::setFilters).show()
        }
        ui.sortChips.setOnCheckedStateChangeListener { _, checked ->
            scan.setSort(
                when (checked.firstOrNull()) {
                    R.id.sortRvol -> SortMode.RVOL
                    R.id.sortChange -> SortMode.CHANGE
                    R.id.sortPrice -> SortMode.PRICE
                    else -> SortMode.SCORE
                }
            )
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { scan.uiState.collect { render(it) } }
                launch { updates.offer.collect { renderUpdate(it) } }
                launch { updates.installRequests.collect { install(it) } }
                launch { updates.checkResults.collect { showUpdateCheck(it) } }
                launch {
                    // Keeps the elapsed-time clock in the hero card ticking during a scan.
                    while (true) {
                        delay(1_000)
                        val state = scan.uiState.value
                        if (state.isRunning) renderHero(state)
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        radarSpin?.cancel()
        filterSheet?.dismiss()
        detailSheet?.dismiss()
        menuSheet?.dismiss()
        super.onDestroy()
    }

    private fun render(state: ScanUiState) {
        renderHero(state)
        renderStats(state)
        renderResults(state)
        ui.root.keepScreenOn = state.isRunning && settings.keepScreenOn
    }

    private fun renderHero(state: ScanUiState) {
        val clock = StockFormat.duration(state.elapsedAt(SystemClock.elapsedRealtime()))
        when (state.phase) {
            ScanPhase.IDLE -> showStatus(
                getString(R.string.status_ready_title),
                getString(R.string.status_ready_body),
                R.drawable.ic_radar,
                0,
            )
            ScanPhase.LOADING -> showStatus(
                getString(R.string.status_loading_title),
                getString(R.string.status_loading_body, clock),
                R.drawable.ic_radar,
                0,
            )
            ScanPhase.SCANNING -> showStatus(
                getString(R.string.status_scanning_title),
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
                    if (state.total > 0) {
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
            } else {
                showStatus(
                    getString(R.string.status_done_title),
                    getString(R.string.status_done_body, state.visible.size, clock),
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

        val (label, icon) = when (state.phase) {
            ScanPhase.IDLE -> R.string.action_start to R.drawable.ic_play
            ScanPhase.LOADING, ScanPhase.SCANNING ->
                (if (state.stopping) R.string.action_stopping else R.string.action_stop) to R.drawable.ic_stop
            ScanPhase.DONE -> if (state.stopped) {
                R.string.action_start to R.drawable.ic_play
            } else {
                R.string.action_rescan to R.drawable.ic_refresh
            }
            ScanPhase.FAILED -> R.string.action_retry to R.drawable.ic_refresh
        }
        ui.startButton.setText(label)
        ui.startButton.setIconResource(icon)
        // A running scan's button stops it; it rests only while a stop finishes the requests under way.
        ui.startButton.isEnabled = !state.stopping
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
        ui.statTopValue.text = state.visible.firstOrNull()?.let { StockFormat.score(it.stock.score) } ?: empty
        ui.statSpikesValue.text = if (state.visible.isEmpty()) empty else state.volumeSpikes.toString()
    }

    private fun renderResults(state: ScanUiState) {
        val shown = state.sortedResults
        val hasResults = shown.isNotEmpty()
        ui.resultCount.isVisible = hasResults
        ui.resultCount.text = StockFormat.count(shown.size)
        renderFilterChip(state.filters)
        renderListTools(state, hasResults)
        // Invisible rather than gone: the hint also pushes the search and share buttons to the end of the row.
        ui.resultsHint.isInvisible = !hasResults
        for (index in 0 until ui.sortChips.childCount) ui.sortChips.getChildAt(index).isEnabled = hasResults
        val sortChip = when (state.sort) {
            SortMode.SCORE -> R.id.sortScore
            SortMode.RVOL -> R.id.sortRvol
            SortMode.CHANGE -> R.id.sortChange
            SortMode.PRICE -> R.id.sortPrice
        }
        if (ui.sortChips.checkedChipId != sortChip) ui.sortChips.check(sortChip)

        ui.results.isVisible = hasResults
        ui.emptyScroll.isVisible = !hasResults
        renderEmpty(state)

        // A star toggle only rebinds its card; anything that changes which stocks show, or their order, jumps to the top.
        val reorder = state.results !== shownResults || state.filters != shownFilters || state.sort != shownSort ||
            state.watchlistOnly != shownWatchlistOnly || state.query != shownQuery
        if (reorder || state.watchlist != shownWatchlist) {
            val freshResults = state.results !== shownResults
            shownResults = state.results
            shownFilters = state.filters
            shownSort = state.sort
            shownWatchlistOnly = state.watchlistOnly
            shownQuery = state.query
            shownWatchlist = state.watchlist
            adapter.submitList(shown) {
                if (reorder) ui.results.scrollToPosition(0)
                if (freshResults && hasResults) ui.results.scheduleLayoutAnimation()
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
                state.phase == ScanPhase.IDLE -> Triple(R.string.empty_title, R.string.empty_body, R.drawable.ic_trending_up)
                state.stopped -> Triple(R.string.empty_stopped_title, R.string.empty_stopped_body, R.drawable.ic_stop)
                else -> Triple(R.string.empty_failed_title, R.string.empty_failed_body, R.drawable.ic_error)
            }
            ui.emptyTitle.setText(title)
            ui.emptyBody.setText(body)
            ui.emptyIcon.setImageResource(icon)
        }

        val spin = state.isRunning && ui.emptyScroll.isVisible
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

    private fun applyWindowInsets() {
        val listBottom = ui.results.paddingBottom
        val emptyBottom = ui.emptyState.paddingBottom
        val bars = WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        // The app bar handles the status bar itself (fitsSystemWindows + statusBarForeground in the layout),
        // so content scrolled under the status bar stays hidden.
        ViewCompat.setOnApplyWindowInsetsListener(ui.listContainer) { _, insets ->
            val inset = insets.getInsets(bars)
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
        onBackPressedDispatcher.addCallback(this, closeSearchOnBack)
        ui.watchlistChip.setOnCheckedChangeListener { _, checked -> scan.setWatchlistOnly(checked) }
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

        val scanned = state.results.isNotEmpty()
        ui.searchButton.isVisible = scanned
        ui.shareButton.isVisible = scanned
        ui.shareButton.isEnabled = hasResults
        ui.shareButton.alpha = if (hasResults) 1f else 0.38f

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
            !state.watchlistOnly -> return false
            state.watchlist.isEmpty() -> Triple(
                getString(R.string.list_empty_watchlist_title),
                getString(R.string.list_empty_watchlist_body),
                R.drawable.ic_star_outline,
            )
            else -> Triple(
                getString(R.string.list_empty_watchlist_filtered_title),
                getString(R.string.list_empty_watchlist_filtered_body),
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

    /** Shares the top of the list exactly as shown, so filters, watchlist, search and sort all apply. */
    private fun shareShown() {
        val state = scan.uiState.value
        val stocks = state.sortedResults.take(ShareText.COUNT).map { it.stock }
        if (stocks.isEmpty()) return
        val active = state.filters.activeCount
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

    /** Opens the stock's detail sheet; its button leads on to the chart site picked in Settings. */
    private fun openChart(stock: ScanResult) {
        detailSheet?.dismiss()
        detailSheet = StockDetailSheet(this, stock, settings.chartSite).show()
    }

    private fun openMenu() {
        showMenuSheet { MenuSheet(this, updates.installedName, settings.theme, ::onMenuPick).show() }
    }

    private fun onMenuPick(entry: MenuSheet.Entry) {
        when (entry) {
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

        override fun skyAnimationChanged(on: Boolean) {
            ui.galaxySky.animate = on
        }

        /** Like [applyTheme]: the scan and its results live on in the view model. */
        override fun glassStyleChanged(style: GlassStyle) {
            settings.glassStyle = style
            recreate()
        }

        override fun clearWatchlist() = scan.clearWatchlist()

        override fun resetFilters() = scan.setFilters(ScanFilters())

        override fun clearRankHistory() = scan.clearRankHistory()
    }

    /** Saves the theme and rebuilds the screen in it; the scan and its results live on in the view model. */
    private fun applyTheme(theme: ThemeChoice) {
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

    private fun openReleases() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, AppUpdate.RELEASES_PAGE.toUri()))
        } catch (error: ActivityNotFoundException) {
            Toast.makeText(this, R.string.about_open_failed, Toast.LENGTH_SHORT).show()
        }
    }
}
