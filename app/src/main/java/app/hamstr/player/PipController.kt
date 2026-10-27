package app.hamstr.player

import android.app.PictureInPictureParams
import android.os.Build
import android.util.Rational
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import app.hamstr.data.DownloadTask
import java.io.File
import kotlin.math.roundToInt

/** Activity-owned: closing the composable player must not leave auto-enter enabled. */
internal class PipController(private val apply: (PictureInPictureParams) -> Unit) {
    private var task: DownloadTask? = null
    private var player: Player? = null
    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) = refresh()
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = refresh()
        override fun onVideoSizeChanged(videoSize: VideoSize) = refresh()
        override fun onEvents(player: Player, events: Player.Events) = refresh()
    }

    val eligible: Boolean
        get() = task?.let { t ->
            t.filePath?.let { kindOf(File(it)) } == Kind.Video &&
                player?.isPlaying == true && player?.currentMediaItem?.mediaId == t.id
        } == true

    fun bind(task: DownloadTask?, player: Player?) {
        if (this.player !== player) {
            this.player?.removeListener(listener)
            this.player = player
            player?.addListener(listener)
        }
        this.task = task
        refresh()
    }

    fun params(): PictureInPictureParams {
        val size = player?.videoSize
        val aspect = if (size != null && size.width > 0 && size.height > 0) {
            size.width * size.pixelWidthHeightRatio / size.height
        } else 16f / 9f
        val ratio = Rational((aspect.coerceIn(0.42f, 2.38f) * 1000).roundToInt(), 1000)
        return PictureInPictureParams.Builder().setAspectRatio(ratio).apply {
            if (Build.VERSION.SDK_INT >= 31) setAutoEnterEnabled(eligible).setSeamlessResizeEnabled(true)
        }.build()
    }

    fun refresh() { apply(params()) }

    fun close() = bind(null, null)
}
