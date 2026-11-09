package com.yourapp.wine.phonecorpus

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

/**
 * Keeps the 124-label benchmark process active when the user navigates away from
 * [WineAppLabelsActivity] (either to another screen in WineTracker or to another app while
 * WineTracker stays open in the background).
 */
class WineAppLabelsRunService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val status = intent?.getStringExtra(EXTRA_STATUS)?.takeIf { it.isNotBlank() }
            ?: "Running 124-label benchmark…"
        promoteForeground(status)
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "WineAppLabelsRunService foreground timeout reached (fgsType=$fgsType); releasing foreground state")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    override fun onDestroy() {
        releaseWakeLock()
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        runCatching { getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun promoteForeground(status: String) {
        val notification = buildNotification(this, status)
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to promote benchmark service to foreground: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun acquireWakeLock() {
        runCatching {
            val pm = getSystemService(PowerManager::class.java) ?: return
            val lock = wakeLock ?: pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "WineTracker:WineAppLabelsBenchmark",
            ).apply {
                setReferenceCounted(false)
                wakeLock = this
            }
            if (!lock.isHeld) {
                lock.acquire(MAX_WAKELOCK_MS)
                Log.d(TAG, "Benchmark partial wake lock acquired")
            }
        }.onFailure { e ->
            Log.w(TAG, "Benchmark wake lock unavailable: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            wakeLock?.let { lock ->
                if (lock.isHeld) {
                    lock.release()
                    Log.d(TAG, "Benchmark partial wake lock released")
                }
            }
            wakeLock = null
        }
    }

    companion object {
        private const val TAG = "WineTracker"
        private const val CHANNEL_ID = "wine_app_labels_benchmark"
        private const val NOTIFICATION_ID = 4125
        private const val ACTION_START = "com.yourapp.wine.phonecorpus.START_BENCHMARK"
        private const val ACTION_STOP = "com.yourapp.wine.phonecorpus.STOP_BENCHMARK"
        private const val EXTRA_STATUS = "status"
        private const val MAX_WAKELOCK_MS = 30L * 60L * 1000L

        private fun ensureChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Large image dataset test",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Progress while the 124-label wine benchmark runs in the background"
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }

        private fun buildNotification(context: Context, status: String): Notification {
            ensureChannel(context)
            val openIntent = Intent(context, WineAppLabelsActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            return Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_popup_sync)
                .setContentTitle("Large image dataset test")
                .setContentText(status)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build()
        }

        internal fun start(context: Context, status: String) {
            val app = context.applicationContext
            runCatching {
                val intent = Intent(app, WineAppLabelsRunService::class.java).apply {
                    action = ACTION_START
                    putExtra(EXTRA_STATUS, status)
                }
                app.startForegroundService(intent)
                Log.d(TAG, "Benchmark foreground service started: $status")
            }.onFailure { e ->
                Log.w(TAG, "Benchmark foreground service start skipped: ${e.javaClass.simpleName}: ${e.message}")
            }
        }

        internal fun update(context: Context, status: String) {
            val app = context.applicationContext
            runCatching {
                val manager = app.getSystemService(NotificationManager::class.java) ?: return
                manager.notify(NOTIFICATION_ID, buildNotification(app, status))
            }
        }

        internal fun stop(context: Context) {
            val app = context.applicationContext
            runCatching {
                app.stopService(Intent(app, WineAppLabelsRunService::class.java))
                app.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
                Log.d(TAG, "Benchmark foreground service stopped")
            }.onFailure { e ->
                Log.w(TAG, "Benchmark foreground service stop warning: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }
}
