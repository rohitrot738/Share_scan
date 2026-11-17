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
    fun anEndAndroidRecordedSaysWhatEndedTheAppWhenAndWithWhatMemory() {
        val report = CrashLog.exitReport(
            version = "1.2.149",
            api = 35,
            device = "Xiaomi 23129RN51X",
            exit = CrashLog.Exit.HANG,
            atMillis = 1_793_948_400_000L,
            description = "Input dispatching timed out",
            memoryKb = 190_000L,
            trace = "\"main\" prio=5 tid=1 Blocked\n  at com.rohit.share_scan.X.y(X.kt:10)",
            earlier = listOf(CrashLog.Exit.NATIVE to 1_793_862_000_000L),
        )
        val lines = report.lines()
        assertEquals("Share Scan 1.2.149 · Android 15 (API 35) · Xiaomi 23129RN51X", lines[0])
        assertEquals("ANR: the app stopped responding and Android closed it · 6 Nov 2026, 12:30 IST", lines[1])
        assertEquals("Android: Input dispatching timed out", lines[2])
        assertEquals("Memory: 185 MB", lines[3])
        assertEquals("Earlier: NATIVE 5 Nov 2026, 12:30", lines[4])
        assertTrue(report.endsWith("at com.rohit.share_scan.X.y(X.kt:10)"))
        assertTrue(CrashLog.exitReport("1", 34, "p", CrashLog.Exit.NATIVE, 0L, null, 0L, "x".repeat(20_000)).length <= CrashLog.MAX_CHARS)
    }

    @Test
    fun fromAnAnrDumpTheMainThreadAndTheThreadHoldingItsLock() {
        val dump = """
            ----- pid 4242 at 2026-11-11 13:42:01 -----
            Cmd line: com.rohit.share_scan

            "Signal Catcher" daemon prio=10 tid=6 Runnable
              at nothing

            "main" prio=5 tid=1 Blocked
              | group="main" sCount=1
              at com.rohit.share_scan.ScanStore.load(ScanStore.kt:61)
              - waiting to lock <0x0c3b2a4f> (a java.lang.Object) held by thread 23

            "pool-3-thread-1" prio=5 tid=23 Runnable
              at java.util.zip.GZIPOutputStream.write(GZIPOutputStream.java:148)
              at com.rohit.share_scan.ScanStore.save(ScanStore.kt:40)

            "pool-4-thread-2" prio=5 tid=24 Waiting
              at sun.misc.Unsafe.park(Native method)
        """.trimIndent()
        val threads = CrashLog.anrThreads(dump)!!
        assertTrue(threads.startsWith("\"main\" prio=5 tid=1 Blocked"))
        assertTrue(threads.contains("ScanStore.load(ScanStore.kt:61)"))
        assertTrue(threads.contains("\"pool-3-thread-1\" prio=5 tid=23 Runnable"))
        assertTrue(threads.contains("ScanStore.save(ScanStore.kt:40)"))
        assertTrue(!threads.contains("Signal Catcher") && !threads.contains("tid=24"))
        assertEquals(null, CrashLog.anrThreads("no threads here"))
    }

    @Test
    fun aNativeCrashRecordGivesItsReadableLinesInOrder() {
        val record = byteArrayOf(8, 2, 18) + "Fatal signal 11 (SIGSEGV)".toByteArray() + byteArrayOf(0, 26, 4) +
            "abc".toByteArray() + byteArrayOf(1) + "0123456789abcdef0123".toByteArray() + byteArrayOf(2) +
            "/system/lib64/libhwui.so".toByteArray() + byteArrayOf(3) + "Fatal signal 11 (SIGSEGV)".toByteArray()
        assertEquals("Fatal signal 11 (SIGSEGV)\n/system/lib64/libhwui.so", CrashLog.nativeStrings(record))
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
