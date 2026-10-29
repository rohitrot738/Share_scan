package com.rohit.share_scan

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.content.edit
import androidx.core.content.pm.PackageInfoCompat
import java.io.File
import kotlin.concurrent.thread

/**
 * The update's APK, fetched by Android's download manager so the download goes on with the app out of sight or closed,
 * its progress in the system's own notification. Once in, the file is checked (this app, the expected version) and
 * moved into the cache folder the installer is handed files from ([UpdateViewModel.UPDATE_FOLDER]). The download under
 * way is remembered in the preferences, so the screen finds it again after the app was closed.
 */
object UpdateDownload {
    sealed interface Status {
        data class Running(val percent: Int) : Status
        data object Done : Status
        data object Failed : Status
    }

    private const val DOWNLOAD_ID = "update_download_id"
    private const val DOWNLOAD_CODE = "update_download_code"
    private const val DOWNLOAD_NAME = "update_download_name"

    /** Starts downloading [release], in place of any download started before; false when Android cannot take it. */
    fun start(context: Context, release: ReleaseInfo): Boolean {
        cancel(context)
        val folder = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return false
        File(folder, fileName(release.versionCode)).delete()
        val request = DownloadManager.Request(Uri.parse(release.apkUrl))
            .setTitle(Languages.localized(context).getString(R.string.update_download_title, release.versionName))
            .setDescription(Languages.localized(context).getString(R.string.update_download_body))
            .setMimeType(AppUpdate.APK_MIME)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setAllowedOverMetered(true)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName(release.versionCode))
            .addRequestHeader("User-Agent", "ShareScan (Android)")
        val id = runCatching { manager(context).enqueue(request) }.getOrNull() ?: return false
        prefs(context).edit {
            putLong(DOWNLOAD_ID, id)
            putInt(DOWNLOAD_CODE, release.versionCode)
            putString(DOWNLOAD_NAME, release.versionName)
        }
        return true
    }

    /** How the download of version [versionCode] stands; null when none is remembered for it. */
    fun status(context: Context, versionCode: Int): Status? {
        val prefs = prefs(context)
        if (prefs.getInt(DOWNLOAD_CODE, 0) != versionCode) return null
        val id = prefs.getLong(DOWNLOAD_ID, -1L).takeIf { it >= 0L } ?: return null
        val cursor = runCatching { manager(context).query(DownloadManager.Query().setFilterById(id)) }.getOrNull()
            ?: return Status.Failed
        cursor.use {
            // Gone from the download manager: the user removed it from the Downloads app.
            if (!it.moveToFirst()) return Status.Failed
            return when (it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                DownloadManager.STATUS_SUCCESSFUL -> Status.Done
                DownloadManager.STATUS_FAILED -> Status.Failed
                else -> {
                    val done = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    val total = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                    Status.Running(if (total > 0L) (done * 100 / total).toInt().coerceIn(0, 99) else 0)
                }
            }
        }
    }

    /**
     * Moves the finished download of version [versionCode] into the installer's folder when it checks out as this app
     * at that version, and returns it there; null when it does not (the bad file is dropped). The screen and the
     * download manager's broadcast can both call this; whichever comes second finds the file already moved.
     */
    @Synchronized
    fun finish(context: Context, versionCode: Int): File? {
        val target = apkFile(context, versionCode)
        if (target.isFile && isApkOf(context, target, versionCode)) return target
        val prefs = prefs(context)
        if (prefs.getInt(DOWNLOAD_CODE, 0) != versionCode) return null
        val id = prefs.getLong(DOWNLOAD_ID, -1L)
        val downloaded = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.let { File(it, fileName(versionCode)) }
        try {
            if (downloaded == null || !downloaded.isFile || !isApkOf(context, downloaded, versionCode)) return null
            target.parentFile?.mkdirs()
            downloaded.copyTo(target, overwrite = true)
            return target
        } catch (failure: java.io.IOException) {
            target.delete()
            return null
        } finally {
            // Removing the download also deletes its file.
            if (id >= 0L) runCatching { manager(context).remove(id) }
            forget(context)
        }
    }

    /** Drops the download under way, if any, and its file. */
    fun cancel(context: Context) {
        val id = prefs(context).getLong(DOWNLOAD_ID, -1L)
        if (id >= 0L) runCatching { manager(context).remove(id) }
        forget(context)
    }

    /** The version code and name of download [id] when it is the update's. */
    fun pending(context: Context, id: Long): Pair<Int, String>? {
        val prefs = prefs(context)
        if (id < 0L || prefs.getLong(DOWNLOAD_ID, -1L) != id) return null
        return prefs.getInt(DOWNLOAD_CODE, 0) to prefs.getString(DOWNLOAD_NAME, null).orEmpty()
    }

    /** Where version [versionCode]'s checked APK waits for the installer. */
    fun apkFile(context: Context, versionCode: Int) =
        File(File(context.cacheDir, UpdateViewModel.UPDATE_FOLDER), fileName(versionCode))

    private fun isApkOf(context: Context, apk: File, versionCode: Int): Boolean {
        val manager = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            manager.getPackageArchiveInfo(apk.path, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            manager.getPackageArchiveInfo(apk.path, 0)
        } ?: return false
        return info.packageName == context.packageName && PackageInfoCompat.getLongVersionCode(info) == versionCode.toLong()
    }

    private fun forget(context: Context) = prefs(context).edit {
        remove(DOWNLOAD_ID)
        remove(DOWNLOAD_CODE)
        remove(DOWNLOAD_NAME)
    }

    private fun fileName(versionCode: Int) = "ShareScan-$versionCode.apk"

    private fun manager(context: Context) = context.getSystemService(DownloadManager::class.java)

    private fun prefs(context: Context) = context.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE)
}

/**
 * The download manager finished a download. The update's is checked and moved for the installer; with the app out of
 * sight a notification offers to install it, otherwise the screen's own banner does.
 */
class UpdateDownloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val app = context.applicationContext
        val (code, name) = UpdateDownload.pending(app, intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)) ?: return
        val result = goAsync()
        thread(name = "UpdateDownload") {
            try {
                // A failed download stays remembered: the banner says so and offers to try again.
                if (UpdateDownload.status(app, code) != UpdateDownload.Status.Done) return@thread
                if (UpdateDownload.finish(app, code) != null && !AppScreen.open) Notifier.updateReady(app, name)
            } finally {
                result.finish()
            }
        }
    }
}
