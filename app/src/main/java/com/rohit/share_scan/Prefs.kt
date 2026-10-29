package com.rohit.share_scan

/** Shared preference file and keys used across the app. */
object Prefs {
    const val FILE = "scan"

    /** [ScanFilters.encode] string. */
    const val FILTERS = "filters"

    /** String set of watchlisted NSE symbols. */
    const val WATCHLIST = "watchlist"

    /** [WatchBook.encode] text: when and at what price each watchlisted stock was added. */
    const val WATCH_ADDED = "watch_added"

    /** Score ranks from the previous completed scan, used for NEW badges and rank changes. */
    const val LAST_RANKS = "last_ranks"

    /** [ScanMode.name] of the scan the home screen showed last. */
    const val SCAN_MODE = "scan_mode"

    /** [ScanFilters.encode] string of the Support scan's own filters. */
    const val SUPPORT_FILTERS = "support_filters"

    /** [ScanSectors.key] of the scan [LAST_RANKS] came from; ranks of other stocks are not compared. */
    const val LAST_RANKS_SCOPE = "last_ranks_scope"

    /** Version code of a release the user chose "बाद में" for; its banner stays hidden. */
    const val UPDATE_DISMISSED = "update_dismissed"

    /** [ThemeChoice.key] of the picked theme. */
    const val THEME = "theme"

    /** [BackgroundChoice.key] of the picked background, which brings its layout; until one is picked, [COMPACT_LIST]. */
    const val BACKGROUND = "background"

    /** [ListLayout.key] of how the home list draws its stocks; missing is the background's own. */
    const val LIST_LAYOUT = "list_layout"

    /** [AppFont.key] of the picked font; missing is the phone's own. */
    const val FONT = "font"

    /**
     * [AppLanguage.tag] of the picked language, kept beside AppCompat's own record for strings made outside the
     * screens on Android 12 and older ([Languages.localized]); missing until the first start with languages.
     */
    const val LANGUAGE = "language"

    /** [ChartSite.key] the detail sheet's chart button opens. */
    const val CHART_SITE = "chart_site"

    /** [GlassStyle.key] of the picked glass mode. */
    const val GLASS_STYLE = "glass_style"

    /** Whether the glass is on at all; on by default, off gives flat, solid surfaces whatever mode is picked. */
    const val GLASS_ENABLED = "glass_enabled"

    /** Whether the sky behind the home screen moves; on by default. */
    const val SKY_ANIMATION = "sky_animation"

    /** Whether the list's cards show what each company makes as icons ([Products]); on by default. */
    const val PRODUCT_ICONS = "product_icons"

    /** 1.2.138's compact list switch, now only read to pick the first [BACKGROUND] (on: Space Glass). */
    const val COMPACT_LIST = "compact_list"

    /** Live mode: scan through market hours in the background (on by default), and how often, in minutes. */
    const val LIVE_SCAN = "live_scan"
    const val LIVE_INTERVAL = "live_interval"

    /** Notifications for stocks a live scan finds newly STRONG or breaking out; on by default. */
    const val LIVE_ALERTS = "live_alerts"

    /** Breakout, target and stop alerts for watchlisted stocks; on by default. */
    const val WATCH_ALERTS = "watch_alerts"

    /** [WatchAlerts.encode] text: each watchlisted stock's alert state. */
    const val WATCH_STATES = "watch_states"

    /** Watchlisted stocks' news alerts ([NewsAlerts]); on by default. */
    const val NEWS_ALERTS = "news_alerts"

    /** Ids of the filings a news alert or round-up already covered, with when they came ("id@millis" lines). */
    const val NEWS_SEEN = "news_seen"

    /** Epoch day (IST) of the latest morning round-up of the watchlist's news. */
    const val NEWS_DIGEST_DAY = "news_digest_day"

    /** The app asked once for permission to post notifications. */
    const val NOTIFY_ASKED = "notify_asked"

    /** Epoch day (IST) that live mode found to be an exchange holiday; it scans no more that day. */
    const val CLOSED_DAY = "closed_day"

    /** Firebase uid whose cloud watchlist this phone's watchlist was last merged with. */
    const val WATCHLIST_SYNCED_UID = "watchlist_synced_uid"

    /** Random id this phone counts under in [UsageCounter]; nothing else about the phone goes with it. */
    const val INSTALL_ID = "install_id"

    /** Epoch day (IST) of this phone's last [UsageCounter] check-in. */
    const val USAGE_DAY = "usage_day"

    /**
     * Prefix of the industries the scan covers on one exchange ([ScanSectors.encode]), followed by the market's name in
     * lower case ("scan_sectors_nse"); missing means every industry.
     */
    const val SCAN_SECTORS = "scan_sectors_"

    /** Booleans from the Settings sheet; the first three are on by default, the SME and BSE scans off. */
    const val CHECK_UPDATES = "check_updates"
    const val KEEP_SCREEN_ON = "keep_screen_on"
    const val INCLUDE_NSE = "include_nse"
    const val INCLUDE_SME = "include_sme"
    const val INCLUDE_BSE = "include_bse"
}
