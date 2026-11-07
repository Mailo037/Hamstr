package app.hamstr.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.StreamMetadata
import androidx.media3.common.util.UnstableApi
import app.hamstr.data.KeyValues
import app.hamstr.data.Prefs
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

@UnstableApi
class EqProcessorTest {
    private val format = AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT)
    private val song = StreamMetadata.Builder().setPeriodUid("song").build()

    private class SettingsStore(private val normalize: Boolean) : KeyValues {
        override fun getString(key: String, default: String?) = default
        override fun getInt(key: String, default: Int) = default
        override fun getLong(key: String, default: Long) = default
        override fun getBoolean(key: String, default: Boolean) = if (key == "sound.normalize") normalize else default
        override fun all(): Map<String, Any?> = emptyMap()
        override fun edit(): KeyValues.Editor = error("These tests only read settings")
    }

    @Before
    fun normalizeVolume() { Prefs.init(SettingsStore(true)) }

    @After
    fun restoreDefaults() { Prefs.init(SettingsStore(false)) }

    private fun processor(input: AudioFormat = format, metadata: StreamMetadata = song) = EqProcessor().apply {
        configure(input)
        flush(metadata)
    }

    /** A loud, steady 1 kHz tone, measured after the real PCM processor. */
    private fun rms(processor: EqProcessor, input: AudioFormat = format, frames: Int = input.sampleRate / 50): Double {
        val bytes = if (input.encoding == C.ENCODING_PCM_FLOAT) 4 else 2
        val buffer = ByteBuffer.allocateDirect(frames * input.channelCount * bytes).order(ByteOrder.nativeOrder())
        repeat(frames) { i ->
            val sample = 0.8 * sin(2 * PI * 1000 * i / input.sampleRate)
            repeat(input.channelCount) {
                if (bytes == 4) buffer.putFloat(sample.toFloat()) else buffer.putShort((sample * 32767).toInt().toShort())
            }
        }
        buffer.flip()
        processor.queueInput(buffer)
        assertFalse(buffer.hasRemaining())
        val output = processor.output.order(ByteOrder.nativeOrder())
        assertEquals(frames * input.channelCount * bytes, output.remaining())
        var sum = 0.0
        while (output.hasRemaining()) {
            val sample = if (bytes == 4) output.float.toDouble() else output.short / 32768.0
            sum += sample * sample
        }
        return sqrt(sum / (frames * input.channelCount))
    }

    private fun settle(processor: EqProcessor, input: AudioFormat = format) {
        rms(processor, input, input.sampleRate * 8)
    }

    @Test
    fun speedChangeFlushKeepsNormalizedVolumeFor16BitAndFloatPcm() {
        for (encoding in listOf(C.ENCODING_PCM_16BIT, C.ENCODING_PCM_FLOAT)) {
            val input = AudioFormat(48_000, 2, encoding)
            val processor = processor(input)
            settle(processor, input)
            val before = rms(processor, input)
            assertTrue("The tone must have been turned down before changing speed", before < 0.25)

            repeat(4) { change ->
                // DefaultAudioSink drains and flushes the processors when changing speed.
                processor.queueEndOfStream()
                processor.flush(song.buildUpon().setPositionOffsetUs(8_020_000L + change * 20_000L).build())
                val after = rms(processor, input)
                assertEquals("Volume jumped after speed change $change (encoding $encoding)", before, after, before * 0.05)
            }
        }
    }

    @Test
    fun seekInTheSameSongKeepsNormalizedVolume() {
        val processor = processor()
        settle(processor)
        val before = rms(processor)
        processor.flush(song.buildUpon().setPositionOffsetUs(2_000_000).build())
        assertEquals(before, rms(processor), before * 0.05)
    }

    @Test
    fun aNewSongWithTheSameFormatStartsWithFreshNormalization() {
        val processor = processor()
        settle(processor)
        val next = StreamMetadata.Builder().setPeriodUid("next song").build()
        processor.configure(format)
        processor.flush(next)
        assertEquals(rms(processor(metadata = next)), rms(processor), 1e-6)
    }

    @Test
    fun changingSampleRateOrChannelCountRebuildsTheFilter() {
        for (input in listOf(AudioFormat(44_100, 2, C.ENCODING_PCM_16BIT), AudioFormat(48_000, 1, C.ENCODING_PCM_16BIT))) {
            val processor = processor()
            settle(processor)
            processor.configure(input)
            processor.flush(song)
            assertEquals(rms(processor(input), input), rms(processor, input), 1e-6)
        }
    }

    @Test
    fun resetDiscardsThePreviousNormalization() {
        val processor = processor()
        settle(processor)
        processor.reset()
        processor.configure(format)
        processor.flush(song)
        assertEquals(rms(processor()), rms(processor), 1e-6)
    }
}
