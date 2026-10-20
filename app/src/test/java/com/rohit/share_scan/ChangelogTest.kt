package com.rohit.share_scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

class ChangelogTest {
    @Test
    fun entriesReadWithTheNewestStillUnnumbered() {
        val notes = Changelog.parse(
            """[{"version": "", "date": "2026-10-20", "items": ["नया"]},
               {"version": "1.2.120", "date": "2026-10-19", "items": ["एक", "दो"]}]""",
        )
        assertEquals(listOf("", "1.2.120"), notes.map { it.version })
        assertEquals(LocalDate.of(2026, 10, 19), notes[1].date)
        assertEquals(listOf("एक", "दो"), notes[1].items)
        assertEquals("1.2.121", Changelog.versionOf(notes[0], installed = "1.2.121"))
        assertEquals("1.2.120", Changelog.versionOf(notes[1], installed = "1.2.121"))
    }

    @Test
    fun anythingElseReadsAsNoUpdates() {
        assertTrue(Changelog.parse("").isEmpty())
        assertTrue(Changelog.parse("{}").isEmpty())
        assertTrue(Changelog.parse("""[{"version": "1.2.1", "date": "kal", "items": []}]""").isEmpty())
    }

    @Test
    fun theBundledChangelogStaysInOrder() {
        val file = listOf("src/main/assets/changelog.json", "app/src/main/assets/changelog.json").map(::File).first { it.exists() }
        val notes = Changelog.parse(file.readText())
        assertTrue(notes.isNotEmpty())
        assertTrue(notes.all { note -> note.items.isNotEmpty() && note.items.none { it.isBlank() } })
        // Only the newest entry may wait for its version; the rest run newest first, and so do the days.
        assertTrue(notes.drop(1).none { it.version.isEmpty() })
        val numbered = notes.filter { it.version.isNotEmpty() }.map { note -> note.version.split('.').map(String::toInt) }
        assertTrue(numbered.all { it.size == 3 })
        val order = compareBy<List<Int>>({ it[0] }, { it[1] }, { it[2] })
        assertTrue(numbered.zipWithNext().all { (newer, older) -> order.compare(newer, older) > 0 })
        assertTrue(notes.zipWithNext().all { (newer, older) -> !newer.date.isBefore(older.date) })
    }
}
