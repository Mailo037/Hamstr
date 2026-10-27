package app.hamstr.player

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.hamstr.data.DownloadTask
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

/** The in-app player's state: what is loaded and whether it is shown big or as the mini player. */
object Playback {
    private val connectionMutex = Mutex()
    private var openGeneration = 0L
    private val _current = MutableStateFlow<DownloadTask?>(null)
    val current: StateFlow<DownloadTask?> = _current.asStateFlow()
    private val _expanded = MutableStateFlow(false)
    val expanded: StateFlow<Boolean> = _expanded.asStateFlow()
    private val _controller = MutableStateFlow<MediaController?>(null)
    val controller: StateFlow<MediaController?> = _controller.asStateFlow()

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
        _controller.value = controller
        controller
    }

    /** Opens a stashed file in the player (video and audio) or the viewer (images, others). */
    suspend fun open(context: Context, task: DownloadTask, startAt: Long = 0, play: Boolean = true, expand: Boolean = true) = withContext(Dispatchers.Main.immediate) {
        val request = ++openGeneration
        val file = task.filePath?.let(::File)?.takeIf { it.isFile } ?: return@withContext
        _current.value = task
        if (expand) _expanded.value = true
        val kind = kindOf(file)
        if (kind != Kind.Video && kind != Kind.Audio) {
            _controller.value?.pause()
            return@withContext
        }
        val c = connect(context)
        // A close/delete or another selection can happen while the controller connects.
        if (request != openGeneration || _current.value?.id != task.id) return@withContext
        if (!file.isFile) {
            close()
            return@withContext
        }
        val item = MediaItem.Builder()
            .setMediaId(task.id)
            .setUri(Uri.fromFile(file))
            .setMimeType(mimeOf(file))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(task.title)
                    .setArtist(task.source)
                    .setArtworkUri(task.thumbnail?.let(Uri::parse))
                    .build(),
            )
            .build()
        if (c.currentMediaItem?.mediaId != task.id) {
            c.setMediaItem(item, startAt)
            c.prepare()
        }
        if (play) c.play() else c.pause()
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
        context.startActivity(Intent.createChooser(view, "Open with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Shares the file itself, not the link it came from. */
    fun shareFile(context: Context, task: DownloadTask) {
        val file = task.filePath?.let(::File)?.takeIf { it.exists() } ?: return
        val send = Intent(Intent.ACTION_SEND)
            .setType(mimeOf(file))
            .putExtra(Intent.EXTRA_STREAM, uriFor(context, file))
            .putExtra(Intent.EXTRA_TITLE, task.title)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, task.title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
