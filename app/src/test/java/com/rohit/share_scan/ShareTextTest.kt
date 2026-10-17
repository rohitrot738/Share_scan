package com.rohit.share_scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import javax.xml.parsers.DocumentBuilderFactory

class ShareTextTest {
    /** The line format exactly as res/values/strings_list.xml spells it. */
    private val template: String by lazy {
        val file = listOf("src/main/res/values/strings_list.xml", "app/src/main/res/values/strings_list.xml")
            .map(::File)
            .first { it.isFile }
        val strings = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getElementsByTagName("string")
        (0 until strings.length).map { strings.item(it) }
            .first { it.attributes.getNamedItem("name").nodeValue == "list_share_line" }
            .textContent
    }

    private val techm = ScanResult("TECHM", 1_790.0, 88.8, 8.05, 3.2)

    @Test
    fun lineMatchesTheSharedFormat() {
        assertEquals("1. TECHM ₹1,790.00 • score 88.8 • RVOL 8.05×", ShareText.line(template, 1, techm))
    }

    @Test
    fun headerNamesAppDateAndFilters() {
        assertEquals(
            "Share Scan • 7 Oct 2026 • 2 filter लागू",
            ShareText.header("Share Scan", LocalDate.of(2026, 10, 7), "2 filter लागू"),
        )
    }

    @Test
    fun buildSharesTheFirstTenInTheGivenOrder() {
        val stocks = (1..12).map { ScanResult("S$it", 100.0 + it, 90.0 - it, 1.5, 0.0) }.reversed()
        val lines = ShareText.build("HEADER", template, stocks).lines()
        assertEquals(1 + ShareText.COUNT, lines.size)
        assertEquals("HEADER", lines[0])
        assertEquals("1. S12 ₹112.00 • score 78.0 • RVOL 1.50×", lines[1])
        assertEquals("10. S3 ₹103.00 • score 87.0 • RVOL 1.50×", lines[10])
    }

    @Test
    fun buildWithFewStocksHasNoTrailingBlankLine() {
        assertEquals("HEADER", ShareText.build("HEADER", template, emptyList()))
        val text = ShareText.build("HEADER", template, listOf(techm))
        assertTrue(text.endsWith("RVOL 8.05×"))
        assertEquals(2, text.lines().size)
    }
}
