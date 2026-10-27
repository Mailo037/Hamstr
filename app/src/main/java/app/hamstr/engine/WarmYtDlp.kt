package app.hamstr.engine

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.YoutubeDLResponse
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.Writer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * A yt-dlp that keeps running between links. youtubedl-android starts a new Python for every
 * call, and on a phone the interpreter's start and yt-dlp's imports take a second or more
 * before the first request goes out. Only resolving runs here: downloads keep their own
 * process, so they can report progress and be stopped.
 *
 * It speaks JSON lines with assets/warm_ytdlp.py. When the process itself fails, calls return
 * null and the caller runs yt-dlp the usual way; yt-dlp's own errors throw as they would there.
 */
internal object WarmYtDlp {
    private const val TAG = "HamstrWarm"
    private const val IDLE_MILLIS = 3 * 60_000L
    // After this many processes that never got ready, stay out of the way until the next update.
    private const val MAX_FAILURES = 2
    private lateinit var command: List<String>
    private lateinit var env: Map<String, String>
    private lateinit var extras: List<String>
    private val lock = Any()
    private var running: Running? = null
    @Volatile private var failures = 0
    private val ids = AtomicLong()

    private class Running(val process: Process) {
        val writer: Writer = process.outputStream.bufferedWriter(Charsets.UTF_8)
        val ready = CompletableFuture<Unit>()
        val pending = ConcurrentHashMap<Long, CompletableFuture<JSONObject>>()
        @Volatile var closed = false
        @Volatile var lastUsed = SystemClock.elapsedRealtime()
    }

    /** Uses the Python and yt-dlp that youtubedl-android has unpacked, with its environment. */
    fun init(context: Context) {
        val native = File(context.applicationInfo.nativeLibraryDir)
        val base = File(context.noBackupFilesDir, "youtubedl-android")
        val packages = File(base, "packages")
        val home = File(packages, "python/usr").absolutePath
        val script = File(context.noBackupFilesDir, "hamstr/warm_ytdlp.py")
        val bytes = context.assets.open("warm_ytdlp.py").use { it.readBytes() }
        if (!script.isFile || !script.readBytes().contentEquals(bytes)) {
            script.parentFile?.mkdirs()
            script.writeBytes(bytes)
        }
        command = listOf(File(native, "libpython.so").absolutePath, script.absolutePath, File(base, "yt-dlp/yt-dlp").absolutePath)
        env = mapOf(
            "LD_LIBRARY_PATH" to listOf("python", "ffmpeg", "aria2c").joinToString(":") { File(packages, "$it/usr/lib").absolutePath },
            "SSL_CERT_FILE" to File(packages, "python/usr/etc/tls/cert.pem").absolutePath,
            "PATH" to System.getenv("PATH") + ":" + native.absolutePath,
            "PYTHONHOME" to home,
            "HOME" to home,
            "TMPDIR" to context.cacheDir.absolutePath,
        )
        // What youtubedl-android adds to every call.
        extras = listOf(
            "--js-runtimes", "quickjs:" + File(native, "libqjs.so").absolutePath,
            "--ffmpeg-location", File(native, "libffmpeg.so").absolutePath,
        )
        Executors.newSingleThreadScheduledExecutor { Thread(it, "warm-yt-dlp-idle").apply { isDaemon = true } }
            .scheduleWithFixedDelay(::stopIfIdle, 1, 1, TimeUnit.MINUTES)
    }

    /** Starts the process ahead of the first link. */
    fun warm() {
        thread(name = "warm-yt-dlp-start", isDaemon = true) { runCatching { running() } }
    }

    /**
     * Runs [request] like `YoutubeDL.execute`, minus the process start. Null when the warm
     * process can't take it; a yt-dlp error throws [YoutubeDLException] with its stderr.
     */
    fun execute(request: YoutubeDLRequest): YoutubeDLResponse? {
        val started = System.currentTimeMillis()
        val args = request.buildCommand() + (if (request.hasOption("--cache-dir")) emptyList() else listOf("--no-cache-dir")) + extras
        val reply = call(JSONObject().put("args", JSONArray(args))) ?: return null
        val code = reply.optInt("code", 1)
        val err = reply.optString("err")
        if (code != 0) throw YoutubeDLException(err)
        return YoutubeDLResponse(args, code, System.currentTimeMillis() - started, reply.optString("out"), err)
    }

