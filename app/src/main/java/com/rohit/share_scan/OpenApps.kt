package com.rohit.share_scan

import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.google.android.material.button.MaterialButton

/**
 * A share in a trading app or on the chart site chosen in Settings: the [chart] button names the site (or the trading
 * app the site is), and the trading apps' buttons ([BrokerOpener]) leave out the one the chart button already opens.
 * The stock's detail and the sheet of a share without a chart ([UnchartedShareSheet]) both show them.
 */
object OpenApps {
    fun bind(
        activity: AppCompatActivity,
        stock: ScanResult,
        chartSite: ChartSite,
        chart: MaterialButton,
        groww: MaterialButton,
        angelOne: MaterialButton,
        zerodha: MaterialButton,
    ) {
        val site = chartSite.forMarket(stock.market)
        val siteBroker = site.broker
        chart.text = when {
            site == ChartSite.SCREENER -> activity.getString(R.string.detail_open_screener)
            siteBroker != null -> activity.getString(R.string.broker_open_description, activity.getString(siteBroker.label))
            else -> activity.getString(R.string.detail_open_chart_on, activity.getString(site.label))
        }
        if (siteBroker != null) {
            chart.icon = siteBroker.icon(activity)
            chart.iconTint = null
            chart.iconSize = activity.resources.getDimensionPixelSize(R.dimen.broker_icon)
        }
        chart.setOnClickListener { activity.openStockChart(stock, chartSite) }
        // The other trading apps, one tap away; the one the chart button already opens is left out.
        val brokers = BrokerOpener(activity)
        listOf(groww to Broker.GROWW, angelOne to Broker.ANGEL_ONE, zerodha to Broker.ZERODHA).forEach { (button, broker) ->
            button.isVisible = broker != siteBroker
            button.icon = broker.icon(activity)
            button.contentDescription = activity.getString(R.string.broker_open_description, activity.getString(broker.label))
            button.setOnClickListener { brokers.open(broker, stock) }
        }
    }
}
