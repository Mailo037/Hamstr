package app.hamstr.engine

import app.hamstr.data.DownloadEngine
import app.hamstr.BuildConfig
import app.hamstr.data.Choice
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
import java.net.HttpURLConnection
import java.net.URL
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
    val videoBytes: Map<Int, Long> = emptyMap(), // per height, with the best audio added where needed
    val audioBytes: Long = 0,
    val id: String? = null,
) {
    /** The same video under any of its links: extractor and ID. */
    val key get() = if (id != null && source != null) "$source:$id" else null

    /** Roughly what "up to [height]" weighs, or 0 when the site doesn't say. */
    fun bytesFor(height: Int?): Long {
        val h = if (height == null) heights.firstOrNull() else heights.firstOrNull { it <= height } ?: heights.lastOrNull()
        return h?.let { videoBytes[it] } ?: 0
    }
}

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
    // Replaced when a failed start is tried again.
    @Volatile private var ready = CompletableDeferred<Unit>()
    private lateinit var app: Context
    private val _state = MutableStateFlow<EngineState>(EngineState.Starting)
    val state: StateFlow<EngineState> = _state.asStateFlow()
    private val _updating = MutableStateFlow(false)
    val updating: StateFlow<Boolean> = _updating.asStateFlow()
    private val _versionCheck = MutableStateFlow<EngineVersionCheck>(EngineVersionCheck.Idle)
    val versionCheck: StateFlow<EngineVersionCheck> = _versionCheck.asStateFlow()

    lateinit var outputDir: File
        private set
    private lateinit var tempDir: File
    private lateinit var extractorCacheDir: File
    /** [native]: cheek's own JSON from [Cheek.resolve], not yt-dlp's. */
    private data class Preview(val info: MediaInfo, val json: String, val reusable: Boolean, val native: Boolean = false)
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
    /** Callback lines that carry a [app.hamstr.data.Stage] name instead of yt-dlp output. */
    internal const val STAGE_PREFIX = "HAMSTR_STAGE:"
    private val expiredMedia = Regex("HTTP Error (401|403|404|410)|(?:URL|signature).*expired|Requested format is not available", RegexOption.IGNORE_CASE)

    fun start(context: Context) {
        app = context.applicationContext
        outputDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Hamstr")
        tempDir = File(app.cacheDir, "parts").apply { mkdirs() }
        extractorCacheDir = File(app.cacheDir, "yt-dlp").apply { mkdirs() }
        Cheek.init(app)
        scope.launch {
            try {
                boot()
                // Automatic maintenance should not delay a newly shared download.
                scope.launch {
                    delay(30_000)
                    while (Prefs.settings.value.autoUpdate && DownloadStore.tasks.value.any { it.status.active }) delay(10_000)
                    val s = Prefs.settings.value
                    if (s.autoUpdate && System.currentTimeMillis() - s.lastUpdateCheck > 24 * 3600_000L) update(app)
                    if (s.cheekAutoUpdate && Cheek.available && System.currentTimeMillis() - s.lastCheekCheck > 24 * 3600_000L) CheekUpdates.update()
                }
            } catch (e: Throwable) {
                Log.e(TAG, "init failed", e)
                _state.value = EngineState.Broken(e.message ?: e.toString())
                ready.completeExceptionally(e)
            }
        }
    }

    private fun boot() {
        YoutubeDL.getInstance().init(app)
        FFmpeg.getInstance().init(app)
        runCatching { WarmYtDlp.init(app) }.onFailure { Log.w(TAG, "no warm yt-dlp", it) }
        _state.value = EngineState.Ready(YoutubeDL.getInstance().version(app) ?: "?")
        ready.complete(Unit)
    }

    /** After a failed start: unpacks and starts yt-dlp once more. */
    suspend fun retryStart(): Result<String> = withContext(Dispatchers.IO) {
        if (_state.value !is EngineState.Broken) return@withContext Result.success((_state.value as? EngineState.Ready)?.version ?: "")
        _state.value = EngineState.Starting
        ready = CompletableDeferred()
        runCatching {
            boot()
            (_state.value as EngineState.Ready).version
        }.onFailure { e ->
            Log.e(TAG, "init failed again", e)
            _state.value = EngineState.Broken(e.message ?: e.toString())
            ready.completeExceptionally(e)
        }
    }

    suspend fun awaitReady() = ready.await()

    /** Gets yt-dlp going before a link arrives, when one is likely to come soon. */
    fun warmUp() {
        scope.launch { runCatching { awaitReady(); WarmYtDlp.warm() } }
    }

    /** Compare versions without calling the updater or changing the installed archive. */
    suspend fun checkVersion() = checkVersion { latestStableVersion() }

    internal suspend fun checkVersion(latestVersion: suspend () -> String) = withContext(Dispatchers.IO) { updateMutex.withLock {
        _versionCheck.value = EngineVersionCheck.Checking
        try {
            awaitReady()
            val latest = latestVersion()
            // The wrapper can report '?' before the first update. Ask the installed
            // executable so a fresh installation is compared correctly as well.
            val installed = readRuntime {
                YoutubeDL.getInstance().execute(YoutubeDLRequest(emptyList()).apply { addOption("--version") }).out.trim()
            }
            val available = newerYtDlpVersion(latest, installed)
            _state.value = EngineState.Ready(installed)
            _versionCheck.value = if (available) EngineVersionCheck.Available(latest) else EngineVersionCheck.Current
        } catch (e: CancellationException) {
            _versionCheck.value = EngineVersionCheck.Idle
            throw e
        } catch (e: Exception) {
            _versionCheck.value = EngineVersionCheck.Failed(e.message ?: "Version check failed")
        }
    } }

    private suspend fun latestStableVersion(): String = runInterruptible {
        val connection = URL(YoutubeDL.UpdateChannel._STABLE.apiUrl).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("User-Agent", "Hamstr/${BuildConfig.VERSION_NAME}")
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            check(connection.responseCode == 200) { "Version check failed: HTTP ${connection.responseCode}" }
            val release = connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
            release.getString("tag_name").trim().removePrefix("v")
        } finally { connection.disconnect() }
    }

    /** Pulls the newest stable yt-dlp; sites change often, so an old build breaks quickly. */
    suspend fun update(context: Context): Result<String> = withContext(Dispatchers.IO) { updateMutex.withLock {
        _updating.value = true
        try {
            awaitReady()
            val v = runInterruptible {
                val lock = runtimeLock.writeLock()
                lock.lockInterruptibly()
                try {
                    // The warm process reads from the archive that is about to be replaced.
                    WarmYtDlp.stop()
                    YoutubeDL.getInstance().updateYoutubeDL(context, YoutubeDL.UpdateChannel._STABLE)
                    YoutubeDL.getInstance().version(context) ?: "?"
                } finally { lock.unlock() }
            }
            _state.value = EngineState.Ready(v)
            _versionCheck.value = EngineVersionCheck.Current
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

    suspend fun info(url: String): MediaInfo = preview(url).info

    /** One resolution per link, shared by the sheet and the download that follows it. */
    private suspend fun preview(url: String): Preview = withContext(Dispatchers.IO) {
        awaitReady()
        previews.getOrLoad(url) { previewSlots.withPermit { resolve(url) } }
    }

    /**
     * Links no yt-dlp site extractor knows (files, HLS playlists, plain pages) go to cheek
     * first, which needs no Python at all. Everything else, and what cheek can't read, goes
     * to yt-dlp, warm when it can be.
     */
    private suspend fun resolve(url: String): Preview {
        if (Prefs.settings.value.engine == DownloadEngine.Cheek && Cheek.available && readRuntime { WarmYtDlp.extractor(url) } == "Generic") {
            runInterruptible { Cheek.resolve(url) }?.let { nativePreview(url, it) }?.let { return it }
        }
        val req = YoutubeDLRequest(url).apply {
            addOption("--no-playlist")
            addOption("--socket-timeout", 15)
            addOption("--cache-dir", extractorCacheDir.absolutePath)
            addOption("--dump-single-json")
        }
        val response = readRuntime { WarmYtDlp.execute(req) ?: YoutubeDL.getInstance().execute(req) }
        val json = JSONObject(response.out)
        return Preview(
            mediaInfo(url, json), response.out,
            !json.optBoolean("is_live") && !json.optBoolean("is_upcoming") &&
                json.optString("live_status") !in listOf("is_live", "is_upcoming", "post_live") &&
                json.optString("_type", "video") == "video",
        )
    }

    private fun nativePreview(url: String, text: String): Preview? {
        val m = runCatching { JSONObject(text) }.getOrNull() ?: return null
        val streams = m.optJSONArray("streams")?.let { a -> List(a.length()) { a.getJSONObject(it) } }.orEmpty()
        if (streams.isEmpty()) return null
        fun string(key: String) = if (m.isNull(key)) null else m.optString(key).takeIf { it.isNotBlank() }
        val info = MediaInfo(
            url = string("webpage_url") ?: url,
            title = string("title") ?: url,
            thumbnail = string("thumbnail"),
            source = string("extractor"),
            durationSec = m.optDouble("duration", 0.0).toInt(),
            uploader = string("uploader"),
            heights = streams.map { it.optInt("height") }.filter { it > 0 }.distinct().sortedDescending(),
            approxBytes = streams.sumOf { it.optLong("size") },
            audioBytes = streams.filter { it.optString("kind") == "audio" }.sumOf { it.optLong("size") },
        )
        return Preview(info, text, reusable = true, native = true)
    }

    private fun mediaInfo(url: String, v: JSONObject): MediaInfo {
        fun string(key: String) = if (v.isNull(key)) null else v.optString(key).takeIf { it.isNotBlank() }
        val formats = v.optJSONArray("formats")
        val all = (0 until (formats?.length() ?: 0)).mapNotNull { formats?.optJSONObject(it) }
        // Not "has": JSONObject.has(key) is a member and would win over an extension of that name.
        fun JSONObject.hasCodec(codec: String) = !isNull(codec) && optString(codec) != "none"
        fun JSONObject.bytes() = optLong("filesize").takeIf { it > 0 } ?: optLong("filesize_approx")
        val video = all.filter { it.hasCodec("vcodec") && it.optInt("height") > 0 }
        val heights = video.map { it.optInt("height") }.distinct().sortedDescending()
        val audioBytes = all.filter { !it.hasCodec("vcodec") && it.hasCodec("acodec") }.maxOfOrNull { it.bytes() } ?: 0
        val videoBytes = heights.associateWith { h ->
            video.filter { it.optInt("height") == h && it.bytes() > 0 }
                .maxOfOrNull { it.bytes() + if (it.hasCodec("acodec")) 0 else audioBytes } ?: 0
        }
        return MediaInfo(
            url = string("webpage_url") ?: url,
            title = string("title") ?: string("fulltitle") ?: url,
            thumbnail = string("thumbnail"),
            source = string("extractor_key") ?: string("extractor"),
            durationSec = v.optDouble("duration", 0.0).toInt(),
            uploader = string("uploader") ?: string("channel") ?: string("artist"),
            heights = heights,
            approxBytes = v.optLong("filesize").takeIf { it > 0 } ?: v.optLong("filesize_approx"),
            videoBytes = videoBytes,
            audioBytes = audioBytes,
            id = string("id"),
        )
    }

    /** Another quality of a stashed link gets its own name, so it can't overwrite the first file. */
    internal fun outputTemplate(task: DownloadTask): String {
        val tag = if (task.versionOf == null) "" else " " + when (task.choice.mode) {
            Mode.Audio -> "audio"
            else -> task.choice.height?.let { "${it}p" } ?: "best"
        }
        return "%(title).150B [%(id)s]$tag.%(ext)s"
    }

    /** A version is one more file of one video, even when playlists are switched on. */
    internal fun playlists(task: DownloadTask) = Prefs.settings.value.playlists && task.versionOf == null

    fun pathFile(task: DownloadTask) = File(tempDir, "${task.id}.path")

    fun clearTemporaryFiles(task: DownloadTask) {
        File(tempDir, task.id).deleteRecursively()
        pathFile(task).delete()
    }

    suspend fun execute(task: DownloadTask, callback: ((Float, Long, String) -> Unit)? = null): YoutubeDLResponse = withContext(Dispatchers.IO) {
        awaitReady()
        if (Prefs.settings.value.engine == DownloadEngine.Cheek && Cheek.handles(task)) {
            val res = executeCheek(task, callback)
            // Formats cheek can't fetch (DASH fragments, DRM) go to yt-dlp after all.
            if (!Cheek.unsupported(res)) return@withContext res
            Log.i(TAG, "cheek handed ${task.id} back to yt-dlp: ${res.err.lines().firstOrNull()}")
        }
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

    /**
     * The link's preview JSON goes to cheek, which picks the formats from it itself and
     * downloads them, so yt-dlp runs once per link, not once for the sheet and again here.
     */
    private suspend fun executeCheek(task: DownloadTask, callback: ((Float, Long, String) -> Unit)?): YoutubeDLResponse {
        suspend fun download(): YoutubeDLResponse {
            val preview = preview(task.url)
            val format = if (preview.native) null else formatSelector(task.choice)
            return Cheek.execute(task, preview.json, format, tempDir, outputDir, pathFile(task), callback)
        }
        outputDir.mkdirs()
        val first = download()
        // A preview's stream URLs can expire; resolve once more from the page.
        if (first.exitCode != 0 && expiredMedia.containsMatchIn(first.err) && !pathFile(task).exists()) {
            previews.invalidate(task.url)
            return download()
        }
        return first
    }

    /** The yt-dlp format selector for a choice, shared by both engines. */
    internal fun formatSelector(c: Choice): String = when (c.mode) {
        // Prefer an existing stream in the requested codec: remuxing avoids
        // a lossy, CPU-heavy re-encode. Keep the original fallback for other sites.
        Mode.Audio -> when (c.audioFormat) {
            "m4a" -> "ba[ext=m4a]/ba[acodec^=mp4a]/ba[acodec=aac]/ba[ext=mp4]/ba/b"
            "opus" -> "ba[acodec^=opus]/ba[ext=opus]/ba[ext=ogg]/ba/b"
            "mp3" -> "ba[ext=mp3]/ba/b"
            else -> "ba/b"
        }
        Mode.Video -> c.height.let { h -> if (h != null) "bv*[height<=$h]+ba/b[height<=$h]/bv*+ba/b" else "bv*+ba/b" }
        Mode.Custom -> c.customFormat.ifBlank { "bv*+ba/b" }
    }

    fun request(task: DownloadTask, reusePreview: Boolean = true): YoutubeDLRequest {
        val c = task.choice
        val playlists = playlists(task)
        // A playlist preview describes just one video, so it cannot replace a playlist request.
        val preview = if (playlists || !reusePreview) null else previews.get(task.url)?.takeUnless { it.native }
        val infoFile = preview?.let {
            File(File(tempDir, task.id).apply { mkdirs() }, "preview.json").apply { writeText(it.json) }
        }
        return (if (infoFile == null) YoutubeDLRequest(task.url) else YoutubeDLRequest(emptyList())).apply {
            if (infoFile != null) addOption("--load-info-json", infoFile.absolutePath)
            outputDir.mkdirs()
            addOption("-P", outputDir.absolutePath)
            addOption("-P", "temp:" + File(tempDir, task.id).absolutePath)
            addOption("-o", outputTemplate(task))
            // One JSON line per finished file; a playlist leaves one per entry.
            addCommands(listOf("--print-to-file", "after_move:%(.{filepath,title,thumbnail,duration,playlist_title})j", pathFile(task).absolutePath))
            // Read metadata from this extraction instead of starting a second process first.
            addOption("--print", "before_dl:$METADATA_PREFIX%(.{title,thumbnail,extractor_key,duration,uploader,channel,id})j")
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
                    addOption("-f", formatSelector(c))
                    addOption("-x")
                    addOption("--audio-format", c.audioFormat)
                    addOption("--audio-quality", "0")
                    if (c.audioFormat == "mp3") {
                        addOption("--embed-thumbnail")
                        addOption("--convert-thumbnails", "jpg")
                    }
                }
                Mode.Video -> {
                    addOption("-f", formatSelector(c))
                    addOption("--merge-output-format", c.container)
                    if (c.container != "webm") addOption("--embed-thumbnail")
                    if (c.subtitles) {
                        addOption("--write-subs")
                        addOption("--sub-langs", Prefs.settings.value.subtitleLanguages().joinToString(",") { "$it.*" })
                        addOption("--embed-subs")
                    }
                }
                Mode.Custom -> {
                    addOption("-f", formatSelector(c))
                }
            }
        }
    }
}
