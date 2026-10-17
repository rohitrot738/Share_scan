package com.rohit.share_scan

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

/** Keeps the app on its dark galaxy theme whatever the system light/dark setting is. */
class ShareScanApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
    }
}
