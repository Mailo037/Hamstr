package app.hamstr.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AbLoopTest {
    private val minute = 60_000L

    @Test
    fun holdsMarkAThenB() {
        val a = AbLoop.marked(null, 20_000, 20_000, minute)
        assertEquals(AbLoop(20_000), a)
        assertFalse(a.looping)
        assertEquals(AbLoop(20_000, 35_000), AbLoop.marked(a, 35_000, 35_000, minute))
    }

    @Test
    fun bBeforeAStillMakesALoop() {
        assertEquals(AbLoop(10_000, 20_000), AbLoop.marked(AbLoop(20_000), 10_000, 10_000, minute))
    }

    @Test
    fun aHoldOnARunningLoopStartsANewOne() {
        assertEquals(AbLoop(50_000), AbLoop.marked(AbLoop(10_000, 20_000), 50_000, 50_000, minute))
    }

    @Test
    fun aSlideIsTheWholeLoop() {
        assertEquals(AbLoop(12_000, 30_000), AbLoop.marked(null, 30_000, 12_000, minute))
        assertEquals(AbLoop(12_000, 30_000), AbLoop.marked(AbLoop(40_000, 50_000), 12_000, 30_000, minute))
    }

    @Test
    fun tooShortIsOnlyA() {
        assertEquals(AbLoop(20_000), AbLoop.marked(AbLoop(20_000), 20_400, 20_400, minute))
        assertEquals(AbLoop(20_000), AbLoop.marked(null, 20_000, 20_500, minute))
    }

    @Test
    fun bStaysClearOfTheEnd() {
        val loop = AbLoop.marked(AbLoop(30_000), minute, minute, minute)
        assertEquals(minute - AbLoop.END, loop.b)
        assertEquals(minute - AbLoop.END, AbLoop.marked(null, minute + 5_000, minute + 5_000, minute).a)
    }

    @Test
    fun playbackJumpsBackOnlyAtB() {
        val loop = AbLoop(10_000, 20_000)
        assertEquals(-1, loop.jumpFrom(5_000))
        assertEquals(-1, loop.jumpFrom(19_999))
        assertEquals(10_000, loop.jumpFrom(20_000))
        assertEquals(10_000, loop.jumpFrom(45_000))
        assertEquals(-1, AbLoop(10_000).jumpFrom(45_000))
        assertTrue(loop.looping)
    }
}
