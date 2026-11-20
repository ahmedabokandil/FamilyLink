package com.familylink.p2p.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.familylink.p2p.FamilyLinkApp
import com.familylink.p2p.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Persistent foreground service keeping FamilyLink's LAN, Wi-Fi Direct, and decentralized
 * relay listeners active without FCM, and upgrading foreground service types dynamically
 * for active voice/video calls and Android 14/15 MediaProjection screen sharing.
 */
class P2pDaemonService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_IDLE
        val statusText = when (action) {
            ACTION_MEDIA_PROJECTION -> "Screen sharing active in FamilyLink call"
            ACTION_ACTIVE_CALL -> "FamilyLink P2P call in progress"
            else -> "Listening via LAN, Wi-Fi Direct & P2P relays"
        }
        val notification = buildStatusNotification(statusText)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val fgType = when (action) {
                ACTION_MEDIA_PROJECTION ->
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                ACTION_ACTIVE_CALL ->
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                else -> ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
            }
            try {
                startForeground(NOTIFICATION_ID, notification, fgType)
            } catch (_: SecurityException) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
                )
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val app = application as FamilyLinkApp
        scope.launch {
            val identity = app.repository.getIdentity()
            if (identity != null) {
                app.ensureP2pStarted(identity.peerId)
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                "FamilyLink P2P Connection",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps peer-to-peer chat and calls reachable without a central server"
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildStatusNotification(statusText: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("FamilyLink P2P Active")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    companion object {
        const val ACTION_IDLE = "com.familylink.p2p.action.IDLE"
        const val ACTION_ACTIVE_CALL = "com.familylink.p2p.action.ACTIVE_CALL"
        const val ACTION_MEDIA_PROJECTION = "com.familylink.p2p.action.MEDIA_PROJECTION"
        private const val CHANNEL_ID = "familylink_p2p_daemon"
        private const val NOTIFICATION_ID = 1001
    }
}
