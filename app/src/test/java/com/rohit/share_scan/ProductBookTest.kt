package com.rohit.share_scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ProductBookTest {
    @Test
    fun productsKeepTheirOrderAndDropKeysTheAppDoesNotKnow() {
        val book = ProductBook.parse("""{"v":1,"updated":"2026-10-30","stocks":{"MRF":["tyre","toys","paint"],"NEWCO":["rockets"],"X":[]}}""")
        assertEquals(1, book.size)
        assertEquals(listOf("tyre", "toys", "paint"), book.of("MRF"))
        assertEquals(emptyList<String>(), book.of("NEWCO"))
        assertEquals(emptyList<String>(), book.of("TCS"))
    }

    @Test
    fun aCompanyTheFileLacksTakesTheDailyFilesProducts() {
        val book = ProductBook.parse("""{"v":1,"stocks":{"MRF":["tyre"]}}""")
        val cr360 = Cr360Book.parse("""{"v":1,"stocks":{"MRF":{"st":"NEUTRAL","pr":["paint"]},"NEWCO":{"st":"NEUTRAL","pr":["car"]}}}""")
        assertEquals(listOf("tyre"), book.of("MRF", cr360))
        assertEquals(listOf("car"), book.of("NEWCO", cr360))
        assertEquals(emptyList<String>(), book.of("TCS", cr360))
    }

    @Test
    fun brokenOrUnexpectedInputIsEmpty() {
        assertSame(ProductBook.EMPTY, ProductBook.parse(null))
        assertSame(ProductBook.EMPTY, ProductBook.parse("not json"))
        assertSame(ProductBook.EMPTY, ProductBook.parse("""{"v":2,"stocks":{"MRF":["tyre"]}}"""))
    }
}
