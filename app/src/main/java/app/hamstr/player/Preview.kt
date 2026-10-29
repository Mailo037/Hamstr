package app.hamstr.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import app.hamstr.engine.Engine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Listens into a song before stashing it, for picking the right YouTube Music match of a
 * locked one. One small player of its own: it takes the audio focus like any music, starts a
 * little way in (where the song usually is, not its intro) and is gone with the card.
 */
@OptIn(UnstableApi::class)
class Preview(context: Context, private val scope: CoroutineScope) {
    private val player = ExoPlayer.Builder(context)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
        .build()
        .apply {
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED) playing = null
                }
            })
        }
    private var job: Job? = null

    /** The link playing now, or being fetched ([loading]). */
    var playing by mutableStateOf<String?>(null)
        private set
    var loading by mutableStateOf<String?>(null)
        private set
    /** The last link that couldn't be played. */
    var failed by mutableStateOf<String?>(null)
        private set

    /** Plays [url], or stops it when it is the one playing. */
    fun toggle(url: String, durationSec: Int) {
        job?.cancel()
        if (playing == url || loading == url) return stop()
        player.stop()
        playing = null
        loading = url
        failed = null
        job = scope.launch {
            val stream = Engine.previewStream(url)
            loading = null
            if (stream == null) { failed = url; return@launch }
            val source = DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setDefaultRequestProperties(stream.headers)
                .apply { stream.headers["User-Agent"]?.let(::setUserAgent) }
            player.setMediaSource(ProgressiveMediaSource.Factory(source).createMediaSource(MediaItem.fromUri(stream.url)))
            player.prepare()
            if (durationSec > 90) player.seekTo(durationSec * 1000L / 3)
            player.play()
            playing = url
        }
    }

    fun stop() {
        job?.cancel()
        player.stop()
        playing = null
        loading = null
    }

    fun release() {
        stop()
        player.release()
    }
}
