package com.rohit.share_scan

import org.junit.Assert.assertEquals
import org.junit.Test

/** [NotificationLog]: what Menu → सूचनाएँ keeps of the alerts and crashes. */
class NotificationLogTest {
    private val day = 24 * 3_600_000L
    private val now = 1_793_948_400_000L

    private fun entry(at: Long, symbol: String? = "TANLA", kind: NotificationLog.Kind = NotificationLog.Kind.LIVE) =
        NotificationLog.Entry(at, kind, "🚀 1 stocks का breakout", "TANLA · score 91.6 · ₹698.80", symbol)

    @Test
    fun theNewestComesFirstAndTheOldestGoAfterAMonth() {
        var log = emptyList<NotificationLog.Entry>()
        log = NotificationLog.add(log, entry(now - 40 * day), now - 40 * day)
        log = NotificationLog.add(log, entry(now - 2 * day, "PNB"), now - 2 * day)
        log = NotificationLog.add(log, entry(now, "MEESHO"), now)
        // Forty days old is past the month and is dropped as the next comes in.
        assertEquals(listOf("MEESHO", "PNB"), log.map { it.symbol })
    }

    @Test
    fun atMostSoManyAreKept() {
        var log = emptyList<NotificationLog.Entry>()
        repeat(NotificationLog.KEEP + 20) { log = NotificationLog.add(log, entry(now + it), now + it) }
        assertEquals(NotificationLog.KEEP, log.size)
        assertEquals(now + NotificationLog.KEEP + 19, log.first().at)
    }

    @Test
    fun theUnreadAreThoseSinceTheListWasLastOpened() {
        val log = listOf(entry(now), entry(now - 10), entry(now - 20))
        assertEquals(2, NotificationLog.unread(log, now - 15))
        assertEquals(0, NotificationLog.unread(log, now))
    }

    @Test
    fun whatIsKeptReadsBackTheSame() {
        val crash = NotificationLog.Entry(now, NotificationLog.Kind.CRASH, "पिछली बार app बंद हो गया था", "Share Scan 1.2.149\nANR", null, NotificationLog.Opens.CRASH)
        val news = NotificationLog.Entry(now - 5, NotificationLog.Kind.NEWS, "📰 PNB: कानूनी", "Order \"received\"", "PNB", NotificationLog.Opens.NEWS)
        val paper = NotificationLog.Entry(now - 9, NotificationLog.Kind.PAPER, "Paper Trading", "BUY 10 TANLA filled", null, NotificationLog.Opens.PAPER)
        val log = listOf(crash, news, paper, entry(now - 12))
        assertEquals(log, NotificationLog.decode(NotificationLog.encode(log)))
        // A tap on an alert with a share opens the share; without one, nothing.
        assertEquals(NotificationLog.Opens.STOCK, entry(now).opens)
        assertEquals(NotificationLog.Opens.NOTHING, entry(now, symbol = null).opens)
    }

    @Test
    fun anUnreadableFileOrAnUnknownKindIsLeftOut() {
        assertEquals(emptyList<NotificationLog.Entry>(), NotificationLog.decode("not json"))
        assertEquals(emptyList<NotificationLog.Entry>(), NotificationLog.decode(null))
        val text = """[{"at":1,"k":"SOMETHING_NEW","t":"x","x":"y","o":"NOTHING"},{"at":2,"k":"WATCH","t":"🎯 PNB target 1 पूरा","x":"भाव ₹140","s":"PNB","o":"STOCK"}]"""
        assertEquals(listOf("PNB"), NotificationLog.decode(text).map { it.symbol })
    }
}
