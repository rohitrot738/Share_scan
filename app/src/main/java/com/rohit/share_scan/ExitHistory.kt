package com.rohit.share_scan

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.edit
import kotlin.math.max

/**
 * Android's own record of how the app's process ended (Android 11 and later), for the ends the error handler in
 * [ShareScanApp] never sees: a crash inside Android's or a library's native code, the app stuck until Android closed it
 * ("isn't responding"), or closed while on the screen. The next start reports the newest such end of the last
 * [WINDOW_DAYS] days not reported before ([CrashLog.exitReport]), so the first start after an update also tells of one
 * from before it.
 */
object ExitHistory {
    private const val SEEN = "exit_seen_at"
    private const val WINDOW_DAYS = 7L
    private const val LOOK_BACK = 16
    private const val EARLIER = 4

    /** An ANR's thread dump can run to megabytes; its main thread is near the start. */
    private const val MAX_TRACE_BYTES = 3 * 1024 * 1024

    /**
     * The report of the newest end not reported yet, or null; every end up to now then counts as reported. Reads
     * Android's records: never on the main thread.
     */
    fun report(context: Context, version: String, device: String, now: Long = System.currentTimeMillis()): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val exits = exits(context)
        val seen = seenAt(context)
        markSeen(context, exits)
        val fresh = exits
            .filter { it.timestamp > seen && now - it.timestamp < WINDOW_DAYS * 24 * 3_600_000L }
            .mapNotNull { info -> kind(info)?.let { info to it } }
            .sortedByDescending { it.first.timestamp }
        val (info, kind) = fresh.firstOrNull() ?: return null
        val trace = runCatching { trace(info, kind) }.getOrNull()
        return CrashLog.exitReport(
            version = version,
            api = Build.VERSION.SDK_INT,
            device = device,
            exit = kind,
            atMillis = info.timestamp,
            description = info.description,
            memoryKb = max(info.pss, info.rss),
            trace = trace,
            earlier = fresh.drop(1).take(EARLIER).map { (earlier, earlierKind) -> earlierKind to earlier.timestamp },
        )
    }

    /** Counts every end so far as reported: the error handler's own report ([CrashLog]) told of the latest. */
    fun markSeen(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        markSeen(context, exits(context))
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun exits(context: Context): List<ApplicationExitInfo> {
        val manager = context.getSystemService(ActivityManager::class.java) ?: return emptyList()
        return runCatching { manager.getHistoricalProcessExitReasons(null, 0, LOOK_BACK) }.getOrNull().orEmpty()
    }

    private fun seenAt(context: Context): Long =
        context.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE).getLong(SEEN, 0L)

    @RequiresApi(Build.VERSION_CODES.R)
    private fun markSeen(context: Context, exits: List<ApplicationExitInfo>) {
        val newest = exits.maxOfOrNull { it.timestamp } ?: return
        val seen = seenAt(context)
        if (newest > seen) context.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE).edit { putLong(SEEN, newest) }
    }

    /** What ended the process, when it is worth a report; a close in the background to free memory is not. */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun kind(info: ApplicationExitInfo): CrashLog.Exit? = when (info.reason) {
        ApplicationExitInfo.REASON_CRASH_NATIVE -> CrashLog.Exit.NATIVE
        ApplicationExitInfo.REASON_ANR -> CrashLog.Exit.HANG
        ApplicationExitInfo.REASON_CRASH -> CrashLog.Exit.CRASH
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> CrashLog.Exit.START
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> CrashLog.Exit.RESOURCES
        ApplicationExitInfo.REASON_LOW_MEMORY ->
            CrashLog.Exit.MEMORY.takeIf { info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE }
        else -> null
    }

    /** An ANR's stuck threads, or the readable part of a native crash's record (Android 12 and later keep one). */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun trace(info: ApplicationExitInfo, kind: CrashLog.Exit): String? {
        if (kind != CrashLog.Exit.HANG && kind != CrashLog.Exit.NATIVE) return null
        val bytes = info.traceInputStream?.use { stream ->
            val buffer = ByteArray(MAX_TRACE_BYTES)
            var read = 0
            while (read < buffer.size) {
                val n = stream.read(buffer, read, buffer.size - read)
                if (n < 0) break
                read += n
            }
            buffer.copyOf(read)
        } ?: return null
        return if (kind == CrashLog.Exit.HANG) {
            CrashLog.anrThreads(String(bytes, Charsets.UTF_8))
        } else {
            CrashLog.nativeStrings(bytes)
        }
    }
}
