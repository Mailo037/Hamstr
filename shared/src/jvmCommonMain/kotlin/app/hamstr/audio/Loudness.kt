package app.hamstr.audio

import app.hamstr.data.Prefs
import app.hamstr.data.Settings
import app.hamstr.res.*
import org.jetbrains.compose.resources.StringResource
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.math.tanh

/** How loud everything plays, on top of the system volume. */
enum class Level(val id: String, val title: StringResource, val hint: StringResource, val db: Double) {
    Quiet("quiet", Res.string.level_quiet, Res.string.level_quiet_hint, -8.0),
    Normal("normal", Res.string.level_normal, Res.string.level_normal_hint, 0.0),
    Loud("loud", Res.string.level_loud, Res.string.level_loud_hint, 6.0),
    ;

    companion object {
        fun of(id: String) = entries.firstOrNull { it.id == id } ?: Normal
    }
}

/**
 * The volume step and "Normalize volume", last in the chain.
 *
 * Normalizing hears the song the way ears do (weighted like ITU BS.1770, with the bass counting
 * for less still, so a bass line moves it far less than a voice at the same energy) and over
 * seconds, not single beats: a kick
 * drum or a bass hit doesn't turn the voice over it down and up again. It turns the song towards
 * [TARGET], never by more than [RANGE] dB, and not at all in silence; a part that stays louder
 * for a while comes down within a few seconds.
 *
 * What still pokes out is caught by a limiter that takes it out of the bass first: a booming
 * peak makes the bass under [SPLIT] Hz quieter for a moment and leaves the voice where it is.
 * Only a peak that isn't bass turns everything down.
 */
class Loudness(private val rate: Int, private val channels: Int) {
    private var level = Level.Normal
    private var normalize = false
    private var levelGain = 1.0
    private var power = 0.0
    private var heard = 0L
    private var gain = 1.0
    private var aim = 1.0
    private var peak = 0.0
    private var bassPeak = 0.0
    private val rise = coef(3.0)
    private val fall = coef(6.0)
    private val window = (3.0 * rate).toLong()
    private val down = coef(1.0)
    private val up = coef(3.0)
    private val letGo = coef(0.25)
    private val shelf = Stage.shelf(rate)
    private val highPass = Stage.highPass(rate)
    private val lowPass = Stage.lowPass(SPLIT, rate)
    // Two values per filter and channel (transposed direct form II).
    private val zShelf = DoubleArray(channels * 2)
    private val zHigh = DoubleArray(channels * 2)
    private val zLow = DoubleArray(channels * 2)
    private val bass = DoubleArray(channels)

    private fun coef(seconds: Double) = 1 - exp(-1.0 / (seconds * rate))

    /** Follows Settings; false when the sound passes as it is. */
    fun refresh(s: Settings = Prefs.settings.value): Boolean {
        level = Level.of(s.soundLevel)
        levelGain = 10.0.pow(level.db / 20)
        if (!s.normalize && normalize) { gain = 1.0; aim = 1.0; power = 0.0; heard = 0 }
        normalize = s.normalize
        return normalize || level != Level.Normal
    }

    /** Forgets the peaks, after a seek; the volume it found for the song stays. */
    fun reset() {
        peak = 0.0; bassPeak = 0.0
        zShelf.fill(0.0); zHigh.fill(0.0); zLow.fill(0.0)
    }

    /** The gain applied right now, for tests. */
    internal val current get() = gain * levelGain

