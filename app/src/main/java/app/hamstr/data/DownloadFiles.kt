package app.hamstr.data

import android.content.Context
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
}
