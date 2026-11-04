package com.rohit.share_scan

import android.content.Context
import com.rohit.share_scan.BigMoneyBook.Signal
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/** How बड़े पैसे की चाल ([BigMoneyBook]) reads: in the stock's card, the Menu section, the list's mark and the alerts. */
object BigMoneyText {
    private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM")
    private val FULL_DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy")

    /** A score of this much or more reads strong, as the track record's "score60" counts it. */
    const val STRONG = 60

    fun pct(value: Double?): String = value?.let { Cr360Format.number(it, 1) + "%" } ?: "—"

    fun times(value: Double?): String = value?.let { Cr360Format.number(it, 2) } ?: "—"

    /** A rupee amount, its sign dropped: "₹20 Cr", "₹0.5 Cr". */
    fun rupees(value: Double): String = StockFormat.crore(abs(value) / 1e7)

    fun shares(value: Long?): String = value?.let { StockFormat.shares(abs(it).toDouble()) } ?: "—"

    fun day(date: LocalDate?): String = date?.format(DAY) ?: "—"

    /** "30 Apr 2026", for a date that may lie in another year. */
    fun fullDay(date: LocalDate?): String = date?.format(FULL_DAY) ?: "—"

    /** The note on promoter data a month or more behind the session; null while it is recent. */
    fun promotersStale(context: Context, book: BigMoneyBook): String? =
        book.promoterUntil?.takeIf { book.promotersStale }?.let { context.getString(R.string.big_money_promoters_stale, fullDay(it)) }

    /** The list's mark: "🟢 जमा" for accumulation, "🔴 जाल" for churn, with its colour; null for neither. */
    fun mark(context: Context, entry: BigMoneyBook.Entry?): Pair<String, Int>? = when {
        entry == null -> null
        Signal.ACCUMULATION in entry.signals -> context.getString(R.string.big_money_mark_acc) to R.color.bull
        Signal.CHURN in entry.signals -> context.getString(R.string.big_money_mark_churn) to R.color.bear
        else -> null
    }

    /** What a screen reader says for [mark]. */
    fun markDescription(context: Context, entry: BigMoneyBook.Entry?): String? = when {
        entry == null -> null
        Signal.ACCUMULATION in entry.signals -> context.getString(R.string.big_money_mark_acc_description)
        Signal.CHURN in entry.signals -> context.getString(R.string.big_money_mark_churn_description)
        else -> null
    }

    fun signal(context: Context, signal: Signal): String = context.getString(
        when (signal) {
            Signal.ACCUMULATION -> R.string.big_money_signal_acc
            Signal.CHURN -> R.string.big_money_signal_churn
            Signal.DEAL_BUY -> R.string.big_money_signal_deal_buy
            Signal.DEAL_SELL -> R.string.big_money_signal_deal_sell
            Signal.PROMOTER_BUY -> R.string.big_money_signal_promoter_buy
            Signal.PROMOTER_SELL -> R.string.big_money_signal_promoter_sell
        },
    )

    fun tone(signal: Signal): Int = when (signal) {
        Signal.ACCUMULATION, Signal.DEAL_BUY, Signal.PROMOTER_BUY -> R.color.bull
        Signal.CHURN, Signal.DEAL_SELL, Signal.PROMOTER_SELL -> R.color.bear
    }

    /**
     * A signal's past year: "पिछले साल 1,240 बार; 10 सत्र बाद औसत +1.9% (सब शेयर +0.7%), 56% बार ऊपर", with a warning
     * when it did not hold (an inviting signal no better than all shares, a [bearish] one no worse); under 30 times,
     * that the data is still building up.
     */
    fun record(context: Context, record: BigMoneyBook.Record?, bearish: Boolean): String {
        if (record == null || !record.ready) return context.getString(R.string.big_money_record_waiting, record?.n ?: 0)
        val avg = record.avg ?: 0.0
        val base = record.base ?: 0.0
        val line = context.getString(
            R.string.big_money_record,
            StockFormat.count(record.n),
            Cr360Format.signed(avg, 1) + "%",
            Cr360Format.signed(base, 1) + "%",
            Cr360Format.number(record.up ?: 0.0, 0) + "%",
        )
        return when (record.worked(bearish)) {
            false -> line + "\n" + context.getString(if (bearish) R.string.big_money_record_warning_weak else R.string.big_money_record_worse)
            else -> line
        }
    }

    fun streak(context: Context, deal: BigMoneyBook.Deal): String = when {
        deal.streak >= 2 -> context.getString(R.string.big_money_streak_buy, deal.streak)
        deal.streak <= -2 -> context.getString(R.string.big_money_streak_sell, -deal.streak)
        else -> context.getString(R.string.big_money_streak_none)
    }

    /** One part of the score: its name, the points it gave, the most it can give, and the sum behind it. */
    data class Part(val name: String, val points: Double, val max: Int, val body: String)

    fun parts(context: Context, entry: BigMoneyBook.Entry): List<Part> {
        val p = entry.parts + List((6 - entry.parts.size).coerceAtLeast(0)) { 0.0 }
        val deal = entry.deal
        val promoter = entry.promoter
        val build = if (entry.delivery5 != null && entry.deliveryAvg != null && entry.deliveryAvg > 0) entry.delivery5 / entry.deliveryAvg else null
        val churned = Signal.CHURN in entry.signals
        return listOf(
            Part(
                context.getString(R.string.big_money_part_spike), p[0], 30,
                context.getString(R.string.big_money_part_body_spike, pct(entry.delivery), pct(entry.deliveryAvg), times(entry.deliveryRatio)),
            ),
            Part(
                context.getString(R.string.big_money_part_build), p[1], 20,
                context.getString(R.string.big_money_part_body_build, pct(entry.delivery5), pct(entry.deliveryAvg), times(build)),
            ),
            Part(
                context.getString(R.string.big_money_part_deals), p[2], 25,
                if (deal == null) {
                    context.getString(R.string.big_money_deal_none)
                } else {
                    context.getString(
                        R.string.big_money_part_body_deals,
                        (if (deal.shares < 0) "−" else "") + shares(deal.shares),
                        rupees(deal.rupees),
                        shares(entry.volumeAvg?.toLong()),
                        streak(context, deal),
                    )
                },
            ),
            Part(
                context.getString(R.string.big_money_part_promoter), p[3], 25,
                if (promoter == null) {
                    context.getString(R.string.big_money_promoter_none)
                } else {
                    context.getString(
                        if (promoter.rupees >= 0) R.string.big_money_part_body_promoter_buy else R.string.big_money_part_body_promoter_sell,
                        rupees(promoter.rupees),
                        promoter.buys,
                        promoter.sells,
                    )
                },
            ),
            Part(
                context.getString(R.string.big_money_part_churn), p[4], 30,
                context.getString(
                    if (churned) R.string.big_money_part_body_churn else R.string.big_money_part_body_no_churn,
                    times(entry.volumeRatio),
                    pct(entry.delivery),
                ),
            ),
            Part(
                context.getString(R.string.big_money_part_selling), p[5], 50,
                context.getString(if (p[5] < 0) R.string.big_money_part_body_selling else R.string.big_money_part_body_no_selling),
            ),
        )
    }

    /** "22.5", "−30", "0". */
    fun points(value: Double): String = Cr360Format.number(value, 1)
}
