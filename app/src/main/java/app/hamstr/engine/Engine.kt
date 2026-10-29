package app.hamstr.engine

import app.hamstr.data.DownloadEngine
import app.hamstr.BuildConfig
import app.hamstr.R
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
    private data class Preview(val info: MediaInfo, val json: String, val reusable: Boolean, val native: Boolean = false, val file: Boolean = false)
    private val previews = PreviewCache<Preview>(
        scope = scope,
        weightOf = { it.json.length * 2 },
        cacheable = { it.reusable },
    )
    private data class InfoSnapshot(val info: MediaInfo, val reusable: Boolean)
    // Keep display metadata longer than signed media URLs; downloading still uses previews.
    private val infos = PreviewCache<InfoSnapshot>(
        scope = scope, ttlMillis = 30 * 60_000L, maxEntries = 64, maxWeight = 512 * 1024,
        weightOf = { snapshot -> snapshot.info.let {
            256 + 2 * (it.url.length + it.title.length + (it.thumbnail?.length ?: 0) +
                (it.uploader?.length ?: 0)) + it.heights.size * 24
        } },
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
    private val expiredMedia = Regex("""HTTP (?:Error |status client error \()(401|403|404|410)|(?:URL|signature).*expired|Requested format is not available""", RegexOption.IGNORE_CASE)

    fun start(context: Context) {
        app = context.applicationContext
        outputDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Hamstr")
        tempDir = File(app.cacheDir, "parts").apply { mkdirs() }
        extractorCacheDir = File(app.cacheDir, "yt-dlp").apply { mkdirs() }
        Cheek.init(app)
        CineSrc.init(app)
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

    /** A song found under another link, for one that is locked where it was shared. */
    data class Elsewhere(val url: String, val title: String, val uploader: String?, val durationSec: Int) {
        /** How well it fits the song looked for, 0..1; see [findOnYoutubeMusic]. */
        var fit: Float = 0f
            internal set
    }

    /**
     * Looks a song up on YouTube Music and on YouTube by artist and title, for links that are
     * locked with DRM (Spotify, Apple Music, SoundCloud Go). Every hit is ranked by how well it
     * fits: the length (within a few seconds is the same recording), the words of title and
     * artist, and a penalty for covers, remixes and live takes the original doesn't name.
     * The best come first; [best] is the first one if it fits well enough.
     */
    suspend fun searchElsewhere(title: String, artist: String?, durationSec: Int): List<Elsewhere> = withContext(Dispatchers.IO) {
        awaitReady()
        val who = artist?.removeSuffix(" - Topic")?.takeIf { it.isNotBlank() }
        // "#JerseyClub" and the like are tags on the title, not words of the song's name.
        val name = title.replace(Regex("""\s*#[\p{L}\p{N}_]+"""), "").trim().ifEmpty { title }
        val query = listOfNotNull(who, name).joinToString(" ").trim()
        if (query.isEmpty()) return@withContext emptyList()
        val enc = java.net.URLEncoder.encode(query, "UTF-8")
        val hits = LinkedHashMap<String, Elsewhere>()
        for (url in listOf("https://music.youtube.com/search?q=$enc#songs", "ytsearch8:$query")) {
            val out = try {
                val req = YoutubeDLRequest(url).apply {
                    addOption("--flat-playlist")
                    addOption("--playlist-end", 8)
                    addOption("--socket-timeout", 15)
                    addOption("--cache-dir", extractorCacheDir.absolutePath)
                    addOption("--dump-single-json")
                }
                readRuntime { WarmYtDlp.execute(req) ?: YoutubeDL.getInstance().execute(req) }.out
            } catch (e: CancellationException) { throw e } catch (e: Exception) { Log.d(TAG, "Search failed for $url", e); continue }
            val entries = runCatching { JSONObject(out).optJSONArray("entries") }.getOrNull() ?: continue
            for (i in 0 until entries.length()) {
                val e = entries.optJSONObject(i) ?: continue
                val id = e.optString("id").takeIf { it.isNotBlank() } ?: continue
                if (id in hits) continue
                val link = e.optString("url").takeIf { it.startsWith("http") } ?: "https://music.youtube.com/watch?v=$id"
                hits[id] = Elsewhere(link, e.optString("title"), e.optString("channel").ifBlank { e.optString("uploader") }.ifBlank { null }, e.optDouble("duration", 0.0).toInt())
            }
        }
        hits.values.onEach { it.fit = fit(it, name, who, durationSec) }.sortedByDescending { it.fit }
    }

    /** The best of [searchElsewhere] when it fits well enough to offer on its own. */
    fun best(hits: List<Elsewhere>): Elsewhere? = hits.firstOrNull()?.takeIf { it.fit >= 0.5f }

    private val versionWords = listOf("cover", "remix", "live", "karaoke", "instrumental", "sped up", "slowed", "nightcore", "8d", "reverb", "acoustic", "lyrics video", "piano")

    private fun words(s: String) = s.lowercase().replace(Regex("""[^\p{L}\p{N}]+"""), " ").split(' ').filter { it.length > 1 }.toSet()

    private fun fit(hit: Elsewhere, title: String, artist: String?, durationSec: Int): Float {
        val want = words(title) + words(artist.orEmpty())
        val got = words(hit.title) + words(hit.uploader.orEmpty().removeSuffix(" - Topic"))
        val titleWords = words(title)
        // How much of the song's title (and artist) the hit names.
        val named = if (want.isEmpty()) 0f else want.count { it in got } / want.size.toFloat()
        val titled = if (titleWords.isEmpty()) 0f else titleWords.count { it in got } / titleWords.size.toFloat()
        var score = 0.25f * named + 0.35f * titled
        if (durationSec > 0 && hit.durationSec > 0) {
            val off = kotlin.math.abs(hit.durationSec - durationSec)
            score += when {
                off <= 2 -> 0.4f
                off <= 5 -> 0.3f
                off <= 15 -> 0.1f
                else -> -0.4f
            }
        } else score += 0.15f
        val lower = hit.title.lowercase()
        if (versionWords.any { it in lower && it !in title.lowercase() }) score -= 0.3f
        return score.coerceIn(0f, 1f)
    }

    /** Looks a song up and returns the hit that fits it, if one does. */
    suspend fun findOnYoutubeMusic(title: String, artist: String?, durationSec: Int): Elsewhere? = best(searchElsewhere(title, artist, durationSec))

    /** A stream to listen into a hit before stashing it: its audio URL and the headers it needs. */
    data class Stream(val url: String, val headers: Map<String, String>)

    suspend fun previewStream(url: String): Stream? = withContext(Dispatchers.IO) {
        awaitReady()
        // YouTube turns some guests away ("not a bot"); the other public clients usually play.
        val out = try {
            YoutubeAccess.run(url) { client ->
                val req = YoutubeDLRequest(url).apply {
                    addOption("-f", "bestaudio[ext=m4a]/bestaudio/best")
                    addOption("--no-playlist")
                    addOption("--socket-timeout", 15)
                    addOption("--cache-dir", extractorCacheDir.absolutePath)
                    addOption("--dump-json")
                    if (client != null) addOption("--extractor-args", "youtube:player_client=$client")
                }
                readRuntime { WarmYtDlp.execute(req) ?: YoutubeDL.getInstance().execute(req) }
            }.out
        } catch (e: CancellationException) { throw e } catch (e: Exception) { Log.d(TAG, "No preview for $url", e); return@withContext null }
        val o = runCatching { JSONObject(out.lines().first { it.startsWith("{") }) }.getOrNull() ?: return@withContext null
        val stream = o.optString("url").takeIf { it.startsWith("http") } ?: return@withContext null
        val h = o.optJSONObject("http_headers")
        Stream(stream, h?.keys()?.asSequence()?.associateWith { h.optString(it) }.orEmpty())
    }

    fun cachedInfo(url: String): MediaInfo? = infos.get(url)?.info ?: previews.get(url)?.info

    /** Puts a finished look-up in place, as if [MediaInfo.url] had just been resolved. The debug build's demo mode uses it. */
    fun remember(info: MediaInfo) = infos.put(info.url, InfoSnapshot(info, reusable = true))

    suspend fun info(url: String): MediaInfo = infos.getOrLoad(url) {
        // A playlist's list holds no stream URLs, so it can be shown again later.
        preview(url).let { InfoSnapshot(it.info, it.reusable || it.info.entries != null) }
    }.info

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
        if (GlyphLinks.matches(url) && Prefs.settings.value.engine == DownloadEngine.Cheek && Cheek.available) {
            val text = runInterruptible { Cheek.resolve(url) }
                ?: error(app.getString(R.string.glyph_resolve_failed))
            return nativePreview(url, text) ?: error(app.getString(R.string.glyph_resolve_failed))
        }
        CineSrcTarget.parse(url)?.let { target ->
            val text = CineSrc.resolve(target)
            return Preview(mediaInfo(url, JSONObject(text)), text, reusable = true)
        }
        if (readRuntime { WarmYtDlp.extractor(url) }?.equals("Generic", ignoreCase = true) != false) {
            filePreview(url)?.let { return it }
            if (Prefs.settings.value.engine == DownloadEngine.Cheek && Cheek.available) {
                runInterruptible { Cheek.resolve(url) }?.let { nativePreview(url, it) }?.let { return it }
            }
        }
        val response = YoutubeAccess.run(url) { client ->
            val req = YoutubeDLRequest(url).apply {
                // A video in a playlist is the playlist only when whole playlists are switched on.
                addOption(if (Prefs.settings.value.playlists) "--yes-playlist" else "--no-playlist")
                // A playlist lists its videos without extracting each one; a single video is unaffected.
                addOption("--flat-playlist")
                addOption("--socket-timeout", 15)
                addOption("--cache-dir", extractorCacheDir.absolutePath)
                addOption("--dump-single-json")
                if (client != null) addOption("--extractor-args", "youtube:player_client=$client")
            }
            readRuntime { WarmYtDlp.execute(req) ?: YoutubeDL.getInstance().execute(req) }
        }
        val json = JSONObject(response.out)
        return Preview(
            mediaInfo(url, json), response.out,
            !json.optBoolean("is_live") && !json.optBoolean("is_upcoming") &&
                json.optString("live_status") !in listOf("is_live", "is_upcoming", "post_live") &&
                json.optString("_type", "video") == "video",
        )
    }

    private suspend fun filePreview(url: String): Preview? {
        val file = try { runInterruptible { GenericFile.probe(url) } }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { Log.d(TAG, "File probe failed for $url", e); null } ?: return null
        val json = JSONObject().put("id", url.hashCode().toUInt().toString(16))
            .put("title", file.name.substringBeforeLast('.')).put("ext", file.name.substringAfterLast('.'))
            .put("url", url).put("webpage_url", url).put("extractor_key", "Generic")
            .put("filesize", file.size.takeIf { it > 0 }).toString()
        return Preview(mediaInfo(url, JSONObject(json)), json, reusable = true, file = true)
    }

    private fun nativePreview(url: String, text: String): Preview? {
        val m = runCatching { JSONObject(text) }.getOrNull() ?: return null
        if (m.optString("_type") == "playlist") {
            return Preview(mediaInfo(url, m), text, reusable = true, native = true)
        }
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

    private fun mediaInfo(url: String, v: JSONObject) = MediaJson.info(url, v)

    /** The videos of a flat playlist, in its order. Nested playlists (a channel's tabs) stay links. */
    internal fun playlistEntries(v: JSONObject) = MediaJson.playlistEntries(v)

    internal fun outputTemplate(task: DownloadTask) = Formats.outputTemplate(task)

    /** A version is one more file of one video, even when playlists are switched on, and so is a video picked from a playlist. */
    internal fun playlists(task: DownloadTask) =
        Prefs.settings.value.playlists && task.versionOf == null && task.group == null && CineSrcTarget.parse(task.url) == null && !GlyphLinks.matches(task.url)

    fun pathFile(task: DownloadTask) = File(tempDir, "${task.id}.path")

    fun clearTemporaryFiles(task: DownloadTask) {
        File(tempDir, task.id).deleteRecursively()
        pathFile(task).delete()
    }

    suspend fun execute(task: DownloadTask, callback: ((Float, Long, String) -> Unit)? = null): YoutubeDLResponse = withContext(Dispatchers.IO) {
        awaitReady()
        val cineSrc = CineSrcTarget.parse(task.url) != null
        // Instant downloads have no preview yet. Probe generic links before choosing media options.
        if (!cineSrc && previews.get(task.url) == null && readRuntime { WarmYtDlp.extractor(task.url) }?.equals("Generic", ignoreCase = true) != false) {
            filePreview(task.url)?.let { previews.put(task.url, it) }
        }
        if (cineSrc) preview(task.url) // Instant stash also needs WebView resolution before either engine starts.
        if (Prefs.settings.value.engine == DownloadEngine.Cheek && Cheek.handles(task)) {
            val res = executeCheek(task, callback)
            // Formats cheek can't fetch (DASH fragments, DRM) go to yt-dlp after all.
            if (!Cheek.unsupported(res)) return@withContext res
            Log.i(TAG, "cheek handed ${task.id} back to yt-dlp: ${res.err.lines().firstOrNull()}")
        }
        val initialRequest = request(task)
        try {
            executeYtDlp(task, initialRequest, callback)
        } catch (e: YoutubeDLException) {
            // Some bundled versions do not reliably re-extract expired info-json URLs.
            // Retry once from the webpage, retaining .part files for resumption.
            if (!initialRequest.hasOption("--load-info-json") || !expiredMedia.containsMatchIn(e.message.orEmpty()) || pathFile(task).exists()) throw e
            val file = previews.get(task.url)?.file == true
            previews.invalidate(task.url)
            if (file) {
                // Losing a file preview must not turn a retry into an audio conversion.
                previews.put(task.url, filePreview(task.url) ?: throw e)
            } else if (cineSrc) preview(task.url)
            executeYtDlp(task, request(task, reusePreview = cineSrc), callback)
        }
    }

    private suspend fun executeYtDlp(
        task: DownloadTask,
        initialRequest: YoutubeDLRequest,
        callback: ((Float, Long, String) -> Unit)?,
    ): YoutubeDLResponse = YoutubeAccess.run(task.url, mayRetry = { !pathFile(task).exists() }) { client ->
        val req = if (client == null) initialRequest else {
            // A client change needs fresh stream URLs, rather than the previous info JSON.
            previews.invalidate(task.url)
            request(task, reusePreview = false).apply {
                addOption("--extractor-args", "youtube:player_client=$client")
            }
        }
        readRuntime { YoutubeDL.getInstance().execute(req, task.id, callback) }
    }

    /**
     * The link's preview JSON goes to cheek, which picks the formats from it itself and
     * downloads them, so yt-dlp runs once per link, not once for the sheet and again here.
     */
    private suspend fun executeCheek(task: DownloadTask, callback: ((Float, Long, String) -> Unit)?): YoutubeDLResponse {
        suspend fun download(): YoutubeDLResponse {
            val preview = preview(task.url)
            val format = if (preview.native || preview.file) null else formatSelector(task.choice)
            return runInterruptible {
                Cheek.execute(task, preview.json, format, tempDir, outputDir, pathFile(task), callback, mediaFile = !preview.file)
            }
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

    internal fun formatSelector(c: Choice) = Formats.formatSelector(c)

    fun request(task: DownloadTask, reusePreview: Boolean = true): YoutubeDLRequest {
        val c = task.choice
        val file = previews.get(task.url)?.takeIf { it.file }
        val playlists = playlists(task) && file == null
        // A playlist preview describes just one video, so it cannot replace a playlist request.
        val preview = file ?: if (playlists || !reusePreview) null else previews.get(task.url)?.takeUnless { it.native }
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
            if (file == null) addOption("--embed-metadata")
            addOption("--socket-timeout", 20)
            if (file != null) {
                addOption("-f", "b")
                addOption("--fixup", "never")
                // This is an explicitly recognised file, which may legitimately be an installer.
                addOption("--compat-options", "allow-unsafe-ext")
                return@apply
            }
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
