package com.rohit.share_scan

import org.json.JSONArray
import java.time.LocalDate

/** One app update in the About sheet's list. */
data class ReleaseNote(
    /** "1.2.120"; empty for the newest entry, written before its release gave it a number. */
    val version: String,
    /** The day it was released (IST). */
    val date: LocalDate,
    /** What changed, one line each, in Hindi. */
    val items: List<String>,
)

/**
 * Every app update, newest first, from assets/changelog.json:
 * `[{"version": "1.2.120", "date": "2026-10-20", "items": ["…"]}]`. Free of Android types so it can be unit tested.
 *
 * A change to the app adds its entry on top with an empty version. The release built from it takes that entry's items
 * as its GitHub notes (android-release.yml) and shows the entry under its own version ([versionOf]). The next change
 * fills in that version, from the GitHub release, before adding its own entry.
 */
object Changelog {
    const val ASSET = "changelog.json"

    /** The entries in [text], or none when it is not a changelog. */
    fun parse(text: String): List<ReleaseNote> = runCatching {
        val entries = JSONArray(text)
        List(entries.length()) { i ->
            val entry = entries.getJSONObject(i)
            val items = entry.getJSONArray("items")
            ReleaseNote(
                version = entry.optString("version"),
                date = LocalDate.parse(entry.getString("date")),
                items = List(items.length()) { items.getString(it) },
            )
        }
    }.getOrDefault(emptyList())

    /** [note]'s version; the newest entry, still without one, is the [installed] build's own. */
    fun versionOf(note: ReleaseNote, installed: String): String = note.version.ifEmpty { installed }
}
