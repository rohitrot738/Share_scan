package com.rohit.share_scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class Cr360BookTest {
    private val json = """
        {"v":1,"updated":"2026-10-21T20:30+05:30","count":2,"stocks":{
          "RELIANCE":{"s":66.4,"st":"POSITIVE","c":100.0,"q":"2026-06-30","d":"2026-10-21",
            "sec":{"fq":92.0,"gr":61.0,"ev":40.0,"xx":5},"f":{"nq":13,"pq":13,"pe":21.8,"up":-4.5,"oq":12,"hpe":60,"fh":1353.0,"uh":9.36}},
          "NEWCO":{"s":null,"st":"INSUFFICIENT_DATA","c":30.0,"q":null,"d":"2026-10-21","sec":{},"f":{"nq":4,"hy":1},
            "pr":["tyre","hoverboard","car"]}
        }}
    """.trimIndent()

    @Test
    fun readsEachStocksScorePartsAndFacts() {
        val book = Cr360Book.parse(json)
        assertEquals(2, book.size)
        assertEquals("2026-10-21T20:30+05:30", book.updated)
        val r = book.of("RELIANCE")!!
        assertEquals(66.4, r.score!!, 1e-9)
        assertEquals(Cr360Book.State.POSITIVE, r.state)
        assertEquals(100.0, r.confidence, 1e-9)
        assertEquals("2026-06-30", r.quarter)
        assertEquals(mapOf(Cr360Book.Part.FINANCIAL_QUALITY to 92, Cr360Book.Part.GROWTH to 61, Cr360Book.Part.INSIDER_DEALS to 40), r.parts)
        assertEquals(21.8, r.facts[Cr360Book.Fact.PE]!!, 1e-9)
        assertEquals(-4.5, r.facts[Cr360Book.Fact.UPSIDE]!!, 1e-9)
        assertNull(r.facts[Cr360Book.Fact.HALF_YEARLY])
        assertEquals(12.0, r.facts[Cr360Book.Fact.HOLDING_QUARTERS]!!, 1e-9)
        assertEquals(60.0, r.facts[Cr360Book.Fact.HISTORY_PE]!!, 1e-9)
        assertEquals(1353.0, r.facts[Cr360Book.Fact.HISTORY_FAIR_VALUE]!!, 1e-9)
        assertEquals(9.36, r.facts[Cr360Book.Fact.HISTORY_UPSIDE]!!, 1e-9)
        val newco = book.of("NEWCO")!!
        assertNull(newco.score)
        assertEquals(Cr360Book.State.INSUFFICIENT_DATA, newco.state)
        assertEquals("", newco.quarter)
        assertEquals(1.0, newco.facts[Cr360Book.Fact.HALF_YEARLY]!!, 1e-9)
        assertNull(book.of("TCS"))
    }

    @Test
    fun productsKeepTheirOrderAndDropKeysTheAppDoesNotKnow() {
        val book = Cr360Book.parse(json)
        assertEquals(listOf("tyre", "car"), book.productsOf("NEWCO"))
        assertEquals(emptyList<String>(), book.productsOf("RELIANCE"))
        assertEquals(emptyList<String>(), book.productsOf("TCS"))
    }

    @Test
    fun everyProductHasAnIconAndCardsShowTheFirstFew() {
        assertEquals(89, Products.ICONS.size)
        assertEquals(Products.ICONS.size, Products.ICONS.values.toSet().size)
        assertEquals("🛞🚗", Products.icons(listOf("tyre", "car")))
        assertEquals("🛞🚗🚚", Products.icons(listOf("tyre", "car", "truck", "bus")))
        assertEquals("", Products.icons(emptyList()))
    }

    @Test
    fun brokenOrForeignFilesAreEmpty() {
        assertSame(Cr360Book.EMPTY, Cr360Book.parse(null))
        assertSame(Cr360Book.EMPTY, Cr360Book.parse("<html>Not Found</html>"))
        assertSame(Cr360Book.EMPTY, Cr360Book.parse("""{"v":2,"stocks":{}}"""))
        assertEquals(Cr360Book.State.INSUFFICIENT_DATA, Cr360Book.parse("""{"v":1,"stocks":{"X":{"st":"MAYBE"}}}""").of("X")!!.state)
    }

    @Test
    fun partsWeighOneHundred() {
        assertEquals(100, Cr360Book.Part.entries.sumOf { it.weight })
    }

    @Test
    fun figuresAndDatesReadPlainly() {
        assertEquals("21.8", Cr360Format.number(21.8))
        assertEquals("50", Cr360Format.number(50.0, 2))
        assertEquals("0.45", Cr360Format.number(0.4512, 2))
        assertEquals("−5.41", Cr360Format.number(-5.41, 2))
        assertEquals("0", Cr360Format.number(-0.01))
        assertEquals("+1.2", Cr360Format.signed(1.23))
        assertEquals("−0.4", Cr360Format.signed(-0.4))
        assertEquals("0", Cr360Format.signed(0.0))
        assertEquals("Jun 2026", Cr360Format.month("2026-06-30"))
        assertEquals("21 Oct", Cr360Format.day("2026-10-21"))
        assertEquals("", Cr360Format.month(""))
    }

    @Test
    fun sortingBy360crPutsStocksWithoutOneLast() {
        val book = Cr360Book.parse(
            """{"v":1,"stocks":{"A":{"s":40,"st":"CAUTION"},"B":{"s":80,"st":"HIGH_CONVICTION"},"C":{"s":null,"st":"INSUFFICIENT_DATA"}}}""",
        )
        fun stock(symbol: String, score: Double) = ScanResult(symbol = symbol, price = 10.0, score = score, rvol = 1.0, change5d = 0.0)
        val results = listOf(stock("C", 90.0), stock("A", 80.0), stock("D", 70.0), stock("B", 60.0))
        val state = ScanUiState(phase = ScanPhase.DONE, results = results, filters = ScanFilters(limit = 0), cr360 = book, sort = SortMode.CR360)
        assertEquals(listOf("B", "A", "C", "D"), state.sortedResults.map { it.stock.symbol })
        assertEquals(listOf("A", "B", "C", "D"), state.copy(ascending = true).sortedResults.map { it.stock.symbol })
    }
}
