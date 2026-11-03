package com.rohit.share_scan

import android.content.Context
import androidx.core.content.edit
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/**
 * The phone's trading settings and the trade log ([TradeLog]): the capital, the risk a trade may take, delivery or
 * intraday, the risk guard's rules ([RiskGuard]), and the trades written down. Kept in the app's preferences.
 */
object TradeStore {
    private const val CAPITAL = "trade_capital"
    private const val RISK = "trade_risk"
    private const val PRODUCT = "trade_product"
    private const val LOG = "trade_log"
    private const val MAX_TRADES = "trade_max_trades"
    private const val LOSS_PCT = "trade_loss_pct"

    private fun prefs(context: Context) = context.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE)

    /** The trading capital in rupees; 0 until the user sets it. */
    fun capital(context: Context): Double = prefs(context).getFloat(CAPITAL, 0f).toDouble()

    fun setCapital(context: Context, value: Double) = prefs(context).edit { putFloat(CAPITAL, value.coerceAtLeast(0.0).toFloat()) }

    fun risk(context: Context): Double =
        prefs(context).getFloat(RISK, PositionSize.DEFAULT_RISK.toFloat()).toDouble().takeIf { it in PositionSize.RISK_CHOICES }
            ?: PositionSize.DEFAULT_RISK

    fun setRisk(context: Context, value: Double) = prefs(context).edit { putFloat(RISK, value.toFloat()) }

    fun product(context: Context): PositionSize.Product =
        runCatching { PositionSize.Product.valueOf(prefs(context).getString(PRODUCT, null) ?: "") }.getOrDefault(PositionSize.Product.CNC)

    fun setProduct(context: Context, value: PositionSize.Product) = prefs(context).edit { putString(PRODUCT, value.name) }

    fun rules(context: Context): RiskGuard.Rules = RiskGuard.Rules(
        maxTrades = prefs(context).getInt(MAX_TRADES, RiskGuard.DEFAULT_MAX_TRADES).takeIf { it in RiskGuard.MAX_TRADES_CHOICES }
            ?: RiskGuard.DEFAULT_MAX_TRADES,
        dailyLossPct = prefs(context).getFloat(LOSS_PCT, RiskGuard.DEFAULT_DAILY_LOSS_PCT.toFloat()).toDouble()
            .takeIf { it in RiskGuard.DAILY_LOSS_CHOICES } ?: RiskGuard.DEFAULT_DAILY_LOSS_PCT,
    )

    fun setRules(context: Context, maxTrades: Int, dailyLossPct: Double) = prefs(context).edit {
        putInt(MAX_TRADES, maxTrades)
        putFloat(LOSS_PCT, dailyLossPct.toFloat())
    }

    fun trades(context: Context): List<LoggedTrade> = TradeLog.decode(prefs(context).getString(LOG, null))

    fun saveTrades(context: Context, trades: List<LoggedTrade>) = prefs(context).edit { putString(LOG, TradeLog.encode(trades)) }

    /** Writes [trade] down; an intraday one gets the 15:10 square-off reminder ([SquareOffWorker]). */
    fun log(context: Context, trade: LoggedTrade) {
        saveTrades(context, TradeLog.add(trades(context), trade, today()))
        if (trade.product == PositionSize.Product.MIS) SquareOffWorker.schedule(context)
    }

    fun today(): LocalDate = MarketClock.now().toLocalDate()
}

/**
 * The square-off reminder: at [AT] IST on a day with intraday (MIS) trades written down and still open, a notification
 * names them, since brokers close MIS positions themselves from about 15:20 (at a charge).
 */
class SquareOffWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val open = TradeLog.openIntraday(TradeStore.trades(applicationContext), TradeStore.today())
        if (open.isNotEmpty()) Notifier.squareOff(applicationContext, open)
        return Result.success()
    }

    companion object {
        private const val WORK = "square_off"
        val AT: LocalTime = LocalTime.of(15, 10)

        /** Sets today's reminder for [AT]; past it, none is set. */
        fun schedule(context: Context) {
            val now = MarketClock.now()
            val wait = Duration.between(now.toLocalTime(), AT)
            if (wait.isNegative) return
            val request = OneTimeWorkRequestBuilder<SquareOffWorker>()
                .setInitialDelay(wait.toMillis(), TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
