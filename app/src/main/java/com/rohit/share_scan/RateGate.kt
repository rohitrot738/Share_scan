package com.rohit.share_scan

/**
 * Spaces requests to one server across threads, and holds them all back after the server says it has had too many.
 * Yahoo answers HTTP 429 to every chart request once a few thousand have arrived in quick succession, so a full
 * NSE + BSE scan has to pace itself instead of running as fast as its threads can.
 */
internal class RateGate(
    private val intervalMs: Long,
    private val now: () -> Long = System::currentTimeMillis,
    private val sleep: (Long) -> Unit = Thread::sleep,
) {
    private var nextAt = 0L

    /** Blocks until this caller's turn: one request per [intervalMs] at most, and none before a pause has ended. */
    fun acquire() {
        val wait = synchronized(this) {
            val time = now()
            val at = maxOf(time, nextAt)
            nextAt = at + intervalMs
            at - time
        }
        if (wait > 0) sleep(wait)
    }

    /** Holds every caller back for [ms] from now; never shortens a pause that is already longer. */
    fun pause(ms: Long) {
        synchronized(this) { nextAt = maxOf(nextAt, now() + ms) }
    }
}

/** A response outside 2xx; [status] tells Yahoo's rate limit (429) apart from a ticker it does not know (404). */
class HttpStatusException(val status: Int) : RuntimeException("HTTP $status")
