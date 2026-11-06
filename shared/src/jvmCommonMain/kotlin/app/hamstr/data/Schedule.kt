package app.hamstr.data

import java.text.DateFormat
import java.util.Calendar
import java.util.Locale

/**
 * When downloads may start: at any time, or only at night (Settings › Queue), and on the phone
 * only while it charges. A download that is already running finishes; one the user starts
 * with "Start now" ([DownloadTask.anytime]) doesn't wait, and one put off "Tonight"
 * ([DownloadTask.tonight]) waits for the night hours even when the rest doesn't.
 */
object Schedule {
    /** Minutes after midnight at [now], in this device's time zone. */
    fun minuteOf(now: Long): Int = Calendar.getInstance().run {
        timeInMillis = now
        get(Calendar.HOUR_OF_DAY) * 60 + get(Calendar.MINUTE)
    }

    /** [minute] lies between [from] and [to]; the window may run past midnight (23:00 to 06:00). */
    fun inWindow(minute: Int, from: Int, to: Int): Boolean = when {
        from == to -> true
        from < to -> minute in from until to
        else -> minute >= from || minute < to
    }

    /** It is night by the settings, or they don't ask for it. */
    fun night(s: Settings, now: Long = System.currentTimeMillis()) = !s.nightOnly || dark(s, now)

    /** [now] lies in the night hours of the settings, whether or not they hold every download back. */
    fun dark(s: Settings, now: Long = System.currentTimeMillis()) = inWindow(minuteOf(now), s.nightFrom, s.nightTo)

    /** When the next night begins, in milliseconds; [now] while it is night already. */
    fun nextNight(s: Settings, now: Long = System.currentTimeMillis()): Long {
        if (dark(s, now)) return now
        val start = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, s.nightFrom / 60)
            set(Calendar.MINUTE, s.nightFrom % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (start.timeInMillis <= now) start.add(Calendar.DAY_OF_MONTH, 1)
        return start.timeInMillis
    }

    /** Why [t] may not start yet, or null when it may. The computer has no charger to wait for: it passes true. */
    fun hold(t: DownloadTask, s: Settings, charging: Boolean, now: Long = System.currentTimeMillis()): Stage? = holds(s, charging, now)(t)

    /** [hold] for a whole queue: whether it is night is worked out once, not once per waiting download. */
    fun holds(s: Settings, charging: Boolean, now: Long = System.currentTimeMillis()): (DownloadTask) -> Stage? {
        val dark = dark(s, now)
        val night = !s.nightOnly || dark
        return { t ->
            when {
                t.anytime -> null
                !night || t.tonight && !dark -> Stage.WaitingForNight
                s.chargingOnly && !charging -> Stage.WaitingForCharger
                else -> null
            }
        }
    }

    /** [minute] as a time of day the way this language writes it: "01:00", "1:00 AM". */
    fun clock(minute: Int, locale: Locale = Locale.getDefault()): String {
        val at = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, minute / 60 % 24)
            set(Calendar.MINUTE, minute % 60)
        }
        return DateFormat.getTimeInstance(DateFormat.SHORT, locale).format(at.time)
    }
}
