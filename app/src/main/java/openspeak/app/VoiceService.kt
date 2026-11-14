package openspeak.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import openspeak.app.ts.TsSession
import openspeak.app.ui.MainActivity

/**
 * Keeps the process (and microphone access) alive while connected, so voice keeps working
 * with the screen off or the app in the background.
 */
class VoiceService : Service() {
    companion object {
        private const val CHANNEL_ID = "voice"
        private const val NOTIFICATION_ID = 1
        const val ACTION_DISCONNECT = "openspeak.DISCONNECT"
        /** Re-enter the foreground as a media-projection service, then start the pending screen share. */
        const val ACTION_SCREEN_SHARE = "openspeak.SCREEN_SHARE"
        const val ACTION_SCREEN_SHARE_STOPPED = "openspeak.SCREEN_SHARE_STOPPED"
        const val ACTION_STOP_SCREEN_SHARE = "openspeak.STOP_SCREEN_SHARE"
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var sharingScreen = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                TsSession.disconnect()
                return START_NOT_STICKY
            }
            ACTION_STOP_SCREEN_SHARE -> {
                TsSession.stopScreenShare()
                return START_NOT_STICKY
            }
            ACTION_SCREEN_SHARE -> sharingScreen = true
            ACTION_SCREEN_SHARE_STOPPED -> sharingScreen = false
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Голосовое подключение", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val disconnect = PendingIntent.getService(
            this, 1, Intent(this, VoiceService::class.java).setAction(ACTION_DISCONNECT), PendingIntent.FLAG_IMMUTABLE
        )
        val title = TsSession.currentBookmark?.title ?: "TeamSpeak"
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(if (sharingScreen) "Идёт демонстрация экрана" else "Подключено к голосовому серверу")
            .setContentIntent(open)
            .setOngoing(true)
        if (sharingScreen) builder.addAction(
            0, "Остановить показ",
            PendingIntent.getService(
                this, 2, Intent(this, VoiceService::class.java).setAction(ACTION_STOP_SCREEN_SHARE), PendingIntent.FLAG_IMMUTABLE
            )
        )
        val notification: Notification = builder.addAction(0, "Отключиться", disconnect).build()
        var type = if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        if (sharingScreen && Build.VERSION.SDK_INT >= 29) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        if (intent?.action == ACTION_SCREEN_SHARE) TsSession.onScreenShareServiceReady()
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "openspeak:voice")
                .apply { acquire() }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }
}
