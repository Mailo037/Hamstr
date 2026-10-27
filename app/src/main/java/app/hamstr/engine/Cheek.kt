package app.hamstr.engine

import java.util.Locale
import android.content.Context
import android.os.SystemClock
import android.util.Log
import app.hamstr.data.DownloadTask
import app.hamstr.data.Mode
import app.hamstr.data.Prefs
import com.yausername.youtubedl_android.YoutubeDLResponse
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread
import java.util.concurrent.TimeUnit
import app.hamstr.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * cheek (github.com/Mailo037/cheek), the Rust download engine. yt-dlp still decides which
 * streams a link has; cheek fetches them over many connections at once and merges them.
 * It ships inside the APK as libcheek.so and runs as a plain process, like yt-dlp's Python.
 * A newer release can replace it later without a new APK (see [CheekUpdates]).
 */
object Cheek {
    private const val TAG = "HamstrCheek"
    private lateinit var bundled: File
    private lateinit var updates: File
    // Android 10+ won't exec a file from the app's own storage, but its loader will start it.
    private val loader = if (android.os.Process.is64Bit()) "/system/bin/linker64" else "/system/bin/linker"
    private class Installed(val version: String, val file: File)
    @Volatile private var downloaded: Installed? = null
    private val _active = MutableStateFlow(BuildConfig.CHEEK_VERSION)
    /** The version that runs: a downloaded update, or the one inside the APK. */
    val active: StateFlow<String> = _active.asStateFlow()
    val updated get() = downloaded != null
    /** The release asset suffix for this installation's ABI. */
    lateinit var abi: String
        private set
    internal lateinit var ffmpeg: File
        private set
    internal lateinit var libraryPath: String
        private set
    private val processes = ConcurrentHashMap<String, Process>()

    fun init(context: Context) {
        val native = File(context.applicationInfo.nativeLibraryDir)
        bundled = File(native, "libcheek.so")
        abi = when (native.name) { "arm64" -> "arm64-v8a"; "arm" -> "armeabi-v7a"; else -> native.name }
        ffmpeg = File(native, "libffmpeg.so")
        updates = File(context.filesDir, "cheek")
        // An app update can bring a newer Cheek than the last download; the newer one wins.
        val current = runCatching { File(updates, "current").readText().trim() }.getOrNull()
        val file = current?.let { File(updates, "$it/cheek") }
        if (current != null && file!!.isFile && newer(current, BuildConfig.CHEEK_VERSION) && compatible(current)) {
            downloaded = Installed(current, file)
            _active.value = current
        }
        // ffmpeg's shared libraries are unpacked here by youtubedl-android.
        val packages = File(context.noBackupFilesDir, "youtubedl-android/packages")
        libraryPath = listOf("python", "ffmpeg", "aria2c").joinToString(":") { File(packages, "$it/usr/lib").absolutePath }
    }

    val available get() = ::bundled.isInitialized && (downloaded != null || bundled.exists())

    private fun command(): List<String> = downloaded?.let { listOf(loader, it.file.absolutePath) } ?: listOf(bundled.absolutePath)

    /** Asks the executable that runs now for its version, without blocking the UI. */
    suspend fun version(): String = kotlinx.coroutines.runInterruptible(kotlinx.coroutines.Dispatchers.IO) {
        check(available) { "Cheek is not available in this installation" }
        val process = ProcessBuilder(command() + "--version").redirectErrorStream(true).start()
        try {
            check(process.waitFor(5, TimeUnit.SECONDS)) { "Cheek version check timed out" }
            val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
            check(process.exitValue() == 0 && output.startsWith("cheek ")) { "Couldn't read the installed Cheek version" }
            output.removePrefix("cheek ")
        } finally {
            if (process.isAlive) process.destroy()
        }
    }

    /**
     * Hamstr speaks Cheek's command line and JSON events, so it only takes releases that keep
     * them: the same major version, and for 0.x the same minor, like Cargo's semver rules.
     */
    fun compatible(version: String): Boolean {
        val v = parts(version) ?: return false
        val own = parts(BuildConfig.CHEEK_VERSION) ?: return false
        return v[0] == own[0] && (own[0] > 0 || v[1] == own[1])
    }

    /** A release worth installing: compatible and newer than what runs now. */
    fun accepts(version: String) = compatible(version) && newer(version, _active.value)

    private fun parts(version: String) = Regex("""(\d+)\.(\d+)\.(\d+)""").matchEntire(version.trim().removePrefix("v"))?.groupValues?.drop(1)?.map { it.toInt() }

