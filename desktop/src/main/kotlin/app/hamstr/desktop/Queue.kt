package app.hamstr.desktop

import app.hamstr.Log
import app.hamstr.data.Choice
import app.hamstr.data.DownloadEngine
import app.hamstr.data.DownloadControl
import app.hamstr.data.DownloadStore
import app.hamstr.data.DownloadTask
import app.hamstr.data.FileChecks
import app.hamstr.data.FileNames
import app.hamstr.data.Mode
import app.hamstr.data.Prefs
import app.hamstr.data.Stage
import app.hamstr.data.Status
import app.hamstr.engine.EngineState
import app.hamstr.engine.Failures
import app.hamstr.engine.Formats
import app.hamstr.engine.MediaJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * The desktop's download queue, in place of the phone's DownloadService: yt-dlp works out the
 * streams, Cheek fetches them when it can and yt-dlp does the rest. The decisions (formats,
 * file names, reading yt-dlp's JSON) are the shared ones in app.hamstr.engine.
 */
object Queue : DownloadControl {
    private const val TAG = "HamstrQueue"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val processes = ConcurrentHashMap<String, Process>()
    private val _engine = MutableStateFlow<EngineState>(EngineState.Starting)
    val engine: StateFlow<EngineState> = _engine.asStateFlow()

    private const val PROGRESS = "HAMSTR_PROGRESS "
    private val POSTPROCESSOR = Regex("""^\[(\w+)]""")
    private val CHEEK_UNSUPPORTED = Regex("isn't supported yet|has no URL|use yt-dlp", RegexOption.IGNORE_CASE)
    // Seconds before each automatic retry of a broken connection, as on the phone.
    private val BACKOFF = listOf(5, 15, 30, 60, 120)

    private class Run(val code: Int, val err: String)

    fun start() {
        scope.launch {
            // Downloads that were running when the app closed carry on.
            DownloadStore.tasks.value.filter { it.status == Status.Queued && it.stage != null }
                .forEach { t -> DownloadStore.update(t.id, persist = false) { it.copy(stage = null) } }
            boot()
        }
    }

    /** After a failed start: tries the tools once more. */
    fun retryStart() {
        if (_engine.value is EngineState.Broken) scope.launch { boot() }
    }

    private suspend fun boot() {
        _engine.value = EngineState.Starting
        _engine.value = try {
            Tools.install()
            EngineState.Ready(output(listOf(Tools.ytDlp.path, "--version")).trim())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "yt-dlp doesn't start", e)
            EngineState.Broken(e.message ?: e.toString())
        }
        pump()
    }

    private val _updatingYtDlp = MutableStateFlow(false)
    /** True while yt-dlp updates itself. */
    val updatingYtDlp: StateFlow<Boolean> = _updatingYtDlp.asStateFlow()
    private val _ytDlpUpdateError = MutableStateFlow<String?>(null)
    /** Why the last yt-dlp update failed, or null. */
    val ytDlpUpdateError: StateFlow<String?> = _ytDlpUpdateError.asStateFlow()

    /**
     * Pulls the newest stable yt-dlp into the app's own copy; sites change often, so an old build
     * breaks quickly. Runs in the queue's scope, so leaving the page doesn't cut it off halfway.
     */
    fun updateYtDlp() {
        if (_updatingYtDlp.value) return
        _updatingYtDlp.value = true
        _ytDlpUpdateError.value = null
        scope.launch {
            runCatching { output(listOf(Tools.ytDlp.path, "-U", "--update-to", "stable")) }
                .onFailure { Log.w(TAG, "yt-dlp update failed", it); _ytDlpUpdateError.value = it.message ?: it.toString() }
            runCatching { _engine.value = EngineState.Ready(output(listOf(Tools.ytDlp.path, "--version")).trim()) }
            _updatingYtDlp.value = false
        }
    }

    /** Queues a link with the default choice from Settings. */
    fun stash(url: String, choice: Choice = Prefs.settings.value.defaultChoice()) {
        DownloadStore.add(DownloadTask(url = url, title = url, thumbnail = null, source = null, durationSec = 0, choice = choice))
        pump()
    }

    override fun pause(id: String) = stop(id, Status.Paused)

    override fun cancel(id: String) = stop(id, Status.Canceled)

    override fun resume(id: String) {
        DownloadStore.update(id) { it.copy(status = Status.Queued, error = null, stage = null, attempts = 0, retryAt = 0) }
        pump()
    }

    private fun stop(id: String, status: Status) {
        val task = DownloadStore.get(id) ?: return
        if (!task.status.active && !(status == Status.Canceled && task.status == Status.Paused)) return
        DownloadStore.update(id) { it.copy(status = status, speed = "", etaSec = -1, stage = null) }
        processes.remove(id)?.let(::kill)
        jobs.remove(id)?.cancel()
        if (status == Status.Canceled) File(Paths.parts, id).deleteRecursively()
        pump()
    }

    @Synchronized
    private fun pump() {
        if (_engine.value !is EngineState.Ready) return
        val max = Prefs.settings.value.parallel.coerceIn(1, 3)
        val now = System.currentTimeMillis()
        val queued = DownloadStore.tasks.value.filter { it.status == Status.Queued && !jobs.containsKey(it.id) }.reversed() // oldest first
        for (task in queued) {
            if (task.retryAt > now) continue
            if (jobs.size >= max) break
            val job = scope.launch(start = CoroutineStart.LAZY) { download(task) }
            jobs[task.id] = job
            job.invokeOnCompletion {
                jobs.remove(task.id, job)
                scope.launch { pump() }
            }
            job.start()
        }
        // The next retry wakes the queue by itself.
        DownloadStore.tasks.value.filter { it.status == Status.Queued && it.retryAt > now }.minOfOrNull { it.retryAt }?.let { at ->
            scope.launch { delay(at - now + 50); pump() }
        }
    }

    private suspend fun download(task: DownloadTask) {
        DownloadStore.update(task.id) {
            if (it.status == Status.Queued) it.copy(status = Status.Running, error = null, stage = Stage.Resolving, retryAt = 0) else it
        }
        if (DownloadStore.get(task.id)?.status != Status.Running) return
        val work = File(Paths.parts, task.id).apply { mkdirs() }
        val paths = File(work, "paths.jsonl").apply { delete() }
        try {
            val json = output(listOf(
                Tools.ytDlp.path, "--dump-single-json", "--flat-playlist", "--socket-timeout", "15",
                "--cache-dir", Paths.cache.path, if (Prefs.settings.value.playlists) "--yes-playlist" else "--no-playlist", "--", task.url,
            ))
            val v = JSONObject(json)
            val info = MediaJson.info(task.url, v)
            DownloadStore.update(task.id) {
                if (it.status != Status.Running) it else it.copy(
                    title = info.title, thumbnail = info.thumbnail ?: it.thumbnail, source = info.source ?: it.source,
                    durationSec = info.durationSec, uploader = info.uploader ?: it.uploader, mediaKey = it.mediaKey ?: info.key,
                )
            }
            val single = v.optString("_type", "video") == "video"
            val infoFile = File(work, "info.json").apply { writeText(json) }
            Paths.downloads.mkdirs()
            var run: Run? = null
            if (single && Prefs.settings.value.engine == DownloadEngine.Cheek && Tools.hasCheek && cheekHandles(task)) {
                run = cheek(task, infoFile, work, paths)
                // Formats Cheek can't fetch (DASH fragments, DRM) go to yt-dlp after all.
                if (run.code != 0 && CHEEK_UNSUPPORTED.containsMatchIn(run.err)) run = null.also { paths.delete() }
            }
            if (run == null) run = ytDlp(task, if (single) infoFile else null, work, paths)
            if (DownloadStore.get(task.id)?.status != Status.Running) return
            if (run.code != 0) error(run.err.lines().lastOrNull { it.contains("ERROR") }?.substringAfter("ERROR: ")
                ?: run.err.lines().lastOrNull { it.isNotBlank() } ?: "exit ${run.code}")
            finish(task, paths)
            work.deleteRecursively()
        } catch (e: CancellationException) {
            DownloadStore.update(task.id) { if (it.status == Status.Running) it.copy(status = Status.Paused, speed = "", etaSec = -1, stage = null) else it }
            throw e
        } catch (e: Exception) {
            val current = DownloadStore.get(task.id) ?: return
            if (current.status != Status.Running) return
            val message = (e.message ?: e.toString()).take(600)
            Log.w(TAG, "${task.url} failed: $message")
            if (Failures.isNetwork(message) && current.attempts < BACKOFF.size) {
                DownloadStore.update(task.id) {
                    it.copy(status = Status.Queued, stage = Stage.Retrying, attempts = it.attempts + 1,
                        retryAt = System.currentTimeMillis() + BACKOFF[it.attempts] * 1000L, speed = "", etaSec = -1)
                }
            } else {
                DownloadStore.update(task.id) { if (it.status == Status.Running) it.copy(status = Status.Failed, error = message, speed = "", stage = null) else it }
            }
        }
    }

    /** What Cheek can't do yet goes to yt-dlp: subtitles and a custom format string. */
    private fun cheekHandles(task: DownloadTask) = task.choice.mode != Mode.Custom && !(task.choice.mode == Mode.Video && task.choice.subtitles)

    private suspend fun cheek(task: DownloadTask, infoFile: File, work: File, paths: File): Run {
        val c = task.choice
        val cmd = buildList {
            addAll(listOf(Tools.cheek.path, "--info-json", infoFile.path, "--json", "-f", Formats.formatSelector(c)))
            addAll(listOf("-P", Paths.downloads.path, "--temp-dir", work.path, "-o", Formats.outputTemplate(task)))
            // Fewer connections per download when several run at once.
            addAll(listOf("-N", (12 / Prefs.settings.value.parallel.coerceIn(1, 3)).toString()))
            addAll(listOf("--ffmpeg", Tools.ffmpeg.path, "--embed-thumbnail", "--embed-metadata"))
            if (c.mode == Mode.Audio) addAll(listOf("-x", "--audio-format", c.audioFormat)) else addAll(listOf("--merge-output-format", c.container))
        }
        val root = JSONObject(infoFile.readText())
        return exec(task.id, cmd) { line ->
            val e = runCatching { JSONObject(line) }.getOrNull() ?: return@exec
            when (e.optString("type")) {
                "progress" -> progress(task.id, e.optDouble("percent", 0.0).toFloat() / 100f, e.optLong("speed"), if (e.isNull("eta")) -1 else e.optLong("eta", -1))
                "stage" -> stage(task.id, Stage.fromEngine(e.optString("stage")))
                "done" -> paths.appendText(JSONObject().put("filepath", e.optString("path")).put("title", root.opt("title"))
                    .put("thumbnail", root.opt("thumbnail")).put("duration", root.opt("duration")).toString() + "\n")
            }
        }
    }

    private suspend fun ytDlp(task: DownloadTask, infoFile: File?, work: File, paths: File): Run {
        val c = task.choice
        val s = Prefs.settings.value
        val cmd = buildList {
            add(Tools.ytDlp.path)
            if (infoFile != null) addAll(listOf("--load-info-json", infoFile.path))
            addAll(listOf("-P", Paths.downloads.path, "-P", "temp:${work.path}", "-o", Formats.outputTemplate(task)))
            // One JSON line per finished file; a playlist leaves one per entry.
            addAll(listOf("--print-to-file", "after_move:%(.{filepath,title,thumbnail,duration,playlist_title})j", paths.path))
            addAll(listOf("--no-simulate", "--newline", "--progress", "--progress-template",
                "download:$PROGRESS%(progress.downloaded_bytes)s %(progress.total_bytes)s %(progress.total_bytes_estimate)s %(progress.speed)s %(progress.eta)s"))
            addAll(listOf("--cache-dir", Paths.cache.path, "--concurrent-fragments", "4", "--no-mtime", "--socket-timeout", "20"))
            addAll(listOf("--ffmpeg-location", Tools.dir.path, "--embed-metadata"))
            if (!s.playlists) add("--no-playlist")
            when (c.mode) {
                Mode.Audio -> {
                    addAll(listOf("-f", Formats.formatSelector(c), "-x", "--audio-format", c.audioFormat, "--audio-quality", "0"))
                    if (c.audioFormat == "mp3") addAll(listOf("--embed-thumbnail", "--convert-thumbnails", "jpg"))
                }
                Mode.Video -> {
                    addAll(listOf("-f", Formats.formatSelector(c), "--merge-output-format", c.container))
                    if (c.container != "webm") add("--embed-thumbnail")
                    if (c.subtitles) addAll(listOf("--write-subs", "--sub-langs", s.subtitleLanguages().joinToString(",") { "$it.*" }, "--embed-subs"))
                }
                Mode.Custom -> addAll(listOf("-f", Formats.formatSelector(c)))
            }
            if (infoFile == null) addAll(listOf("--", task.url))
        }
        return exec(task.id, cmd) { line ->
            when {
                line.startsWith(PROGRESS) -> {
                    val f = line.removePrefix(PROGRESS).split(' ').map { it.toDoubleOrNull() }
                    val done = f.getOrNull(0) ?: return@exec
                    val total = f.getOrNull(1) ?: f.getOrNull(2)
                    progress(task.id, if (total != null && total > 0) (done / total).toFloat() else 0f, f.getOrNull(3)?.toLong() ?: 0, f.getOrNull(4)?.toLong() ?: -1)
                }
                // yt-dlp's post-processors print their name in brackets: [Merger], [ExtractAudio] …
                line.startsWith("[") -> POSTPROCESSOR.find(line)?.groupValues?.get(1)?.let(Stage::fromEngine)?.let { stage(task.id, it) }
            }
        }
    }

    private val lastProgress = ConcurrentHashMap<String, Long>()

    private fun progress(id: String, fraction: Float, speed: Long, eta: Long) {
        val now = System.currentTimeMillis()
        if (now - (lastProgress[id] ?: 0) < 400) return
        lastProgress[id] = now
        DownloadStore.update(id, persist = false) {
            if (it.status != Status.Running) it else it.copy(
                progress = fraction.coerceIn(0f, 1f).coerceAtLeast(it.progress),
                etaSec = eta, speed = if (speed > 0) speedText(speed) else it.speed,
                // The first bytes end "Resolving"; the post-processors come later.
                stage = if (it.stage == Stage.Resolving && fraction > 0f) null else it.stage,
            )
        }
    }

    private fun stage(id: String, stage: Stage?) {
        stage ?: return
        DownloadStore.update(id, persist = false) { if (it.status == Status.Running) it.copy(stage = stage, speed = "", etaSec = -1) else it }
    }

    private fun speedText(bytesPerSecond: Long) = when {
        bytesPerSecond >= 1 shl 20 -> String.format(Locale.ROOT, "%.1f MB/s", bytesPerSecond / 1048576.0)
        else -> String.format(Locale.ROOT, "%.0f KB/s", bytesPerSecond / 1024.0)
    }

    /** The files a download left, as yt-dlp's after_move print or Cheek's done events wrote them. */
    private fun finish(task: DownloadTask, paths: File) {
        val clean = Prefs.settings.value.cleanNames
        val entries = paths.takeIf { it.exists() }?.readLines().orEmpty().filter { it.isNotBlank() }.mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
            .map { o ->
                val path = o.optString("filepath").takeIf { it.isNotBlank() }?.let { p -> if (clean) FileNames.tidy(File(p)).path else p }
                o.put("filepath", path)
            }
        val main = entries.firstOrNull()
        val file = main?.optString("filepath")?.let(::File)
        val grouped = entries.size > 1
        DownloadStore.update(task.id) {
            if (it.status != Status.Running) it else it.copy(
                status = Status.Done, progress = 1f, filePath = file?.path, sizeBytes = file?.length() ?: 0,
                speed = "", etaSec = -1, finishedAt = System.currentTimeMillis(), stage = null, attempts = 0,
                group = if (grouped) task.id else it.group,
                groupTitle = if (grouped) entries.firstNotNullOfOrNull { e -> e.optString("playlist_title").takeIf { t -> t.isNotBlank() } } ?: it.title else it.groupTitle,
            )
        }
        // A playlist becomes a group: this task keeps the first file, the rest join it.
        val base = DownloadStore.get(task.id) ?: return
        entries.drop(1).forEachIndexed { i, e ->
            val f = File(e.optString("filepath"))
            DownloadStore.add(base.copy(
                id = UUID.randomUUID().toString(), title = e.optString("title").ifBlank { f.nameWithoutExtension },
                thumbnail = e.optString("thumbnail").ifBlank { null }, durationSec = e.optDouble("duration", 0.0).toInt(),
                filePath = f.path, sizeBytes = f.length(), createdAt = base.createdAt + i + 1, mediaKey = null,
            ))
        }
        FileChecks.refresh()
    }

    /** Runs [cmd] for download [id], handing every line of its output to [onLine]. */
    private suspend fun exec(id: String, cmd: List<String>, onLine: (String) -> Unit): Run = runInterruptible(Dispatchers.IO) {
        val proc = ProcessBuilder(cmd).apply { environment()["PYTHONIOENCODING"] = "utf-8" }.start()
        processes[id] = proc
        val err = StringBuffer()
        val errReader = thread(name = "hamstr-stderr") { runCatching { proc.errorStream.bufferedReader().forEachLine { err.appendLine(it) } } }
        try {
            runCatching { proc.inputStream.bufferedReader().forEachLine(onLine) }
            val code = proc.waitFor()
            errReader.join(2000)
            Run(code, err.toString())
        } finally {
            processes.remove(id, proc)
            if (proc.isAlive) kill(proc)
        }
    }

    /** Runs a short command and returns what it printed; fails with its error output. Never on the window's thread. */
    private suspend fun output(cmd: List<String>): String = runInterruptible(Dispatchers.IO) {
        val proc = ProcessBuilder(cmd).apply { environment()["PYTHONIOENCODING"] = "utf-8" }.start()
        val err = StringBuffer()
        val errReader = thread(name = "hamstr-stderr") { runCatching { proc.errorStream.bufferedReader().forEachLine { err.appendLine(it) } } }
        try {
            val out = proc.inputStream.bufferedReader().readText()
            val code = proc.waitFor()
            errReader.join(2000)
            check(code == 0) { err.lines().lastOrNull { it.contains("ERROR") } ?: err.toString().ifBlank { "exit $code" } }
            out
        } finally {
            if (proc.isAlive) kill(proc)
        }
    }

    /** yt-dlp's standalone build starts a child process; both go. */
    private fun kill(proc: Process) {
        proc.descendants().forEach { it.destroyForcibly() }
        proc.destroyForcibly()
    }
}
