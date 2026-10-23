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
import kotlinx.coroutines.delay
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
 * Looks for a newer signed release on launch and, when the user asks, downloads its APK through Android's download
 * manager ([UpdateDownload]), which goes on with the app closed, so the system installer can put it over this one. The
 * launch check can be switched off in Settings; the menu checks on demand. Debug builds never check: they carry a
 * different key.
 */
class UpdateViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE)
    private val folder = File(application.cacheDir, UPDATE_FOLDER)
    /** A notification asked to install once the APK is ready ([installWhenReady]). */
    private var installAsked = false
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
        val offer = UpdateOffer(release = release, installedName = installedName)
        when {
            apk != null -> ready(offer, apk, justDownloaded = false)
            // A download started before the app was closed: follow it again.
            withContext(Dispatchers.IO) { UpdateDownload.status(getApplication(), release.versionCode) } != null -> {
                state.value = offer.copy(step = UpdateStep.DOWNLOADING)
                downloadJob?.cancel()
                downloadJob = viewModelScope.launch { follow(offer) }
            }
            else -> state.value = offer
        }
        return UpdateCheck.Found
    }

    /**
     * The "update ready" notification was tapped: install as soon as the APK is known to be here (the launch check
     * finds it a moment later on a fresh start).
     */
    fun installWhenReady() {
        val current = state.value
        val apk = current?.apk
        if (current?.step == UpdateStep.READY && apk != null && apk.isFile) installs.trySend(apk) else installAsked = true
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
        downloadJob?.cancel()
        downloadJob = viewModelScope.launch {
            if (withContext(Dispatchers.IO) { UpdateDownload.start(getApplication(), current.release) }) {
                follow(current)
            } else {
                state.value = current.copy(step = UpdateStep.FAILED, apk = null)
            }
        }
    }

    /** Shows the download manager's progress twice a second until the APK is in, checked and moved, or failed. */
    private suspend fun follow(current: UpdateOffer) {
        val app = getApplication<Application>()
        val code = current.release.versionCode
        while (true) {
            val status = withContext(Dispatchers.IO) { UpdateDownload.status(app, code) }
            if (status is UpdateDownload.Status.Running) {
                state.value = current.copy(step = UpdateStep.DOWNLOADING, percent = status.percent)
                delay(PROGRESS_MS)
                continue
            }
            // Done here, or already moved by the download manager's broadcast (nothing remembered then).
            val apk = if (status == UpdateDownload.Status.Failed) {
                withContext(Dispatchers.IO) { UpdateDownload.cancel(app) }
                null
            } else {
                withContext(Dispatchers.IO) { UpdateDownload.finish(app, code) }
            }
            if (apk != null) {
                ready(current, apk, justDownloaded = true)
            } else {
                state.value = current.copy(step = UpdateStep.FAILED, apk = null)
            }
            return
        }
    }

    /**
     * The APK is here: the banner offers to install it. The installer opens at once when a notification asked for it, or
     * when the download [justDownloaded] with the app on the screen; an APK kept from before waits for the button.
     */
    private fun ready(current: UpdateOffer, apk: File, justDownloaded: Boolean) {
        state.value = current.copy(step = UpdateStep.READY, percent = 100, apk = apk)
        if (installAsked || (justDownloaded && AppScreen.open)) {
            installAsked = false
            installs.trySend(apk)
        }
    }

    /** "बाद में": hides the banner until a release newer than this one comes out, and drops a download under way. */
    fun dismiss() {
        val current = state.value ?: return
        downloadJob?.cancel()
        UpdateDownload.cancel(getApplication())
        Notifier.cancelUpdateReady(getApplication())
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

    /**
     * Deletes every cached APK except [release]'s, which it returns when already downloaded,
     * so installed or skipped versions do not pile up in the cache.
     */
    private fun keepOnly(release: ReleaseInfo?): File? {
        val keep = release?.let(::apkFile)
        folder.listFiles()?.filter { it != keep }?.forEach { it.delete() }
        return keep?.takeIf { it.isFile }
    }

    private fun apkFile(release: ReleaseInfo) = UpdateDownload.apkFile(getApplication(), release.versionCode)

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
        private const val PROGRESS_MS = 500L
    }
}
