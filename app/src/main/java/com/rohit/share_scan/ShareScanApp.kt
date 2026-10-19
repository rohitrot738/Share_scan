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
        Notifier.createChannels(this)
        LiveSchedule.sync(this)
    }
}
