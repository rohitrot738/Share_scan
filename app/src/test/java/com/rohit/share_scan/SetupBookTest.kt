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
    fun learnedPlansAndRalliesReadBack() {
        val market = PlanTuner.Choice(PlanShape(2.0, 2.0, 1.5), PlanTuner.Record(40_000, 16_000, 20_000, 6_000.0), PlanTuner.Record(9_000, 3_500, 4_600, 900.0), 20_383L)
        val own = PlanTuner.Choice(PlanShape.OWN, PlanTuner.Record(40_000, 14_000, 21_000, 2_000.0), PlanTuner.Record(9_000, 3_000, 5_000, 200.0), 20_383L)
        val best = PlanTuner.Choice(PlanShape(1.5, 3.0), PlanTuner.Record(380, 160, 190, 260.0), PlanTuner.Record(60, 26, 30, 40.0), 20_383L)
        val rally = SetupMiner.Result(
            rules = listOf(SetupMiner.Rule(volume or SetupCondition.NEAR_6M_HIGH.flag, stats(3_000, 900), stats(500, 140), 20_383L)),
            avoid = emptyList(),
            changes = emptyList(),
            train = stats(90_000, 13_500),
            holdout = stats(18_000, 2_700),
            holdoutFrom = 20_300L,
            lift = listOf(SetupMiner.Lift(SetupCondition.NEAR_6M_HIGH, 1.8, 0.27, 9_000)),
            loseLift = emptyList(),
        )
        val book = SetupBook.parse(
            SetupBook.encode(result, emptyMap(), SetupBook.EMPTY, 20_383L, 1, 1, market, own, mapOf((breakout or volume or rsi) to best), rally),
        )
        // The best setup's own plan, else every plan's.
        assertEquals(PlanShape(1.5, 3.0), book.shape(breakout or volume or rsi))
        assertEquals(PlanShape(2.0, 2.0, 1.5), book.shape(breakout or volume))
        assertEquals(PlanShape(2.0, 2.0, 1.5), book.shape(0L))
        assertEquals(6_900.0 / 49_000, book.marketPlan!!.all.averageR, 1e-9)
        assertEquals(PlanShape.OWN, book.ownPlan!!.shape)
        assertEquals(440, book.shown(book.rules.first()).n) // under its own plan
        assertEquals(1_020, book.shown(book.rules[1]).n) // under every plan's
        // What changed: every plan from the app's own to 2 ATR / 2R in 1.5 times the sessions, the setup's from the app's own
        // to 1.5 ATR / 3R.
        assertEquals(
            listOf(PlanShape.OWN to PlanShape(2.0, 2.0, 1.5), PlanShape.OWN to PlanShape(1.5, 3.0)),
            book.planLog.map { it.from to it.to },
        )
        assertEquals(volume or SetupCondition.NEAR_6M_HIGH.flag, book.rallySetup(volume or SetupCondition.NEAR_6M_HIGH.flag or rsi)!!.mask)
        assertNull(book.rallySetup(volume))
        assertEquals(15, SetupBook.percent(book.rallyBase))
        assertEquals(SetupCondition.NEAR_6M_HIGH, book.whyUp.single().condition)

        // The next day the setup loses its own plan: back to every plan's, and the log says so.
        val next = SetupBook.parse(SetupBook.encode(result, emptyMap(), book, 20_384L, 1, 1, market, own, emptyMap(), rally))
        assertEquals(PlanShape(2.0, 2.0, 1.5), next.shape(breakout or volume or rsi))
        assertEquals(PlanShape(1.5, 3.0) to PlanShape(2.0, 2.0, 1.5), next.planLog.first().let { it.from to it.to })
        assertEquals(3, next.planLog.size)

        // Without learned plans the app keeps its own.
        assertEquals(PlanShape.OWN, SetupBook.parse(SetupBook.encode(result, emptyMap(), SetupBook.EMPTY, 20_383L, 1, 1)).shape(breakout or volume))
        assertEquals(PlanShape.OWN, SetupBook.EMPTY.shape(breakout or volume))
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