    /** The yt-dlp extractor for [url], "Generic" when no site extractor knows it; null if unknown. */
    fun extractor(url: String): String? =
        call(JSONObject().put("match", url))?.takeIf { it.optInt("code", 1) == 0 }?.optString("out")?.takeIf { it.isNotBlank() }

    /** Ends the process, for example before yt-dlp is replaced; the next call starts a new one. */
    fun stop() {
        val r = synchronized(lock) { running.also { running = null } }
        failures = 0
        r?.let(::close)
    }

    private fun call(message: JSONObject): JSONObject? {
        val r = running() ?: return null
        val id = ids.incrementAndGet()
        val reply = CompletableFuture<JSONObject>()
        r.pending[id] = reply
        try {
            if (r.closed) return null
            synchronized(r.writer) {
                r.writer.write(message.put("id", id).toString() + "\n")
                r.writer.flush()
            }
            return reply.get()
        } catch (e: InterruptedException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "warm yt-dlp failed; running a fresh one instead", e)
            return null
        } finally {
            r.pending.remove(id)
            r.lastUsed = SystemClock.elapsedRealtime()
        }
    }

    private fun running(): Running? {
        val r = synchronized(lock) { running?.takeIf { !it.closed } ?: start() } ?: return null
        return try {
            r.ready.get(60, TimeUnit.SECONDS)
            r
        } catch (e: InterruptedException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "warm yt-dlp didn't start", e)
            synchronized(lock) { if (running === r) running = null }
            close(r)
            null
        }
    }

    // Called with lock held.
    private fun start(): Running? {
        if (failures >= MAX_FAILURES || !::command.isInitialized) return null
        val process = try {
            ProcessBuilder(command).apply { environment().putAll(env) }.start()
        } catch (e: IOException) {
            failures++
            Log.w(TAG, "couldn't start warm yt-dlp", e)
            return null
        }
        val r = Running(process)
        running = r
        thread(name = "warm-yt-dlp-out", isDaemon = true) { read(r) }
        thread(name = "warm-yt-dlp-err", isDaemon = true) {
            runCatching { process.errorStream.bufferedReader().forEachLine { Log.d(TAG, it) } }
        }
        return r
    }

    private fun read(r: Running) {
        try {
            r.process.inputStream.bufferedReader().forEachLine { line ->
                val message = runCatching { JSONObject(line) }.getOrNull() ?: return@forEachLine
                if (message.optBoolean("ready")) {
                    failures = 0
                    Log.i(TAG, "warm yt-dlp ${message.optString("version")} ready")
                    r.ready.complete(Unit)
                } else {
                    r.pending[message.optLong("id")]?.complete(message)
                }
            }
        } catch (_: IOException) {
        }
        // The process is gone; whoever still waits runs yt-dlp the usual way.
        r.closed = true
        if (!r.ready.isDone) failures++
        val gone = IOException("warm yt-dlp exited")
        r.ready.completeExceptionally(gone)
        r.pending.values.forEach { it.completeExceptionally(gone) }
        synchronized(lock) { if (running === r) running = null }
    }

    private fun stopIfIdle() {
        val idle = synchronized(lock) {
            running?.takeIf { it.pending.isEmpty() && SystemClock.elapsedRealtime() - it.lastUsed > IDLE_MILLIS }?.also { running = null }
        }
        idle?.let(::close)
    }

    private fun close(r: Running) {
        r.closed = true
        // A closed stdin ends the script; a stuck one is stopped.
        runCatching { synchronized(r.writer) { r.writer.close() } }
        if (!runCatching { r.process.waitFor(2, TimeUnit.SECONDS) }.getOrDefault(false)) r.process.destroy()
    }
}
