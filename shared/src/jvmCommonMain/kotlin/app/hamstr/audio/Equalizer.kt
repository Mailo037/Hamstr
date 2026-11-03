package app.hamstr.audio

import app.hamstr.data.Prefs
import app.hamstr.data.Settings
import app.hamstr.res.*
import org.jetbrains.compose.resources.StringResource
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh

/**
 * A ten-band equalizer that sounds the same on the phone and the computer: both players hand
 * their decoded sound to an [EqFilter] before it reaches the speaker. Each band is a bell a
 * little wider than its octave, so neighbours blend into one smooth curve.
 */
object Equalizer {
    val BANDS = intArrayOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)
    const val MAX_DB = 12f
    const val CUSTOM = "custom"

    /**
     * The bands while a slider is held: the players follow the finger at once, and Settings
     * (which writes to disk) only takes the release.
     */
    val live = kotlinx.coroutines.flow.MutableStateFlow<FloatArray?>(null)

    enum class Preset(val id: String, val title: StringResource, val gains: FloatArray) {
        Flat("flat", Res.string.eq_flat, floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)),
        Bass("bass", Res.string.eq_bass, floatArrayOf(6f, 5f, 4f, 2f, 0.5f, 0f, 0f, 0f, 0f, 0f)),
        Treble("treble", Res.string.eq_treble, floatArrayOf(0f, 0f, 0f, 0f, 0f, 0.5f, 2f, 4f, 5f, 6f)),
        Vocals("vocals", Res.string.eq_vocals, floatArrayOf(-2f, -2f, -1f, 1f, 3f, 4f, 4f, 3f, 1f, 0f)),
        Rock("rock", Res.string.eq_rock, floatArrayOf(5f, 4f, 3f, 1f, -1f, -1f, 1f, 3f, 4f, 5f)),
        Pop("pop", Res.string.eq_pop, floatArrayOf(-1f, 1f, 3f, 4f, 3f, 1f, -1f, -1f, 1f, 2f)),
        Electronic("electronic", Res.string.eq_electronic, floatArrayOf(5f, 4f, 1f, 0f, -2f, 1f, 0f, 1f, 4f, 5f)),
        HipHop("hiphop", Res.string.eq_hiphop, floatArrayOf(5f, 4f, 2f, 3f, -1f, -1f, 1f, -1f, 2f, 3f)),
        Jazz("jazz", Res.string.eq_jazz, floatArrayOf(3f, 2f, 1f, 2f, -1f, -1f, 0f, 1f, 2f, 3f)),
        Classical("classical", Res.string.eq_classical, floatArrayOf(3f, 2f, 1f, 0f, 0f, 0f, -1f, 0f, 2f, 3f)),
        Acoustic("acoustic", Res.string.eq_acoustic, floatArrayOf(3f, 3f, 2f, 1f, 1f, 1f, 2f, 3f, 2f, 1f)),
        Speech("speech", Res.string.eq_speech, floatArrayOf(-4f, -3f, -1f, 1f, 3f, 4f, 4f, 2f, 0f, -2f)),
        Night("night", Res.string.eq_night, floatArrayOf(5f, 3f, 1f, 0f, -1f, 0f, 0f, 1f, 2f, 4f)),
        ;

        companion object {
            fun of(id: String) = entries.firstOrNull { it.id == id }
        }
    }

    /** The bands [s] asks for, in dB: a preset's, or the user's own. */
    fun gains(s: Settings): FloatArray = Preset.of(s.eqPreset)?.gains ?: FloatArray(BANDS.size) { s.eqGains.getOrElse(it) { 0f } }

    /** Moves one band; from a preset that makes the user's own curve, starting from the preset. */
    fun setBand(band: Int, db: Float) = Prefs.edit { s ->
        val next = gains(s).copyOf().also { it[band] = db.coerceIn(-MAX_DB, MAX_DB) }
        s.copy(eqOn = true, eqPreset = CUSTOM, eqGains = next.toList())
    }

    fun pick(preset: Preset) = Prefs.edit { it.copy(eqOn = true, eqPreset = preset.id) }

    /** How loud [gains] make each of [freqs] (Hz), in dB, as all bands together shape them. */
    fun curve(gains: FloatArray, freqs: DoubleArray, rate: Int = 48_000): DoubleArray {
        val filters = BANDS.indices.filter { gains[it] != 0f && BANDS[it] < rate * 0.45 }.map { Biquad.design(it, gains[it], rate) }
        return DoubleArray(freqs.size) { i -> filters.sumOf { it.dbAt(freqs[i], rate) } }
    }

    /** The loudest point of [gains], which the filter makes room for so a boost doesn't clip. */
    internal fun peak(gains: FloatArray, rate: Int): Double =
        curve(gains, DoubleArray(90) { 20.0 * 1.08.pow(it) }, rate).maxOf { max(it, 0.0) }
}

