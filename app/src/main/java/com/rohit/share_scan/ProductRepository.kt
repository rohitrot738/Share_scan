package com.rohit.share_scan

import android.content.Context

/** The products file the app carries ([ProductBook]), read once from its assets and then kept in memory. */
object ProductRepository {
    private const val ASSET = "products.json"

    @Volatile private var book: ProductBook? = null

    /** Call off the main thread: the file holds every company. */
    fun book(context: Context): ProductBook {
        book?.let { return it }
        synchronized(this) {
            book?.let { return it }
            val read = runCatching { context.assets.open(ASSET).bufferedReader().use { it.readText() } }.getOrNull()
            return ProductBook.parse(read).also { book = it }
        }
    }
}
