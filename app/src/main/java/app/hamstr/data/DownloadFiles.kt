package app.hamstr.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.util.Log
import app.hamstr.player.Playback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Shared file deletion for the library and player menus. */
object DownloadFiles {
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

    /** Swaps [old] for [new]: the player moves over to the new file first, then the old one goes. */
    suspend fun replace(context: Context, old: DownloadTask, new: DownloadTask): Result<Unit> {
        withContext(Dispatchers.Main.immediate) {
            if (Playback.isCurrent(old)) Playback.switchTo(context, new)
        }
        return delete(context, old)
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
