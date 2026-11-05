package app.hamstr.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PaceTest {
    @Test
    fun throttleLetsOneThroughPerIntervalAndKey() {
        var now = 1_000L
        val pace = Throttle(500) { now }
        assertTrue(pace.ready("a"))
        assertFalse(pace.ready("a"))
        assertTrue(pace.ready("b"), "each download has its own pace")
        now += 499
        assertFalse(pace.ready("a"))
        now += 1
        assertTrue(pace.ready("a"))
        // A new stage: its first progress shows at once.
        pace.reset("a")
        assertTrue(pace.ready("a"))
    }

    @Test
    fun throttleStartsAtOnceWhateverTheClockReads() {
        // A monotonic clock may start anywhere, even below zero.
        val pace = Throttle(500) { -10_000L }
        assertTrue(pace.ready("a"))
        assertFalse(pace.ready("a"))
    }

    @Test
    fun throttleLetsOnlyOneOfManySimultaneousCallersThrough() {
        repeat(50) {
            val pace = Throttle(60_000)
            val gate = java.util.concurrent.CountDownLatch(1)
            val passed = java.util.concurrent.atomic.AtomicInteger()
            val threads = (0 until 8).map { Thread { gate.await(); if (pace.ready("a")) passed.incrementAndGet() }.apply { start() } }
            gate.countDown()
            threads.forEach { it.join() }
            assertEquals(1, passed.get())
        }
    }

    @Test
    fun latestSendsOnlyWhatChanged() {
        val latest = Latest<String>()
        val sent = mutableListOf<String>()
        fun send(s: String) = latest.send(s) { sent += s; true }
        assertTrue(send("10 %"))
        assertFalse(send("10 %"))
        assertTrue(send("20 %"))
        assertFalse(send("20 %"))
        // The notification was removed: the same content goes out again.
        latest.forget()
        assertTrue(send("20 %"))
        assertEquals(listOf("10 %", "20 %", "20 %"), sent)
    }

    @Test
    fun latestTriesAFailedDeliveryAgain() {
        val latest = Latest<String>()
        var tries = 0
        assertTrue(latest.send("10 %") { tries++; false })
        // Not delivered: the same content is tried again rather than taken as shown.
        assertTrue(latest.send("10 %") { tries++; true })
        assertFalse(latest.send("10 %") { tries++; true })
        assertEquals(2, tries)
    }
}
