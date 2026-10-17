package com.rohit.share_scan

import org.junit.Assert.assertEquals
import org.junit.Test

class RateGateTest {
    private var clock = 1_000L
    private val sleeps = mutableListOf<Long>()
    private val gate = RateGate(intervalMs = 100, now = { clock }, sleep = { sleeps += it; clock += it })

    @Test
    fun spacesRequestsByTheInterval() {
        repeat(3) { gate.acquire() }

        assertEquals(listOf(100L, 100L), sleeps)
        assertEquals(1_200L, clock)
    }

    @Test
    fun doesNotWaitWhenRequestsAreAlreadySpacedOut() {
        gate.acquire()
        clock += 500
        gate.acquire()

        assertEquals(emptyList<Long>(), sleeps)
    }

    @Test
    fun pauseHoldsTheNextRequestBack() {
        gate.acquire()
        gate.pause(5_000)
        gate.acquire()

        assertEquals(listOf(5_000L), sleeps)
    }

    @Test
    fun shorterPauseDoesNotCutALongerOneShort() {
        gate.pause(5_000)
        gate.pause(1_000)
        gate.acquire()

        assertEquals(listOf(5_000L), sleeps)
    }
}
