package com.overdrive.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import kotlin.concurrent.thread

/**
 * Live mode for the widget: after you tap refresh, the widget updates every 30 seconds. It keeps going while the
 * climate is heating (up to 35 minutes), and stops by itself a minute and a half after the heating ends, or after
 * four minutes if nothing is heating. Runs as a foreground service (visible notification) because Android does not
 * let a widget update this often any other way.
 */
class LiveRefreshService : Service() {

    companion object {
        private const val STEP_MS = 30_000L
        private const val MAX_MS = 35 * 60_000L
        @Volatile private var running = false
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("live", "Live widget", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "live")
            .setSmallIcon(R.drawable.ic_bolt)
            .setContentTitle("BYDrive widget: updating every 30 s")
            .setContentText("Stops by itself when the heating ends")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(46, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(46, n)
        if (running) return START_NOT_STICKY            // already live; the tap that started this has just refreshed it
        running = true
        val lock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "bydrive:live")
            .apply { acquire(MAX_MS + 60_000L) }
        thread {
            try {
                val p = getSharedPreferences("widget", MODE_PRIVATE)
                val begin = System.currentTimeMillis()
                var seenHeat = false
                var quiet = 0
                while (System.currentTimeMillis() - begin < MAX_MS) {
                    try { SealDriveWidget.refreshNow(this) } catch (_: Exception) {}
                    val heating = p.getBoolean("heating", false) && !p.contains("err")
                    if (heating) { seenHeat = true; quiet = 0 } else quiet++
                    if (quiet >= (if (seenHeat) 3 else 8)) break
                    try { Thread.sleep(STEP_MS) } catch (_: InterruptedException) { break }
                }
            } finally {
                running = false
                if (lock.isHeld) lock.release()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }
}
