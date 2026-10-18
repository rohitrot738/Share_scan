package com.rohit.share_scan

import org.junit.Assert.assertEquals
import org.junit.Test

class GlassStyleTest {
    @Test
    fun unknownKeysFallBackToFrosted() {
        assertEquals(GlassStyle.FROSTED, GlassStyle.fromKey(null))
        assertEquals(GlassStyle.FROSTED, GlassStyle.fromKey("mirror"))
    }

    @Test
    fun keysRoundTrip() {
        GlassStyle.entries.forEach { assertEquals(it, GlassStyle.fromKey(it.key)) }
    }

    @Test
    fun everyStyleHasItsOwnOverlayAndLabel() {
        val styles = GlassStyle.entries
        assertEquals(styles.size, styles.map { it.overlay }.toSet().size)
        assertEquals(styles.size, styles.map { it.label }.toSet().size)
        assertEquals(styles.size, styles.map { it.body }.toSet().size)
    }
}
