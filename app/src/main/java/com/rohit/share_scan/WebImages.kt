package com.rohit.share_scan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Pictures from the internet ([ProductPhotos]): fetched once, kept in the app's cache folder and in memory, and shrunk
 * to the size they are shown at. A picture that does not come leaves the view as it was (its drawn picture).
 */
object WebImages {
    private const val MAX_BYTES = 3_000_000
    private const val CACHE_DIR = "web-images"

    private val memory = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** At most four downloads at a time. */
    private val downloads = Semaphore(4)

    /**
     * Shows [url] in [view] at about [sizePx] across once it is in; [onShown] runs then. The view's tag holds the
     * address it waits for, so a view reused for another picture meanwhile is left alone.
     */
    fun load(activity: AppCompatActivity, view: ImageView, url: String, sizePx: Int, onShown: () -> Unit = {}) {
        view.tag = url
        memory.get(url)?.let {
            view.setImageBitmap(it)
            onShown()
            return
        }
        activity.lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) { runCatching { fetch(activity, url, sizePx) }.getOrNull() } ?: return@launch
            memory.put(url, bitmap)
            if (view.tag == url) {
                view.setImageBitmap(bitmap)
                onShown()
            }
        }
    }

    private suspend fun fetch(context: Context, url: String, sizePx: Int): Bitmap? {
        val dir = File(context.cacheDir, CACHE_DIR).apply { mkdirs() }
        val file = File(dir, sha1(url))
        if (!file.isFile) {
            val bytes = downloads.withPermit { download(url) } ?: return null
            val temp = File(dir, file.name + ".tmp")
            temp.writeBytes(bytes)
            temp.renameTo(file)
        }
        return decode(file, sizePx)
    }

    private fun download(url: String): ByteArray? {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "ShareScan (Android)")
            if (connection.responseCode != 200) return null
            connection.inputStream.use { input ->
                val bytes = input.readNBytesCompat(MAX_BYTES + 1)
                bytes.takeIf { it.size in 1..MAX_BYTES }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (out.size() < limit) {
            val n = read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    /** The file's picture, shrunk by halves until it is no more than twice [sizePx] across. */
    private fun decode(file: File, sizePx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            file.delete()
            return null
        }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= sizePx * 2) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun sha1(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
