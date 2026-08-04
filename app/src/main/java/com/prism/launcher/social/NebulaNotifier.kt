package com.prism.launcher.social

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.prism.launcher.LauncherActivity
import com.prism.launcher.R

/** Posts a notification when a followed Nebula persona's background-generated post lands. */
object NebulaNotifier {

    private const val CHANNEL_ID = "nebula_social"

    fun notifyNewPost(context: Context, bot: SocialBotEntity, content: String) {
        ensureChannel(context)

        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return

        val openIntent = Intent(context, LauncherActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, bot.botId.hashCode(), openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("${bot.name} posted on Nebula")
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(bot.botId.hashCode(), notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS was revoked between the areNotificationsEnabled() check and here — ignore.
        }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            "Nebula Social",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "New posts from people you follow on Nebula"
        }
        manager.createNotificationChannel(channel)
    }
}
