package com.rohit.share_scan

import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The app's own record of a crash. When the app is about to be closed by an error ("Share Scan keeps stopping"), the
 * error is written to a file ([save], by [ShareScanApp]); the next start hands it over once ([take]) and offers it to
 * be copied or sent ([MainActivity]), so the line that failed can be found. Free of Android types so it can be unit
 * tested.
 */
object CrashLog {
    const val FILE = "last_crash.txt"

    /** About what a chat message carries; a longer error keeps its start and its end (the root cause). */
    const val MAX_CHARS = 6_000
    private const val HEAD_CHARS = 3_400

    private val WHEN: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.ENGLISH)
    private val INDIA: ZoneId = ZoneId.of("Asia/Kolkata")

    /** Android's version number by API level, for the report's first line. */
    private val ANDROID = mapOf(
        26 to "8.0", 27 to "8.1", 28 to "9", 29 to "10", 30 to "11", 31 to "12", 32 to "12L",
        33 to "13", 34 to "14", 35 to "15", 36 to "16",
    )

    /**
     * The report: the app's version, Android's and the phone's, the thread and the time (IST), then the whole error
     * with its causes and where each was thrown.
     */
    fun report(version: String, api: Int, device: String, thread: String, atMillis: Long, error: Throwable): String {
        val head = "Share Scan $version · Android ${ANDROID[api] ?: "?"} (API $api) · $device\n" +
            "Thread: $thread · ${WHEN.format(Instant.ofEpochMilli(atMillis).atZone(INDIA))} IST\n"
        val full = head + error.stackTraceToString()
        if (full.length <= MAX_CHARS) return full
        val gap = "\n…\n"
        return full.take(HEAD_CHARS) + gap + full.takeLast(MAX_CHARS - HEAD_CHARS - gap.length)
    }

    /** Keeps [report] in [dir] for the next start, in place of any earlier one. */
    fun save(dir: File, report: String) {
        File(dir, FILE).writeText(report)
    }

    /** The kept report, or null; it is handed over once and then gone. */
    fun take(dir: File): String? {
        val file = File(dir, FILE)
        if (!file.isFile) return null
        val text = runCatching { file.readText() }.getOrNull()
        file.delete()
        return text?.takeIf { it.isNotBlank() }
    }
}
