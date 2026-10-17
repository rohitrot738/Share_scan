package com.rohit.share_scan

import org.junit.Assert.assertEquals
import org.junit.Test

class RateGateTest {
    private var clock = 1_000L
    private val sleeps = mutableListOf<Long>()
    private val gate = RateGate(burst = 3, perSecond = 10.0, now = { clock }, sleep = { sleeps += it; clock += it })

    @Test
    fun letsTheBurstThroughThenPacesAtTheSteadyRate() {
        repeat(5) { gate.acquire() }

        assertEquals(listOf(100L, 100L), sleeps)
    }

    @Test
    fun refillsOverTimeUpToTheBurst() {
        repeat(3) { gate.acquire() }
        clock += 60_000
        repeat(3) { gate.acquire() }
        gate.acquire()

        assertEquals(listOf(100L), sleeps)
    }

    @Test
    fun pauseHoldsEveryoneBackThenResumesAtTheSteadyRate() {
        gate.acquire()
        gate.pause(5_000)
        gate.acquire()
        gate.acquire()

        assertEquals(listOf(5_100L, 100L), sleeps)
    }

    @Test
    fun shorterPauseDoesNotCutALongerOneShort() {
        gate.pause(5_000)
        gate.pause(1_000)
        gate.acquire()

        assertEquals(listOf(5_100L), sleeps)
    }
}
