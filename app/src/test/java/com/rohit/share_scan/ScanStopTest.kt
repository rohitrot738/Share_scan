package com.rohit.share_scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** [NseScanner.runStoppable], the part of a scan that a stop cuts short; real threads, no network. */
class ScanStopTest {
    @Test(timeout = 10_000)
    fun runsEverythingWithoutAStop() {
        val pool = Executors.newFixedThreadPool(3)
        try {
            val rows = NseScanner.runStoppable(pool, (1..50).toList(), { false }) { n -> if (n % 10 == 0) null else n }

            assertEquals((1..50).filter { it % 10 != 0 }, rows)
            assertTrue(pool.isTerminated)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test(timeout = 10_000)
    fun stopSkipsTheQueueAndKeepsWhatFinishedInOrder() {
        val pool = Executors.newFixedThreadPool(2)
        val stopped = AtomicBoolean(false)
        val ran = AtomicInteger()
        try {
            val items = (1..500).toList()
            val rows = NseScanner.runStoppable(pool, items, stopped::get, checkMs = 10, graceMs = 2_000) { n ->
                ran.incrementAndGet()
                Thread.sleep(5)
                if (n == 20) stopped.set(true)
                n
            }

            assertTrue((1..20).all { it in rows })
            assertTrue("kept ${rows.size}", rows.size < 30)
            assertTrue("ran ${ran.get()}", ran.get() < 30)
            assertEquals(rows.sorted(), rows)
            assertTrue(pool.isShutdown)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test(timeout = 5_000)
    fun stopDoesNotWaitLongForAStuckTask() {
        val pool = Executors.newFixedThreadPool(2)
        val stopped = AtomicBoolean(false)
        try {
            val rows = NseScanner.runStoppable(pool, listOf(1, 2, 3), stopped::get, checkMs = 10, graceMs = 100) { n ->
                if (n == 1) {
                    stopped.set(true)
                    // A request that hangs: the scan returns without it instead of waiting it out.
                    Thread.sleep(60_000)
                }
                n
            }

            assertFalse(1 in rows)
        } finally {
            pool.shutdownNow()
        }
    }
}
