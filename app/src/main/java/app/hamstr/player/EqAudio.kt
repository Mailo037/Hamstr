package app.hamstr.player

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import app.hamstr.audio.EqFilter
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
 * The shared [EqFilter] on ExoPlayer's decoded sound, 16-bit or float. It stays in the chain
 * while the equalizer is off and passes the sound through, so switching it on needs no new
 * audio track; other encodings (passthrough, 24-bit) skip it.
 */
@UnstableApi
private class EqProcessor : BaseAudioProcessor() {
    private var filter: EqFilter? = null

    override fun onConfigure(input: AudioFormat): AudioFormat =
        if (input.encoding == C.ENCODING_PCM_16BIT || input.encoding == C.ENCODING_PCM_FLOAT) input else AudioFormat.NOT_SET

    override fun onFlush() {
        filter = EqFilter(inputAudioFormat.sampleRate, inputAudioFormat.channelCount)
    }

    override fun onReset() {
        filter = null
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
            var ch = 0
            if (inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT) {
                while (input.remaining() >= 4) {
                    out.putFloat(f.next(input.float.toDouble(), ch).toFloat())
                    if (++ch == channels) ch = 0
                }
            } else {
                while (input.remaining() >= 2) {
                    out.putShort((f.next(input.short / 32768.0, ch) * 32767).toInt().coerceIn(-32768, 32767).toShort())
                    if (++ch == channels) ch = 0
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
