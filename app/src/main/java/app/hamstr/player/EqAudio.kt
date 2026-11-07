package app.hamstr.player

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.StreamMetadata
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import app.hamstr.audio.SoundChain
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** ExoPlayer's renderers with Hamstr's equalizer in front of the speaker. */
@UnstableApi
internal class EqRenderers(context: Context) : DefaultRenderersFactory(context) {
    override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioOutputPlaybackParameters: Boolean): AudioSink =
        DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParameters)
            .setAudioProcessors(arrayOf(EqProcessor()))
            .build()
}

/**
 * The shared [SoundChain] (equalizer, mono or 8D, then volume and normalizing) on ExoPlayer's decoded sound, 16-bit or
 * float. It stays in the chain while both are off and passes the sound through, so switching on needs no new
 * audio track; other encodings (passthrough, 24-bit) skip it.
 */
@UnstableApi
internal class EqProcessor : BaseAudioProcessor() {
    private var filter: SoundChain? = null
    private var format = AudioFormat.NOT_SET
    private var period: Any? = null
    private var one = DoubleArray(2)

    override fun onConfigure(input: AudioFormat): AudioFormat =
        if (input.encoding == C.ENCODING_PCM_16BIT || input.encoding == C.ENCODING_PCM_FLOAT) input else AudioFormat.NOT_SET

    override fun onFlush(streamMetadata: StreamMetadata) {
        if (filter == null || format != inputAudioFormat || period != streamMetadata.periodUid) {
            filter = SoundChain(inputAudioFormat.sampleRate, inputAudioFormat.channelCount)
            one = DoubleArray(inputAudioFormat.channelCount)
        } else {
            // Speed changes flush too. Clear delayed samples, not the song's learned volume.
            filter?.reset()
        }
        format = inputAudioFormat
        period = streamMetadata.periodUid
    }

    override fun onReset() {
        filter = null
        format = AudioFormat.NOT_SET
        period = null
    }

    override fun queueInput(input: ByteBuffer) {
        val size = input.remaining()
        if (size == 0) return
        val out = replaceOutputBuffer(size)
        val f = filter
        if (f == null || !f.refresh()) {
            out.put(input)
        } else {
            input.order(ByteOrder.nativeOrder())
            val channels = inputAudioFormat.channelCount
            val x = one
            // Whole frames: the channel mix needs every channel of one moment together.
            if (inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT) {
                while (input.remaining() >= 4 * channels) {
                    for (c in 0 until channels) x[c] = input.float.toDouble()
                    f.frame(x)
                    for (c in 0 until channels) out.putFloat(x[c].toFloat())
                }
            } else {
                while (input.remaining() >= 2 * channels) {
                    for (c in 0 until channels) x[c] = input.short / 32768.0
                    f.frame(x)
                    for (c in 0 until channels) out.putShort((x[c] * 32767).toInt().coerceIn(-32768, 32767).toShort())
                }
            }
            input.position(input.limit())
        }
        out.flip()
    }
}

/** The equalizer as a sheet over the player: the song page's button opens it, MainActivity shows it. */
object EqSheet {
    val open = kotlinx.coroutines.flow.MutableStateFlow(false)
}

/** "Play on" over the player: its cast button opens it, MainActivity shows it. */
object CastSheetState {
    val open = kotlinx.coroutines.flow.MutableStateFlow(false)
}
