package app.hamstr.engine

import android.content.Context
import android.os.Environment
import android.util.Log
import app.hamstr.data.DownloadTask
import app.hamstr.data.DownloadStore
import app.hamstr.data.Mode
import app.hamstr.data.Prefs
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import java.io.File
import java.util.concurrent.locks.ReentrantReadWriteLock

/** Metadata shown in the share sheet before downloading. */
data class MediaInfo(
    val url: String,
    val title: String,
    val thumbnail: String?,
    val source: String?,
    val durationSec: Int,
    val uploader: String?,
    val heights: List<Int>, // video heights on offer, descending
    val approxBytes: Long,
)

sealed interface EngineState {
    data object Starting : EngineState
    data class Ready(val version: String) : EngineState
    data class Broken(val message: String) : EngineState
}

/**
 * yt-dlp (Python) and ffmpeg, bundled and run on the device by youtubedl-android.
 * Nothing goes through a Hamstr server; the only network traffic is to the media site
 * and, for updates, yt-dlp's GitHub releases.
 */
object Engine {
    private const val TAG = "HamstrEngine"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ready = CompletableDeferred<Unit>()
    private val _state = MutableStateFlow<EngineState>(EngineState.Starting)
    val state: StateFlow<EngineState> = _state.asStateFlow()
    private val _updating = MutableStateFlow(false)
    val updating: StateFlow<Boolean> = _updating.asStateFlow()

    lateinit var outputDir: File
        private set
    private lateinit var tempDir: File
    private lateinit var extractorCacheDir: File
    private data class Preview(val info: MediaInfo, val json: String, val reusable: Boolean)
    private val previews = PreviewCache<Preview>(
        scope = scope,
        weightOf = { it.json.length * 2 },
        cacheable = { it.reusable },
    )
    private val previewSlots = Semaphore(2)
    // The updater replaces the Python archive. Readers can run together, but must
    // finish before it is replaced or imports can fail with a zipimport EOFError.
    private val runtimeLock = ReentrantReadWriteLock(true)
    private val updateMutex = Mutex()

    internal const val METADATA_PREFIX = "HAMSTR_METADATA:"
    private val expiredMedia = Regex("HTTP Error (401|403|404|410)|(?:URL|signature).*expired|Requested format is not available", RegexOption.IGNORE_CASE)

    fun start(context: Context) {
        val app = context.applicationContext
        outputDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Hamstr")
        tempDir = File(app.cacheDir, "parts").apply { mkdirs() }
        extractorCacheDir = File(app.cacheDir, "yt-dlp").apply { mkdirs() }
        scope.launch {
            try {
                YoutubeDL.getInstance().init(app)
                FFmpeg.getInstance().init(app)
                _state.value = EngineState.Ready(YoutubeDL.getInstance().version(app) ?: "?")
                ready.complete(Unit)
                // Automatic maintenance should not delay a newly shared download.
                scope.launch {
                    delay(30_000)
                    while (Prefs.settings.value.autoUpdate && DownloadStore.tasks.value.any { it.status.active }) delay(10_000)
                    val s = Prefs.settings.value
                    if (s.autoUpdate && System.currentTimeMillis() - s.lastUpdateCheck > 24 * 3600_000L) update(app)
                }
            } catch (e: Throwable) {
                Log.e(TAG, "init failed", e)
                _state.value = EngineState.Broken(e.message ?: e.toString())
                ready.completeExceptionally(e)
            }
        }
    }

    suspend fun awaitReady() = ready.await()

