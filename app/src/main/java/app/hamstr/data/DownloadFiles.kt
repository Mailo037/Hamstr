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

    /** Finished downloads whose file was deleted outside Hamstr. */
    val missing: StateFlow<Set<String>> get() = FileChecks.missing

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

    /** The name the rename field starts with: the file's name without its extension. */
    fun baseName(task: DownloadTask): String = task.filePath?.let { File(it).nameWithoutExtension } ?: task.title

    /**
     * Renames the file on disk to [name] (the extension stays) and moves every entry and the
     * player over to it. Returns the renamed entry.
     */
    suspend fun rename(context: Context, task: DownloadTask, name: String): Result<DownloadTask> {
        val renamed = withContext(Dispatchers.IO) {
            runCatching {
                val file = task.filePath?.let(::File) ?: error("This download has no saved file")
                check(file.isFile) { "The file is gone" }
                val base = name.replace(UNSAFE, "").trim().trimEnd('.').trim()
                require(base.isNotEmpty()) { "Give it a name" }
                val target = File(file.parentFile, if (file.extension.isEmpty()) base else "$base.${file.extension}")
                require(target.name.toByteArray().size <= 240) { "That name is too long" }
                if (target.name == file.name) return@runCatching null
                // Shared storage ignores case, so a case-only change goes through a temporary name.
                val caseOnly = target.name.equals(file.name, ignoreCase = true)
                check(caseOnly || !target.exists()) { "A file with that name already exists" }
                val moved = if (caseOnly) {
                    val temp = File(file.parentFile, ".hamstr-rename-${System.nanoTime()}")
                    file.renameTo(temp) && (temp.renameTo(target) || temp.renameTo(file).let { false })
                } else file.renameTo(target)
                check(moved) { "Couldn't rename the file" }
                try {
                    MediaScannerConnection.scanFile(context.applicationContext, arrayOf(file.absolutePath), null, null)
                } catch (e: Exception) {
                    Log.w("HamstrFiles", "Could not refresh the media index", e)
                }
                val uri = Importer.scan(context.applicationContext, target)
                DownloadStore.moveFile(file, target, base, uri?.toString())
                DownloadStore.get(task.id)
            }
        }
        return renamed.map { t ->
            t ?: return Result.success(task)
            withContext(Dispatchers.Main.immediate) {
                if (Playback.isCurrent(task)) Playback.switchTo(context, t)
            }
            t
        }
    }

    // Characters no file system on the phone or a PC accepts in a name.
    private val UNSAFE = Regex("""[\\/:*?"<>|\u0000-\u001F]""")

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
    fun refreshMissing() = FileChecks.refresh()

    /** Swaps [old] for [new]: the player moves over to the new file first, then the old one goes. */
    suspend fun replace(context: Context, old: DownloadTask, new: DownloadTask): Result<Unit> {
        withContext(Dispatchers.Main.immediate) {
            if (Playback.isCurrent(old)) Playback.switchTo(context, new)
        }
        return delete(context, old)
    }

    fun cleanName(stem: String) = FileNames.cleanName(stem)

    fun tidy(file: File) = FileNames.tidy(file)

    /**
     * Whether [file] has a picture track: false for audio in a video container (a song from
     * SoundCloud fetched as "video"), null when the platform can't read the file.
     */
    fun hasVideo(file: File): Boolean? {
        if (app.hamstr.player.kindOf(file) == app.hamstr.player.Kind.Audio) return false
        if (app.hamstr.player.kindOf(file) != app.hamstr.player.Kind.Video) return null
        return runCatching {
            MediaMetadataRetriever().run {
                try {
                    setDataSource(file.absolutePath)
                    extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
                } finally { release() }
            }
        }.getOrNull()
    }

    /** A "video" download that turned out to be sound only becomes the song it is. */
    fun asAudio(t: DownloadTask): DownloadTask {
        val ext = t.filePath?.let { File(it).extension.lowercase() }.orEmpty()
        val format = when (ext) { "mp4", "m4a" -> "m4a"; "webm", "ogg", "opus" -> "opus"; "" -> t.choice.audioFormat; else -> ext }
        return t.copy(choice = t.choice.copy(mode = Mode.Audio, audioFormat = format), videoHeight = 0)
    }

    /** Earlier downloads stored as videos although they hold only sound; runs once per start. */
    fun fixAudioKinds() {
        scope.launch {
            DownloadStore.tasks.value
                .filter { it.status == Status.Done && it.choice.mode != Mode.Audio && it.videoHeight == 0 && it.filePath != null }
                .forEach { t ->
                    val file = File(t.filePath!!)
                    if (file.isFile && hasVideo(file) == false) DownloadStore.update(t.id) { asAudio(it) }
                }
        }
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
