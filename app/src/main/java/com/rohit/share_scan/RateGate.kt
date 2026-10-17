package com.rohit.share_scan

import kotlin.math.ceil
import kotlin.math.min

/**
 * Paces requests to one server across threads like a token bucket: [burst] requests may go at once, then
 * [perSecond] a second. Yahoo answers HTTP 429 to every chart request once about 3,000 have come in quickly, so a
 * full NSE + BSE scan has to pace itself instead of running as fast as its threads can. After a 429 the caller
 * [pause]s the gate, and every thread waits.
 */
internal class RateGate(
    private val burst: Int,
    perSecond: Double,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val sleep: (Long) -> Unit = Thread::sleep,
) {
    private val perMs = perSecond / 1000.0
    private var tokens = burst.toDouble()
    /** When [tokens] was last topped up; after a pause, the moment the pause ends. */
    private var updatedAt = now()

    /** Blocks until this caller may send a request. */
    fun acquire() {
        val wait = synchronized(this) {
            val time = now()
            if (time > updatedAt) {
                tokens = min(burst.toDouble(), tokens + (time - updatedAt) * perMs)
                updatedAt = time
            }
            tokens -= 1.0
            val readyAt = if (tokens >= 0.0) updatedAt else updatedAt + ceil(-tokens / perMs).toLong()
            readyAt - time
        }
        if (wait > 0) sleep(wait)
    }

    /**
     * Holds every caller back for [ms] from now, then lets requests through at the steady rate only: the server has
     * just refused one, so there is no burst left. Never shortens a pause that is already longer.
     */
    fun pause(ms: Long) {
        synchronized(this) {
            updatedAt = maxOf(updatedAt, now() + ms)
            tokens = min(tokens, 0.0)
        }
    }
}

/** A response outside 2xx; [status] tells Yahoo's rate limit (429) apart from a ticker it does not know (404). */
class HttpStatusException(val status: Int) : RuntimeException("HTTP $status")
