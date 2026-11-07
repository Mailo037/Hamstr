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

    /** A steady voice at 1 kHz with a bass hit at 60 Hz twice a second, 150 ms each. */
    private fun voiceAndBass(i: Int, voice: Double, bass: Double): Double {
        val t = i.toDouble() / rate
        val hit = (t % 0.5) < 0.15
        return voice * sin(2 * PI * 1000 * t) + (if (hit) bass * sin(2 * PI * 60 * t) else 0.0)
    }

    /** How loud the 1 kHz voice comes out in [x], by Goertzel. */
    private fun voiceIn(x: DoubleArray): Double {
        val k = 2 * kotlin.math.cos(2 * PI * 1000 / rate)
        var s1 = 0.0; var s2 = 0.0
        for (v in x) { val s = v + k * s1 - s2; s2 = s1; s1 = s }
        return sqrt(s1 * s1 + s2 * s2 - k * s1 * s2) * 2 / x.size
    }

    @Test
    fun aBassLineDoesNotTurnTheVoiceDown() {
        val chain = SoundChain(rate, 2).apply { refresh(Settings(normalize = true)) }
        val x = DoubleArray(2)
        var alone = 0.0
        var low = Double.MAX_VALUE; var high = 0.0
        // Ten seconds of voice alone, then ten with a bass line under it.
        for (i in 0 until rate * 20) {
            val v = if (i < rate * 10) voiceAndBass(i, 0.1, 0.0) else voiceAndBass(i, 0.1, 0.3)
            x[0] = v; x[1] = v
            chain.frame(x)
            if (i == rate * 10 - 1) alone = chain.loudness.current
            if (i >= rate * 10) { val g = chain.loudness.current; low = minOf(low, g); high = maxOf(high, g) }
        }
        // Before, the voice went about 5 dB down as soon as the bass came in.
        val drop = 20 * kotlin.math.log10(alone / low)
        assertTrue(drop < 1.5, "voice down by $drop dB")
        assertTrue(20 * kotlin.math.log10(high / low) < 1.5, "gain $low..$high over the beats")
    }

    @Test
    fun theLimiterTakesBassPeaksOutOfTheBass() {
        val chain = SoundChain(rate, 2).apply { refresh(Settings(soundLevel = "loud")) }
        val x = DoubleArray(2)
        val window = rate / 50
        val hit = DoubleArray(window); val between = DoubleArray(window)
        var peak = 0.0
        for (i in 0 until rate * 3) {
            val v = voiceAndBass(i, 0.25, 0.5)
            x[0] = v; x[1] = v
            chain.frame(x)
            peak = maxOf(peak, abs(x[0]))
            // In the last second: 20 ms in the middle of a hit, and 20 ms well after it.
            val t = i - rate * 2
            if (t in rate / 20 until rate / 20 + window) hit[t - rate / 20] = x[0]
            if (t in rate * 2 / 5 until rate * 2 / 5 + window) between[t - rate * 2 / 5] = x[0]
        }
        val during = voiceIn(hit); val after = voiceIn(between)
        assertTrue(during > after * 0.85, "voice during a hit $during, between $after")
        assertTrue(peak <= 1.0, "peak: $peak")
    }

    @Test
    fun silenceIsNotPulledUp() {
        val (rms, _) = play(SoundChain(rate, 2).apply { refresh(Settings(normalize = true)) }, 0.0005, 10)
        assertTrue(rms < 0.0005, "silence: $rms")
    }
}
