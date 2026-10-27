package app.hamstr.player

import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.hamstr.R
import app.hamstr.data.DownloadTask
import app.hamstr.data.Positions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class Kind { Video, Audio, Image, Other }

/** What a stashed file is, by its extension. */
fun kindOf(file: File): Kind {
    val mime = mimeOf(file)
    return when {
        mime.startsWith("video/") -> Kind.Video
        mime.startsWith("audio/") -> Kind.Audio
        mime.startsWith("image/") -> Kind.Image
        else -> Kind.Other
    }
}

fun mimeOf(file: File): String = when (val ext = file.extension.lowercase()) {
    "opus", "ogg", "oga" -> "audio/ogg"
    "m4a" -> "audio/mp4"
    "mkv" -> "video/x-matroska"
    "webm" -> "video/webm"
    else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
}

private fun playable(t: DownloadTask) = t.filePath?.let(::File)?.takeIf { it.isFile }?.let(::kindOf).let { it == Kind.Video || it == Kind.Audio }

/** The in-app player's state: what is loaded and whether it is shown big or as the mini player. */
object Playback {
    // Clips this short loop on their own, like on the sites they come from.
    private const val LOOP_UP_TO_SEC = 60

    private val connectionMutex = Mutex()
    private var openGeneration = 0L
    private val _current = MutableStateFlow<DownloadTask?>(null)
    val current: StateFlow<DownloadTask?> = _current.asStateFlow()
    private val _expanded = MutableStateFlow(false)
    val expanded: StateFlow<Boolean> = _expanded.asStateFlow()
    private val _controller = MutableStateFlow<MediaController?>(null)
    val controller: StateFlow<MediaController?> = _controller.asStateFlow()
    private val _queue = MutableStateFlow<List<DownloadTask>>(emptyList())
    /** What plays one after the other; a single file is a queue of one. */
    val queue: StateFlow<List<DownloadTask>> = _queue.asStateFlow()

