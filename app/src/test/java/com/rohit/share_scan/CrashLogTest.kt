package com.rohit.share_scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** [CrashLog]: what a crash leaves behind for the user to send, read once. */
class CrashLogTest {
    private fun folder(): File = Files.createTempDirectory("crash").toFile()

    @Test
    fun aReportNamesTheVersionThePhoneTheThreadAndTheWholeError() {
        val error = IllegalStateException("outer", IllegalArgumentException("Cannot round NaN value."))
        val report = CrashLog.report("1.2.147", 34, "samsung SM-A155F", "main", 1_793_948_400_000L, error)
        val lines = report.lines()
        assertEquals("Share Scan 1.2.147 · Android 14 (API 34) · samsung SM-A155F", lines[0])
        assertEquals("Thread: main · 6 Nov 2026, 12:30 IST", lines[1])
        assertTrue(report.contains("java.lang.IllegalStateException: outer"))
        // The cause is kept, with where it was thrown.
        assertTrue(report.contains("Caused by: java.lang.IllegalArgumentException: Cannot round NaN value."))
        assertTrue(report.contains("at com.rohit.share_scan.CrashLogTest"))
    }

    @Test
    fun aVeryLongErrorIsCutToWhatAMessageCarries() {
        var error: Throwable = RuntimeException("start")
        repeat(400) { error = RuntimeException("level $it", error) }
        val report = CrashLog.report("1.2.147", 34, "phone", "worker", 0L, error)
        assertTrue(report.length <= CrashLog.MAX_CHARS)
        assertTrue(report.startsWith("Share Scan 1.2.147"))
    }

    @Test
    fun theSavedReportIsHandedOverOnceAndThenGone() {
        val dir = folder()
        CrashLog.save(dir, "Share Scan 1.2.147 · Android 14\nboom")
        assertEquals("Share Scan 1.2.147 · Android 14\nboom", CrashLog.take(dir))
        assertEquals(null, CrashLog.take(dir))
        // A second crash before the app opened again: the latest is the one kept.
        CrashLog.save(dir, "first")
        CrashLog.save(dir, "second")
        assertEquals("second", CrashLog.take(dir))
    }
}
