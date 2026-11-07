package app.hamstr.data

import app.hamstr.R
import android.text.format.DateFormat
import java.util.UUID
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.provider.OpenableColumns
import app.hamstr.engine.Engine
import app.hamstr.nearby.ReceivedFile
import app.hamstr.player.Kind
import app.hamstr.player.kindOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.coroutines.resume

/**
 * Brings files the user already has into the stash: they are copied to Download/Hamstr and
 * show up next to downloads, in the library and the player.
 */
object Importer {
    /** Copies each picked file and returns how many made it. */
    suspend fun import(context: Context, uris: List<Uri>): Int = withContext(Dispatchers.IO) {
        // Several files picked at once stay together, like a playlist.
        val group = if (uris.size > 1) UUID.randomUUID().toString() to context.getString(R.string.import_group, DateFormat.format("d MMM, HH:mm", System.currentTimeMillis())) else null
        val start = System.currentTimeMillis()
        uris.withIndex().count { (i, uri) -> runCatching { importOne(context, uri, group, start + i) }.isSuccess }
    }

    /**
     * Copies picked files into the cache under their own names, for another device: Nearby sends
     * plain files. Each batch gets its own folder; ones older than a day are cleared.
     */
    suspend fun forSending(context: Context, uris: List<Uri>): List<File> = withContext(Dispatchers.IO) {
        val root = File(context.cacheDir, "nearby-out")
        root.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 24 * 3600_000L }?.forEach { it.deleteRecursively() }
        val dir = File(root, System.currentTimeMillis().toString()).apply { mkdirs() }
        uris.mapNotNull { uri ->
            runCatching {
                freeName(dir, nameAndSize(context, uri).first.replace(Regex("""[\\/:*?"<>|]"""), "_")).also { out ->
                    context.contentResolver.openInputStream(uri)!!.use { input -> out.outputStream().use { input.copyTo(it, 256 * 1024) } }
                }
            }.getOrNull()
        }
    }

    private fun nameAndSize(context: Context, uri: Uri): Pair<String, Long> =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) to (if (c.isNull(1)) -1L else c.getLong(1)) else null
        } ?: ((uri.lastPathSegment?.substringAfterLast('/') ?: "file") to -1L)

    private suspend fun importOne(context: Context, uri: Uri, group: Pair<String, String>?, createdAt: Long) {
        val resolver = context.contentResolver
        val (name, size) = nameAndSize(context, uri)
        Engine.outputDir.mkdirs()
        val clean = name.replace(Regex("""[\\/:*?"<>|]"""), "_")
        // A file that already sits in Download/Hamstr is listed as it is instead of copied again.
        val existing = File(Engine.outputDir, clean).takeIf {
            // Files left over from an earlier install may not be readable any more; those get copied.
            it.exists() && size >= 0 && it.length() == size && runCatching { it.inputStream().close() }.isSuccess
        }
        if (existing != null && DownloadStore.tasks.value.any { it.filePath == existing.absolutePath }) return
        val target = existing ?: freeName(Engine.outputDir, clean).also { out ->
            resolver.openInputStream(uri)!!.use { input -> out.outputStream().use { input.copyTo(it, 256 * 1024) } }
        }
        register(context, target, uri.toString(), "Imported", group, createdAt)
    }

    /**
     * Files another device sent, each with the name it gave: they move into the Hamstr folder
     * and onto the list, together when there are several. Returns the ones that made it.
     */
    suspend fun adopt(context: Context, files: List<ReceivedFile>, from: String): List<File> = withContext(Dispatchers.IO) {
        val group = if (files.size > 1) UUID.randomUUID().toString() to from else null
        val start = System.currentTimeMillis()
        files.withIndex().mapNotNull { (i, f) ->
            val (part, name) = f
            runCatching {
                Engine.outputDir.mkdirs()
                val target = freeName(Engine.outputDir, name.replace(Regex("""[\\/:*?"<>|]"""), "_"))
                if (!part.renameTo(target)) {
                    part.copyTo(target)
                    part.delete()
                }
                register(context, target, Uri.fromFile(target).toString(), if (f.metadata == null) from else null, group, start + i, f)
                target
            }.onFailure { part.delete() }.also { f.cover?.delete() }.getOrNull()
        }
    }

    private suspend fun register(context: Context, target: File, url: String, source: String?, group: Pair<String, String>?, createdAt: Long, received: ReceivedFile? = null) {
        val kind = kindOf(target)
        var duration = 0
        var thumbnail: String? = if (kind == Kind.Image) Uri.fromFile(target).toString() else null
        if (kind == Kind.Video || kind == Kind.Audio) {
            // Not AutoCloseable before Android 10, so released by hand.
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(target.path)
                duration = (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L).div(1000).toInt()
                val frame: Bitmap? = if (kind == Kind.Video) r.getFrameAtTime(duration * 1_000_000L / 3) else null
                thumbnail = poster(context, target, r.embeddedPicture, frame)
            } catch (_: Exception) {
            } finally {
                r.release()
            }
        }
        val contentUri = scan(context, target)
        val task = DownloadTask(
            url = url,
            title = target.nameWithoutExtension,
            thumbnail = thumbnail,
            source = source,
            durationSec = duration,
            choice = Prefs.settings.value.defaultChoice().copy(mode = if (kind == Kind.Audio) Mode.Audio else Mode.Video, container = target.extension),
            status = Status.Done,
            progress = 1f,
            filePath = target.absolutePath,
            contentUri = contentUri?.toString(),
            sizeBytes = target.length(),
            createdAt = createdAt,
            finishedAt = System.currentTimeMillis(),
            group = group?.first,
            groupTitle = group?.second,
        )
        val described = received?.metadata?.applyTo(task) ?: task
        DownloadStore.add(described.copy(thumbnail = received?.keepCover(File(context.filesDir, "posters")) ?: described.thumbnail))
    }

    /** Embedded cover art, or a frame a third into the video, saved for the library cards. */
    private fun poster(context: Context, file: File, art: ByteArray?, frame: Bitmap?): String? {
        val out = File(File(context.filesDir, "posters").apply { mkdirs() }, file.nameWithoutExtension.hashCode().toString() + ".jpg")
        when {
            art != null -> out.writeBytes(art)
            frame != null -> out.outputStream().use { frame.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            else -> return null
        }
        return Uri.fromFile(out).toString()
    }

    internal fun freeName(dir: File, name: String) = FileNames.freeName(dir, name)

    internal suspend fun scan(context: Context, file: File): Uri? = withTimeoutOrNull(10_000) {
        suspendCancellableCoroutine { cont ->
            MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), null) { _, uri -> if (cont.isActive) cont.resume(uri) }
        }
    }
}
