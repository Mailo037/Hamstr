package app.hamstr.engine

import android.app.NotificationManager
import android.content.Context

/**
 * Notifications that only tell news step aside once the news is seen: opening Hamstr shows
 * the finished (and failed) downloads in the stash and the new version in Settings, so
 * their notifications go. Progress and the player's own notification stay.
 */
object Seen {
    private val NEWS = setOf(DownloadService.CH_DONE, "updates")

    /** Hamstr is on screen: a finished download shows up in the stash, no notification needed. */
    @Volatile var foreground = false

    fun appOpened(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        runCatching {
            nm.activeNotifications.filter { it.notification.channelId in NEWS }.forEach { nm.cancel(it.tag, it.id) }
        }
    }

    /** A download was opened from its notification or the list: its "done" notification goes. */
    fun opened(context: Context, id: String) {
        runCatching { context.getSystemService(NotificationManager::class.java)?.cancel(id.hashCode()) }
    }
}
