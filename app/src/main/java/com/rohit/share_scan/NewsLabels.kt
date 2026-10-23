package com.rohit.share_scan

import android.content.Context
import androidx.annotation.StringRes
import java.time.format.DateTimeFormatter
import java.util.Locale

/** How news shows: each [NewsKind]'s label and colours, and when an item came, in the app's words. */
object NewsLabels {
    private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
    private val DAY_CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.ENGLISH)

    @StringRes
    fun label(kind: NewsKind): Int = when (kind) {
        NewsKind.RESULTS -> R.string.news_kind_results
        NewsKind.ORDER -> R.string.news_kind_order
        NewsKind.DIVIDEND -> R.string.news_kind_dividend
        NewsKind.FUNDRAISE -> R.string.news_kind_fundraise
        NewsKind.DEAL -> R.string.news_kind_deal
        NewsKind.EXPANSION -> R.string.news_kind_expansion
        NewsKind.MANAGEMENT -> R.string.news_kind_management
        NewsKind.RATING -> R.string.news_kind_rating
        NewsKind.LEGAL -> R.string.news_kind_legal
        NewsKind.MEETING -> R.string.news_kind_meeting
        NewsKind.INVESTOR -> R.string.news_kind_investor
        NewsKind.OTHER -> R.string.news_kind_other
        NewsKind.ROUTINE -> R.string.news_kind_routine
    }

    /** Text and container colours for [kind]'s pill. Colour tells the kind apart, not whether the news is good. */
    fun colors(kind: NewsKind): Pair<Int, Int> = when (kind) {
        NewsKind.RESULTS, NewsKind.DEAL, NewsKind.RATING -> R.color.brand_primary to R.color.primary_container
        NewsKind.ORDER, NewsKind.DIVIDEND, NewsKind.EXPANSION -> R.color.bull to R.color.bull_container
        NewsKind.FUNDRAISE, NewsKind.MANAGEMENT, NewsKind.MEETING -> R.color.warn to R.color.warn_container
        NewsKind.LEGAL -> R.color.bear to R.color.bear_container
        NewsKind.INVESTOR, NewsKind.OTHER, NewsKind.ROUTINE -> R.color.neutral to R.color.neutral_container
    }

    fun timingColors(timing: StockNews.Timing): Pair<Int, Int> = when (timing) {
        StockNews.Timing.PRE_OPEN -> R.color.warn to R.color.warn_container
        StockNews.Timing.LIVE -> R.color.bull to R.color.bull_container
    }

    @StringRes
    fun timing(timing: StockNews.Timing): Int = when (timing) {
        StockNews.Timing.PRE_OPEN -> R.string.news_pre_open
        StockNews.Timing.LIVE -> R.string.news_live
    }

    /** "आज 08:42", "कल 17:10", or "19 Oct, 11:30" (IST). */
    fun whenText(context: Context, at: Long, now: Long = System.currentTimeMillis()): String {
        val time = MarketClock.now(at)
        val today = MarketClock.now(now).toLocalDate()
        return when (time.toLocalDate()) {
            today -> context.getString(R.string.news_today, CLOCK.format(time))
            today.minusDays(1) -> context.getString(R.string.news_yesterday, CLOCK.format(time))
            else -> DAY_CLOCK.format(time)
        }
    }
}
