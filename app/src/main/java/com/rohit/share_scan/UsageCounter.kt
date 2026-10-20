package com.rohit.share_scan

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import com.google.firebase.Timestamp
import com.google.firebase.firestore.AggregateSource
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.util.Date
import java.util.UUID

/**
 * Counts the phones the app runs on, for its maker alone. Once a day (IST) each phone writes devices/{random id} in
 * Firestore: its version and the server's time, nothing else about the phone or its user. Only the maker's GitHub
 * account ([OWNER_GITHUB_ID]) may read them back, as the Firestore rules in docs/LOGIN_SETUP.md enforce, and the menu
 * offers the count to that account alone ([isOwner]). A build without Firebase counts nothing.
 */
object UsageCounter {
    /** rohitrot738's numeric GitHub id, which Firebase keeps as the GitHub login's uid. */
    const val OWNER_GITHUB_ID = "178737756"

    private const val TAG = "ShareScan"
    private const val DEVICES = "devices"
    private const val FIELD_VERSION = "v"
    private const val FIELD_SEEN = "seen"

    /** The Firestore rules accept versions this long at most. */
    private const val MAX_VERSION = 20

    private const val RELEASES_API = "https://api.github.com/repos/rohitrot738/Share_scan/releases?per_page=100&page="
    private const val RELEASES_PER_PAGE = 100
    private const val MAX_PAGES = 10

    /** Writes this phone's check-in, at most once a day; a failed one is tried again on the next open. */
    fun checkIn(context: Context, version: String) {
        val app = context.applicationContext
        AccountManager.auth(app) ?: return
        val prefs = app.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE)
        val today = LocalDate.now(MarketClock.INDIA).toEpochDay()
        if (prefs.getLong(Prefs.USAGE_DAY, -1L) == today) return
        val id = prefs.getString(Prefs.INSTALL_ID, null)
            ?: UUID.randomUUID().toString().also { prefs.edit { putString(Prefs.INSTALL_ID, it) } }
        FirebaseFirestore.getInstance().collection(DEVICES).document(id)
            .set(mapOf(FIELD_VERSION to version.take(MAX_VERSION), FIELD_SEEN to FieldValue.serverTimestamp()))
            .addOnSuccessListener { prefs.edit { putLong(Prefs.USAGE_DAY, today) } }
            .addOnFailureListener { Log.w(TAG, "Usage check-in failed", it) }
    }

    /** The signed-in account logged in with, or is linked to, the maker's GitHub account. */
    fun isOwner(context: Context): Boolean =
        AccountManager.auth(context.applicationContext)?.currentUser?.providerData.orEmpty()
            .any { it.providerId == AccountManager.PROVIDER_GITHUB && it.uid == OWNER_GITHUB_ID }

    /** What the usage sheet shows: the recent counts, every phone ever counted, and the APK's GitHub downloads. */
    data class Report(val stats: UsageStats, val phones: Long, val downloads: Int?)

    /** Reads the check-ins; Firestore refuses anyone but the maker. */
    suspend fun report(context: Context): Report {
        AccountManager.auth(context.applicationContext) ?: throw AccountManager.UnavailableException()
        val now = System.currentTimeMillis()
        val devices = FirebaseFirestore.getInstance().collection(DEVICES)
        val since = Timestamp(Date(now - UsageStats.MONTH_DAYS * UsageStats.DAY_MS))
        val checkIns = devices.whereGreaterThanOrEqualTo(FIELD_SEEN, since).get().await().documents.mapNotNull { doc ->
            val seen = doc.getTimestamp(FIELD_SEEN) ?: return@mapNotNull null
            UsageStats.CheckIn(seen.toDate().time, doc.getString(FIELD_VERSION).orEmpty())
        }
        val phones = devices.count().get(AggregateSource.SERVER).await().count
        val downloads = withContext(Dispatchers.IO) { runCatching { downloads() }.getOrNull() }
        return Report(UsageStats.of(checkIns, now), phones, downloads)
    }

    /** The APK's downloads over every release, page by page; null when GitHub does not answer. */
    private fun downloads(): Int? {
        var total = 0
        for (page in 1..MAX_PAGES) {
            val connection = (URL(RELEASES_API + page).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 25_000
                setRequestProperty("User-Agent", "ShareScan (Android)")
                setRequestProperty("Accept", "application/vnd.github+json")
            }
            val releases = try {
                if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
                UsageStats.releasePage(connection.inputStream.bufferedReader().use { it.readText() }) ?: return null
            } finally {
                connection.disconnect()
            }
            total += releases.downloads
            if (releases.releases < RELEASES_PER_PAGE) break
        }
        return total
    }
}
