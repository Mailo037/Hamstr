package app.hamstr.audio

import app.hamstr.data.Settings
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EqualizerTest {
    private val rate = 48_000

    @AfterTest
    fun noBandHeld() {
        Equalizer.live.value = null
    }

    /** How loud a [freq] Hz tone comes out of [s]'s equalizer, in dB against what went in. */
    private fun gainAt(freq: Double, s: Settings): Double {
        val f = EqFilter(rate, 1)
        f.refresh(s)
        var inSum = 0.0
        var outSum = 0.0
        // Half a second to settle, then measure the next half.
        for (i in 0 until rate) {
            val x = 0.1 * sin(2 * PI * freq * i / rate)
            val y = f.next(x, 0)
            if (i >= rate / 2) { inSum += x * x; outSum += y * y }
        }
        return 10 * log10(outSum / inSum)
    }

    @Test
    fun offOrFlatLeavesTheSoundAlone() {
        val f = EqFilter(rate, 2)
        assertFalse(f.refresh(Settings(eqOn = false, eqPreset = "rock")))
        assertFalse(f.refresh(Settings(eqOn = true, eqPreset = "flat")))
        assertFalse(f.refresh(Settings(eqOn = true, eqPreset = Equalizer.CUSTOM, eqGains = List(10) { 0f })))
    }

    @Test
    fun bassBoostLiftsTheLowsAgainstTheHighs() {
        val s = Settings(eqOn = true, eqPreset = "bass")
        val low = gainAt(62.0, s)
        val high = gainAt(4000.0, s)
        // The filter does what the drawn curve says, less some headroom for every tone alike.
        val curve = Equalizer.curve(Equalizer.Preset.Bass.gains, doubleArrayOf(62.0, 4000.0))
        assertEquals(curve[0] - curve[1], low - high, 0.5)
        assertTrue(low - high > 4, "low $low dB, high $high dB")
        assertTrue(high < 0.5, "headroom leaves the highs a little quieter, not louder: $high")
    }

    @Test
    fun aCustomBandShapesItsOctave() {
        val gains = List(10) { if (it == 5) -9f else 0f } // 1 kHz down
        val s = Settings(eqOn = true, eqPreset = Equalizer.CUSTOM, eqGains = gains)
        assertTrue(gainAt(1000.0, s) < -7, "1 kHz is cut")
        assertTrue(gainAt(125.0, s) > -1, "125 Hz is left alone")
        val curve = Equalizer.curve(gains.toFloatArray(), doubleArrayOf(1000.0, 125.0))
        assertEquals(-9.0, curve[0], 0.5)
        assertEquals(0.0, curve[1], 0.5)
    }

    @Test
    fun loudPeaksBendInsteadOfClipping() {
        val f = EqFilter(rate, 1)
        f.refresh(Settings(eqOn = true, eqPreset = Equalizer.CUSTOM, eqGains = List(10) { 12f }))
        val out = (0 until 4800).map { f.next(0.99 * sin(2 * PI * 100 * it / rate), 0) }
        assertTrue(out.all { it in -1.0..1.0 })
    }

    @Test
    fun aHeldBandIsHeardBeforeItIsSaved() {
        val f = EqFilter(rate, 1)
        val off = Settings(eqOn = false)
        assertFalse(f.refresh(off))
        Equalizer.live.value = FloatArray(10).also { it[0] = 6f }
        assertTrue(f.refresh(off))
        Equalizer.live.value = null
        assertFalse(f.refresh(off))
    }

    @Test
    fun sixteenBitSamplesPassUntouchedWhenOff() {
        val f = EqFilter(rate, 2)
        val buf = ByteArray(400) { (it * 7).toByte() }
        val before = buf.copyOf()
        f.process16(buf, 0, buf.size, Settings(eqOn = false))
        assertContentEquals(before, buf)
        f.process16(buf, 0, buf.size, Settings(eqOn = true, eqPreset = "rock"))
        assertFalse(before.contentEquals(buf))
    }
}
