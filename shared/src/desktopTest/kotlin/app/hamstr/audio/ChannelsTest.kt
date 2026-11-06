package app.hamstr.audio

import app.hamstr.data.Settings
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChannelsTest {
    private val rate = 48_000

    @Test
    fun stereoAndSingleChannelsPassAsTheyAre() {
        assertFalse(SoundChain(rate, 2).refresh(Settings(soundChannels = "stereo")))
        assertFalse(SoundChain(rate, 1).refresh(Settings(soundChannels = "8d")))
        assertEquals(Channels.Stereo, Channels.of("something old"))
    }

    @Test
    fun monoGivesBothEarsTheSame() {
        val chain = SoundChain(rate, 2)
        assertTrue(chain.refresh(Settings(soundChannels = "mono")))
        val x = doubleArrayOf(0.6, -0.2)
        chain.frame(x)
        assertEquals(0.2, x[0], 1e-9)
        assertEquals(0.2, x[1], 1e-9)
    }

    /** A centred tone, loudness per ear over each quarter of one turn. */
    @Test
    fun aroundMovesTheSoundFromEarToEar() {
        val chain = SoundChain(rate, 2)
        assertTrue(chain.refresh(Settings(soundChannels = "8d")))
        val turn = rate * ChannelMix.PERIOD
        val left = DoubleArray(4)
        val right = DoubleArray(4)
        val x = DoubleArray(2)
        for (i in 0 until turn) {
            val v = 0.3 * sin(2 * PI * 440.0 * i / rate)
            x[0] = v; x[1] = v
            chain.frame(x)
            assertTrue(abs(x[0]) <= 1.0 && abs(x[1]) <= 1.0)
            val q = i * 4 / turn
            left[q] += x[0] * x[0]; right[q] += x[1] * x[1]
        }
        // First it goes right (and back to the front), then behind to the left.
        assertTrue(right[0] > left[0] * 3, "right ${right[0]} left ${left[0]}")
        assertTrue(left[2] > right[2] * 3, "right ${right[2]} left ${left[2]}")
    }

    @Test
    fun theFilterOfTheDesktopKeepsFramesTogether() {
        val chain = SoundChain(rate, 2)
        val buf = ByteArray(8)
        // Two frames: left loud, right silent.
        for (f in 0 until 2) { buf[f * 4] = 0x00; buf[f * 4 + 1] = 0x40 }
        chain.process16(buf, 0, buf.size, Settings(soundChannels = "mono"))
        for (f in 0 until 2) {
            val l = ((buf[f * 4].toInt() and 0xFF) or (buf[f * 4 + 1].toInt() shl 8))
            val r = ((buf[f * 4 + 2].toInt() and 0xFF) or (buf[f * 4 + 3].toInt() shl 8))
            assertEquals(l, r)
            assertTrue(abs(l - 0x2000) <= 1)
        }
    }
}
