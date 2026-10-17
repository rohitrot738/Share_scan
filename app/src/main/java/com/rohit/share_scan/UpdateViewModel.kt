package com.rohit.share_scan

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.edit
import androidx.core.content.pm.PackageInfoCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

enum class UpdateStep { AVAILABLE, DOWNLOADING, READY, FAILED }

/** What a check the user asked for found; automatic checks on launch stay silent. */
sealed interface UpdateCheck {
    data object Found : UpdateCheck
    data class UpToDate(val version: String) : UpdateCheck
    data object Failed : UpdateCheck
    /** Debug builds carry a different key, so they never look for releases. */
    data object Disabled : UpdateCheck
}

/** A newer release the banner offers; [apk] is set once it is downloaded and checked. */
data class UpdateOffer(
    val release: ReleaseInfo,
    val installedName: String,
    val step: UpdateStep = UpdateStep.AVAILABLE,
    val percent: Int = 0,
    val apk: File? = null,
)

/**
 * Looks for a newer signed release on launch and, when the user asks, downloads its APK into the cache
 * so the system installer can put it over this one. The launch check can be switched off in Settings; the menu
 * checks on demand. Debug builds never check: they carry a different key.
 */
class UpdateViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE)
    private val folder = File(application.cacheDir, UPDATE_FOLDER)
    private val state = MutableStateFlow<UpdateOffer?>(null)
    private val installs = Channel<File>(Channel.CONFLATED)
    private val checks = Channel<UpdateCheck>(Channel.BUFFERED)
    private val debuggable = (application.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    private var downloadJob: Job? = null
    private var checkJob: Job? = null

    /** The banner's content; null hides it. */
    val offer: StateFlow<UpdateOffer?> = state.asStateFlow()

    /** APKs ready for the system installer, sent once right after a download finishes. */
    val installRequests: Flow<File> = installs.receiveAsFlow()

    /** Outcomes of [checkNow], one per tap. */
    val checkResults: Flow<UpdateCheck> = checks.receiveAsFlow()

    /** False for debug builds, which never look for releases. */
    val canCheck: Boolean get() = !debuggable

    /** This build's version name and code, for the menu and About. */
    val installedName: String
    val installedCode: Long

    init {
        val installed = packageInfo()
        installedName = installed?.versionName.orEmpty()
        installedCode = installed?.let(PackageInfoCompat::getLongVersionCode) ?: 0L
        if (!debuggable && AppSettings(application).checkUpdates) {
            checkJob = viewModelScope.launch { check(manual = false) }
        }
    }

    /** The menu's "Update देखें": checks even when launch checks are off or this release was put off. */
    fun checkNow() {
        if (debuggable) {
            checks.trySend(UpdateCheck.Disabled)
            return
        }
        val running = checkJob
        checkJob = viewModelScope.launch {
            running?.join()
            checks.send(if (state.value != null) UpdateCheck.Found else check(manual = true))
        }
    }

    private suspend fun check(manual: Boolean): UpdateCheck {
        // Without our own version every release would look newer.
        if (installedCode <= 0L) return UpdateCheck.Failed
        val latest = withContext(Dispatchers.IO) { runCatching { fetchLatest() }.getOrNull() } ?: return UpdateCheck.Failed
        val release = latest.takeIf { AppUpdate.isNewer(it, installedCode) }
        val apk = withContext(Dispatchers.IO) { keepOnly(release) }
        if (release == null) return UpdateCheck.UpToDate(installedName)
        if (!manual && prefs.getInt(Prefs.UPDATE_DISMISSED, 0) == release.versionCode) return UpdateCheck.Found
        state.value = UpdateOffer(
            release = release,
            installedName = installedName,
            step = if (apk != null) UpdateStep.READY else UpdateStep.AVAILABLE,
            apk = apk,
        )
        return UpdateCheck.Found
    }

    /** The banner's main button: downloads the APK, or hands it to the installer again once downloaded. */
    fun proceed() {
        val current = state.value ?: return
        val apk = current.apk
        when {
            current.step == UpdateStep.DOWNLOADING -> Unit
            // The cache can be cleared while the banner shows; then the APK is fetched again.
            current.step == UpdateStep.READY && apk != null && apk.isFile -> installs.trySend(apk)
            else -> download(current)
        }
    }

    private fun download(current: UpdateOffer) {
        state.value = current.copy(step = UpdateStep.DOWNLOADING, percent = 0, apk = null)
        downloadJob = viewModelScope.launch {
            val apk = withContext(Dispatchers.IO) { runCatching { fetchApk(current.release) }.getOrNull() }
            state.value = if (apk != null) {
                current.copy(step = UpdateStep.READY, percent = 100, apk = apk)
            } else {
                current.copy(step = UpdateStep.FAILED, apk = null)
            }
            if (apk != null) installs.trySend(apk)
        }
    }

    /** "बाद में": hides the banner until a release newer than this one comes out. */
    fun dismiss() {
        val current = state.value ?: return
        downloadJob?.cancel()
        prefs.edit { putInt(Prefs.UPDATE_DISMISSED, current.release.versionCode) }
        state.value = null
    }

    private fun fetchLatest(): ReleaseInfo? {
        val connection = connect(AppUpdate.LATEST_RELEASE_API)
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        return try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            AppUpdate.parseLatest(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    /** Downloads to a .part file and keeps it only when it is the expected version of this app. */
    private suspend fun fetchApk(release: ReleaseInfo): File? {
        folder.mkdirs()
        val target = apkFile(release)
        val partial = File(folder, target.name + ".part")
        try {
            val connection = connect(release.apkUrl)
            try {
                if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
                val total = connection.contentLengthLong
                var copied = 0L
                connection.inputStream.use { input ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            coroutineContext.ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            copied += read
                            if (total > 0) showPercent((copied * 100 / total).toInt())
                        }
                    }
                }
                if (total > 0 && copied != total) return null
            } finally {
                connection.disconnect()
            }
            if (!isApkOf(partial, release) || !partial.renameTo(target)) return null
            return target
        } finally {
            partial.delete()
        }
    }

    private fun showPercent(percent: Int) {
        val current = state.value ?: return
        if (current.step == UpdateStep.DOWNLOADING && percent > current.percent) {
            state.value = current.copy(percent = percent.coerceAtMost(99))
        }
    }

    private fun isApkOf(apk: File, release: ReleaseInfo): Boolean {
        val manager = getApplication<Application>().packageManager
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            manager.getPackageArchiveInfo(apk.path, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            manager.getPackageArchiveInfo(apk.path, 0)
        } ?: return false
        return info.packageName == getApplication<Application>().packageName &&
            PackageInfoCompat.getLongVersionCode(info) == release.versionCode.toLong()
    }

    /**
     * Deletes every cached APK except [release]'s, which it returns when already downloaded,
     * so installed or skipped versions do not pile up in the cache.
     */
    private fun keepOnly(release: ReleaseInfo?): File? {
        val keep = release?.let(::apkFile)
        folder.listFiles()?.filter { it != keep }?.forEach { it.delete() }
        return keep?.takeIf { it.isFile }
    }

    private fun apkFile(release: ReleaseInfo) = File(folder, "ShareScan-${release.versionCode}.apk")

    private fun packageInfo(): PackageInfo? {
        val app = getApplication<Application>()
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                app.packageManager.getPackageInfo(app.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                app.packageManager.getPackageInfo(app.packageName, 0)
            }
        }.getOrNull()
    }

    private fun connect(address: String) = (URL(address).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 25_000
        setRequestProperty("User-Agent", "ShareScan (Android)")
    }

    companion object {
        /** Cache sub-folder the FileProvider in the manifest shares with the installer (res/xml/update_paths.xml). */
        const val UPDATE_FOLDER = "updates"
    }
}
