package app.hamstr.player

import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import app.hamstr.cast.CastMedia
import app.hamstr.cast.CastSession
import app.hamstr.cast.CastStatus
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.roundToInt

/**
 * The queue playing on a TV or speaker ([CastSession]) as a Media3 player. The session holds it
 * instead of ExoPlayer while Hamstr casts, so the app's player, the notification, the lock
 * screen and the volume keys all steer the device; when a song ends there, the next one goes.
 */
@UnstableApi
internal class CastPlayer(
    val cast: CastSession,
    items: List<MediaItem>,
    index: Int,
    positionMs: Long,
    play: Boolean,
    private var repeat: Int,
    private var shuffle: Boolean,
) : SimpleBasePlayer(Looper.getMainLooper()) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var items = items.toMutableList()
    private var index = index.coerceIn(0, (items.size - 1).coerceAtLeast(0))
    private var playWhenReady = play
    private var ended = false
    private var sentAt = 0L

    init {
        send(positionMs / 1000.0, play)
        scope.launch {
            combine(cast.status, cast.media) { s, m -> s to m }.collect { (s, m) ->
                // A song that ended there: on to the next, as the queue goes.
                if (s.state == CastStatus.State.Finished && m?.id == items.getOrNull(this@CastPlayer.index)?.mediaId && System.currentTimeMillis() - sentAt > 3_000) {
                    val next = following()
                    if (next != null) { this@CastPlayer.index = next; send(0.0, true) } else { ended = true; playWhenReady = false }
                }
                invalidateState()
            }
        }
    }

    private fun following(): Int? = when {
        repeat == Player.REPEAT_MODE_ONE -> index
        shuffle && items.size > 1 -> (items.indices - index).random()
        index + 1 < items.size -> index + 1
        repeat == Player.REPEAT_MODE_ALL && items.isNotEmpty() -> 0
        else -> null
    }

    private fun send(at: Double, play: Boolean) {
        val item = items.getOrNull(index) ?: return
        val media = media(item) ?: return
        ended = false
        sentAt = System.currentTimeMillis()
        cast.load(media, at, play)
    }

    override fun getState(): State {
        val s = cast.status.value
        val loaded = cast.media.value?.id == items.getOrNull(index)?.mediaId
        val playlist = items.mapIndexed { i, item ->
            MediaItemData.Builder(item.mediaId.ifEmpty { "item$i" } + "#$i")
                .setMediaItem(item)
                .setDurationUs(if (i == index && loaded && s.duration > 0) (s.duration * 1_000_000).toLong() else C.TIME_UNSET)
                .setIsSeekable(true)
                .build()
        }
        val state = when {
            items.isEmpty() -> Player.STATE_IDLE
            ended -> Player.STATE_ENDED
            !loaded || s.state == CastStatus.State.Loading -> Player.STATE_BUFFERING
            s.state == CastStatus.State.Failed -> Player.STATE_IDLE
            else -> Player.STATE_READY
        }
        val position = if (loaded) (s.positionNow() * 1000).toLong() else 0L
        return State.Builder()
            .setAvailableCommands(COMMANDS)
            .setPlaylist(playlist)
            .setCurrentMediaItemIndex(index.coerceIn(0, (items.size - 1).coerceAtLeast(0)))
            .setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(state)
            .setContentPositionMs(
                if (s.state == CastStatus.State.Playing && loaded) PositionSupplier.getExtrapolating(position, 1f) else PositionSupplier.getConstant(position),
            )
            .setRepeatMode(repeat)
            .setShuffleModeEnabled(shuffle)
            .setDeviceInfo(DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE).setMinVolume(0).setMaxVolume(MAX_VOLUME).build())
            .setDeviceVolume(((s.volume ?: 0.5f) * MAX_VOLUME).roundToInt())
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        this.playWhenReady = playWhenReady
        if (ended && playWhenReady) send(0.0, true)
        else if (playWhenReady) cast.play() else cast.pause()
        return done()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        val to = if (mediaItemIndex == C.INDEX_UNSET) index else mediaItemIndex
        val at = (if (positionMs == C.TIME_UNSET) 0 else positionMs) / 1000.0
        if (to != index || cast.media.value?.id != items.getOrNull(index)?.mediaId || ended) {
            index = to.coerceIn(0, items.lastIndex)
            send(at, playWhenReady || ended)
            if (ended) playWhenReady = true
        } else cast.seek(at)
        return done()
    }

    override fun handleSetMediaItems(mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<*> {
        items = mediaItems.toMutableList()
        index = if (startIndex == C.INDEX_UNSET) 0 else startIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        send(if (startPositionMs == C.TIME_UNSET) 0.0 else startPositionMs / 1000.0, playWhenReady)
        return done()
    }

    override fun handleAddMediaItems(index: Int, mediaItems: MutableList<MediaItem>): ListenableFuture<*> {
        items.addAll(index.coerceIn(0, items.size), mediaItems)
        if (index <= this.index && items.size > mediaItems.size) this.index += mediaItems.size
        return done()
    }

    override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<*> {
        val playingRemoved = index in fromIndex until toIndex
        repeat(toIndex - fromIndex) { items.removeAt(fromIndex) }
        when {
            items.isEmpty() -> { index = 0; cast.pause() }
            playingRemoved -> { index = fromIndex.coerceAtMost(items.lastIndex); send(0.0, playWhenReady) }
            index >= toIndex -> index -= toIndex - fromIndex
        }
        return done()
    }

    override fun handleMoveMediaItems(fromIndex: Int, toIndex: Int, newIndex: Int): ListenableFuture<*> {
        val current = items.getOrNull(index)
        val moved = items.subList(fromIndex, toIndex).toList()
        repeat(toIndex - fromIndex) { items.removeAt(fromIndex) }
        items.addAll(newIndex.coerceIn(0, items.size), moved)
        index = items.indexOf(current).coerceAtLeast(0)
        return done()
    }

    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> { repeat = repeatMode; return done() }
    override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean): ListenableFuture<*> { shuffle = shuffleModeEnabled; return done() }

    override fun handleSetDeviceVolume(deviceVolume: Int, flags: Int): ListenableFuture<*> {
        cast.setVolume(deviceVolume.coerceIn(0, MAX_VOLUME) / MAX_VOLUME.toFloat())
        return done()
    }
    override fun handleIncreaseDeviceVolume(flags: Int): ListenableFuture<*> = handleSetDeviceVolume(volumeNow() + 1, flags)
    override fun handleDecreaseDeviceVolume(flags: Int): ListenableFuture<*> = handleSetDeviceVolume(volumeNow() - 1, flags)
    private fun volumeNow() = ((cast.status.value.volume ?: 0.5f) * MAX_VOLUME).roundToInt()

    override fun handleStop(): ListenableFuture<*> { playWhenReady = false; cast.pause(); return done() }
    override fun handlePrepare(): ListenableFuture<*> = done()
    override fun handleRelease(): ListenableFuture<*> { scope.cancel(); return done() }

    /** Where the queue is, for going back to the phone's own player. */
    val queue: List<MediaItem> get() = items.toList()
    val currentIndex: Int get() = index
    val positionMs: Long get() = if (cast.media.value?.id == items.getOrNull(index)?.mediaId) (cast.status.value.positionNow() * 1000).toLong() else 0L
    val wantsToPlay: Boolean get() = playWhenReady

    private fun done(): ListenableFuture<*> { invalidateState(); return Futures.immediateVoidFuture() }

    companion object {
        private const val MAX_VOLUME = 20

        private val COMMANDS = Player.Commands.Builder().addAll(
            Player.COMMAND_PLAY_PAUSE, Player.COMMAND_PREPARE, Player.COMMAND_STOP,
            Player.COMMAND_SEEK_TO_DEFAULT_POSITION, Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_MEDIA_ITEM,
            Player.COMMAND_SEEK_BACK, Player.COMMAND_SEEK_FORWARD,
            Player.COMMAND_SET_REPEAT_MODE, Player.COMMAND_SET_SHUFFLE_MODE,
            Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_TIMELINE, Player.COMMAND_GET_METADATA,
            Player.COMMAND_SET_MEDIA_ITEM, Player.COMMAND_CHANGE_MEDIA_ITEMS,
            Player.COMMAND_GET_DEVICE_VOLUME, Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS, Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS,
            Player.COMMAND_RELEASE,
        ).build()

        /** A media item from the queue as the TV gets it: the file, its kind, its cover. */
        fun media(item: MediaItem): CastMedia? {
            val uri = item.localConfiguration?.uri ?: return null
            val file = uri.path?.let(::File)?.takeIf { uri.scheme == "file" && it.isFile } ?: return null
            val mime = item.localConfiguration?.mimeType ?: "application/octet-stream"
            val meta = item.mediaMetadata
            return CastMedia(
                id = item.mediaId,
                file = file,
                title = meta.title?.toString() ?: file.nameWithoutExtension,
                artist = meta.artist?.toString(),
                mime = mime,
                video = mime.startsWith("video/"),
                cover = meta.artworkUri?.let { if (it.scheme == "file") it.path else it.toString() },
                duration = app.hamstr.data.DownloadStore.tasks.value.firstOrNull { it.id == item.mediaId }?.durationSec?.toDouble() ?: 0.0,
            )
        }
    }
}
