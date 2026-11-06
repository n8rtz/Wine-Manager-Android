package com.n8rtz.winetracker.scanner

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat

/** Foreground lifetime and bounded partial wake lock for a user-initiated offline label scan. */
class LabelScanService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Label scanning", NotificationManager.IMPORTANCE_LOW))
        wakeLock = runCatching {
            getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                "WineTracker:LabelScan").apply { setReferenceCounted(false); acquire(120_000L) }
        }.onFailure { Log.w("LabelScanService", "Could not acquire the scan wake lock", it) }.getOrNull()
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        val pending = launch?.let { PendingIntent.getActivity(this, 0, it,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE) }
        val notification = Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle("Reading wine label").setContentText("Scanning on this device")
            .setContentIntent(pending).setOngoing(true).setOnlyAlertOnce(true).build()
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(NOTIFICATION_ID, notification)
        } catch (error: Exception) {
            Log.e("LabelScanService", "Could not keep the scan running", error)
            ProductionLabelScanSession.serviceFailed()
            stopSelf()
            return START_NOT_STICKY
        }
        ProductionLabelScanSession.recover(applicationContext)
        return START_STICKY
    }
    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object {
        private const val CHANNEL = "production_label_scan"
        private const val NOTIFICATION_ID = 4133
        fun start(context: Context): Boolean = try {
            ContextCompat.startForegroundService(context, Intent(context, LabelScanService::class.java))
            true
        } catch (error: Exception) {
            Log.e("LabelScanService", "Could not start background scan", error)
            false
        }
        fun stop(context: Context) { context.stopService(Intent(context, LabelScanService::class.java)) }
    }
}
