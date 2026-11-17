package com.rohit.share_scan

import android.content.Context
import androidx.core.content.edit
import java.io.File

/**
 * Where [NotificationLog] is kept: a file in the app's storage, written from the screen and from background work
 * alike, one change at a time. Each read and write is a small file: still, call it off the main thread where it can be.
 */
object NotificationInbox {
    private const val FILE = "notifications.json"
    private const val SEEN = "notifications_seen_at"
    private val lock = Any()

    private fun file(context: Context) = File(context.applicationContext.filesDir, FILE)

    fun load(context: Context): List<NotificationLog.Entry> = synchronized(lock) {
        NotificationLog.decode(runCatching { file(context).takeIf { it.isFile }?.readText() }.getOrNull())
    }

    /** Keeps [entry]; a failure to write loses only this entry. */
    fun record(context: Context, entry: NotificationLog.Entry) {
        runCatching {
            synchronized(lock) {
                val target = file(context)
                val kept = NotificationLog.decode(target.takeIf { it.isFile }?.readText())
                val temp = File(target.parentFile, "$FILE.tmp")
                temp.writeText(NotificationLog.encode(NotificationLog.add(kept, entry, System.currentTimeMillis())))
                temp.renameTo(target)
            }
        }
    }

    fun clear(context: Context) = synchronized(lock) { file(context).delete() }

    /** How many came since the list was last opened, for the Menu. */
    fun unread(context: Context): Int = NotificationLog.unread(load(context), seenAt(context))

    fun seenAt(context: Context): Long =
        context.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE).getLong(SEEN, 0L)

    fun markSeen(context: Context) =
        context.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE).edit { putLong(SEEN, System.currentTimeMillis()) }
}
