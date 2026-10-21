package com.rohit.share_scan

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

@get:StringRes
val Broker.label: Int
    get() = when (this) {
        Broker.GROWW -> R.string.broker_groww
        Broker.ANGEL_ONE -> R.string.broker_angel_one
        Broker.ZERODHA -> R.string.broker_zerodha
    }

/**
 * Opens a stock in a [Broker]'s app. Groww: its search finds the stock's page, which the Groww app opens (a browser
 * without the app). Angel One and Zerodha: the symbol goes to the clipboard and the app opens, for its search.
 */
class BrokerOpener(private val activity: AppCompatActivity) {
    fun open(broker: Broker, stock: ScanResult) {
        if (broker == Broker.GROWW) openGroww(stock) else copyAndLaunch(broker, stock)
    }

    private fun openGroww(stock: ScanResult) {
        activity.lifecycleScope.launch {
            val id = growwPages[stock.symbol]
                ?: withContext(Dispatchers.IO) { growwSearch(stock) }?.also { growwPages[stock.symbol] = it }
                ?: Broker.growwGuess(stock.name)
            if (id == null) copyAndLaunch(Broker.GROWW, stock) else view(Broker.growwUrl(id).toUri(), Broker.GROWW)
        }
    }

    /** The stock's Groww page id from Groww's search; null when the search fails or does not know it. */
    private fun growwSearch(stock: ScanResult): String? = try {
        val connection = (URL(Broker.growwSearchUrl(stock)).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("User-Agent", "ShareScan (Android)")
            setRequestProperty("Accept", "application/json")
        }
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) null
            else Broker.growwPage(connection.inputStream.bufferedReader().use { it.readText() }, stock)
        } finally {
            connection.disconnect()
        }
    } catch (error: Exception) {
        Log.w(TAG, "Groww search failed for ${stock.symbol}", error)
        null
    }

    /** [uri] in [broker]'s app when it takes the link, in the browser otherwise. */
    private fun view(uri: Uri, broker: Broker) {
        val intent = Intent(Intent.ACTION_VIEW, uri)
        try {
            activity.startActivity(Intent(intent).setPackage(broker.appId))
        } catch (missing: ActivityNotFoundException) {
            try {
                activity.startActivity(intent)
            } catch (error: ActivityNotFoundException) {
                Toast.makeText(activity, R.string.open_chart_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun copyAndLaunch(broker: Broker, stock: ScanResult) {
        val name = activity.getString(broker.label)
        val launch = activity.packageManager.getLaunchIntentForPackage(broker.appId)
        if (launch == null) {
            Toast.makeText(activity, activity.getString(R.string.broker_missing, name), Toast.LENGTH_SHORT).show()
            return
        }
        activity.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(stock.symbol, stock.symbol))
        Toast.makeText(activity, activity.getString(R.string.broker_copied, stock.symbol, name), Toast.LENGTH_LONG).show()
        activity.startActivity(launch)
    }

    private companion object {
        const val TAG = "ShareScan"
        const val TIMEOUT_MS = 4_000

        /** Page ids Groww's search gave, by symbol, for as long as the app runs. */
        val growwPages = HashMap<String, String>()
    }
}