    private suspend fun connect(context: Context): MediaController = connectionMutex.withLock {
        _controller.value?.takeIf { it.isConnected }?.let { return@withLock it }
        val app = context.applicationContext
        val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
        val future = MediaController.Builder(app, token).buildAsync()
        val controller = suspendCancellableCoroutine { cont ->
            future.addListener({
                try { cont.resume(future.get()) } catch (e: Exception) { cont.resumeWithException(e) }
            }, ContextCompat.getMainExecutor(app))
            cont.invokeOnCancellation { MediaController.releaseFuture(future) }
        }
        // The queue moves on by itself; the UI follows whatever plays now.
        controller.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val id = mediaItem?.mediaId ?: return
                _queue.value.firstOrNull { it.id == id }?.let { _current.value = it }
            }
        })
        _controller.value = controller
        controller
    }

    /** What plays after [task] when it is started from [from]: the rest of its playlist, or the other songs. */
    fun queueFor(task: DownloadTask, from: List<DownloadTask>): List<DownloadTask> {
        val kind = task.filePath?.let(::File)?.takeIf { it.isFile }?.let(::kindOf)
        val pool = when {
            task.group != null -> from.filter { it.group == task.group }.sortedBy { it.createdAt }
            kind == Kind.Audio -> from.filter { t -> t.filePath?.let(::File)?.takeIf { it.isFile }?.let(::kindOf) == Kind.Audio }
            else -> return listOf(task)
        }.filter(::playable).distinctBy { it.id }
        return if (pool.any { it.id == task.id }) pool else listOf(task)
    }

    private fun item(task: DownloadTask, file: File) = MediaItem.Builder()
        .setMediaId(task.id)
        .setUri(Uri.fromFile(file))
        .setMimeType(mimeOf(file))
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(task.title)
                .setArtist(task.uploader ?: task.source)
                .setArtworkUri(task.thumbnail?.let(Uri::parse))
                .build(),
        )
        .build()

    /**
     * Opens a stashed file in the player (video and audio) or the viewer (images, others),
     * where it was left last time. Returns false when the file is gone.
     */
    suspend fun open(
        context: Context,
        task: DownloadTask,
        queue: List<DownloadTask> = listOf(task),
        startAt: Long = -1,
        play: Boolean = true,
        expand: Boolean = true,
    ): Boolean = withContext(Dispatchers.Main.immediate) {
        val request = ++openGeneration
        val file = task.filePath?.let(::File)?.takeIf { it.isFile } ?: return@withContext false
        _current.value = task
        if (expand) _expanded.value = true
        val kind = kindOf(file)
        if (kind != Kind.Video && kind != Kind.Audio) {
            _queue.value = listOf(task)
            _controller.value?.pause()
            return@withContext true
        }
        val c = connect(context)
        // A close/delete or another selection can happen while the controller connects.
        if (request != openGeneration || _current.value?.id != task.id) return@withContext true
        if (!file.isFile) {
            close()
            return@withContext false
        }
        val entries = queue.filter(::playable).ifEmpty { listOf(task) }.let { q -> if (q.none { it.id == task.id }) listOf(task) else q }
        val index = entries.indexOfFirst { it.id == task.id }.coerceAtLeast(0)
        val sameQueue = _queue.value.map { it.id } == entries.map { it.id } && c.mediaItemCount == entries.size
        _queue.value = entries
        if (!sameQueue || c.currentMediaItem?.mediaId != task.id) {
            val at = if (startAt >= 0) startAt else Positions.resumeAt(task.id)
            if (sameQueue) c.seekTo(index, at)
            else c.setMediaItems(entries.map { t -> item(t, File(t.filePath!!)) }, index, at)
            c.prepare()
            // A short clip on its own loops; a queue plays through.
            c.repeatMode = if (entries.size == 1 && task.durationSec in 1..LOOP_UP_TO_SEC) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            c.shuffleModeEnabled = false
        }
        if (play) c.play() else c.pause()
        true
    }

    /** Another quality of what is open: same spot, same play/pause, same player size. */
    suspend fun switchTo(context: Context, task: DownloadTask) = withContext(Dispatchers.Main.immediate) {
        val c = _controller.value?.takeIf { it.isConnected && _current.value?.family == task.family }
        if (c == null) open(context, task)
        else open(context, task, startAt = c.currentPosition, play = c.playWhenReady, expand = false)
    }

    fun expand() { _expanded.value = true }
    fun minimize() { if (_current.value != null) _expanded.value = false }

    fun close() {
        openGeneration++
        _current.value = null
        _expanded.value = false
        _queue.value = emptyList()
        _controller.value?.takeIf { it.isConnected }?.run { pause(); stop(); clearMediaItems() }
    }

    /** Different history entries can point at the same downloaded file. */
    fun isCurrent(task: DownloadTask): Boolean {
        val current = _current.value ?: return false
        val sameFile = current.filePath != null && task.filePath != null &&
            File(current.filePath).absoluteFile.normalize() == File(task.filePath).absoluteFile.normalize()
        return current.id == task.id || sameFile
    }

    fun closeIfCurrent(task: DownloadTask) { if (isCurrent(task)) close() }

    // ---- leaving the app with the file ------------------------------------------------

    private fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, context.packageName + ".files", file)

    /** "Open with…": always asks which app (video player, music app, PDF reader, Word, …). */
    fun openWith(context: Context, task: DownloadTask) {
        val file = task.filePath?.let(::File)?.takeIf { it.exists() } ?: return
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uriFor(context, file), mimeOf(file))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(view, context.getString(R.string.menu_open_with)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Shares the file itself, not the link it came from. */
    fun shareFile(context: Context, task: DownloadTask) = shareFiles(context, listOf(task))

    /** Shares several files at once, from the library's selection. */
    fun shareFiles(context: Context, tasks: List<DownloadTask>) {
        val files = tasks.mapNotNull { t -> t.filePath?.let(::File)?.takeIf { it.exists() }?.let { t to it } }
        if (files.isEmpty()) return
        val uris = files.map { (_, f) -> uriFor(context, f) }
        val clip = ClipData.newRawUri(files[0].first.title, uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
        val send = if (files.size == 1) {
            Intent(Intent.ACTION_SEND).setType(mimeOf(files[0].second)).putExtra(Intent.EXTRA_STREAM, uris[0]).putExtra(Intent.EXTRA_TITLE, files[0].first.title)
        } else {
            val types = files.map { mimeOf(it.second) }.distinct()
            val type = types.singleOrNull() ?: types.map { it.substringBefore('/') }.distinct().singleOrNull()?.let { "$it/*" } ?: "*/*"
            Intent(Intent.ACTION_SEND_MULTIPLE).setType(type).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        send.clipData = clip
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, files[0].first.title.takeIf { files.size == 1 }).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
