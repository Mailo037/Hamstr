package app.hamstr.engine

import java.util.Locale
import android.content.Context
import android.util.Log
import app.hamstr.data.DownloadTask
import app.hamstr.data.Mode
import app.hamstr.data.Prefs
import com.yausername.youtubedl_android.YoutubeDLResponse
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.util.concurrent.TimeUnit

/**
 * cheek (github.com/Mailo037/cheek), the Rust download engine. yt-dlp still decides which
 * streams a link has; cheek fetches them over many connections at once and merges them.
 * It ships inside the APK as libcheek.so and runs as a plain process, like yt-dlp's Python.
 */
object Cheek {
    private const val TAG = "HamstrCheek"
    private lateinit var bin: File
    private lateinit var ffmpeg: File
    private lateinit var libraryPath: String
    private val processes = ConcurrentHashMap<String, Process>()

    fun init(context: Context) {
        val native = File(context.applicationInfo.nativeLibraryDir)
        bin = File(native, "libcheek.so")
        ffmpeg = File(native, "libffmpeg.so")
        // ffmpeg's shared libraries are unpacked here by youtubedl-android.
        val packages = File(context.noBackupFilesDir, "youtubedl-android/packages")
        libraryPath = listOf("python", "ffmpeg", "aria2c").joinToString(":") { File(packages, "$it/usr/lib").absolutePath }
    }

    val available get() = ::bin.isInitialized && bin.exists()

    /** Read the executable actually installed with this APK, without blocking the UI. */
    suspend fun version(): String = runInterruptible(Dispatchers.IO) {
        check(available) { "Cheek is not available in this installation" }
        val process = ProcessBuilder(bin.absolutePath, "--version").redirectErrorStream(true).start()
        try {
            check(process.waitFor(5, TimeUnit.SECONDS)) { "Cheek version check timed out" }
            val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
            check(process.exitValue() == 0 && output.startsWith("cheek ")) { "Couldn't read the installed Cheek version" }
            output.removePrefix("cheek ")
        } finally {
            if (process.isAlive) process.destroy()
        }
    }

    /** What cheek can't do yet goes to yt-dlp: playlists, subtitles, a custom format string. */
    fun handles(task: DownloadTask): Boolean {
        val c = task.choice
        return available && !Prefs.settings.value.playlists && c.mode != Mode.Custom && !(c.mode == Mode.Video && c.subtitles)
    }

    fun destroy(id: String) {
        processes.remove(id)?.destroy()
    }

    /**
     * Downloads [info] (yt-dlp's JSON with its chosen formats). Progress arrives through
     * [callback] in yt-dlp's own shapes, so the service treats both engines alike.
     */
    fun execute(task: DownloadTask, info: String, tempDir: File, outputDir: File, pathFile: File, callback: ((Float, Long, String) -> Unit)?): YoutubeDLResponse {
        val started = System.currentTimeMillis()
        val work = File(tempDir, task.id).apply { mkdirs() }
        val infoFile = File(work, "cheek-info.json").apply { writeText(info) }
        val c = task.choice
        val cmd = buildList {
            addAll(listOf(bin.absolutePath, "--info-json", infoFile.absolutePath, "--json"))
            addAll(listOf("-P", outputDir.absolutePath, "--temp-dir", work.absolutePath, "-o", "%(title).150B [%(id)s].%(ext)s"))
            // Fewer connections per download when several run at once.
            addAll(listOf("-N", (12 / Prefs.settings.value.parallel.coerceIn(1, 3)).toString()))
            addAll(listOf("--ffmpeg", ffmpeg.absolutePath))
            when (c.mode) {
                Mode.Audio -> addAll(listOf("-x", "--audio-format", c.audioFormat))
                else -> addAll(listOf("--merge-output-format", c.container))
            }
        }
        // The title and thumbnail are known before the first byte, like yt-dlp's before_dl print.
        runCatching {
            val o = JSONObject(info)
            val meta = JSONObject().put("title", o.optString("title")).put("thumbnail", o.opt("thumbnail"))
                .put("extractor_key", o.opt("extractor_key")).put("duration", o.opt("duration"))
            callback?.invoke(0f, -1, Engine.METADATA_PREFIX + meta)
        }

        val proc = ProcessBuilder(cmd).apply { environment()["LD_LIBRARY_PATH"] = libraryPath }.start()
        processes[task.id] = proc
        val err = StringBuffer()
        val errReader = thread(name = "cheek-stderr") { proc.errorStream.bufferedReader().forEachLine { err.appendLine(it) } }
        var failure: String? = null
        try {
            proc.inputStream.bufferedReader().forEachLine { line ->
                val e = runCatching { JSONObject(line) }.getOrNull() ?: return@forEachLine
                when (e.optString("type")) {
                    "progress" -> {
                        val pct = e.optDouble("percent", 0.0)
                        val eta = if (e.isNull("eta")) -1L else e.optLong("eta", -1)
                        // Phrased like yt-dlp's progress line so the service reads the speed the same way.
                        val line2 = String.format(Locale.ROOT, "[download] %5.1f%% of %s at %s ETA %s", pct, mib(e.optLong("total")), mib(e.optLong("speed")) + "/s", eta)
                        callback?.invoke(pct.toFloat(), eta, line2)
                    }
                    "done" -> {
                        val o = JSONObject(info)
                        pathFile.appendText(
                            JSONObject().put("filepath", e.optString("path")).put("title", o.opt("title"))
                                .put("thumbnail", o.opt("thumbnail")).put("duration", o.opt("duration")).toString() + "\n",
                        )
                    }
                    "error" -> failure = e.optString("message")
                }
            }
            val code = proc.waitFor()
            errReader.join(2000)
            if (code != 0) Log.w(TAG, "cheek exit $code: ${failure ?: err.toString().takeLast(2000)}")
            return YoutubeDLResponse(cmd, code, System.currentTimeMillis() - started, "", failure ?: err.toString())
        } catch (e: Exception) {
            Log.w(TAG, "cheek stopped", e)
            throw e
        } finally {
            processes.remove(task.id)
            if (proc.isAlive) proc.destroy()
        }
    }

    /** Errors that mean "this one is for yt-dlp", not "the download failed". */
    fun unsupported(response: YoutubeDLResponse) = response.exitCode != 0 &&
        Regex("isn't supported yet|has no URL|use yt-dlp", RegexOption.IGNORE_CASE).containsMatchIn(response.err)

    private fun mib(bytes: Long) = String.format(Locale.ROOT, "%.2fMiB", bytes / 1048576.0)
}
