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
