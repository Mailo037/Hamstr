package app.hamstr.audio

import app.hamstr.data.Prefs
import app.hamstr.data.Settings
import app.hamstr.res.*
import org.jetbrains.compose.resources.StringResource
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/** How the sound reaches the ears: as recorded, folded into one channel, or circling the head. */
enum class Channels(val id: String, val title: StringResource, val hint: StringResource) {
    Stereo("stereo", Res.string.sound_stereo, Res.string.sound_stereo_hint),
    Mono("mono", Res.string.sound_mono, Res.string.sound_mono_hint),
    /** "8D audio": the song slowly circles the listener, best on headphones. */
    Around("8d", Res.string.sound_8d, Res.string.sound_8d_hint),
    ;

    companion object {
        fun of(id: String) = entries.firstOrNull { it.id == id } ?: Stereo
    }
}

/**
 * Mixes the channels of one stream of interleaved frames after the equalizer. Mono averages
 * them; Around takes the middle of a stereo song and moves it once around the head every
 * [PERIOD] seconds: louder on the near ear, a little later on the far one, and a little duller
 * while it is behind. Streams with one channel pass as they are.
 */
class ChannelMix(private val rate: Int, private val channels: Int) {
    private var mode = Channels.Stereo
    private var frames = 0L
    // The middle's last samples, for the far ear's delay.
    private val ring = DoubleArray(RING)
    private var at = 0
    private var dullL = 0.0
    private var dullR = 0.0
    private val dullK = 1 - exp(-2 * PI * 2500.0 / rate)

    /** Follows Settings; false when the channels pass as they are. */
    fun refresh(s: Settings = Prefs.settings.value): Boolean {
        mode = Channels.of(s.soundChannels)
        return channels >= 2 && mode != Channels.Stereo
    }

    /** Forgets the sound so far, after a seek. */
    fun reset() {
        ring.fill(0.0); dullL = 0.0; dullR = 0.0
    }

    /** One frame of [channels] samples in [x] from [off], in place. */
    fun frame(x: DoubleArray, off: Int = 0) {
        when (mode) {
            Channels.Stereo -> {}
            Channels.Mono -> {
                var sum = 0.0
                for (c in 0 until channels) sum += x[off + c]
                val m = sum / channels
                for (c in 0 until channels) x[off + c] = m
            }
            Channels.Around -> around(x, off)
        }
    }

    private fun around(x: DoubleArray, off: Int) {
        val l = x[off]; val r = x[off + 1]
        val mid = (l + r) / 2
        val side = (l - r) / 2
        ring[at] = mid
        val phase = 2 * PI * (frames++ % (rate.toLong() * PERIOD)) / (rate.toDouble() * PERIOD)
        val pan = sin(phase) // -1 left, 1 right
        val behind = (-cos(phase)).coerceAtLeast(0.0)
        // Equal power, so it is as loud in the middle as at the sides.
        val a = (pan + 1) * PI / 4
        val delay = abs(pan) * MAX_DELAY * rate
        val far = delayed(delay)
        val nearL = pan <= 0
        var outL = GAIN * cos(a) * (if (nearL) mid else far)
        var outR = GAIN * sin(a) * (if (nearL) far else mid)
        dullL += dullK * (outL - dullL); dullR += dullK * (outR - dullR)
        outL += (dullL - outL) * behind * 0.5
        outR += (dullR - outR) * behind * 0.5
        x[off] = soft(outL + side * WIDTH)
        x[off + 1] = soft(outR - side * WIDTH)
        at = (at + 1) % RING
    }

    /** The middle [samples] ago, between two samples where it falls between them. */
    private fun delayed(samples: Double): Double {
        val whole = samples.toInt()
        val frac = samples - whole
        val a = ring[(at - whole + RING) % RING]
        val b = ring[(at - whole - 1 + RING * 2) % RING]
        return a + (b - a) * frac
    }

    private fun soft(y: Double): Double {
        val m = abs(y)
        return if (m <= 0.9) y else sign(y) * (0.9 + 0.1 * tanh((m - 0.9) / 0.1))
    }

    companion object {
        /** Seconds for one turn around the head. */
        const val PERIOD = 8
        // Sound takes about this long from one ear to the other.
        private const val MAX_DELAY = 0.0006
        private const val RING = 128
        // Of the stereo width the song had, so it doesn't turn into a single point.
        private const val WIDTH = 0.3
        private val GAIN = sqrt(2.0) * 0.8
    }
}

/**
 * The equalizer, the channel mix and the loudness together, as both players run them on decoded sound:
 * [refresh] once per buffer, then [frame] for each frame of [channels] samples from -1 to 1.
 */
class SoundChain(rate: Int, private val channels: Int) {
    val eq = EqFilter(rate, channels)
    val mix = ChannelMix(rate, channels)
    val loudness = Loudness(rate, channels)
    private var eqOn = false
    private var mixOn = false
    private var loudOn = false
    private val one = DoubleArray(channels)

    /** False when nothing would change the sound. */
    fun refresh(s: Settings = Prefs.settings.value): Boolean {
        eqOn = eq.refresh(s); mixOn = mix.refresh(s); loudOn = loudness.refresh(s)
        return eqOn || mixOn || loudOn
    }

    fun reset() { eq.reset(); mix.reset(); loudness.reset() }

    fun frame(x: DoubleArray, off: Int = 0) {
        if (eqOn) for (c in 0 until channels) x[off + c] = eq.next(x[off + c], c)
        if (mixOn) mix.frame(x, off)
        if (loudOn) loudness.frame(x, off)
    }

    /** Signed 16-bit little-endian frames, interleaved, in place: what FFmpeg hands the desktop's player. */
    fun process16(buf: ByteArray, off: Int, len: Int, s: Settings = Prefs.settings.value) {
        if (!refresh(s)) return
        val step = channels * 2
        var i = off
        val end = off + len - len % step
        while (i < end) {
            for (c in 0 until channels) {
                val j = i + c * 2
                one[c] = ((buf[j].toInt() and 0xFF) or (buf[j + 1].toInt() shl 8)).toShort() / 32768.0
            }
            frame(one)
            for (c in 0 until channels) {
                val j = i + c * 2
                val out = (one[c] * 32767).toInt().coerceIn(-32768, 32767)
                buf[j] = out.toByte(); buf[j + 1] = (out shr 8).toByte()
            }
            i += step
        }
    }
}
