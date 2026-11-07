package app.hamstr.audio

import app.hamstr.data.Settings
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LoudnessTest {
    private val rate = 48_000

    /** Plays [seconds] of a 440 Hz tone at [amp] through [chain]; the RMS of the last second. */
    private fun play(chain: SoundChain, amp: Double, seconds: Int): Pair<Double, Double> {
        var sum = 0.0
        var peak = 0.0
        val x = DoubleArray(2)
        val total = rate * seconds
        for (i in 0 until total) {
            val v = amp * sin(2 * PI * 440 * i / rate)
            x[0] = v; x[1] = v
            chain.frame(x)
            if (i >= total - rate) { sum += x[0] * x[0]; peak = maxOf(peak, abs(x[0])) }
        }
        return sqrt(sum / rate) to peak
    }

    @Test
    fun normalAndOffPassAsTheyAre() {
        assertFalse(SoundChain(rate, 2).refresh(Settings()))
        assertEquals(Level.Normal, Level.of("something old"))
        assertTrue(SoundChain(rate, 2).refresh(Settings(soundLevel = "quiet")))
        assertTrue(SoundChain(rate, 2).refresh(Settings(normalize = true)))
    }

    @Test
    fun quietIsSofterAndLoudIsLouderWithoutClipping() {
        val quiet = SoundChain(rate, 2).apply { refresh(Settings(soundLevel = "quiet")) }
        val loud = SoundChain(rate, 2).apply { refresh(Settings(soundLevel = "loud")) }
        val (q, _) = play(quiet, 0.3, 2)
        val (l, _) = play(loud, 0.3, 2)
        val (lp, peak) = play(SoundChain(rate, 2).apply { refresh(Settings(soundLevel = "loud")) }, 0.9, 2)
        assertTrue(q < 0.3 / sqrt(2.0) * 0.5, "quiet: $q")
        assertTrue(l > 0.3 / sqrt(2.0) * 1.6, "loud: $l")
        assertTrue(peak <= 1.0 && lp > 0, "peak: $peak")
    }

    @Test
    fun normalizeBringsQuietAndLoudSongsTogether() {
        val (soft, _) = play(SoundChain(rate, 2).apply { refresh(Settings(normalize = true)) }, 0.05, 12)
        val (hard, peak) = play(SoundChain(rate, 2).apply { refresh(Settings(normalize = true)) }, 0.9, 12)
        // 25 dB apart going in, a few dB apart coming out.
        assertTrue(hard / soft < 2.0, "soft $soft, hard $hard")
        assertTrue(peak <= 1.0)
    }

    @Test
    fun silenceIsNotPulledUp() {
        val (rms, _) = play(SoundChain(rate, 2).apply { refresh(Settings(normalize = true)) }, 0.0005, 10)
        assertTrue(rms < 0.0005, "silence: $rms")
    }
}
