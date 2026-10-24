package com.newtermux.pi

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Notificacao de "Pi turn finished".
 *
 * Without it, if the Pi panel is in the background (or the user is
 * in another app), there is no way to know the response is ready.
 */
object PiNotify {

    private const val CHANNEL_ID = "nebula_pi"
    private const val NOTIFY_ID = 4201

    /** Creates the channel (only meaningful on Android 8+). */
    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Nebula Pi",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Notifies when Pi finishes answering"
            enableVibration(true)
        }
        manager.createNotificationChannel(channel)
    }

    /** Posts the notification. [summary] is a short preview of the response. */
    fun turnDone(context: Context, summary: String) {
        ensureChannel(context)
        val intent = Intent(context, PiActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pending = PendingIntent.getActivity(context, 0, intent, flags)
        val text = summary.trim().ifEmpty { "Response ready." }
        val short = if (text.length > 140) text.take(140).trimEnd() + "…" else text
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("Nebula Pi terminou")
            .setContentText(short)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFY_ID, notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS permission missing: silent.
        }
    }
}