    /** Pulls the newest stable yt-dlp; sites change often, so an old build breaks quickly. */
    suspend fun update(context: Context): Result<String> = withContext(Dispatchers.IO) { updateMutex.withLock {
        _updating.value = true
        try {
            awaitReady()
            val v = runInterruptible {
                val lock = runtimeLock.writeLock()
                lock.lockInterruptibly()
                try {
                    YoutubeDL.getInstance().updateYoutubeDL(context, YoutubeDL.UpdateChannel._STABLE)
                    YoutubeDL.getInstance().version(context) ?: "?"
                } finally { lock.unlock() }
            }
            _state.value = EngineState.Ready(v)
            Prefs.edit { it.copy(lastUpdateCheck = System.currentTimeMillis()) }
            Result.success(v)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "update failed", e)
            Result.failure(e)
        } finally {
            _updating.value = false
        }
    } }

    private suspend fun <T> readRuntime(block: () -> T): T = runInterruptible(Dispatchers.IO) {
        val lock = runtimeLock.readLock()
        lock.lockInterruptibly()
        try { block() } finally { lock.unlock() }
    }

    suspend fun info(url: String): MediaInfo = withContext(Dispatchers.IO) {
        awaitReady()
        previews.getOrLoad(url) {
            previewSlots.withPermit {
                val req = YoutubeDLRequest(url).apply {
                    addOption("--no-playlist")
                    addOption("--socket-timeout", 15)
                    addOption("--cache-dir", extractorCacheDir.absolutePath)
                    addOption("--dump-single-json")
                }
                val response = readRuntime { YoutubeDL.getInstance().execute(req) }
                val json = JSONObject(response.out)
                Preview(
                    mediaInfo(url, json), response.out,
                    !json.optBoolean("is_live") && !json.optBoolean("is_upcoming") &&
                        json.optString("live_status") !in listOf("is_live", "is_upcoming", "post_live") &&
                        json.optString("_type", "video") == "video",
                )
            }
        }.info
    }

    private fun mediaInfo(url: String, v: JSONObject): MediaInfo {
        fun string(key: String) = if (v.isNull(key)) null else v.optString(key).takeIf { it.isNotBlank() }
        val formats = v.optJSONArray("formats")
        val heights = (0 until (formats?.length() ?: 0))
            .mapNotNull { formats?.optJSONObject(it) }
            .filter { !it.isNull("vcodec") && it.optString("vcodec") != "none" && it.optInt("height") > 0 }
            .map { it.optInt("height") }
            .distinct()
            .sortedDescending()
        return MediaInfo(
            url = string("webpage_url") ?: url,
            title = string("title") ?: string("fulltitle") ?: url,
            thumbnail = string("thumbnail"),
            source = string("extractor_key") ?: string("extractor"),
            durationSec = v.optDouble("duration", 0.0).toInt(),
            uploader = string("uploader"),
            heights = heights,
            approxBytes = v.optLong("filesize").takeIf { it > 0 } ?: v.optLong("filesize_approx"),
        )
    }

    fun pathFile(task: DownloadTask) = File(tempDir, "${task.id}.path")

    fun clearTemporaryFiles(task: DownloadTask) {
        File(tempDir, task.id).deleteRecursively()
        pathFile(task).delete()
    }

    suspend fun execute(task: DownloadTask, callback: ((Float, Long, String) -> Unit)? = null): YoutubeDLResponse = withContext(Dispatchers.IO) {
        awaitReady()
        val initialRequest = request(task)
        try {
            readRuntime { YoutubeDL.getInstance().execute(initialRequest, task.id, callback) }
        } catch (e: YoutubeDLException) {
            // Some bundled versions do not reliably re-extract expired info-json URLs.
            // Retry once from the webpage, retaining .part files for resumption.
            if (!initialRequest.hasOption("--load-info-json") || !expiredMedia.containsMatchIn(e.message.orEmpty()) || pathFile(task).exists()) throw e
            previews.invalidate(task.url)
            readRuntime { YoutubeDL.getInstance().execute(request(task, reusePreview = false), task.id, callback) }
        }
    }

    fun request(task: DownloadTask, reusePreview: Boolean = true): YoutubeDLRequest {
        val c = task.choice
        val playlists = Prefs.settings.value.playlists
        // A playlist preview describes just one video, so it cannot replace a playlist request.
        val preview = if (playlists || !reusePreview) null else previews.get(task.url)
        val infoFile = preview?.let {
            File(File(tempDir, task.id).apply { mkdirs() }, "preview.json").apply { writeText(it.json) }
        }
        return (if (infoFile == null) YoutubeDLRequest(task.url) else YoutubeDLRequest(emptyList())).apply {
            if (infoFile != null) addOption("--load-info-json", infoFile.absolutePath)
            outputDir.mkdirs()
            addOption("-P", outputDir.absolutePath)
            addOption("-P", "temp:" + File(tempDir, task.id).absolutePath)
            addOption("-o", "%(title).150B [%(id)s].%(ext)s")
            // One JSON line per finished file; a playlist leaves one per entry.
            addCommands(listOf("--print-to-file", "after_move:%(.{filepath,title,thumbnail,duration,playlist_title})j", pathFile(task).absolutePath))
            // Read metadata from this extraction instead of starting a second process first.
            addOption("--print", "before_dl:$METADATA_PREFIX%(.{title,thumbnail,extractor_key,duration})j")
            addOption("--no-simulate")
            addOption("--progress")
            addOption("--newline")
            addOption("--progress-delta", "0.5")
            addOption("--cache-dir", extractorCacheDir.absolutePath)
            // Bound total fragment workers when several Python processes run on a phone.
            addOption("--concurrent-fragments", (8 / Prefs.settings.value.parallel.coerceIn(1, 3)).coerceIn(2, 4))
            addOption("--buffer-size", "256K")
            if (!playlists) addOption("--no-playlist")
            addOption("--no-mtime")
            addOption("--embed-metadata")
            addOption("--socket-timeout", 20)
            when (c.mode) {
                Mode.Audio -> {
                    // Prefer an existing stream in the requested codec: remuxing avoids
                    // a lossy, CPU-heavy re-encode. Keep the original fallback for other sites.
                    addOption("-f", when (c.audioFormat) {
                        "m4a" -> "ba[ext=m4a]/ba[acodec^=mp4a]/ba[acodec=aac]/ba[ext=mp4]/ba/b"
                        "opus" -> "ba[acodec^=opus]/ba[ext=opus]/ba[ext=ogg]/ba/b"
                        "mp3" -> "ba[ext=mp3]/ba/b"
                        else -> "ba/b"
                    })
                    addOption("-x")
                    addOption("--audio-format", c.audioFormat)
                    addOption("--audio-quality", "0")
                    if (c.audioFormat == "mp3") {
                        addOption("--embed-thumbnail")
                        addOption("--convert-thumbnails", "jpg")
                    }
                }
                Mode.Video -> {
                    val h = c.height
                    addOption("-f", if (h != null) "bv*[height<=$h]+ba/b[height<=$h]/bv*+ba/b" else "bv*+ba/b")
                    addOption("--merge-output-format", c.container)
                    if (c.container != "webm") addOption("--embed-thumbnail")
                    if (c.subtitles) {
                        addOption("--write-subs")
                        addOption("--sub-langs", "de.*,en.*")
                        addOption("--embed-subs")
                    }
                }
                Mode.Custom -> {
                    addOption("-f", c.customFormat.ifBlank { "bv*+ba/b" })
                }
            }
        }
    }
}
