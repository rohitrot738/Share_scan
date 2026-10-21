package com.rohit.share_scan

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * The 360CR file ([Cr360Book]) the app shows: the repository's daily job publishes it to the cr360-data release, and
 * the app keeps one copy, downloaded at most every [REFRESH_MS]. A new download replaces the old file, so the copy
 * never grows past one day's file; a failed or broken download leaves the copy in use.
 */
object Cr360Repository {
    private const val SOURCE = "https://github.com/rohitrot738/Share_scan/releases/download/cr360-data/cr360.json.gz"
    private const val FILE = "cr360.json.gz"
    private const val REFRESH_MS = 12L * 60 * 60 * 1000
    /** A real file scores well over a thousand stocks; anything much smaller is an error page or a broken build. */
    private const val MIN_STOCKS = 500

    @Volatile private var book: Cr360Book? = null

    /** One download at a time: the app's start and a scan can both ask for a fresh file. */
    private val refreshing = Any()

    /** The book already in memory, without reading the file; empty before [book] or a download. */
    fun current(): Cr360Book = book ?: Cr360Book.EMPTY

    /** The book in use, read once from the kept file and then kept in memory; empty before the first download. */
    fun book(context: Context): Cr360Book {
        book?.let { return it }
        synchronized(this) {
            book?.let { return it }
            val file = File(context.applicationContext.filesDir, FILE)
            val read = if (file.isFile) runCatching { Cr360Book.parse(unzip(file.readBytes())) }.getOrNull() else null
            return (read ?: Cr360Book.EMPTY).also { book = it }
        }
    }

    /** Downloads a fresh file when the kept one is [REFRESH_MS] old or missing; true when a new book is in use. Call off the main thread. */
    fun refreshIfStale(context: Context): Boolean = synchronized(refreshing) { refresh(context) }

    private fun refresh(context: Context): Boolean {
        val dir = context.applicationContext.filesDir
        val file = File(dir, FILE)
        val temp = File(dir, "$FILE.tmp")
        temp.delete()
        if (file.isFile && System.currentTimeMillis() - file.lastModified() < REFRESH_MS) return false
        val bytes = runCatching { download() }.getOrNull()
        val fresh = bytes?.let { runCatching { Cr360Book.parse(unzip(it)) }.getOrNull() }
        if (bytes == null || fresh == null || fresh.size < MIN_STOCKS) {
            // Try again after the usual wait rather than on every start.
            if (file.isFile) file.setLastModified(System.currentTimeMillis())
            return false
        }
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
