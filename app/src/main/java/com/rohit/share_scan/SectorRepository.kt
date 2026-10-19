package com.rohit.share_scan

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * The sector file the scan and the Scan sheet read: the copy downloaded from the repository's main branch when there
 * is one, otherwise the copy built into the app. The download is tried at most once a week, before a scan; a failed
 * or broken download leaves the copy in use.
 */
object SectorRepository {
    private const val SOURCE = "https://raw.githubusercontent.com/rohitrot738/Share_scan/main/data/sectors.json"
    private const val ASSET = "sectors.json"
    private const val FILE = "sectors.json"
    private const val REFRESH_MS = 7L * 24 * 60 * 60 * 1000
    /** A real file lists thousands of stocks; anything much smaller is an error page or a broken build. */
    private const val MIN_STOCKS = 1_000

    @Volatile private var book: SectorBook? = null

    /** The sectors in use, read once and then kept. */
    fun book(context: Context): SectorBook {
        book?.let { return it }
        synchronized(this) {
            book?.let { return it }
            val app = context.applicationContext
            val downloaded = File(app.filesDir, FILE).takeIf { it.isFile }?.let { SectorBook.parse(it.readText()) }
            val built = runCatching { app.assets.open(ASSET).bufferedReader().use { it.readText() } }.getOrNull()
                ?.let(SectorBook::parse) ?: SectorBook.EMPTY
            // The newer of the two: an app update can bring a fresher file than an old download.
            val pick = if (downloaded != null && downloaded.size >= MIN_STOCKS && downloaded.updated >= built.updated) downloaded else built
            book = pick
            return pick
        }
    }

    /** Downloads a fresh file when the one kept is a week old or missing; call off the main thread. */
    fun refreshIfStale(context: Context) {
        val app = context.applicationContext
        val file = File(app.filesDir, FILE)
        if (file.isFile && System.currentTimeMillis() - file.lastModified() < REFRESH_MS) return
        val text = runCatching { download() }.getOrNull()
        val fresh = SectorBook.parse(text)
        if (text == null || fresh.size < MIN_STOCKS) {
            // Try again next week rather than before every scan.
            if (file.isFile) file.setLastModified(System.currentTimeMillis())
            return
        }
        val temp = File(app.filesDir, "$FILE.tmp")
        temp.writeText(text)
        if (!temp.renameTo(file)) {
            file.delete()
            temp.renameTo(file)
        }
        synchronized(this) { book = null }
    }

    private fun download(): String {
        val connection = (URL(SOURCE).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 25_000
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
