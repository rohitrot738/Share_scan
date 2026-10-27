package com.rohit.share_scan

import com.rohit.share_scan.Cr360Book.Fact
import com.rohit.share_scan.Cr360Framework.Flag
import com.rohit.share_scan.Cr360Framework.Model
import com.rohit.share_scan.Cr360Framework.Penalty
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class Cr360FrameworkTest {
    /** A row as the daily job writes it (cr360/app_store.framework_facts). */
    private val json = """
        {"v":1,"updated":"2026-10-27T20:30+05:30","count":3,"stocks":{
          "TATASTEEL":{"s":41.0,"st":"CAUTION","c":100.0,"q":"2026-06-30","d":"2026-10-27","sec":{"va":30},
            "f":{"fv":118.2,"up":-21.4,"mdl":3,"smd":3,"ivr":184.69,"bp":94.56,"mos":20.0,"flg":17,"pf":3,"adr":20260501,
                 "pl":0.0,"icr":3.1,"cfp":1.4,"evo":7.5,"evn":8.2,"eb":30000.0,"dt":90000.0,"sh":1248.0,"roce":9.0,"e200":150.4}},
          "HDFCBANK":{"s":70.0,"st":"POSITIVE","c":100.0,"q":"2026-09-30","d":"2026-10-27","sec":{},
            "f":{"fv":930.02,"mdl":1,"smd":1,"ivr":930.02,"bp":604.51,"mos":35.0,"bv":391.0,"abv":388.0,"nnpa":0.5,"pbo":2.38,"roa":1.62,
                 "flg":96,"wsl":690.5}},
          "OLD":{"s":50.0,"st":"NEUTRAL","c":90.0,"q":"2026-06-30","d":"2026-10-01","sec":{},"f":{"fv":100.0,"up":5.0}}
        }}
    """.trimIndent()

    private val book = Cr360Book.parse(json)

    private fun of(symbol: String) = Cr360Framework.of(book.of(symbol)!!.facts)

    @Test
    fun readsTheModelValuesFlagsAndPenalties() {
        val steel = of("TATASTEEL")!!
        assertEquals(Model.CYCLICAL, steel.model)
        assertEquals(Model.CYCLICAL, steel.sectorModel)
        assertEquals(118.2, steel.value!!, 1e-9)
        assertEquals(184.69, steel.rawValue!!, 1e-9)
        assertEquals(94.56, steel.buyPrice!!, 1e-9)
        assertEquals(Cr360Framework.Size.LARGE, steel.size)
        // 17 = REJECT + CYCLICAL_WARNING; 3 = AUDITOR + DEBT.
        assertEquals(setOf(Flag.REJECT, Flag.CYCLICAL_WARNING), steel.flags)
        assertEquals(setOf(Penalty.AUDITOR, Penalty.DEBT), steel.penalties)
        assertEquals(0.6 * 0.8, steel.penaltyFactor, 1e-12)
        assertEquals(LocalDate.of(2026, 5, 1), steel.auditorResigned)
        assertEquals(1248.0, steel[Fact.SHARES_CR]!!, 1e-9)
        assertTrue(steel.blocked(redFlag = false))

        val bank = of("HDFCBANK")!!
        assertEquals(Model.BANK, bank.model)
        assertEquals(Cr360Framework.Size.SMALL, bank.size)
        assertEquals(setOf(Flag.WEEKLY_SWEEP, Flag.BEST), bank.flags)
        assertTrue(bank.penalties.isEmpty())
        assertEquals(1.0, bank.penaltyFactor, 0.0)
        assertNull(bank.auditorResigned)
        assertFalse(bank.blocked(redFlag = false))
        assertTrue(bank.best(redFlag = false))
        // An operator's volume blocks the buy and takes away the best set-up.
        assertTrue(bank.blocked(redFlag = true))
        assertFalse(bank.best(redFlag = true))
        // A file built before the framework has none.
        assertNull(of("OLD"))
    }

    @Test
    fun priceAgainstTheBuyPrice() {
        val bank = of("HDFCBANK")!!
        assertEquals((704.0 / 604.51 - 1) * 100, bank.fromBuy(704.0)!!, 1e-9)
        assertTrue(bank.fromBuy(590.0)!! < 0)
        assertNull(bank.fromBuy(0.0))
    }

    @Test
    fun sizeFollowsTheMarginOfSafety() {
        fun size(mos: Double) = Cr360Framework.of(mapOf(Fact.MODEL to 0.0, Fact.MARGIN_OF_SAFETY to mos))!!.size
        assertEquals(Cr360Framework.Size.LARGE, size(20.0))
        assertEquals(Cr360Framework.Size.MID, size(27.5))
        assertEquals(Cr360Framework.Size.SMALL, size(35.0))
        // A fallback to the blend keeps the sector's own model apart.
        val fallback = Cr360Framework.of(mapOf(Fact.MODEL to 0.0, Fact.SECTOR_MODEL to 4.0))!!
        assertEquals(Model.GENERAL, fallback.model)
        assertEquals(Model.CASH, fallback.sectorModel)
    }

    @Test
    fun aVolumeSpikeWithoutNewsIsTheOperatorRedFlag() {
        val now = 1_800_000_000_000L
        val day = StockNews.DAY_MS
        fun item(at: Long, kind: NewsKind = NewsKind.OTHER, official: Boolean = true) = NewsItem("X", at, "t", "NSE", "u", kind, official, "$at")
        // Three times the volume and nothing in three days: red flag.
        assertTrue(Cr360Framework.operatorRedFlag(3.2, listOf(item(now - 5 * day)), now))
        assertTrue(Cr360Framework.operatorRedFlag(3.0, emptyList(), now))
        // A filing or an article explains it; a routine notice does not.
        assertFalse(Cr360Framework.operatorRedFlag(3.2, listOf(item(now - 2 * day)), now))
        assertFalse(Cr360Framework.operatorRedFlag(3.2, listOf(item(now - day, official = false)), now))
        assertTrue(Cr360Framework.operatorRedFlag(3.2, listOf(item(now - day, NewsKind.ROUTINE)), now))
        // A smaller spike, or news that could not be read, raises nothing.
        assertFalse(Cr360Framework.operatorRedFlag(2.9, emptyList(), now))
        assertFalse(Cr360Framework.operatorRedFlag(5.0, null, now))
        assertFalse(Cr360Framework.operatorRedFlag(Double.NaN, emptyList(), now))
    }
}
