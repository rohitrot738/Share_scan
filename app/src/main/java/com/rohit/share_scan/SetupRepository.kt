package com.rohit.share_scan

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * The learned setups ([SetupBook]) the app uses: the daily learner publishes setups.json to the cr360-data release,
 * and the app keeps one copy, downloaded at most every [REFRESH_MS]. A failed or broken download leaves the copy in
 * use; before the first one the book is empty and the app keeps its own setups.
 */
object SetupRepository {
    private const val SOURCE = "https://github.com/rohitrot738/Share_scan/releases/download/cr360-data/setups.json"
    private const val FILE = "setups.json"
    private const val REFRESH_MS = 12L * 60 * 60 * 1000

    @Volatile private var book: SetupBook? = null

    /** One download at a time: the app's start and a scan can both ask for a fresh file. */
    private val refreshing = Any()

    /** The book already in memory, without reading the file; empty before [book] or a download. */
    fun current(): SetupBook = book ?: SetupBook.EMPTY

    /** The book in use, read once from the kept file and then kept in memory; empty before the first download. */
    fun book(context: Context): SetupBook {
        book?.let { return it }
        synchronized(this) {
            book?.let { return it }
            val file = File(context.applicationContext.filesDir, FILE)
            val read = if (file.isFile) runCatching { SetupBook.parse(file.readText()) }.getOrNull() else null
            return (read ?: SetupBook.EMPTY).also { book = it }
        }
    }

    /** Downloads a fresh file when the kept one is [REFRESH_MS] old or missing; true when a new book is in use. Call off the main thread. */
    fun refreshIfStale(context: Context): Boolean = synchronized(refreshing) { refresh(context) }

    private fun refresh(context: Context): Boolean {
        val dir = context.applicationContext.filesDir
        val file = File(dir, FILE)
        if (file.isFile && System.currentTimeMillis() - file.lastModified() < REFRESH_MS) return false
        val text = runCatching { download() }.getOrNull()
        val fresh = text?.let { SetupBook.parse(it) }
        if (text == null || fresh == null || fresh === SetupBook.EMPTY) {
            // Try again after the usual wait rather than on every start.
            if (file.isFile) file.setLastModified(System.currentTimeMillis())
            return false
        }
        val temp = File(dir, "$FILE.tmp")
        temp.writeText(text)
        if (!temp.renameTo(file)) {
            file.delete()
            temp.renameTo(file)
        }
        synchronized(this) { book = fresh }
        return true
    }

    private fun download(): String {
        val connection = (URL(SOURCE).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "ShareScan/1.0 (Android)")
        }
        try {
            if (connection.responseCode !in 200..299) throw HttpStatusException(connection.responseCode)
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
