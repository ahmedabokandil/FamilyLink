package com.familylink.p2p.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import com.familylink.core.webrtc.ActiveCallState
import com.familylink.p2p.ui.call.IncomingCallActivity

/**
 * Displays high-priority full-screen incoming call notifications with ringtone and vibration
 * so serverless P2P family calls wake the device even while locked (zero FCM required).
 */
class CallNotificationManager(private val context: Context) {

    private val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        ensureCallChannel()
    }

    fun showIncomingCall(state: ActiveCallState) {
        val fullScreenIntent = Intent(context, IncomingCallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val fullScreenPending = PendingIntent.getActivity(
            context,
            2001,
            fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val callKind = if (state.isVideoCall) "Incoming FamilyLink Video Call" else "Incoming FamilyLink Voice Call"
        val notification = NotificationCompat.Builder(context, CALL_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle(state.title.ifBlank { "Family Call" })
            .setContentText(callKind)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setAutoCancel(false)
            .setFullScreenIntent(fullScreenPending, true)
            .setContentIntent(fullScreenPending)
            .build()

        nm.notify(INCOMING_CALL_NOTIFICATION_ID, notification)
    }

    fun dismissIncomingCall() {
        nm.cancel(INCOMING_CALL_NOTIFICATION_ID)
    }

    private fun ensureCallChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            val audioAttrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val channel = NotificationChannel(
                CALL_CHANNEL_ID,
                "Incoming Family Calls",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Full-screen ringing alerts for incoming P2P voice and video calls"
                setSound(ringtoneUri, audioAttrs)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 600, 400, 600)
            }
            nm.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val CALL_CHANNEL_ID = "familylink_incoming_calls"
        private const val INCOMING_CALL_NOTIFICATION_ID = 2002
    }
}
