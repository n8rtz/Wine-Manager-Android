package com.yourapp.wine.gliner

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
 * Keeps the GLiNER lane lab running when the user leaves [GlinerLabActivity]: another screen in
 * WineTracker, another app, or the screen switched off. Mirrors
 * [com.yourapp.wine.phonecorpus.WineAppLabelsRunService]: the same foreground notification and
 * partial wake lock, so the run is not frozen when the activity is not visible.
 */
class GlinerLabRunService:Service() {
    private var wakeLock:PowerManager.WakeLock?=null

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
        acquireWakeLock()
    }

    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        val status=intent?.getStringExtra(EXTRA_STATUS)?.takeIf { it.isNotBlank() } ?: "Running GLiNER lane lab…"
        promoteForeground(status)
        if(intent?.action==ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId:Int,fgsType:Int) {
        Log.w(TAG,"GLiNER lab foreground timeout reached (fgsType=$fgsType); releasing foreground state")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    override fun onDestroy() {
        releaseWakeLock()
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        super.onDestroy()
    }

    override fun onBind(intent:Intent?):IBinder?=null

    private fun promoteForeground(status:String) {
        val notification=buildNotification(this,status)
        try {
            if(Build.VERSION.SDK_INT>=34) {
                startForeground(NOTIFICATION_ID,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID,notification)
            }
        } catch(error:Exception) {
            Log.w(TAG,"Unable to promote the GLiNER lab service to foreground: ${error.javaClass.simpleName}: ${error.message}")
        }
    }

    private fun acquireWakeLock() {
        runCatching {
            val pm=getSystemService(PowerManager::class.java) ?: return
            val lock=wakeLock ?: pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"WineTracker:GlinerLaneLab").apply {
                setReferenceCounted(false)
                wakeLock=this
            }
            if(!lock.isHeld) lock.acquire(MAX_WAKELOCK_MS)
        }.onFailure { error ->
            Log.w(TAG,"GLiNER lab wake lock unavailable: ${error.javaClass.simpleName}: ${error.message}")
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            wakeLock?.let { lock -> if(lock.isHeld) lock.release() }
            wakeLock=null
        }
    }

    companion object {
        private const val TAG="WineTracker"
        private const val CHANNEL_ID="gliner_lane_lab"
        private const val NOTIFICATION_ID=4132
        private const val ACTION_START="com.yourapp.wine.gliner.START_LAB"
        private const val ACTION_STOP="com.yourapp.wine.gliner.STOP_LAB"
        private const val EXTRA_STATUS="status"
        private const val MAX_WAKELOCK_MS=60L*60L*1000L

        private fun ensureChannel(context:Context) {
            val manager=context.getSystemService(NotificationManager::class.java) ?: return
            if(manager.getNotificationChannel(CHANNEL_ID)!=null) return
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID,"GLiNER lane lab",NotificationManager.IMPORTANCE_LOW).apply {
                    description="Progress while the GLiNER lane lab runs in the background"
                    setShowBadge(false)
                }
            )
        }

        private fun buildNotification(context:Context,status:String):Notification {
            ensureChannel(context)
            val openIntent=Intent(context,GlinerLabActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            val pendingIntent=PendingIntent.getActivity(context,0,openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            return Notification.Builder(context,CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_popup_sync)
                .setContentTitle("GLiNER lane lab")
                .setContentText(status)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build()
        }

        internal fun start(context:Context,status:String) {
            val app=context.applicationContext
            runCatching {
                app.startForegroundService(Intent(app,GlinerLabRunService::class.java).apply {
                    action=ACTION_START
                    putExtra(EXTRA_STATUS,status)
                })
            }.onFailure { error ->
                Log.w(TAG,"GLiNER lab foreground service start skipped: ${error.javaClass.simpleName}: ${error.message}")
            }
        }

        internal fun update(context:Context,status:String) {
            val app=context.applicationContext
            runCatching {
                app.getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID,buildNotification(app,status))
            }
        }

        internal fun stop(context:Context) {
            val app=context.applicationContext
            runCatching {
                app.stopService(Intent(app,GlinerLabRunService::class.java))
                app.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
            }
        }
    }
}
