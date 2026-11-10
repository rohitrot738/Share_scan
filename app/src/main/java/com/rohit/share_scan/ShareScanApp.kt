package com.rohit.share_scan

import android.app.Application
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate

/**
 * Keeps the app on its dark galaxy theme whatever the system light/dark setting is, and sets up live mode: the
 * notification channels and the background work that scans through market hours. An error that would close the app is
 * kept first ([CrashLog]), for the next start to offer.
 */
class ShareScanApp : Application() {
    override fun onCreate() {
        super.onCreate()
        keepCrashes()
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        Languages.applyWords(Languages.localized(this))
        Notifier.createChannels(this)
        LiveSchedule.sync(this)
    }

    /** Writes down the error of a crash, on any thread, before Android closes the app as it would have. */
    private fun keepCrashes() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val device = "${Build.MANUFACTURER} ${Build.MODEL}"
                CrashLog.save(filesDir, CrashLog.report(BuildConfig.VERSION_NAME, Build.VERSION.SDK_INT, device, thread.name, System.currentTimeMillis(), error))
            }
            previous?.uncaughtException(thread, error)
        }
    }
}

/**
 * Whether the app's screen is up; work that ends with the app out of sight ([ScanService], [UpdateDownload]) says so in
 * a notification.
 */
object AppScreen {
    @Volatile var open: Boolean = false
}
