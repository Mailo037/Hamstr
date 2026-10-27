package app.hamstr.engine

import app.hamstr.R
import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import app.hamstr.data.DownloadStore
import app.hamstr.data.DownloadTask
import app.hamstr.data.Importer
import app.hamstr.data.Mode
import app.hamstr.data.Status
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Saves the sound of a stashed video as its own file, on the phone with FFmpeg and without
 * downloading anything. The stream is copied as it is when a music app can play it as is.
 */
object AudioOut {
    /** The audio stream's codec mapped to a file type that holds it without re-encoding. */
    private fun containerFor(video: File): String? {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(video.absolutePath)
            val mime = (0 until extractor.trackCount).map { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty() }
                .firstOrNull { it.startsWith("audio/") } ?: return null
            return when (mime) {
                MediaFormat.MIMETYPE_AUDIO_AAC -> "m4a"
                MediaFormat.MIMETYPE_AUDIO_OPUS -> "opus"
                MediaFormat.MIMETYPE_AUDIO_VORBIS -> "ogg"
                MediaFormat.MIMETYPE_AUDIO_MPEG -> "mp3"
                MediaFormat.MIMETYPE_AUDIO_FLAC -> "flac"
                else -> ""
            }
        } finally { extractor.release() }
    }

    /** Adds the audio file to the stash next to [video] and returns it. */
    suspend fun save(context: Context, video: DownloadTask): Result<DownloadTask> = withContext(Dispatchers.IO) {
        runCatching {
            Engine.awaitReady()
            val source = video.filePath?.let(::File)?.takeIf { it.isFile } ?: error(context.getString(R.string.audio_video_gone))
            val copy = containerFor(source) ?: error(context.getString(R.string.audio_no_sound))
            // Anything else is re-encoded to AAC, which every player knows.
            val ext = copy.ifEmpty { "m4a" }
            Engine.outputDir.mkdirs()
            val out = Importer.freeName(Engine.outputDir, "${source.nameWithoutExtension} audio.$ext")
            val codec = if (copy.isEmpty()) listOf("-c:a", "aac", "-b:a", "192k") else listOf("-c:a", "copy")
            val cmd = listOf(Cheek.ffmpeg.absolutePath, "-nostdin", "-y", "-i", source.absolutePath, "-map", "0:a:0", "-vn", "-map_metadata", "0") +
                codec + out.absolutePath
            val err = runInterruptible {
                val proc = ProcessBuilder(cmd).redirectErrorStream(true).apply { environment()["LD_LIBRARY_PATH"] = Cheek.libraryPath }.start()
                try {
                    val log = proc.inputStream.bufferedReader().readText()
                    check(proc.waitFor(10, TimeUnit.MINUTES)) { "FFmpeg took too long" }
                    if (proc.exitValue() == 0) null else log.lines().lastOrNull { it.isNotBlank() } ?: "FFmpeg exit ${proc.exitValue()}"
                } finally { if (proc.isAlive) proc.destroy() }
            }
            if (err != null || !out.isFile || out.length() == 0L) {
                out.delete()
                error(err ?: "FFmpeg wrote no audio")
            }
            val task = video.copy(
                id = java.util.UUID.randomUUID().toString(),
                choice = video.choice.copy(mode = Mode.Audio, audioFormat = ext),
                status = Status.Done, progress = 1f,
                filePath = out.absolutePath, contentUri = Importer.scan(context, out)?.toString(),
                sizeBytes = out.length(), videoHeight = 0,
                versionOf = video.family, replaces = null,
                createdAt = System.currentTimeMillis(), finishedAt = System.currentTimeMillis(),
            )
            DownloadStore.add(task)
            task
        }
    }
}
