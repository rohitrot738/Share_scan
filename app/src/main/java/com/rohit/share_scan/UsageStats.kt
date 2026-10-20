package com.rohit.share_scan

import org.json.JSONArray
import java.time.Instant
import java.time.ZoneId

/**
 * How many phones opened the app lately, from the check-ins [UsageCounter] keeps: one per phone, holding when it last
 * opened the app and its version. Free of Android types so it can be unit tested.
 */
data class UsageStats(
    /** Phones that opened the app since midnight IST. */
    val today: Int,
    /** Phones that opened it in the last [WEEK_DAYS] days. */
    val week: Int,
    /** Phones that opened it in the last [MONTH_DAYS] days. */
    val month: Int,
    /** The versions on those [month] phones, most phones first: "1.2.123" to 4. */
    val versions: List<Pair<String, Int>>,
) {
    /** One phone's latest check-in: when (epoch millis) and the version it ran. */
    data class CheckIn(val seenAt: Long, val version: String)

    /** How many releases one page of GitHub's list held, and how often their APKs were downloaded. */
    data class ReleasePage(val releases: Int, val downloads: Int)

    companion object {
        const val WEEK_DAYS = 7
        const val MONTH_DAYS = 30
        const val DAY_MS = 24 * 60 * 60 * 1000L

        /** The counts at [now] (epoch millis) from [checkIns]; "today" starts at midnight in [zone]. */
        fun of(checkIns: List<CheckIn>, now: Long, zone: ZoneId = MarketClock.INDIA): UsageStats {
            val midnight = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
            val month = checkIns.filter { it.seenAt >= now - MONTH_DAYS * DAY_MS }
            val versions = month.groupingBy { it.version.ifBlank { "?" } }.eachCount().entries
                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenByDescending { it.key })
                .map { it.key to it.value }
            return UsageStats(
                today = month.count { it.seenAt >= midnight },
                week = month.count { it.seenAt >= now - WEEK_DAYS * DAY_MS },
                month = month.size,
                versions = versions,
            )
        }

        /** One page of GitHub's release list, or null when [json] is not one. */
        fun releasePage(json: String): ReleasePage? = runCatching {
            val releases = JSONArray(json)
            var downloads = 0
            for (i in 0 until releases.length()) {
                val assets = releases.getJSONObject(i).optJSONArray("assets") ?: continue
                for (j in 0 until assets.length()) {
                    val asset = assets.getJSONObject(j)
                    if (asset.optString("name") == AppUpdate.APK_NAME) downloads += asset.optInt("download_count")
                }
            }
            ReleasePage(releases.length(), downloads)
        }.getOrNull()
    }
}
