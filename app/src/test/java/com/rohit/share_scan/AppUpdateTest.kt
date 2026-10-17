package com.rohit.share_scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateTest {
    private fun release(tag: String, assetName: String = "ShareScan.apk", url: String? = null, extra: String = ""): String {
        val link = url ?: "https://github.com/rohitrot738/Share_scan/releases/download/$tag/$assetName"
        return """{"tag_name":"$tag","name":"Share Scan","assets":[{"name":"notes.txt","browser_download_url":"x"},""" +
            """{"name":"$assetName","browser_download_url":"$link"}]$extra}"""
    }

    @Test
    fun readsVersionAndApkFromLatestRelease() {
        val info = AppUpdate.parseLatest(release("v1.2.103"))
        assertEquals(
            ReleaseInfo(103, "1.2.103", "https://github.com/rohitrot738/Share_scan/releases/download/v1.2.103/ShareScan.apk"),
            info,
        )
    }

    @Test
    fun rejectsReleasesItCannotTrust() {
        assertNull(AppUpdate.parseLatest(release("v1.2.103", assetName = "Other.apk")))
        assertNull(AppUpdate.parseLatest(release("v1.2.103", url = "https://example.com/ShareScan.apk")))
        assertNull(AppUpdate.parseLatest(release("nightly")))
        assertNull(AppUpdate.parseLatest(release("v1.2.103", extra = ""","draft":true""")))
        assertNull(AppUpdate.parseLatest(release("v1.2.103", extra = ""","prerelease":true""")))
        assertNull(AppUpdate.parseLatest("not json"))
    }

    @Test
    fun comparesVersionCodes() {
        assertEquals(102, AppUpdate.versionCodeFromTag("v1.2.102"))
        assertEquals(7, AppUpdate.versionCodeFromTag("7"))
        val info = ReleaseInfo(103, "1.2.103", "u")
        assertTrue(AppUpdate.isNewer(info, 102))
        assertFalse(AppUpdate.isNewer(info, 103))
        assertFalse(AppUpdate.isNewer(info, 200))
    }
}
