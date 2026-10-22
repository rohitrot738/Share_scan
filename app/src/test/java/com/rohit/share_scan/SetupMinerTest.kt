package com.rohit.share_scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SetupMinerTest {
    private val volume = SetupCondition.VOLUME_2X.flag
    private val rsi = SetupCondition.RSI_50_70.flag
    private val quiet = SetupCondition.QUIET_BEFORE.flag

    private val hot = SetupCondition.RSI_70_PLUS.flag

    /**
     * 300 sessions × 40 plans; the market wins 30%, plans with volume and RSI together 65%, quiet ones 30% too, quiet
     * and hot ones together [quietAndHotRate]. Half the failures hit the stop; nine in ten of the quiet and hot ones.
     */
    private fun market(seed: Int = 1, volumeAndRsiRate: Double = 0.65, quietAndHotRate: Double = 0.3): List<SetupMiner.Sample> {
        val random = Random(seed)
        return (0 until 300).flatMap { day ->
            List(40) {
                var mask = 0L
                if (random.nextDouble() < 0.3) mask = mask or volume
                if (random.nextDouble() < 0.4) mask = mask or rsi
                if (random.nextDouble() < 0.5) mask = mask or quiet
                if (random.nextDouble() < 0.3) mask = mask or hot
                val quietAndHot = mask and (quiet or hot) == (quiet or hot)
                val rate = when {
                    quietAndHot -> quietAndHotRate
                    mask and (volume or rsi) == (volume or rsi) -> volumeAndRsiRate
                    else -> 0.3
                }
                val win = random.nextDouble() < rate
                val stopped = !win && random.nextDouble() < if (quietAndHot) 0.9 else 0.5
                SetupMiner.Sample(mask, 20_000L + day, win, stopped)
            }
        }
    }

    @Test
    fun aSetupThatWinsOnPlansItWasNotPickedOnIsAdded() {
        val result = SetupMiner.learn(market(), emptyMap(), 20_400L)
        val best = result.rules.first()
        assertEquals(volume or rsi, best.mask)
        assertTrue(best.holdout.rate > 0.55)
        assertEquals(20_400L, best.since)
        assertTrue(result.changes.single { it.mask == best.mask }.added)
        assertTrue(result.rules.none { it.mask == quiet }) // no better than the market
        assertEquals(0.3 + 0.0, result.holdout.rate, 0.06)
        assertEquals(SetupCondition.VOLUME_2X, result.lift.first { it.condition == SetupCondition.VOLUME_2X }.condition)
        assertTrue(result.lift.first { it.condition == SetupCondition.VOLUME_2X }.lift > 1.2)
    }

    @Test
    fun aSetupThatStopsWorkingIsRemovedAndOneInUseKeepsItsDate() {
        val previous = mapOf((volume or rsi) to 20_100L)
        val kept = SetupMiner.learn(market(), previous, 20_400L)
        assertEquals(20_100L, kept.rules.first { it.mask == volume or rsi }.since)
        assertTrue(kept.changes.none { it.mask == volume or rsi })

        val failing = SetupMiner.learn(market(seed = 2, volumeAndRsiRate = 0.3), previous, 20_400L)
        assertTrue(failing.rules.none { it.mask == volume or rsi })
        val removed = failing.changes.single { it.mask == volume or rsi }
        assertFalse(removed.added)
        assertFalse(removed.replaced)
    }

    @Test
    fun nearlyTheSameTradesAreUsedOnce() {
        val result = SetupMiner.learn(market(), emptyMap(), 20_400L)
        // "volume + RSI" and "volume + RSI + quiet" pick largely the same plans; only one of them is used.
        val masks = result.rules.map { it.mask }
        assertFalse((volume or rsi) in masks && (volume or rsi or quiet) in masks)
    }

    @Test
    fun aSetupWhoseTradesWentTheWrongWayIsLearnedAsAMistake() {
        val result = SetupMiner.learn(market(quietAndHotRate = 0.08), emptyMap(), 20_400L)
        val worst = result.avoid.first()
        assertEquals(quiet or hot, worst.mask)
        assertTrue(worst.holdout.rate < 0.15)
        assertTrue(worst.all.stopRate > 0.7) // most of them hit the stop, not just came too late
        val added = result.changes.single { it.mask == worst.mask }
        assertTrue(added.added && added.avoid)
        // "Hot" alone or "quiet" alone pick the same bad trades, and one more condition adds nothing: it is named once.
        assertEquals(1, result.avoid.count { it.mask and (quiet or hot) != 0L })
        assertTrue(result.rules.none { it.mask and (quiet or hot) == (quiet or hot) })
        // The stopped trades had quiet and hot in common more than the rest.
        assertTrue(result.loseLift.first().condition in setOf(SetupCondition.QUIET_BEFORE, SetupCondition.RSI_70_PLUS))
        assertTrue(result.loseLift.first().lift > 1.2)
    }

    @Test
    fun aMistakeThatStopsFailingIsRemoved() {
        val previous = mapOf((quiet or hot) to 20_100L)
        val still = SetupMiner.learn(market(quietAndHotRate = 0.08), emptyMap(), 20_400L, previous)
        assertEquals(20_100L, still.avoid.first { it.mask == quiet or hot }.since)

        val fixed = SetupMiner.learn(market(seed = 2), emptyMap(), 20_400L, previous)
        assertTrue(fixed.avoid.none { it.mask == quiet or hot })
        val removed = fixed.changes.single { it.mask == quiet or hot }
        assertFalse(removed.added)
        assertTrue(removed.avoid)
    }

    @Test
    fun aConditionThatAddsNothingIsLeftOut() {
        // Volume + RSI wins 65% with or without "quiet"; the narrower setup is the same one sliced by chance.
        val result = SetupMiner.learn(market(), emptyMap(), 20_400L)
        assertTrue(result.rules.none { it.mask == volume or rsi or quiet })
        assertTrue(result.changes.none { it.mask == volume or rsi or quiet })
    }

    @Test
    fun wilsonBoundIsCautiousOnFewPlans() {
        assertEquals(0.0, SetupMiner.wilsonLower(SetupMiner.Stats(0, 0)), 1e-9)
        assertTrue(SetupMiner.wilsonLower(SetupMiner.Stats(10, 7)) < 0.45)
        assertTrue(SetupMiner.wilsonLower(SetupMiner.Stats(1000, 700)) > 0.67)
        assertEquals(1.0, SetupMiner.wilsonUpper(SetupMiner.Stats(0, 0)), 1e-9)
        assertTrue(SetupMiner.wilsonUpper(SetupMiner.Stats(10, 1)) > 0.35)
        assertTrue(SetupMiner.wilsonUpper(SetupMiner.Stats(1000, 100)) < 0.125)
    }
}
