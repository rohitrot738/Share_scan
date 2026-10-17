package com.rohit.share_scan

import org.json.JSONObject

/** A signed release published by the "Release Android APK" workflow. */
data class ReleaseInfo(val versionCode: Int, val versionName: String, val apkUrl: String)

/** Reads GitHub's latest-release answer; kept free of Android types so it can be unit tested. */
object AppUpdate {
    const val LATEST_RELEASE_API = "https://api.github.com/repos/rohitrot738/Share_scan/releases/latest"
    const val APK_NAME = "ShareScan.apk"
    const val APK_MIME = "application/vnd.android.package-archive"
    private const val DOWNLOAD_PREFIX = "https://github.com/rohitrot738/Share_scan/releases/download/"

    /** Release tags look like "v1.2.102"; the last number is the version code that release was built with. */
    fun versionCodeFromTag(tag: String): Int? = tag.trim().removePrefix("v").substringAfterLast('.').toIntOrNull()

    /** The release's APK, or null for drafts, pre-releases, unexpected tags or an APK hosted anywhere else. */
    fun parseLatest(json: String): ReleaseInfo? {
        val release = runCatching { JSONObject(json) }.getOrNull() ?: return null
        if (release.optBoolean("draft") || release.optBoolean("prerelease")) return null
        val tag = release.optString("tag_name")
        val versionCode = versionCodeFromTag(tag) ?: return null
        val assets = release.optJSONArray("assets") ?: return null
        for (index in 0 until assets.length()) {
            val asset = assets.optJSONObject(index) ?: continue
            val url = asset.optString("browser_download_url")
            if (asset.optString("name") == APK_NAME && url.startsWith(DOWNLOAD_PREFIX)) {
                return ReleaseInfo(versionCode, tag.trim().removePrefix("v"), url)
            }
        }
        return null
    }

    fun isNewer(release: ReleaseInfo, installedVersionCode: Long): Boolean = release.versionCode > installedVersionCode
}
