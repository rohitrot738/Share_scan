package com.rohit.share_scan

import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri

/**
 * Opens [stock] where [site] says: in the trading app for Groww, Angel One or Zerodha ([BrokerOpener]), else on the
 * chart website (Chartink's BSE-only stocks on TradingView, [ChartSite.forMarket]).
 */
fun AppCompatActivity.openStockChart(stock: ScanResult, site: ChartSite) {
    val broker = site.forMarket(stock.market).broker
    if (broker != null) {
        BrokerOpener(this).open(broker, stock)
        return
    }
    try {
        startActivity(Intent(Intent.ACTION_VIEW, site.url(stock.symbol, stock.market, stock.bseCode).toUri()))
    } catch (error: ActivityNotFoundException) {
        Toast.makeText(this, R.string.open_chart_failed, Toast.LENGTH_SHORT).show()
    }
}
