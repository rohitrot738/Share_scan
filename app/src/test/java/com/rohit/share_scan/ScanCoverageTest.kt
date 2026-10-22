package com.rohit.share_scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ScanCoverageTest {
    @Test
    fun missesAddUpFromEveryThread() {
        val tally = ScanCoverage.Tally()
        val pool = Executors.newFixedThreadPool(4)
        repeat(400) { i ->
            pool.execute {
                tally.add(
                    when (i % 8) {
                        0, 1, 2, 3 -> ScanCoverage.Miss.NO_DATA
                        4, 5 -> ScanCoverage.Miss.TOO_NEW
                        6 -> ScanCoverage.Miss.LIMITED
                        else -> ScanCoverage.Miss.FAILED
                    },
                )
            }
        }
        pool.shutdown()
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
        val coverage = tally.coverage(listed = 5_000, charted = 4_600)
        assertEquals(ScanCoverage(5_000, 4_600, noData = 200, tooNew = 100, limited = 50, failed = 50), coverage)
        assertEquals(400, coverage.missing)
    }

    @Test
    fun theClearestReasonRanksLast() {
        // A stock with several tickers keeps the clearest reason any of them gave.
        assertTrue(ScanCoverage.Miss.TOO_NEW > ScanCoverage.Miss.LIMITED)
        assertTrue(ScanCoverage.Miss.LIMITED > ScanCoverage.Miss.FAILED)
        assertTrue(ScanCoverage.Miss.FAILED > ScanCoverage.Miss.NO_DATA)
    }
}
