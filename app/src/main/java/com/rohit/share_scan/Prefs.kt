package com.rohit.share_scan

/** Shared preference file and keys used across the app. */
object Prefs {
    const val FILE = "scan"

    /** [ScanFilters.encode] string. */
    const val FILTERS = "filters"

    /** String set of watchlisted NSE symbols. */
    const val WATCHLIST = "watchlist"

    /** Score ranks from the previous completed scan, used for NEW badges and rank changes. */
    const val LAST_RANKS = "last_ranks"

    /** Version code of a release the user chose "बाद में" for; its banner stays hidden. */
    const val UPDATE_DISMISSED = "update_dismissed"
}
