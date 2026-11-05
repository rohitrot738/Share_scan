package com.rohit.share_scan

import android.content.Context
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import androidx.core.content.ContextCompat

/**
 * A stock's moves as the list shows them: today's (since the session before, live while the market trades) and the
 * 5 sessions', each in its own colour. A row from a scan saved before today's move was kept shows the 5 sessions'
 * alone.
 */
object MoveText {
    /** "▲ 1.20% आज · ▲ 5.20% 5D" ([arrows]) or "+1.20% आज · +5.20% 5D". */
    fun both(context: Context, stock: ScanResult, arrows: Boolean = true): CharSequence = SpannableStringBuilder().apply {
        stock.change1d?.let { today ->
            coloured(context, context.getString(R.string.move_today, format(today, arrows)), today)
            append(" · ")
        }
        coloured(context, context.getString(R.string.move_5d, format(stock.change5d, arrows)), stock.change5d)
    }

    /** "+1.20% आज · +5.20% 5D" for the watchlist's line. */
    fun bothSigned(context: Context, stock: ScanResult): CharSequence = both(context, stock, arrows = false)

    /**
     * The price with today's move after it, smaller and in its colour: on a line of its own under the price
     * ([below]), as in the compact table, or beside it.
     */
    fun priceAndToday(context: Context, stock: ScanResult, below: Boolean): CharSequence = SpannableStringBuilder().apply {
        append(context.getString(R.string.item_price, StockFormat.price(stock.price)))
        val today = stock.change1d ?: return@apply
        append(if (below) "\n" else "  ")
        val start = length
        coloured(context, StockFormat.signedChange(today), today)
        setSpan(RelativeSizeSpan(0.8f), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun format(value: Double, arrows: Boolean): String = if (arrows) StockFormat.change(value) else StockFormat.signedChange(value)

    private fun SpannableStringBuilder.coloured(context: Context, text: String, value: Double) {
        val start = length
        append(text)
        val colour = ContextCompat.getColor(context, if (value >= 0) R.color.bull else R.color.bear)
        setSpan(ForegroundColorSpan(colour), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}
