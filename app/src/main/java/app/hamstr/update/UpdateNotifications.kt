package app.hamstr.update

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import app.hamstr.MainActivity
import app.hamstr.R
import app.hamstr.data.Prefs

internal object UpdateNotifications {
    const val PROGRESS = 7401
    private const val RESULT = 7402
    private const val CHANNEL = "update_downloads"

    fun createChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.channel_update_downloads), NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun open(context: Context) = PendingIntent.getActivity(
        context, RESULT, Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_UPDATE)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun progress(context: Context, release: Release, progress: Float): Notification {
        val cancel = PendingIntent.getService(context, PROGRESS,
            Intent(context, UpdateDownloadService::class.java).setAction(UpdateDownloadService.CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val percent = (progress * 100).toInt().coerceIn(0, 100)
        return NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_hamstr).setColor(Prefs.settings.value.accent)
            .setContentTitle(context.getString(R.string.upd_notification_downloading, release.version))
            .setContentText(if (progress < 0) context.getString(R.string.upd_background) else "$percent % · ${context.getString(R.string.upd_background)}")
            .setContentIntent(open(context)).setOngoing(true).setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS).setProgress(100, percent, progress < 0)
            .addAction(0, context.getString(R.string.menu_cancel), cancel).build()
    }

    fun ready(context: Context, release: Release) = post(context,
        NotificationCompat.Builder(context, Updater.CH_UPDATES)
            .setSmallIcon(R.drawable.ic_stat_hamstr).setColor(Prefs.settings.value.accent)
            .setContentTitle(context.getString(R.string.upd_ready, release.version))
            .setContentText(context.getString(R.string.upd_ready_hint)).setContentIntent(open(context))
            .setAutoCancel(true).addAction(0, context.getString(R.string.upd_install), open(context)).build())

    fun failed(context: Context, message: String) = post(context,
        NotificationCompat.Builder(context, Updater.CH_UPDATES)
            .setSmallIcon(R.drawable.ic_stat_hamstr).setContentTitle(context.getString(R.string.set_update_failed))
            .setContentText(message).setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(open(context)).setAutoCancel(true).build())

    fun confirm(context: Context, intent: Intent) {
        val action = PendingIntent.getActivity(context, RESULT, intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        post(context, NotificationCompat.Builder(context, Updater.CH_UPDATES)
            .setSmallIcon(R.drawable.ic_stat_hamstr).setContentTitle(context.getString(R.string.upd_confirm))
            .setContentText(context.getString(R.string.upd_ready_hint)).setContentIntent(action).setAutoCancel(true).build())
    }

    private fun post(context: Context, notification: Notification) {
        runCatching { context.getSystemService(NotificationManager::class.java).notify(RESULT, notification) }
    }

    fun cancelResult(context: Context) { context.getSystemService(NotificationManager::class.java).cancel(RESULT) }
}
