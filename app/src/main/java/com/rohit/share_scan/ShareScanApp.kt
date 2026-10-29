package com.rohit.share_scan

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

/**
 * Keeps the app on its dark galaxy theme whatever the system light/dark setting is, and sets up live mode: the
 * notification channels and the background work that scans through market hours.
 */
class ShareScanApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        Languages.applyWords(Languages.localized(this))
        Notifier.createChannels(this)
        LiveSchedule.sync(this)
    }
}

/**
 * Whether the app's screen is up; work that ends with the app out of sight ([ScanService], [UpdateDownload]) says so in
 * a notification.
 */
object AppScreen {
    @Volatile var open: Boolean = false
}
