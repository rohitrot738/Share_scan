package com.rohit.share_scan

/** Shared preference file and keys; the scan screen and the background alert read the same values. */
object Prefs {
    const val FILE = "scan"

    /** [ScanFilters.encode] string. */
    const val FILTERS = "filters"

    /** String set of watchlisted NSE symbols. */
    const val WATCHLIST = "watchlist"

    /** Score ranks from the previous completed scan, used for NEW badges and rank changes. */
    const val LAST_RANKS = "last_ranks"

    /** Boolean: whether the daily background scan and notification are on. */
    const val ALERTS = "alerts"
}
