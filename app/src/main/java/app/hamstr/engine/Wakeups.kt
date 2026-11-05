package app.hamstr.engine

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import app.hamstr.data.DownloadStore
import app.hamstr.data.Prefs
import app.hamstr.data.Schedule
import app.hamstr.data.Status
import app.hamstr.data.Subscriptions

/**
 * One alarm brings the queue back when nothing else would: when the night begins for downloads
 * that wait for it, and when a followed channel is due for a look. The download service stops
 * while it waits for those, so no notification sits there for hours. An exact alarm is one of
 * the few things that may start a foreground service from the background (USE_EXACT_ALARM,
 * and SCHEDULE_EXACT_ALARM before Android 13); without it the alarm comes later, and only
 * starts what Android lets it.
 */
object Wakeups {
    private const val TAG = "HamstrWake"

    /**
     * Sets the alarm for the next of the two, or takes it away when neither is coming. One plan at
     * a time (the app's watcher and the queue both plan), so what the system holds and [planned]
     * can't drift apart.
     */
    @Synchronized
    fun plan(context: Context) {
        val s = Prefs.settings.value
        val now = System.currentTimeMillis()
        // Only by day: at night the queue runs anyway. And not by the stage alone, which lives in
        // memory; the process may have gone since.
        val night = Schedule.nextNight(s, now).takeIf {
            s.nightOnly && !Schedule.night(s, now) && DownloadStore.tasks.value.any { t -> t.status == Status.Queued && !t.anytime }
        }
        // One that is overdue (no network last time) comes again in a quarter of an hour, not at once;
        // rounded up to five minutes, so planning again a moment later asks for the same time.
        val feeds = Subscriptions.nextCheck()?.let { due ->
            val soonest = now + 15 * 60_000
            if (due >= soonest) due else (soonest + STEP - 1) / STEP * STEP
        }
        val at = listOfNotNull(night, feeds).minOrNull()
        // pump() plans after every download; the system hears of it only when the time changed.
        val want = at ?: NONE
        if (planned == want) return
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(
            context, 0, Intent(context, WakeReceiver::class.java).setAction(ACTION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        if (at == null) {
            am.cancel(pi)
        } else {
            runCatching {
                if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }.onFailure { Log.w(TAG, "No alarm", it); return }
        }
        planned = want
    }

    /** The alarm went off (or the phone started again and forgot it): the next plan sets one whatever it was. */
    @Synchronized
    fun forget() {
        planned = UNKNOWN
    }

    // The alarm as last handed to the system in this process; NONE: cancelled, UNKNOWN: not known.
    private var planned = UNKNOWN
    private const val NONE = 0L
    private const val UNKNOWN = -1L
    private const val STEP = 5 * 60_000L

    /** The phone is on a charger (or a USB port that charges it). */
    fun charging(context: Context) = context.getSystemService(BatteryManager::class.java)?.isCharging ?: true

    internal const val ACTION = "app.hamstr.WAKE"
}

/** The alarm went off, or the phone started again (which forgets alarms): the queue looks. */
class WakeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Wakeups.forget()
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Wakeups.plan(context)
            // What was waiting when the phone went off waits on; the queue sees whether it still has to.
            if (DownloadStore.tasks.value.none { it.status == Status.Queued }) return
        }
        runCatching { DownloadService.send(context, DownloadService.ACTION_WAKE) }
            .onFailure { Log.w("HamstrWake", "Android didn't let the queue start", it) }
    }
}
