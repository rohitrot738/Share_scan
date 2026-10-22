package com.rohit.share_scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class SetupBookTest {
    private val volume = SetupCondition.VOLUME_2X.flag
    private val breakout = SetupCondition.BREAKOUT_TODAY.flag
    private val rsi = SetupCondition.RSI_50_70.flag
    private val quiet = SetupCondition.QUIET_BEFORE.flag
    private val hot = SetupCondition.RSI_70_PLUS.flag

    private fun stats(n: Int, w: Int, s: Int = 0) = SetupMiner.Stats(n, w, s)

    private val result = SetupMiner.Result(
        rules = listOf(
            SetupMiner.Rule(breakout or volume or rsi, stats(400, 240), stats(60, 37), 20_380L),
            SetupMiner.Rule(breakout or volume, stats(900, 450), stats(120, 58), 20_300L),
        ),
        avoid = listOf(
            SetupMiner.Rule(quiet or hot, stats(800, 64, 600), stats(150, 12, 120), 20_383L),
            SetupMiner.Rule(hot, stats(3_000, 600, 1_800), stats(500, 90, 300), 20_350L),
        ),
        changes = listOf(
            SetupMiner.Change(20_383L, true, breakout or volume or rsi, stats(400, 240), stats(60, 37)),
            SetupMiner.Change(20_383L, true, quiet or hot, stats(800, 64, 600), stats(150, 12, 120), avoid = true),
        ),
        train = stats(50_000, 15_000, 20_000),
        holdout = stats(9_000, 2_800, 3_500),
        holdoutFrom = 20_300L,
        lift = listOf(SetupMiner.Lift(SetupCondition.VOLUME_2X, 1.4, 0.42, 9_000)),
        loseLift = listOf(SetupMiner.Lift(SetupCondition.RSI_70_PLUS, 1.5, 0.6, 3_500)),
    )

    @Test
    fun theLearnersFileReadsBack() {
        val old = SetupBook(
            emptyList(),
            listOf(SetupMiner.Change(20_200L, false, rsi, stats(500, 160), stats(80, 22))),
            stats(0, 0), stats(0, 0), emptyList(), emptyMap(), 20_382L, 0, 0,
        )
        val kinds = mapOf(BreakoutSetup.Kind.VOLUME to SetupBook.KindStats(stats(1200, 744), stats(1200, 840)))
        val book = SetupBook.parse(SetupBook.encode(result, kinds, old, 20_383L, 2_500, 61_000))
        assertEquals(listOf(breakout or volume or rsi, breakout or volume), book.rules.map { it.mask })
        assertEquals(0.6, book.rules.first().train.rate, 1e-9)
        assertEquals(20_380L, book.rules.first().since)
        assertEquals(listOf(20_383L, 20_383L, 20_200L), book.changes.map { it.day }) // today's first, the old log after
        assertEquals(listOf(false, true, false), book.changes.map { it.avoid })
        assertEquals(listOf(quiet or hot, hot), book.avoid.map { it.mask })
        assertEquals(720, book.avoid.first().all.stops)
        assertEquals(20_000, book.train.stops)
        assertEquals(SetupCondition.RSI_70_PLUS, book.loseLift.single().condition)
        assertEquals(1.4, book.lift.single().lift, 1e-9)
        assertEquals(62 to 70, book.quickHits(BreakoutSetup.Kind.VOLUME))
        assertNull(book.quickHits(BreakoutSetup.Kind.LOW_VOLUME))
        assertEquals(2_500, book.stocks)
        assertEquals(0.3, book.train.rate, 1e-9)
    }

    @Test
    fun aStockGetsTheBestSetupWhoseConditionsAllHold() {
        val book = SetupBook.parse(SetupBook.encode(result, emptyMap(), SetupBook.EMPTY, 20_383L, 1, 1))
        assertEquals(breakout or volume or rsi, book.best(breakout or volume or rsi or SetupCondition.ABOVE_SMA50.flag)!!.mask)
        assertEquals(breakout or volume, book.best(breakout or volume)!!.mask)
        assertNull(book.best(volume or rsi))
    }

    @Test
    fun aStockGetsTheWorstMistakeWhoseConditionsAllHold() {
        val book = SetupBook.parse(SetupBook.encode(result, emptyMap(), SetupBook.EMPTY, 20_383L, 1, 1))
        assertEquals(quiet or hot, book.mistake(quiet or hot or volume)!!.mask)
        assertEquals(hot, book.mistake(hot or breakout)!!.mask)
        assertNull(book.mistake(quiet or breakout))
        assertNull(book.mistake(0L))
    }

    @Test
    fun aFileFromBeforeMistakesHasNone() {
        val book = SetupBook.parse("""{"v":1,"rules":[{"m":$volume,"n":200,"w":90,"hn":40,"hw":20}],"base":{"n":1000,"w":300}}""")
        assertEquals(1, book.rules.size)
        assertEquals(0, book.avoid.size)
        assertEquals(0, book.rules.single().train.stops)
        assertNull(book.mistake(volume))
    }

    @Test
    fun brokenFilesAreEmpty() {
        assertSame(SetupBook.EMPTY, SetupBook.parse(null))
        assertSame(SetupBook.EMPTY, SetupBook.parse("<html>"))
        assertSame(SetupBook.EMPTY, SetupBook.parse("""{"v":2}"""))
        // A condition this app does not know (bit 60) drops that setup only.
        assertEquals(0, SetupBook.parse("""{"v":1,"rules":[{"m":${1L shl 60},"n":1,"w":1}]}""").rules.size)
    }
}
