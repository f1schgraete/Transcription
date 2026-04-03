package com.siptranscribe.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat

class SipService : Service() {

    companion object {
        private const val CHANNEL_ID = "sip_service_channel"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) =
            context.startForegroundService(Intent(context, SipService::class.java))

        fun stop(context: Context) =
            context.stopService(Intent(context, SipService::class.java))
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Bereit"))
        LinphoneManager.init(this)

        LinphoneManager.onIncomingCall = { call ->
            val intent = Intent(this, CallActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(CallActivity.EXTRA_IS_INCOMING, true)
                putExtra(
                    CallActivity.EXTRA_REMOTE_ADDRESS,
                    call.remoteAddress?.displayName
                        ?: call.remoteAddress?.username
                        ?: "Unbekannt"
                )
            }
            startActivity(intent)
        }

        LinphoneManager.onRegistrationStateChanged = { _, msg ->
            val notif = buildNotification(msg)
            val mgr = getSystemService(NotificationManager::class.java)
            mgr.notify(NOTIFICATION_ID, notif)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        LinphoneManager.destroy()
    }

    private fun buildNotification(status: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_phone)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.app_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "SIP Telefon Status" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