    /** One frame of [channels] samples in [x] from [off], in place. */
    fun frame(x: DoubleArray, off: Int = 0) {
        if (normalize) {
            var ms = 0.0
            for (c in 0 until channels) {
                val k = highPass.run(shelf.run(x[off + c], zShelf, c * 2), zHigh, c * 2)
                ms += k * k
            }
            ms /= channels
            if (heard < window) {
                // Until it has heard enough of the song, a plain average of what isn't silence, so
                // the first seconds are neither pulled up from nothing nor turned down by one beat.
                if (ms > GATE * GATE) { heard++; power += (ms - power) / heard }
            } else {
                power += (if (ms > power) rise else fall) * (ms - power)
            }
            // In silence or a fade-out it drifts back to as recorded instead of pulling the hiss up.
            aim = if (power > GATE * GATE) (TARGET / sqrt(power)).coerceIn(MIN_GAIN, MAX_GAIN) else 1.0
            gain += (if (aim < gain) down else up) * (aim - gain)
        }
        val g = gain * levelGain
        var loudest = 0.0
        var loudestBass = 0.0
        for (c in 0 until channels) {
            val y = x[off + c] * g
            val b = lowPass.run(y, zLow, c * 2)
            bass[c] = b
            loudest = maxOf(loudest, abs(y))
            loudestBass = maxOf(loudestBass, abs(b))
        }
        // A limiter without look-ahead: it holds peaks under the ceiling and lets go slowly.
        peak = if (loudest > peak) loudest else peak + letGo * (loudest - peak)
        bassPeak = if (loudestBass > bassPeak) loudestBass else bassPeak + letGo * (loudestBass - bassPeak)
        var bassCut = 0.0
        var squeeze = 1.0
        if (peak > CEILING) {
            // Take what is too much out of the bass, as far as the bass goes, and only the rest out of everything.
            val over = peak - CEILING
            bassCut = if (bassPeak > 0) (over / bassPeak).coerceAtMost(1 - MIN_BASS) else 0.0
            val left = peak - bassCut * bassPeak
            if (left > CEILING) squeeze = CEILING / left
        }
        for (c in 0 until channels) x[off + c] = soft((x[off + c] * g - bassCut * bass[c]) * squeeze)
    }

    private fun soft(y: Double): Double {
        val m = abs(y)
        return if (m <= 0.9) y else sign(y) * (0.9 + 0.1 * tanh((m - 0.9) / 0.1))
    }

    /** One biquad, normalised (a0 = 1), run in place on [z] from [i]. */
    private class Stage(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {
        fun run(x: Double, z: DoubleArray, i: Int): Double {
            val y = b0 * x + z[i]
            z[i] = b1 * x - a1 * y + z[i + 1]
            z[i + 1] = b2 * x - a2 * y
            return y
        }

        companion object {
            /** BS.1770's first stage: about +4 dB above 1.7 kHz, where ears are most sensitive. */
            fun shelf(rate: Int): Stage {
                val k = tan(PI * 1681.974450955533 / rate)
                val q = 0.7071752369554196
                val vh = 10.0.pow(3.999843853973347 / 20)
                val vb = vh.pow(0.4996667741545416)
                val a0 = 1 + k / q + k * k
                return Stage((vh + vb * k / q + k * k) / a0, 2 * (k * k - vh) / a0, (vh - vb * k / q + k * k) / a0,
                    2 * (k * k - 1) / a0, (1 - k / q + k * k) / a0)
            }

            /**
             * Where BS.1770 has a high-pass at 38 Hz, one at [BASS_WEIGHT] Hz: bass sounds far less loud
             * than its energy says (about 10 dB less at 60 Hz), and a song's bass line shouldn't move the voice.
             */
            fun highPass(rate: Int): Stage {
                val w = 2 * PI * BASS_WEIGHT / rate
                val alpha = kotlin.math.sin(w) / (2 * sqrt(0.5))
                val cw = kotlin.math.cos(w)
                val a0 = 1 + alpha
                return Stage((1 + cw) / 2 / a0, -(1 + cw) / a0, (1 + cw) / 2 / a0, -2 * cw / a0, (1 - alpha) / a0)
            }

            /** A Butterworth low-pass at [freq] Hz: the bass the limiter turns down first. */
            fun lowPass(freq: Double, rate: Int): Stage {
                val w = 2 * PI * freq / rate
                val alpha = kotlin.math.sin(w) / (2 * sqrt(0.5))
                val cw = kotlin.math.cos(w)
                val a0 = 1 + alpha
                return Stage((1 - cw) / 2 / a0, (1 - cw) / a0, (1 - cw) / 2 / a0, -2 * cw / a0, (1 - alpha) / a0)
            }
        }
    }

    companion object {
        /** About -16 dB (K-weighted RMS): loud masters come down a little, quiet ones come up. */
        const val TARGET = 0.16
        const val RANGE = 12.0
        private val MAX_GAIN = 10.0.pow(RANGE / 20)
        private val MIN_GAIN = 10.0.pow(-RANGE / 20)
        // Below about -40 dB it is silence or a fade, which shouldn't be pulled up.
        private const val GATE = 0.01
        private const val CEILING = 0.95
        /** Below this (Hz) the sound counts for less and less when normalizing. */
        const val BASS_WEIGHT = 100.0
        /** Where the bass ends for the limiter, in Hz. */
        const val SPLIT = 150.0
        // The bass never goes below a quarter of itself: past that the rest comes off everything.
        private const val MIN_BASS = 0.25
    }
}
