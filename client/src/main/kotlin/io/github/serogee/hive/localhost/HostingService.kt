package io.github.serogee.hive.localhost

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
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.github.serogee.hive.HiveApplication
import io.github.serogee.hive.MainActivity
import kotlinx.coroutines.launch

/**
 * Keeps the app running while this phone hosts a room, so Players stay connected when the Host
 * switches apps or turns off the screen. The room server itself lives in [LocalHost].
 */
class HostingService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Hosting sessions", NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("Hosting a Hive session")
            .setContentText("Players on this network can join. End it in the Sessions tab when you finish.")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        // Android can restart this service in a new process, where the server is not running yet.
        val container = (application as HiveApplication).container
        container.appScope.launch {
            val hosting = runCatching { container.rooms.resumeLocalHosting() }.getOrDefault(false)
            if (!hosting && !container.rooms.localHostRunning.value) stopSelf()
        }
        return START_STICKY
    }

    companion object {
        private const val CHANNEL = "hosting"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            context.startForegroundService(Intent(context, HostingService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, HostingService::class.java))
        }
    }
}