    private fun newer(a: String, b: String): Boolean {
        val x = parts(a) ?: return false
        val y = parts(b) ?: return true
        return (0..2).firstOrNull { x[it] != y[it] }?.let { x[it] > y[it] } ?: false
    }

    /** Puts a downloaded release in place once it has proven that it starts on this phone. */
    internal fun install(version: String, bytes: ByteArray) {
        val dir = File(updates, version).apply { deleteRecursively(); mkdirs() }
        val file = File(dir, "cheek")
        file.writeBytes(bytes)
        check(file.setExecutable(true, true)) { "Couldn't mark Cheek $version as executable" }
        val process = ProcessBuilder(loader, file.absolutePath, "--version").redirectErrorStream(true).start()
        try {
            check(process.waitFor(10, TimeUnit.SECONDS)) { "Cheek $version didn't start" }
            val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
            check(process.exitValue() == 0 && output.startsWith("cheek ")) { "Cheek $version doesn't run on this phone" }
        } finally {
            if (process.isAlive) process.destroy()
        }
        File(updates, "current.tmp").apply { writeText(version) }.renameTo(File(updates, "current"))
        downloaded = Installed(version, file)
        _active.value = version
        // Running downloads keep their open file; new ones start the new version.
        updates.listFiles()?.filter { it.isDirectory && it.name != version }?.forEach { it.deleteRecursively() }
    }

    /** What cheek can't do yet goes to yt-dlp: playlists, subtitles, a custom format string. */
    fun handles(task: DownloadTask): Boolean {
        val c = task.choice
        return available && !Engine.playlists(task) && c.mode != Mode.Custom && !(c.mode == Mode.Video && c.subtitles)
    }

    fun destroy(id: String) {
        processes.remove(id)?.destroy()
    }

