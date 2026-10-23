package com.rohit.share_scan

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Keeps the user's scan going with the app out of sight. Android freezes an app within seconds of it leaving the
 * screen unless a foreground service runs, so while the scan runs this service holds a notification with its progress
 * and a stop button, and keeps the CPU awake with the screen off. Once the scan ends the notification goes; when the
 * app is not on the screen then, another says how the scan ended. Live mode's scans run in WorkManager's job instead.
 */
class ScanService : Service() {
    private val scope = MainScope()
    private var following: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            ScanEngine.stop()
            // The scan winds down within a second or so and the following loop ends the service.
            if (following == null) stopSelf()
            return START_NOT_STICKY
        }
        val progress = ScanEngine.progress.value
        // A service started for the foreground has to say so at once, even for a scan that already ended.
        if (!enterForeground(progress)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (following == null) following = scope.launch { follow(progress.runId) }
        return START_NOT_STICKY
    }

    /** Android 15 ends a data-sync service after six hours a day; a scan takes minutes, so this only stops a stuck one. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        ScanEngine.stop()
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        release()
        super.onDestroy()
    }

    private fun enterForeground(progress: ScanEngine.Progress): Boolean = try {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, Notifier.ID_SCAN, Notifier.scanProgress(this, progress), type)
        true
    } catch (refused: RuntimeException) {
        // Android may refuse a foreground start (e.g. from the background); the scan then runs as before, on screen.
        false
    }

    /** Shows the scan's progress every second until it ends, then hands over to [finish]. */
    private suspend fun follow(runId: Int) {
        hold()
        while (true) {
            val progress = ScanEngine.progress.value
            if (!progress.running || progress.runId != runId) break
            Notifier.showScan(this, Notifier.scanProgress(this, progress))
            delay(UPDATE_MS)
        }
        finish(runId)
    }

    private fun finish(runId: Int) {
        release()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        if (!AppScreen.open) Notifier.scanDone(this, runId)
        following = null
        stopSelf()
    }

    /** Keeps the CPU running with the screen off; Android stops a scan's network otherwise. */
    private fun hold() {
        val power = getSystemService(PowerManager::class.java) ?: return
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_TAG).apply {
            setReferenceCounted(false)
            acquire(WAKE_LIMIT_MS)
        }
    }

    private fun release() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    companion object {
        private const val ACTION_STOP = "com.rohit.share_scan.STOP_SCAN"
        private const val UPDATE_MS = 1_000L
        private const val WAKE_TAG = "ShareScan:scan"
        /** Longer than any scan; the lock lets go by itself should the service never end. */
        private const val WAKE_LIMIT_MS = 45L * 60 * 1000

        /** Starts the service for the scan the user just started; called while the app is on the screen. */
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, ScanService::class.java))
            } catch (refused: RuntimeException) {
                // Not allowed right now: the scan still runs while the app stays open.
            }
        }

        /** The notification's stop button. */
        fun stopIntent(context: Context): PendingIntent = PendingIntent.getService(
            context,
            0,
            Intent(context, ScanService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
