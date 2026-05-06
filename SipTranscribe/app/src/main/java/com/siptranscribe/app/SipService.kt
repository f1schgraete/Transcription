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
import org.linphone.core.Account
import org.linphone.core.Core
import org.linphone.core.CoreListenerStub
import org.linphone.core.RegistrationState

class SipService : Service() {

    private var coreListener: CoreListenerStub? = null

    companion object {
        private const val CHANNEL_ID = "sip_service_channel"
        const val CALL_CHANNEL_ID = "incoming_call_channel"
        private const val NOTIFICATION_ID = 1
        const val INCOMING_CALL_NOTIF_ID = 2

        const val ACTION_LOGOUT = "com.siptranscribe.app.action.LOGOUT"

        fun start(context: Context) =
            context.startForegroundService(Intent(context, SipService::class.java))

        fun stop(context: Context) =
            context.stopService(Intent(context, SipService::class.java))

        /**
         * Cleanly tear the service down: send SIP UNREGISTER, then stop the
         * foreground service. The Android OS won't auto-restart a service after
         * an explicit stopSelf, so this is the way to actually log out.
         */
        fun logoutAndStop(context: Context) {
            context.startService(Intent(context, SipService::class.java).apply {
                action = ACTION_LOGOUT
            })
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        startForeground(NOTIFICATION_ID, buildStatusNotification("Bereit"))
        LinphoneManager.init(this)

        LinphoneManager.onIncomingCall = { call ->
            val addr = call.remoteAddress
            val callerName = addr?.displayName ?: addr?.username ?: "Unbekannt"
            val callerNumber = addr?.username ?: ""
            showIncomingCallNotification(callerName, callerNumber)
        }

        // Use a CoreListenerStub directly so we don't overwrite the UI callback
        // that MainActivity registers on LinphoneManager.onRegistrationStateChanged.
        coreListener = object : CoreListenerStub() {
            override fun onAccountRegistrationStateChanged(
                core: Core,
                account: Account,
                state: RegistrationState?,
                message: String
            ) {
                val statusText = when (state) {
                    RegistrationState.Ok       -> "Registriert"
                    RegistrationState.Failed   -> "Fehler: $message"
                    RegistrationState.Progress -> "Registrierung läuft..."
                    RegistrationState.Cleared  -> "Abgemeldet"
                    else -> message
                }
                val mgr = getSystemService(NotificationManager::class.java)
                mgr.notify(NOTIFICATION_ID, buildStatusNotification(statusText))
            }
        }
        LinphoneManager.getCore()?.addListener(coreListener!!)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_LOGOUT) {
            LinphoneManager.unregisterAndDestroy()
            getSystemService(NotificationManager::class.java)
                .cancel(INCOMING_CALL_NOTIF_ID)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        coreListener?.let { LinphoneManager.getCore()?.removeListener(it) }
        LinphoneManager.destroy()
    }

    private fun showIncomingCallNotification(callerName: String, callerNumber: String) {
        val callIntent = Intent(this, CallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_NO_USER_ACTION
            putExtra(CallActivity.EXTRA_IS_INCOMING, true)
            putExtra(CallActivity.EXTRA_REMOTE_ADDRESS, callerName)
            putExtra(CallActivity.EXTRA_REMOTE_NUMBER, callerNumber)
        }
        val fullScreenPI = PendingIntent.getActivity(
            this, INCOMING_CALL_NOTIF_ID,
            callIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CALL_CHANNEL_ID)
            .setContentTitle("Eingehender Anruf")
            .setContentText(callerName)
            .setSmallIcon(R.drawable.ic_phone)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(fullScreenPI, true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(false)
            .setOngoing(true)
            .build()
        getSystemService(NotificationManager::class.java)
            .notify(INCOMING_CALL_NOTIF_ID, notification)
    }

    private fun buildStatusNotification(status: String): Notification {
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

    private fun createNotificationChannels() {
        val mgr = getSystemService(NotificationManager::class.java)

        // Low-priority service channel (status bar only)
        val serviceChannel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.app_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "SIP Telefon Status" }
        mgr.createNotificationChannel(serviceChannel)

        // High-priority channel for incoming calls (full-screen intent + heads-up)
        val callChannel = NotificationChannel(
            CALL_CHANNEL_ID,
            "Eingehende Anrufe",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Benachrichtigung bei eingehenden Anrufen"
            lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
        }
        mgr.createNotificationChannel(callChannel)
    }
}
