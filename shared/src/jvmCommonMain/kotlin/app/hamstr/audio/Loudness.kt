package app.hamstr.audio

import app.hamstr.data.Prefs
import app.hamstr.data.Settings
import app.hamstr.res.*
import org.jetbrains.compose.resources.StringResource
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sqrt
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
 * The volume step and "Normalize volume", last in the chain. Normalizing follows how loud the
 * song has been over the last seconds and turns it towards [TARGET]: quickly down when it gets
 * louder, slowly up when it gets quieter, never by more than [RANGE] dB, and not at all in
 * silence. A limiter keeps the peaks of a boosted song from clipping.
 */
class Loudness(private val rate: Int, private val channels: Int) {
    private var level = Level.Normal
    private var normalize = false
    private var levelGain = 1.0
    private var power = 0.0
    private var gain = 1.0
    private var aim = 1.0
    private var peak = 0.0
    private val rise = coef(0.3)
    private val fall = coef(4.0)
    // The gain follows its aim quickly down and slowly up, so the first moments of a song,
    // before it is known how loud it is, don't jump out.
    private val down = coef(0.05)
    private val up = coef(1.5)
    private val letGo = coef(0.2)

    private fun coef(seconds: Double) = 1 - exp(-1.0 / (seconds * rate))

    /** Follows Settings; false when the sound passes as it is. */
    fun refresh(s: Settings = Prefs.settings.value): Boolean {
        level = Level.of(s.soundLevel)
        levelGain = 10.0.pow(level.db / 20)
        if (!s.normalize && normalize) { gain = 1.0; aim = 1.0; power = 0.0 }
        normalize = s.normalize
        return normalize || level != Level.Normal
    }

    /** Forgets the peaks, after a seek; the volume it found for the song stays. */
    fun reset() { peak = 0.0 }

    /** The gain applied right now, for tests. */
    internal val current get() = gain * levelGain

    /** One frame of [channels] samples in [x] from [off], in place. */
    fun frame(x: DoubleArray, off: Int = 0) {
        if (normalize) {
            var ms = 0.0
            for (c in 0 until channels) ms += x[off + c] * x[off + c]
            ms /= channels
            power += (if (ms > power) rise else fall) * (ms - power)
            // In silence or a fade-out it drifts back to as recorded instead of pulling the hiss up.
            aim = if (power > GATE * GATE) (TARGET / sqrt(power)).coerceIn(MIN_GAIN, MAX_GAIN) else 1.0
            gain += (if (aim < gain) down else up) * (aim - gain)
        }
        val g = gain * levelGain
        var loudest = 0.0
        for (c in 0 until channels) loudest = maxOf(loudest, abs(x[off + c] * g))
        // A limiter without look-ahead: it holds peaks under the ceiling and lets go slowly.
        peak = if (loudest > peak) loudest else peak + letGo * (loudest - peak)
        val squeeze = if (peak > CEILING) CEILING / peak else 1.0
        for (c in 0 until channels) x[off + c] = soft(x[off + c] * g * squeeze)
    }

    private fun soft(y: Double): Double {
        val m = abs(y)
        return if (m <= 0.9) y else sign(y) * (0.9 + 0.1 * tanh((m - 0.9) / 0.1))
    }

    companion object {
        /** About -16 dB RMS: loud masters come down a little, quiet ones come up. */
        const val TARGET = 0.16
        const val RANGE = 12.0
        private val MAX_GAIN = 10.0.pow(RANGE / 20)
        private val MIN_GAIN = 10.0.pow(-RANGE / 20)
        // Below about -40 dB it is silence or a fade, which shouldn't be pulled up.
        private const val GATE = 0.01
        private const val CEILING = 0.95
    }
}
