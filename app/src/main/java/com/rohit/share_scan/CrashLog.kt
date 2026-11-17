package com.rohit.share_scan

import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The app's own record of a crash. When the app is about to be closed by an error ("Share Scan keeps stopping"), the
 * error is written to a file ([save], by [ShareScanApp]); the next start hands it over once ([take]) and offers it to
 * be copied or sent ([MainActivity]), so the line that failed can be found. The ends no error handler sees (a native
 * crash, the app stuck and closed by Android) come from Android's own record instead ([exitReport], [ExitHistory]).
 * Free of Android types so it can be unit tested.
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

    /** How Android says the app ended ([ExitHistory]), for the ends the error handler never sees. */
    enum class Exit(val label: String) {
        NATIVE("Native crash (in Android's or a library's own code)"),
        HANG("ANR: the app stopped responding and Android closed it"),
        CRASH("Crash"),
        MEMORY("Closed by Android for memory while on the screen"),
        RESOURCES("Closed by Android for using too much CPU or battery"),
        START("The app failed to start"),
    }

    /**
     * The report of an end Android recorded: the app's version (now), Android's and the phone's, what ended it and
     * when (IST), Android's own words and the memory in use, earlier such ends, then [trace] (the stuck threads of an
     * ANR, [anrThreads]; the readable part of a native crash's record, [nativeStrings]). Cut to [MAX_CHARS] from the
     * start, where the deciding lines are.
     */
    fun exitReport(
        version: String,
        api: Int,
        device: String,
        exit: Exit,
        atMillis: Long,
        description: String?,
        memoryKb: Long,
        trace: String?,
        earlier: List<Pair<Exit, Long>> = emptyList(),
    ): String {
        val lines = mutableListOf(
            "Share Scan $version · Android ${ANDROID[api] ?: "?"} (API $api) · $device",
            "${exit.label} · ${WHEN.format(Instant.ofEpochMilli(atMillis).atZone(INDIA))} IST",
        )
        description?.trim()?.takeIf { it.isNotEmpty() }?.let { lines += "Android: $it" }
        if (memoryKb > 0) lines += "Memory: ${memoryKb / 1024} MB"
        if (earlier.isNotEmpty()) {
            lines += "Earlier: " + earlier.joinToString("; ") { (kind, at) ->
                "${kind.name} ${WHEN.format(Instant.ofEpochMilli(at).atZone(INDIA))}"
            }
        }
        trace?.trim()?.takeIf { it.isNotEmpty() }?.let { lines += ""; lines += it }
        return lines.joinToString("\n").take(MAX_CHARS)
    }

    /**
     * From an ANR's thread dump, the main thread's stack (where the app was stuck) and, when it waits on a lock, the
     * stack of the thread holding it; null without a main thread.
     */
    fun anrThreads(dump: String): String? {
        val blocks = dump.split(Regex("\n\\s*\n")).map { it.trim('\n') }
        val main = blocks.firstOrNull { it.startsWith("\"main\"") } ?: return null
        val holder = Regex("held by (?:thread|tid=)\\s*(\\d+)").find(main)?.groupValues?.get(1)
        val other = holder?.let { tid -> blocks.firstOrNull { it.startsWith("\"") && it.lineSequence().first().contains(" tid=$tid ") } }
        return listOfNotNull(main, other).joinToString("\n\n")
    }

    /**
     * The readable runs of a native crash's record (Android keeps it as binary): the signal, the abort message and the
     * crashing thread's frames with their libraries, in their order; build ids and bits of numbers left out.
     */
    fun nativeStrings(record: ByteArray, max: Int = NATIVE_LINES): String {
        val runs = LinkedHashSet<String>()
        val run = StringBuilder()
        fun close() {
            val text = run.toString().trim()
            run.clear()
            if (text.length >= MIN_RUN && !HEX.matches(text)) runs += text
        }
        for (byte in record) {
            val c = byte.toInt() and 0xFF
            if (c in 0x20..0x7E) run.append(c.toChar()) else close()
            if (runs.size >= max) break
        }
        close()
        return runs.take(max).joinToString("\n")
    }

    private const val MIN_RUN = 5
    private const val NATIVE_LINES = 90
    private val HEX = Regex("[0-9a-fA-F]{12,}")

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
