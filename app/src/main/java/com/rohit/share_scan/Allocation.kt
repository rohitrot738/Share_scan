package com.rohit.share_scan

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.floor

/**
 * Spreads an amount over the watchlist's stocks (the /long-portfolio skill): each stock's share is the inverse of its
 * daily volatility (14-day ATR over the price), so a calmer stock gets more money and each adds about the same swing to
 * the whole. Also says how long each has been held for the tax on a sale (/long-rebalance): listed shares held over a
 * year pay long-term capital gains tax. Free of Android types so it can be unit tested.
 */
object Allocation {
    data class Part(
        val stock: ScanResult,
        /** Share of the amount, 0 to 1. */
        val weight: Double,
        val amount: Double,
        /** Whole shares that amount buys at today's price. */
        val qty: Int,
        /** Its daily volatility: ATR over price, in percent. */
        val volatilityPct: Double,
    )

    /** Held longer than this many days, a listed share's gain is long-term. */
    const val LONG_TERM_DAYS = 365L

    /** Equity tax since 23 July 2024: 20% on short-term gains, 12.5% on long-term ones over ₹1.25 lakh a year. */
    const val SHORT_TERM_TAX_PCT = 20.0
    const val LONG_TERM_TAX_PCT = 12.5

    /**
     * [total] spread over [stocks] by inverse volatility; a stock without a price or an ATR is left out. Largest share
     * first.
     */
    fun inverseVolatility(stocks: List<ScanResult>, total: Double): List<Part> {
        if (!(total > 0.0)) return emptyList()
        val vols = stocks.mapNotNull { stock ->
            val atr = stock.structure?.atr14?.takeIf { it > 0.0 } ?: return@mapNotNull null
            if (!(stock.price > 0.0)) return@mapNotNull null
            stock to atr / stock.price * 100.0
        }
        if (vols.isEmpty()) return emptyList()
        val inverse = vols.sumOf { 1.0 / it.second }
        return vols.map { (stock, vol) ->
            val weight = (1.0 / vol) / inverse
            val amount = total * weight
            Part(stock, weight, amount, floor(amount / stock.price + 1e-9).toInt(), vol)
        }.sortedByDescending { it.weight }
    }

    /** Days from [from] to [today]. */
    fun daysHeld(from: LocalDate, today: LocalDate): Long = ChronoUnit.DAYS.between(from, today).coerceAtLeast(0)

    fun longTerm(from: LocalDate, today: LocalDate): Boolean = daysHeld(from, today) > LONG_TERM_DAYS
}
