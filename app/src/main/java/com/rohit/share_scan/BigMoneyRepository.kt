package com.rohit.share_scan

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * The बड़े पैसे की चाल file ([BigMoneyBook]): the repository's evening job publishes it to the cr360-data release, and
 * the app keeps one copy, fetched again as [BigMoneySchedule.due] says (each weekday evening once the new session is
 * out). A failed or broken download leaves the copy in use.
 */
object BigMoneyRepository {
    private const val SOURCE = "https://github.com/rohitrot738/Share_scan/releases/download/cr360-data/big-money.json.gz"
    private const val FILE = "big-money.json.gz"

    /** NSE's file has about 3,100 shares; far fewer is an error page or a broken build. */
    private const val MIN_STOCKS = 1000

    @Volatile private var book: BigMoneyBook? = null

    private val refreshing = Any()

    /** The book already in memory, without reading the file; empty before [book] or a download. */
    fun current(): BigMoneyBook = book ?: BigMoneyBook.EMPTY

    /** The book in use, read once from the kept file; empty before the first download. */
    fun book(context: Context): BigMoneyBook {
        book?.let { return it }
        synchronized(this) {
            book?.let { return it }
            val file = File(context.applicationContext.filesDir, FILE)
            val read = if (file.isFile) runCatching { BigMoneyBook.parse(unzip(file.readBytes())) }.getOrNull() else null
            return (read ?: BigMoneyBook.EMPTY).also { book = it }
        }
    }

    /** Fetches the file when [BigMoneySchedule.due]; true when a new book is in use. Call off the main thread. */
    fun refreshIfStale(context: Context, now: java.time.ZonedDateTime = MarketClock.now()): Boolean = synchronized(refreshing) {
        val dir = context.applicationContext.filesDir
        val file = File(dir, FILE)
        val age = if (file.isFile) System.currentTimeMillis() - file.lastModified() else Long.MAX_VALUE
        if (!BigMoneySchedule.due(book(context).date, age, now)) return false
        val bytes = runCatching { download() }.getOrNull()
        val fresh = bytes?.let { runCatching { BigMoneyBook.parse(unzip(it)) }.getOrNull() }
        if (bytes == null || fresh == null || fresh.size < MIN_STOCKS) {
            // Try again after the usual wait rather than on every start.
            if (file.isFile) file.setLastModified(System.currentTimeMillis())
            return false
        }
        if (fresh.date == book?.date && file.isFile) {
            // The same session again: only the time of the try changes.
            file.setLastModified(System.currentTimeMillis())
            return false
        }
        val temp = File(dir, "$FILE.tmp")
        temp.writeBytes(bytes)
        if (!temp.renameTo(file)) {
            file.delete()
            temp.renameTo(file)
        }
        synchronized(this) { book = fresh }
        return true
    }

    private fun unzip(bytes: ByteArray): String = GZIPInputStream(bytes.inputStream()).bufferedReader().use { it.readText() }

    private fun download(): ByteArray {
        val connection = (URL(SOURCE).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "ShareScan/1.0 (Android)")
        }
        try {
            if (connection.responseCode !in 200..299) throw HttpStatusException(connection.responseCode)
            return connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }
}