    /**
     * Resolves [url] without yt-dlp: a direct file, an HLS playlist or a page that names its
     * media. Returns cheek's own JSON, or null when cheek finds nothing there.
     */
    fun resolve(url: String): String? {
        if (!available) return null
        val proc = ProcessBuilder(command() + listOf("-J", "--resolver", "native", "--", url)).start()
        val out = StringBuffer()
        val reader = thread(name = "cheek-resolve") { out.append(proc.inputStream.bufferedReader().readText()) }
        try {
            if (!proc.waitFor(30, TimeUnit.SECONDS)) return null
            reader.join(2000)
            return out.toString().takeIf { proc.exitValue() == 0 && it.isNotBlank() }
        } catch (e: InterruptedException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "cheek couldn't resolve $url", e)
            return null
        } finally {
            if (proc.isAlive) proc.destroy()
        }
    }

    /**
     * Downloads [info]: yt-dlp's JSON, from which cheek picks [format] itself when given, or
     * cheek's own from [resolve]. Progress arrives through [callback] in yt-dlp's own shapes,
     * so the service treats both engines alike.
     */
    fun execute(task: DownloadTask, info: String, format: String?, tempDir: File, outputDir: File, pathFile: File, callback: ((Float, Long, String) -> Unit)?, mediaFile: Boolean = true): YoutubeDLResponse {
        val started = System.currentTimeMillis()
        val work = File(tempDir, task.id).apply { mkdirs() }
        val infoFile = File(work, "cheek-info.json").apply { writeText(info) }
        val c = task.choice
        val cmd = buildList {
            addAll(command())
            addAll(listOf("--info-json", infoFile.absolutePath, "--json"))
            format?.let { addAll(listOf("-f", it)) }
            addAll(listOf("-P", outputDir.absolutePath, "--temp-dir", work.absolutePath, "-o", Engine.outputTemplate(task)))
            // Fewer connections per download when several run at once.
            addAll(listOf("-N", (12 / Prefs.settings.value.parallel.coerceIn(1, 3)).toString()))
            if (!mediaFile) return@buildList
            addAll(listOf("--ffmpeg", ffmpeg.absolutePath))
            // Covers and tags in every file, like yt-dlp's --embed-thumbnail --embed-metadata.
            addAll(listOf("--embed-thumbnail", "--embed-metadata"))
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
                .put("uploader", o.opt("uploader")).put("channel", o.opt("channel")).put("id", o.opt("id"))
            callback?.invoke(0f, -1, Engine.METADATA_PREFIX + meta)
        }

        val proc = ProcessBuilder(cmd).apply { environment()["LD_LIBRARY_PATH"] = libraryPath }.start()
        processes[task.id] = proc
        val err = StringBuffer()
        // Pausing or losing the network kills the process; its closed pipe must not take the app down.
        val errReader = thread(name = "cheek-stderr") { runCatching { proc.errorStream.bufferedReader().forEachLine { err.appendLine(it) } } }
        var failure: String? = null
        var downloaded = 0L
        var lastByteAt = SystemClock.elapsedRealtime()
        var postProcessing = false
        var stalled = false
        val rootInfo = JSONObject(info)
        var currentInfo = rootInfo
        val playlistEntries = rootInfo.optJSONArray("entries")
        try {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Download stopped")
            proc.inputStream.bufferedReader().forEachLine { line ->
                val e = runCatching { JSONObject(line) }.getOrNull() ?: return@forEachLine
                when (e.optString("type")) {
                    "info" -> {
                        // A playlist resolves one song at a time; metadata follows the current song.
                        val entry = playlistEntries?.let { list ->
                            (0 until list.length()).mapNotNull { list.optJSONObject(it) }
                                .firstOrNull { it.optString("id") == e.optString("id") }
                        }
                        currentInfo = JSONObject((entry ?: rootInfo).toString())
                            .put("title", e.optString("title"))
                            .put("id", e.optString("id"))
                            .put("extractor_key", e.optString("extractor"))
                        callback?.invoke(0f, -1, Engine.METADATA_PREFIX + currentInfo)
                        downloaded = 0L
                        lastByteAt = SystemClock.elapsedRealtime()
                        postProcessing = false
                    }
                    "progress" -> {
                        val bytes = e.optLong("downloaded")
                        val now = SystemClock.elapsedRealtime()
                        if (bytes != downloaded) {
                            downloaded = bytes
                            lastByteAt = now
                        } else if (!postProcessing && !stalled && now - lastByteAt >= 90_000) {
                            // Cheek keeps emitting progress even when every fragment is stuck.
                            // Let the existing yt-dlp fallback try this stream instead of
                            // occupying a queue slot indefinitely at zero bytes per second.
                            stalled = true
                            TaskProcesses.stop(work)
                            proc.destroy()
                        }
                        val pct = e.optDouble("percent", 0.0)
                        val eta = if (e.isNull("eta")) -1L else e.optLong("eta", -1)
                        // Phrased like yt-dlp's progress line so the service reads the speed the same way.
                        val line2 = String.format(Locale.ROOT, "[download] %5.1f%% of %s at %s ETA %s", pct, mib(e.optLong("total")), mib(e.optLong("speed")) + "/s", eta)
                        callback?.invoke(pct.toFloat(), eta, line2)
                    }
                    "stage" -> {
                        postProcessing = true
                        callback?.invoke(100f, -1, Engine.STAGE_PREFIX + e.optString("stage"))
                    }
                    "done" -> {
                        val o = currentInfo
                        pathFile.appendText(
                            JSONObject().put("filepath", e.optString("path")).put("title", o.opt("title"))
                                .put("thumbnail", o.opt("thumbnail")).put("duration", o.opt("duration"))
                                .put("playlist_title", if (playlistEntries != null) rootInfo.opt("title") else null).toString() + "\n",
                        )
                    }
                    "error" -> failure = e.optString("message")
                }
            }
            val code = proc.waitFor()
            errReader.join(2000)
            if (stalled) return YoutubeDLResponse(cmd, 1, System.currentTimeMillis() - started, "", "Download stalled without new bytes; use yt-dlp")
            if (code != 0) Log.w(TAG, "cheek exit $code: ${failure ?: err.toString().takeLast(2000)}")
            return YoutubeDLResponse(cmd, code, System.currentTimeMillis() - started, "", failure ?: err.toString())
        } catch (e: Exception) {
            // Destroying the stalled process may close its stdout while it is being read.
            if (stalled && e !is InterruptedException) {
                return YoutubeDLResponse(cmd, 1, System.currentTimeMillis() - started, "", "Download stalled without new bytes; use yt-dlp")
            }
            Log.w(TAG, "cheek stopped", e)
            throw e
        } finally {
            processes.remove(task.id, proc)
            if (proc.isAlive) {
                TaskProcesses.stop(work)
                proc.destroy()
            }
        }
    }

    /** Errors that mean "this one is for yt-dlp", not "the download failed". */
    fun unsupported(response: YoutubeDLResponse) = response.exitCode != 0 &&
        Regex("isn't supported yet|has no URL|use yt-dlp", RegexOption.IGNORE_CASE).containsMatchIn(response.err)

    private fun mib(bytes: Long) = String.format(Locale.ROOT, "%.2fMiB", bytes / 1048576.0)
}
