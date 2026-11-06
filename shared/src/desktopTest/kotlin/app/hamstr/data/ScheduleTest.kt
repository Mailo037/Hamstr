package app.hamstr.data

import java.util.Calendar
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScheduleTest {
    private fun at(hour: Int, minute: Int = 0): Long = Calendar.getInstance().apply {
        set(2026, Calendar.NOVEMBER, 4, hour, minute, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun task(anytime: Boolean = false) = DownloadTask(url = "https://example.com/v", title = "v", thumbnail = null, source = null, durationSec = 0,
        choice = Settings().defaultChoice(), anytime = anytime)

    @Test
    fun aNightRunsPastMidnight() {
        assertTrue(Schedule.inWindow(23 * 60, 22 * 60, 6 * 60))
        assertTrue(Schedule.inWindow(2 * 60, 22 * 60, 6 * 60))
        assertTrue(!Schedule.inWindow(6 * 60, 22 * 60, 6 * 60))
        assertTrue(!Schedule.inWindow(12 * 60, 22 * 60, 6 * 60))
        // From one to seven, as it comes.
        assertTrue(Schedule.inWindow(60, 60, 420))
        assertTrue(!Schedule.inWindow(420, 60, 420))
        // The same hour twice is all day.
        assertTrue(Schedule.inWindow(12 * 60, 60, 60))
    }

    @Test
    fun daytimeDownloadsWaitForTheNightUnlessStartedNow() {
        val s = Settings(nightOnly = true, nightFrom = 60, nightTo = 420)
        assertEquals(Stage.WaitingForNight, Schedule.hold(task(), s, charging = true, now = at(15)))
        assertNull(Schedule.hold(task(), s, charging = true, now = at(3)))
        assertNull(Schedule.hold(task(anytime = true), s, charging = true, now = at(15)))
        // Off, nothing waits.
        assertNull(Schedule.hold(task(), Settings(), charging = false, now = at(15)))
    }

    @Test
    fun theChargerHoldsOnlyWhenAskedFor() {
        val s = Settings(chargingOnly = true)
        assertEquals(Stage.WaitingForCharger, Schedule.hold(task(), s, charging = false, now = at(15)))
        assertNull(Schedule.hold(task(), s, charging = true, now = at(15)))
        assertNull(Schedule.hold(task(anytime = true), s, charging = false, now = at(15)))
    }

    @Test
    fun theNextNightIsTonightOrNow() {
        val s = Settings(nightOnly = true, nightFrom = 60, nightTo = 420)
        // In the afternoon: at one the next morning.
        assertEquals(at(1) + 24 * 60 * 60 * 1000L, Schedule.nextNight(s, at(15)))
        // At half past midnight: half an hour later.
        assertEquals(at(1), Schedule.nextNight(s, at(0, 30)))
        // During the night: now.
        assertEquals(at(3), Schedule.nextNight(s, at(3)))
    }

    @Test
    fun heldStagesWait() {
        assertTrue(Stage.WaitingForNight.held && Stage.WaitingForNight.waiting)
        assertTrue(Stage.WaitingForCharger.held && Stage.WaitingForCharger.waiting)
        assertTrue(!Stage.WaitingForWifi.held)
        // "Start now" ends the schedule's hold and waiting for Wi-Fi, nothing else.
        assertTrue(Stage.WaitingForWifi.startable && Stage.WaitingForNight.startable)
        assertTrue(!Stage.WaitingForNetwork.startable && !Stage.Retrying.startable)
    }
}
