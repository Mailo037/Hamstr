package app.hamstr.player

import kotlin.math.max
import kotlin.math.min

/**
 * An A-B loop on a file's timeline, in milliseconds: playback that reaches [b] goes back to
 * [a]. Until [b] is marked it only waits for it. Holding the seek bar marks the points
 * ([marked]); both players keep to it the same way ([jumpFrom]).
 */
data class AbLoop(val a: Long, val b: Long? = null) {
    val looping: Boolean get() = b != null

    /** Where playback at [at] goes: back to [a] once [b] is reached, otherwise nowhere (-1). */
    fun jumpFrom(at: Long): Long = if (b != null && at >= b) a else -1

    companion object {
        /** A shorter loop would only stutter. */
        const val MIN = 1000L

        /** B stays this far from the end, so the file doesn't end (or move on) before it loops. */
        const val END = 300L

        /**
         * What a hold on the seek bar makes of [loop]. Held at one spot ([to] == [from]), the
         * first hold marks A and the next one B, on either side of A; a hold on a running loop
         * starts a new one there. Held and slid from [from] to [to], the loop is the stretch
         * between them. One shorter than [MIN] is only its A.
         */
        fun marked(loop: AbLoop?, from: Long, to: Long, duration: Long): AbLoop {
            val end = if (duration > 0) max(0, duration - END) else Long.MAX_VALUE
            fun between(x: Long, y: Long): AbLoop {
                val a = min(x, y).coerceIn(0, end)
                val b = max(x, y).coerceIn(0, end)
                return if (b - a >= MIN) AbLoop(a, b) else AbLoop(a)
            }
            return when {
                to != from -> between(from, to)
                loop != null && !loop.looping -> between(loop.a, from)
                else -> AbLoop(from.coerceIn(0, end))
            }
        }
    }
}
