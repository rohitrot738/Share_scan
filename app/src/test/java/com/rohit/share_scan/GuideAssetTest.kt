package com.rohit.share_scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The beginner's guide bundled in the app (assets/guide) opens whole without internet. */
class GuideAssetTest {
    private val dir = listOf("src/main/assets/guide", "app/src/main/assets/guide").map(::File).first { it.exists() }
    private val page = File(dir, "index.html").readText()

    @Test
    fun everyPictureThePageNamesShipsWithIt() {
        val pictures = Regex("""src="([^"]+)"""").findAll(page).map { it.groupValues[1] }.toList()
        assertTrue(pictures.size >= 15)
        val missing = pictures.filterNot { File(dir, it).isFile }
        assertEquals(emptyList<String>(), missing)
    }

    @Test
    fun itLoadsNothingFromTheInternetAndItsFontsAreTheApps() {
        assertTrue(page.startsWith("<!doctype html>"))
        // Only plain links may point out (they open in the browser); nothing the page loads may.
        assertEquals(emptyList<String>(), Regex("""(?:src|url\()\s*"?(https?://[^")]+)""").findAll(page).map { it.groupValues[1] }.toList())
        assertTrue(!page.contains("<link"))
        val res = dir.parentFile!!.parentFile!!.resolve("res/font")
        val fonts = Regex("""file:///android_res/font/([a-z_]+\.ttf)""").findAll(page).map { it.groupValues[1] }.toSet()
        assertTrue(fonts.isNotEmpty())
        assertEquals(emptySet<String>(), fonts.filterNot { File(res, it).isFile }.toSet())
    }
}
