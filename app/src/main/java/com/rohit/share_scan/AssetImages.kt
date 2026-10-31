package com.rohit.share_scan

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The pictures the app carries in its assets ([ProductPhotos]): read off the main thread, shrunk to the size they are
 * shown at and kept in memory while the app runs. A picture that cannot be read leaves the view as it was.
 */
object AssetImages {
    private val memory = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /**
     * Shows [path] in [view] at about [sizePx] across; [onShown] runs once it is in. The view's tag holds the picture
     * it waits for, so a view given another picture meanwhile is left alone.
     */
    fun load(activity: AppCompatActivity, view: ImageView, path: String, sizePx: Int, onShown: () -> Unit = {}) {
        view.tag = path
        memory.get(path)?.let {
            view.setImageBitmap(it)
            onShown()
            return
        }
        activity.lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) { runCatching { decode(activity, path, sizePx) }.getOrNull() } ?: return@launch
            memory.put(path, bitmap)
            if (view.tag == path) {
                view.setImageBitmap(bitmap)
                onShown()
            }
        }
    }

    /** The picture, shrunk by halves while it stays at least twice [sizePx] across. */
    private fun decode(activity: AppCompatActivity, path: String, sizePx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        activity.assets.open(path).use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= sizePx * 2) sample *= 2
        return activity.assets.open(path).use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
    }
}
