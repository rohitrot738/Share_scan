package com.rohit.share_scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class UsageStatsTest {
    private fun at(text: String) = Instant.parse(text).toEpochMilli()

    @Test
    fun phonesAreCountedByWhenTheyLastOpenedTheApp() {
        val now = at("2026-10-20T06:00:00Z") // 11:30 IST
        val stats = UsageStats.of(
            listOf(
                UsageStats.CheckIn(at("2026-10-19T19:00:00Z"), "1.2.123"), // 00:30 IST today
                UsageStats.CheckIn(at("2026-10-19T18:00:00Z"), "1.2.123"), // 23:30 IST yesterday
                UsageStats.CheckIn(at("2026-10-14T06:00:00Z"), "1.2.122"), // six days ago
                UsageStats.CheckIn(at("2026-10-10T06:00:00Z"), "1.2.120"), // ten days ago
                UsageStats.CheckIn(at("2026-09-10T06:00:00Z"), "1.2.101"), // forty days ago: not counted
            ),
            now,
        )
        assertEquals(1, stats.today)
        assertEquals(3, stats.week)
        assertEquals(4, stats.month)
        assertEquals(listOf("1.2.123" to 2, "1.2.122" to 1, "1.2.120" to 1), stats.versions)
    }

    @Test
    fun noCheckInsCountNothing() {
        assertEquals(UsageStats(0, 0, 0, emptyList()), UsageStats.of(emptyList(), at("2026-10-20T06:00:00Z")))
    }

    @Test
    fun releaseDownloadsAddUpTheApkOnly() {
        val page = UsageStats.releasePage(
            """[{"tag_name": "v1.2.122", "assets": [{"name": "ShareScan.apk", "download_count": 4},
                                                   {"name": "notes.txt", "download_count": 9}]},
               {"tag_name": "v1.2.121", "assets": [{"name": "ShareScan.apk", "download_count": 1}]},
               {"tag_name": "v1.2.120"}]""",
        )
        assertEquals(UsageStats.ReleasePage(releases = 3, downloads = 5), page)
        assertNull(UsageStats.releasePage("{\"message\": \"rate limited\"}"))
    }
}