/** One filter's coefficients, normalised (a0 = 1), after the RBJ audio EQ cookbook. */
internal class Biquad(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {
    fun dbAt(freq: Double, rate: Int): Double {
        val w = 2 * PI * freq / rate
        val c1 = cos(w); val s1 = sin(w); val c2 = cos(2 * w); val s2 = sin(2 * w)
        val nr = b0 + b1 * c1 + b2 * c2; val ni = -(b1 * s1 + b2 * s2)
        val dr = 1 + a1 * c1 + a2 * c2; val di = -(a1 * s1 + a2 * s2)
        return 10 * log10((nr * nr + ni * ni) / (dr * dr + di * di))
    }

    companion object {
        fun design(band: Int, db: Float, rate: Int): Biquad {
            val a = 10.0.pow(db / 40.0)
            val w = 2 * PI * Equalizer.BANDS[band] / rate
            val cw = cos(w)
            val alpha = sin(w) / (2 * Q)
            return norm(1 + alpha * a, -2 * cw, 1 - alpha * a, 1 + alpha / a, -2 * cw, 1 - alpha / a)
        }

        // About 1.4 octaves between the half-gain points.
        private const val Q = 1.0

        private fun norm(b0: Double, b1: Double, b2: Double, a0: Double, a1: Double, a2: Double) =
            Biquad(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
    }
}

/**
 * The equalizer for one stream of interleaved samples at [rate] Hz. The audio thread calls
 * [refresh] once per buffer, which picks up a change in Settings, and then [next] for each
 * sample. Off or flat, the sound passes untouched.
 */
class EqFilter(private val rate: Int, private val channels: Int) {
    private var on = false
    private var preset = ""
    private var custom: List<Float>? = null
    private var held: FloatArray? = null
    private var filters: Array<Biquad> = emptyArray()
    // Transposed direct form II: two values per filter and channel.
    private var z = DoubleArray(0)
    private var pre = 1.0

    /** Follows Settings; false when there is nothing to do and the sound can pass as it is. */
    fun refresh(s: Settings = Prefs.settings.value): Boolean {
        val live = Equalizer.live.value
        if (live !== held || s.eqOn != on || s.eqPreset != preset || s.eqGains !== custom) {
            val wasOn = filters.isNotEmpty()
            on = s.eqOn; preset = s.eqPreset; custom = s.eqGains; held = live
            val gains = live ?: Equalizer.gains(s)
            val bands = Equalizer.BANDS.indices.filter { (on || live != null) && gains[it] != 0f && Equalizer.BANDS[it] < rate * 0.45 }
            filters = bands.map { Biquad.design(it, gains[it], rate) }.toTypedArray()
            // Room for the boost, though not all of it: the soft knee in [next] takes the rest.
            pre = if (filters.isEmpty()) 1.0 else 10.0.pow(-Equalizer.peak(gains, rate) * 0.6 / 20)
            if (!wasOn || z.size != filters.size * channels * 2) z = DoubleArray(filters.size * channels * 2)
        }
        return filters.isNotEmpty()
    }

    /** Forgets the sound so far, after a seek. */
    fun reset() = z.fill(0.0)

    /** One sample of [channel], from -1 to 1. */
    fun next(x: Double, channel: Int): Double {
        var y = x * pre
        var j = channel * filters.size * 2
        for (f in filters) {
            val out = f.b0 * y + z[j]
            z[j] = f.b1 * y - f.a1 * out + z[j + 1]
            z[j + 1] = f.b2 * y - f.a2 * out
            y = out
            j += 2
        }
        // Peaks past 0.9 bend towards 1 instead of being cut off.
        val m = kotlin.math.abs(y)
        return if (m <= 0.9) y else kotlin.math.sign(y) * (0.9 + 0.1 * tanh((m - 0.9) / 0.1))
    }

    /** Signed 16-bit little-endian samples, interleaved, in place: what FFmpeg hands the desktop's player. */
    fun process16(buf: ByteArray, off: Int, len: Int, s: Settings = Prefs.settings.value) {
        if (!refresh(s)) return
        var i = off
        var ch = 0
        val end = off + len - len % 2
        while (i < end) {
            val v = (buf[i].toInt() and 0xFF) or (buf[i + 1].toInt() shl 8)
            val out = (next(v / 32768.0, ch) * 32767).toInt().coerceIn(-32768, 32767)
            buf[i] = out.toByte(); buf[i + 1] = (out shr 8).toByte()
            i += 2
            if (++ch == channels) ch = 0
        }
    }
}
