package app.hamstr.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.util.Log
import app.hamstr.player.Playback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Shared file deletion for the library and player menus. */
object DownloadFiles {
    /** How long a deleted file waits for Undo. */
    const val UNDO_MS = 8_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private const val TRASH = "trash"

    private val _missing = MutableStateFlow<Set<String>>(emptySet())
    /** Finished downloads whose file was deleted outside Hamstr. */
    val missing: StateFlow<Set<String>> = _missing.asStateFlow()

    suspend fun delete(context: Context, task: DownloadTask): Result<Unit> = withContext(Dispatchers.Main.immediate) {
        Playback.closeIfCurrent(task)
        withContext(Dispatchers.IO) {
            runCatching {
                val file = task.filePath?.let(::File) ?: error("This download has no saved file")
                check(!file.exists() || file.isFile) { "This entry is not a file" }
                check(file.delete() || !file.exists()) { "Couldn't delete the file" }
                DownloadStore.removeFile(file)
                try {
                    MediaScannerConnection.scanFile(context.applicationContext, arrayOf(file.absolutePath), null, null)
                } catch (e: Exception) {
                    Log.w("HamstrFiles", "Could not refresh the media index", e)
                }
                Unit
            }
        }
    }

    /** Files on their way out: the entries are gone from the list, the files wait for [UNDO_MS]. */
    class Pending internal constructor(
        internal val removed: List<IndexedValue<DownloadTask>>,
        internal val files: Set<String>,
        internal val job: Job,
    ) {
        val count get() = files.size
    }

    /**
     * Takes [tasks] off the list right away, with every other entry of the same files, and
     * deletes the files once [UNDO_MS] passed without [undo]. Call on the main thread.
     */
    fun deleteLater(context: Context, tasks: List<DownloadTask>): Pending {
        val app = context.applicationContext
        tasks.forEach(Playback::closeIfCurrent)
        val files = tasks.mapNotNull { t -> t.filePath?.let { File(it).absoluteFile.normalize().path } }.toSet()
        val ids = DownloadStore.tasks.value.filter { t ->
            t.id in tasks.map { it.id } || t.filePath?.let { File(it).absoluteFile.normalize().path } in files
        }.map { it.id }.toSet()
        val removed = DownloadStore.removeAll(ids)
        // Kept on disk too, so a file isn't left behind when the process dies before the end.
        trash(app).edit().putStringSet(TRASH, trashed(app) + files).apply()
        val job = scope.launch {
            delay(UNDO_MS)
            files.forEach { erase(app, File(it)) }
            trash(app).edit().putStringSet(TRASH, trashed(app) - files).apply()
        }
        return Pending(removed, files, job)
    }

    fun undo(context: Context, pending: Pending) {
        pending.job.cancel()
        DownloadStore.restore(pending.removed)
        trash(context).edit().putStringSet(TRASH, trashed(context) - pending.files).apply()
    }

    /** Deletes files whose Undo time ran out while Hamstr wasn't running. */
    fun finishPending(context: Context) {
        val app = context.applicationContext
        val files = trashed(app)
        if (files.isEmpty()) return
        scope.launch {
            val listed = DownloadStore.tasks.value.mapNotNull { t -> t.filePath?.let { File(it).absoluteFile.normalize().path } }.toSet()
            files.filter { it !in listed }.forEach { erase(app, File(it)) }
            trash(app).edit().remove(TRASH).apply()
        }
    }

    private fun trash(context: Context) = context.getSharedPreferences("files", Context.MODE_PRIVATE)
    private fun trashed(context: Context): Set<String> = trash(context).getStringSet(TRASH, emptySet()).orEmpty().toSet()

    private fun erase(context: Context, file: File) {
        if (file.isFile && !file.delete()) Log.w("HamstrFiles", "Could not delete $file")
        runCatching { MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), null, null) }
    }

    /** Looks for finished downloads whose file is gone; cheap enough to run on every return to the app. */
    fun refreshMissing() {
        scope.launch {
            _missing.value = DownloadStore.tasks.value
                .filter { it.status == Status.Done && it.filePath != null && !File(it.filePath).exists() }
                .map { it.id }.toSet()
        }
    }

    /** Swaps [old] for [new]: the player moves over to the new file first, then the old one goes. */
    suspend fun replace(context: Context, old: DownloadTask, new: DownloadTask): Result<Unit> {
        withContext(Dispatchers.Main.immediate) {
            if (Playback.isCurrent(old)) Playback.switchTo(context, new)
        }
        return delete(context, old)
    }

    /** yt-dlp's "Title [id] 720p" becomes "Title 720p"; the ID only kept two downloads apart. */
    fun cleanName(stem: String): String =
        Regex("""^(.*\S) \[[^\[\]]+\]((?: [\w-]+)?)$""").find(stem)?.let { it.groupValues[1] + it.groupValues[2] } ?: stem

    /** Renames a finished file to its clean name, next to any file that already has it. */
    fun tidy(file: File): File {
        val clean = cleanName(file.nameWithoutExtension)
        if (clean == file.nameWithoutExtension) return file
        val target = Importer.freeName(file.parentFile ?: return file, if (file.extension.isEmpty()) clean else "$clean.${file.extension}")
        return if (file.renameTo(target)) target else file
    }

    /** The video's height in pixels, or 0 for audio and files the platform can't read. */
    fun probeHeight(file: File): Int = runCatching {
        MediaMetadataRetriever().run {
            try {
                setDataSource(file.absolutePath)
                if (extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) != "yes") 0
                else extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            } finally { release() }
        }
    }.getOrDefault(0)
}
