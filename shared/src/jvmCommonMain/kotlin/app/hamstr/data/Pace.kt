package app.hamstr.data

import java.util.concurrent.ConcurrentHashMap

/**
 * Lets something through at most once per [intervalMs] for each key (a download's progress, its
 * stage's progress), on a monotonic clock: a system clock set back doesn't hold anything still.
 * [reset] lets the next one through at once, e.g. the first progress of a new stage.
 */
class Throttle(private val intervalMs: Long, private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private val last = ConcurrentHashMap<String, Long>()

    /** Whether [key] may go now; two threads asking at once can't both get through. */
    fun ready(key: String): Boolean {
        val now = clock()
        var passed = false
        // One atomic step per key: the check and the new time together.
        last.compute(key) { _, before ->
            if (before != null && now - before < intervalMs) before else now.also { passed = true }
        }
        return passed
    }

    fun reset(key: String) {
        last.remove(key)
    }
}

/**
 * Sends something only when it differs from what was last delivered: the progress notification,
 * which the queue asks for far more often than it changes. A delivery that failed counts as not
 * sent, so the same content is tried again next time.
 */
class Latest<K> {
    private var delivered: Any? = NOTHING

    /** Hands [key] to [deliver] unless it was the last one delivered; true when [deliver] ran. */
    @Synchronized
    fun send(key: K, deliver: () -> Boolean): Boolean {
        if (delivered == key) return false
        delivered = NOTHING
        if (deliver()) delivered = key
        return true
    }

    /** What was delivered is gone (the notification was removed): the next send goes through. */
    @Synchronized
    fun forget() {
        delivered = NOTHING
    }

    private object NOTHING
}
