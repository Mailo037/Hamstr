package app.hamstr.engine

import app.hamstr.ui.getString
import app.hamstr.ui.stageString
import app.hamstr.data.DownloadControl
import app.hamstr.data.Latest
import app.hamstr.data.Throttle
import java.util.UUID
import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.job.JobParameters
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.MimeTypeMap
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import app.hamstr.MainActivity
import app.hamstr.R
import android.util.Log
import app.hamstr.data.Choice
import app.hamstr.data.DownloadFiles
import app.hamstr.data.DownloadStore
import app.hamstr.data.DownloadTask
import app.hamstr.data.Mode
import app.hamstr.data.Downloads
import app.hamstr.data.Prefs
import app.hamstr.data.Schedule
import app.hamstr.data.Stage
import app.hamstr.data.Status
import app.hamstr.data.Subscriptions
import android.content.BroadcastReceiver
import android.content.IntentFilter
import kotlinx.coroutines.MainScope
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.resume

/**
 * Runs the download queue with one progress notification: as a foreground service, or, on
 * Android 14 and later when the user started the downloads in Hamstr, held by a
 * user-initiated data transfer job ([DownloadJob]), which Android doesn't cut off after six
 * hours a day. Pause = stop the yt-dlp process (its .part file stays); resume runs it again.
 * Without a usable network, queued downloads wait and start on their own once it's back;
 * a connection that breaks mid-download is retried a few times with growing pauses. What the
 * schedule holds (Schedule: the night, the charger) waits too; for the night the service stops
 * and an alarm (Wakeups) brings it back, which also looks at followed channels (Subscriptions).
 */
class DownloadService : Service() {
    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(app.hamstr.AppLanguage.wrap(base))

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = mutableMapOf<String, Job>()
    private val stopping = mutableSetOf<String>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val retryTick = Runnable { pump() }
    private var lastStartId = 0
    private var lastNotify = 0L
    // Playlist files still being put in the media index; the service waits for them before it stops.
    private val indexing = java.util.concurrent.atomic.AtomicInteger()
    // Playlists' files waiting for the media index: one worker takes them in turn, so many playlists
    // finishing at once don't each start their own.
    private val toIndex = kotlinx.coroutines.channels.Channel<List<DownloadTask>>(kotlinx.coroutines.channels.Channel.UNLIMITED)
    // What the progress notification shows now (and for whom: the service or a job); the same isn't built or sent twice.
    private val shown = Latest<Pair<DownloadJob?, ProgressCard>>()
    // Set once Android's daily limit for background downloads ran out, until the next start.
    @Volatile private var halted = false
    // The transfer job that holds the queue now; the service then runs bound, without a foreground service.
    @Volatile private var host: DownloadJob? = null
    // Followed channels are being looked at; the service stays until the new videos are queued.
    @Volatile private var lookingAtFeeds = false

    /** Plugged in, the downloads that wait for the charger go; unplugged, running ones wait again. */
    private val power = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!Prefs.settings.value.chargingOnly) return
            mainHandler.post { if (intent.action == Intent.ACTION_POWER_CONNECTED) wake() else holdForCharger() }
        }
    }

    /** What the job binds to. */
    inner class Local : Binder() {
        val service get() = this@DownloadService
    }

    override fun onBind(intent: Intent?): IBinder = Local()

    override fun onCreate() {
        super.onCreate()
        current = this
        // Network changes (and the Wi-Fi-only switch) start waiting downloads or hold running ones.
        scope.launch {
            combine(Network.state, Prefs.settings) { _, s -> s.wifiOnly to Network.allows(s.wifiOnly) }
                .distinctUntilChanged()
                .drop(1)
                .collect { (_, allowed) -> mainHandler.post { if (!allowed) holdRunning() else wake() } }
        }
        scope.launch {
            for (files in toIndex) {
                try {
                    index(files)
                } finally {
                    indexing.decrementAndGet()
                    finishIfIdle()
                }
            }
        }
        ContextCompat.registerReceiver(
            this, power, IntentFilter().apply { addAction(Intent.ACTION_POWER_CONNECTED); addAction(Intent.ACTION_POWER_DISCONNECTED) },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        halted = false
        if (host == null) goForeground()
        handle(intent?.action, intent?.getStringExtra(EXTRA_ID))
        // Held by a transfer job the service runs bound; this start only carried the command.
        if (host != null) stopSelf(startId)
        return START_NOT_STICKY
    }

    /** A command from the notification, the app, or a transfer job taking over. On the main thread. */
    internal fun handle(action: String?, id: String?) {
        when (action) {
            ACTION_PAUSE -> id?.let { stop(it, Status.Paused) }
            ACTION_PAUSE_ALL -> synchronized(this) { jobs.keys.toList() }.forEach { stop(it, Status.Paused) }
            ACTION_CANCEL -> id?.let { stop(it, Status.Canceled) }
            ACTION_RESUME -> id?.let { DownloadStore.update(it, block = ::resumed) }
            ACTION_START -> DownloadStore.updateAll(persist = false, which = { it.status == Status.Queued && it.stage == Stage.PausedBySystem }) { it.copy(stage = null) }
            // The alarm, a changed schedule or "Look now": held downloads ask the schedule again.
            ACTION_WAKE -> {
                DownloadStore.updateAll(persist = false, which = { it.status == Status.Queued && it.stage?.held == true }) { it.copy(stage = null) }
                lookAtFeeds(force = id == FORCE)
            }
        }
        pump()
    }

    /** Looks at the followed channels that are due ([force]: all of them) and queues what is new. */
    private fun lookAtFeeds(force: Boolean) {
        if (lookingAtFeeds || !(force || Subscriptions.due()) || !Network.state.value.online) return
        lookingAtFeeds = true
        scope.launch {
            try {
                notifyProgress(force = true)
                Subscriptions.check({ Engine.feed(it) }, force) { tasks -> DownloadStore.addAll(tasks) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("HamstrService", "Could not look at the followed channels", e)
            } finally {
                lookingAtFeeds = false
                mainHandler.post { pump() }
            }
        }
    }

    /** A transfer job took the queue over: from now on no foreground service, and no daily limit. */
    internal fun attach(job: DownloadJob) {
        host = job
        halted = false
        // A foreground service from before (downloads started in the background) can end; the job's notification stays.
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
        mainHandler.post { handle(ACTION_START, null) }
    }

    internal fun detach(job: DownloadJob) {
        if (host === job) host = null
    }

    /**
     * Android ended the job: the network went, or the user stopped Hamstr in the task
     * manager. Running downloads stop and wait; true asks Android to start the job again later.
     */
    internal fun jobStopped(job: DownloadJob, reason: Int): Boolean {
        if (host !== job) return false
        val byUser = Build.VERSION.SDK_INT >= 31 && reason == JobParameters.STOP_REASON_USER
        val stage = when {
            byUser -> null
            !Network.state.value.online -> Stage.WaitingForNetwork
            else -> Stage.PausedBySystem
        }
        halted = true
        synchronized(this) { jobs.keys.toList() }.forEach { stop(it, if (byUser) Status.Paused else Status.Queued, stage) }
        mainHandler.removeCallbacks(retryTick)
        host = null
        return !byUser && DownloadStore.tasks.value.any { it.status.active }
    }

    private fun goForeground() {
        shown.forget()
        val n = progressNotification()
        ServiceCompat.startForeground(
            this, NOTIF_PROGRESS, n,
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
    }

    @Synchronized
    private fun pump() {
        if (!scope.isActive || halted) return
        val s = Prefs.settings.value
        val max = s.parallel.coerceIn(1, 3)
        val now = System.currentTimeMillis()
        val waiting = when {
            Network.allows(s.wifiOnly) -> null
            Network.state.value.online -> Stage.WaitingForWifi
            else -> Stage.WaitingForNetwork
        }
        val charging = Wakeups.charging(this)
        val queued = DownloadStore.tasks.value.filter { it.status == Status.Queued && it.id !in jobs && it.id !in stopping }.reversed() // oldest first
        // Set in one step after the loop: a held playlist would otherwise copy the list once per video.
        val holds = HashMap<String, Stage>()
        val hold = Schedule.holds(s, charging, now)
        for (task in queued) {
            // The schedule first: waiting for the night says more than waiting for Wi-Fi.
            val stage = hold(task) ?: waiting
            if (stage != null) {
                if (task.stage != stage) holds[task.id] = stage
                continue
            }
            if (task.retryAt > now) continue
            if (jobs.size >= max) break
            // Lazy start: the job must be in the map before run() can finish and remove it.
            val job = scope.launch(start = CoroutineStart.LAZY) { run(task) }
            jobs[task.id] = job
            // Completion also runs when cancellation prevents the coroutine body
            // from starting, so a quick Cancel cannot leave a queue slot occupied.
            job.invokeOnCompletion {
                synchronized(this) { if (jobs[task.id] === job) jobs.remove(task.id) }
                if (scope.isActive) scope.launch {
                    if (DownloadStore.get(task.id)?.status == Status.Canceled) Engine.clearTemporaryFiles(task)
                    notifyProgress(force = true)
                    pump()
                }
            }
            job.start()
        }
        if (holds.isNotEmpty()) DownloadStore.updateAll(persist = false, which = { it.id in holds && it.status == Status.Queued }) { it.copy(stage = holds.getValue(it.id)) }
        // The next retry wakes the queue by itself.
        mainHandler.removeCallbacks(retryTick)
        if (waiting == null) {
            DownloadStore.tasks.value.filter { it.status == Status.Queued && it.retryAt > now }.minOfOrNull { it.retryAt }
                ?.let { mainHandler.postDelayed(retryTick, it - now + 50) }
        }
        notifyProgress(force = true)
        Wakeups.plan(this)
        if (jobs.isEmpty() && stopping.isEmpty()) finishIfIdle()
    }

    /**
     * A playlist's Pause: what only waits is paused in one step, the running ones are stopped, and
     * the queue looks once (its notification, the alarm, whether the service can end). Main thread.
     */
    internal fun pauseMany(ids: Set<String>) {
        // Under pump's lock: none of them starts between being looked at and being paused.
        val busy = synchronized(this) { DownloadStore.pauseWaiting(ids, jobs.keys.toHashSet()) }
        busy.forEach { stop(it, Status.Paused) }
        pump()
    }

    /** The network is back (or Wi-Fi is): waiting and retrying downloads go at once. */
    private fun wake() {
        DownloadStore.updateAll(persist = false, which = { it.status == Status.Queued && (it.stage?.waiting == true && it.stage != Stage.PausedBySystem) }) {
            it.copy(stage = null, retryAt = 0, attempts = 0)
        }
        pump()
    }

    /** Off the charger with "Only while charging": running downloads stop and wait for it again. */
    private fun holdForCharger() {
        synchronized(this) { jobs.keys.toList() }.filter { DownloadStore.get(it)?.anytime != true }
            .forEach { stop(it, Status.Queued, Stage.WaitingForCharger) }
        pump()
    }

    /** The network went away: running downloads stop and wait instead of failing slowly. */
    private fun holdRunning() {
        val waiting = if (Network.state.value.online) Stage.WaitingForWifi else Stage.WaitingForNetwork
        synchronized(this) { jobs.keys.toList() }.forEach { stop(it, Status.Queued, waiting) }
        pump()
    }

    private suspend fun run(task: DownloadTask) {
        try {
            currentCoroutineContext().ensureActive()
            if (DownloadStore.get(task.id)?.status != Status.Queued) return
            DownloadStore.update(task.id) {
                if (it.status == Status.Queued) it.copy(status = Status.Running, error = null, stage = Stage.Resolving, retryAt = 0) else it
            }
            if (DownloadStore.get(task.id)?.status != Status.Running) return
            notifyProgress(force = true)
            Engine.awaitReady()
            currentCoroutineContext().ensureActive()
            val pathFile = Engine.pathFile(task).also { it.delete() }
            val runJob = currentCoroutineContext()[Job]!!
            // How often this download's progress, and its stage's, reach the list.
            val pace = Throttle(PROGRESS_MS)
            val stagePace = Throttle(PROGRESS_MS)
            val res = Engine.execute(task) { progress, eta, line ->
                if (runJob.isActive) {
                    when {
                        line.startsWith(Engine.METADATA_PREFIX) -> updateMetadata(task.id, line.removePrefix(Engine.METADATA_PREFIX))
                        // FFmpeg reports many times a second; the bar moves at the same pace as the download's.
                        line.startsWith(Engine.STAGE_PROGRESS_PREFIX) -> {
                            if (stagePace.ready(task.id)) line.removePrefix(Engine.STAGE_PROGRESS_PREFIX).toFloatOrNull()?.let { pct ->
                                DownloadStore.tick(task.id) { if (it.stage != null) it.copy(stageProgress = (pct / 100f).coerceIn(0f, 1f)) else it }
                            }
                        }
                        line.startsWith(Engine.STAGE_PREFIX) -> {
                            // A new stage shows its first progress at once, however short it is.
                            stagePace.reset(task.id)
                            setStage(task.id, Stage.fromEngine(line.removePrefix(Engine.STAGE_PREFIX)))
                        }
                        line.startsWith("[download]") -> {
                            if (pace.ready(task.id)) {
                                val speed = SPEED.find(line)?.groupValues?.get(1)?.let(::prettySpeed) ?: ""
                                DownloadStore.tick(task.id) {
                                    it.copy(
                                        progress = (progress / 100f).coerceIn(0f, 1f).coerceAtLeast(it.progress),
                                        etaSec = eta, speed = speed.ifEmpty { it.speed },
                                        // The first bytes end "Resolving"; yt-dlp has no later stage before its post-processors.
                                        stage = if (it.stage == Stage.Resolving && progress > 0f) null else it.stage,
                                    )
                                }
                                notifyProgress()
                            }
                        }
                        // yt-dlp's post-processors print their name in brackets: [Merger], [ExtractAudio] …
                        line.startsWith("[") -> POSTPROCESSOR.find(line)?.groupValues?.get(1)?.let(Stage::fromEngine)?.let {
                            stagePace.reset(task.id)
                            setStage(task.id, it)
                        }
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            if (res.exitCode != 0) error(res.err.lines().lastOrNull { it.isNotBlank() } ?: "yt-dlp exit ${res.exitCode}")
            val clean = Prefs.settings.value.cleanNames
            val entries = pathFile.takeIf { it.exists() }?.readLines().orEmpty().filter { it.isNotBlank() }.map(::finishedEntry)
                .map { e -> if (clean && e.path.isNotEmpty()) e.copy(path = DownloadFiles.tidy(File(e.path)).absolutePath) else e }
            val main = entries.firstOrNull()
            val path = main?.path
            val file = path?.let(::File)
            val uri = file?.let { scan(it) }
            currentCoroutineContext().ensureActive()
            // A playlist becomes a group: this task keeps the first entry, the rest join it.
            val grouped = entries.size > 1
            val groupTitle = if (grouped) entries.firstNotNullOfOrNull { it.playlist } ?: task.title else null
            val height = file?.let(DownloadFiles::probeHeight) ?: 0
            // Asked for a video, got only sound (SoundCloud, Bandcamp …): it is a song.
            val soundOnly = height == 0 && task.choice.mode != Mode.Audio && file != null && DownloadFiles.hasVideo(file) == false
            DownloadStore.update(task.id) {
                if (it.status != Status.Running) it else (if (soundOnly) DownloadFiles.asAudio(it.copy(filePath = path)) else it).copy(status = Status.Done, progress = 1f, filePath = path, contentUri = uri?.toString(),
                    sizeBytes = file?.length() ?: 0, videoHeight = height, speed = "", etaSec = -1, finishedAt = System.currentTimeMillis(),
                    title = if (grouped) main?.title ?: it.title else it.title,
                    thumbnail = if (grouped) main?.thumbnail ?: it.thumbnail else it.thumbnail,
                    group = if (grouped) task.id else it.group, groupTitle = if (grouped) groupTitle else it.groupTitle, stage = null, attempts = 0)
            }
            if (grouped && DownloadStore.get(task.id)?.status == Status.Done) {
                val base = DownloadStore.get(task.id) ?: task
                // The files are in: their entries go on the list (and to disk) at once, in one step.
                // The media index can take seconds a file and is optional: it fills in their content
                // URIs beside the queue (the next download needn't wait for it), a few at a time; the
                // service stays until it is done, and a stop meanwhile loses only the URIs not in yet.
                val rest = entries.drop(1).mapIndexed { i, e ->
                    val f = File(e.path)
                    base.copy(
                        id = UUID.randomUUID().toString(), title = e.title ?: f.nameWithoutExtension, thumbnail = e.thumbnail,
                        durationSec = e.duration, filePath = e.path, contentUri = null, sizeBytes = f.length(),
                        createdAt = base.createdAt + i + 1, mediaKey = null,
                    )
                }
                DownloadStore.addAll(rest)
                indexing.incrementAndGet()
                toIndex.trySend(rest)
            }
            if (DownloadStore.get(task.id)?.status == Status.Done) {
                Engine.clearTemporaryFiles(task)
                notifyDone(task.id)
                replaceOld(task.id)
                openWhenDone(task.id)
            }
        } catch (e: CancellationException) {
            DownloadStore.update(task.id) {
                if (it.status == Status.Running) it.copy(status = Status.Paused, speed = "", etaSec = -1, stage = null) else it
            }
            throw e
        } catch (e: YoutubeDL.CanceledException) {
            // Status was already set to Paused/Canceled by stop().
        } catch (e: Throwable) {
            val current = DownloadStore.get(task.id) ?: return
            if (current.status != Status.Running) return
            val message = cleanError(e.message)
            val s = Prefs.settings.value
            when {
                // Offline now: no attempt used up, it simply waits for the network.
                !Network.allows(s.wifiOnly) -> DownloadStore.update(task.id) {
                    it.copy(status = Status.Queued, stage = if (Network.state.value.online) Stage.WaitingForWifi else Stage.WaitingForNetwork, speed = "", etaSec = -1)
                }
                Failures.isNetwork(message) && current.attempts < BACKOFF.size -> DownloadStore.update(task.id) {
                    it.copy(status = Status.Queued, stage = Stage.Retrying, attempts = it.attempts + 1,
                        retryAt = System.currentTimeMillis() + BACKOFF[it.attempts] * 1000L, speed = "", etaSec = -1)
                }
                else -> {
                    DownloadStore.update(task.id) { if (it.status == Status.Running) it.copy(status = Status.Failed, error = message, speed = "", stage = null) else it }
                    if (DownloadStore.get(task.id)?.status == Status.Failed) notifyFailed(task.id)
                }
            }
        }
    }

    /** Puts a playlist's files in the media index and their content URIs on the list, eight at a time. */
    private suspend fun index(files: List<DownloadTask>) {
        val paths = files.associate { it.id to it.filePath }
        for (chunk in files.chunked(8)) {
            val found = scanAll(chunk.map { File(it.filePath!!) })
            val batch = chunk.mapNotNull { t -> found[File(t.filePath!!).absolutePath]?.let { t.id to it.toString() } }.toMap()
            if (batch.isEmpty()) continue
            DownloadStore.updateAll(which = { t -> t.contentUri == null && batch[t.id] != null && paths[t.id] == t.filePath }) {
                it.copy(contentUri = batch.getValue(it.id))
            }
        }
    }

    private fun setStage(id: String, stage: Stage?) {
        stage ?: return
        DownloadStore.update(id, persist = false) { if (it.status == Status.Running) it.copy(stage = stage, stageProgress = -1f, speed = "", etaSec = -1) else it }
        notifyProgress(force = true)
    }

    /** "Replace" drops the old quality only once the new file is safely in. */
    private suspend fun replaceOld(id: String) {
        val new = DownloadStore.get(id) ?: return
        val old = new.replaces?.let(DownloadStore::get) ?: return
        DownloadStore.update(id) { it.copy(replaces = null) }
        if (old.status != Status.Done || old.filePath == new.filePath) return
        DownloadFiles.replace(this, old, new).onFailure { Log.w("HamstrService", "Could not remove the replaced file", it) }
    }

    private data class Finished(val path: String, val title: String?, val thumbnail: String?, val duration: Int, val playlist: String?)

    private fun finishedEntry(line: String): Finished {
        val o = runCatching { JSONObject(line) }.getOrNull() ?: return Finished(line.trim(), null, null, 0, null)
        fun string(key: String) = if (o.isNull(key)) null else o.optString(key).takeIf { it.isNotBlank() }
        return Finished(string("filepath") ?: "", string("title"), string("thumbnail"), o.optDouble("duration", 0.0).toInt(), string("playlist_title"))
    }

    private fun updateMetadata(id: String, text: String) {
        val metadata = runCatching { JSONObject(text) }.getOrNull() ?: return
        fun string(key: String) = if (metadata.isNull(key)) null else metadata.optString(key).takeIf { it.isNotBlank() }
        val extractor = string("extractor_key")
        val key = string("id")?.let { mediaId -> extractor?.let { "$it:$mediaId" } }
        DownloadStore.update(id) {
            if (it.status != Status.Running) it else it.copy(
                title = string("title") ?: it.title,
                thumbnail = string("thumbnail") ?: it.thumbnail,
                source = extractor ?: it.source,
                durationSec = metadata.optDouble("duration", it.durationSec.toDouble()).toInt(),
                uploader = string("uploader") ?: string("channel") ?: it.uploader,
                mediaKey = it.mediaKey ?: key,
                channelUrl = string("channel_url") ?: string("uploader_url") ?: it.channelUrl,
            )
        }
        notifyProgress(force = true)
    }

    private fun stop(id: String, status: Status, stage: Stage? = null) {
        val task = DownloadStore.get(id) ?: return
        if (!task.status.active && !(status == Status.Canceled && task.status == Status.Paused)) return
        DownloadStore.update(id) {
            if (it.status.active || status == Status.Canceled && it.status == Status.Paused)
                it.copy(status = status, speed = "", etaSec = -1, stage = stage, retryAt = 0)
            else it
        }
        if (DownloadStore.get(id)?.status != status) return
        val job = synchronized(this) {
            if (!stopping.add(id)) return
            jobs[id]
        }
        job?.cancel()
        scope.launch {
            try {
                TaskProcesses.stop(File(cacheDir, "parts/$id"))
                YoutubeDL.getInstance().destroyProcessById(id)
                Cheek.destroy(id)
                if (DownloadStore.get(id)?.status == Status.Canceled && job == null) Engine.clearTemporaryFiles(task)
            } finally {
                synchronized(this@DownloadService) { stopping.remove(id) }
                pump()
            }
        }
        notifyProgress(force = true)
    }

    private fun finishIfIdle() {
        // Serialize shutdown with incoming commands. An old worker must not stop a
        // newly started foreground service before its start command is handled.
        mainHandler.post {
            synchronized(this) {
                // What waits for the night has the alarm to come back with; no service sits there for hours.
                val idle = scope.isActive && jobs.isEmpty() && stopping.isEmpty() && !lookingAtFeeds && indexing.get() == 0 &&
                    DownloadStore.tasks.value.none { it.status == Status.Queued && it.stage != Stage.PausedBySystem && it.stage != Stage.WaitingForNight }
                val job = host
                when {
                    !idle -> {}
                    // The job ends and lets go of the service, which then stops by itself.
                    job != null -> job.done()
                    stopSelfResult(lastStartId) -> {
                        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        shown.forget()
                    }
                }
            }
        }
    }

    /**
     * Android 15 gives data-sync services 6 hours a day in the background. When they're up,
     * everything stops cleanly and waits as queued; opening Hamstr starts it all again.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        halted = true
        synchronized(this) { jobs.keys.toList() }.forEach { stop(it, Status.Queued, Stage.PausedBySystem) }
        DownloadStore.updateAll(persist = false, which = { it.status == Status.Queued }) { it.copy(stage = Stage.PausedBySystem) }
        mainHandler.removeCallbacks(retryTick)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
        if (canNotify()) {
            val n = NotificationCompat.Builder(this, CH_DONE)
                .setSmallIcon(R.drawable.ic_stat_hamstr)
                .setColor(Prefs.settings.value.accent)
                .setContentTitle(getString(R.string.notif_timeout_title))
                .setContentText(getString(R.string.notif_timeout_text))
                .setStyle(NotificationCompat.BigTextStyle().bigText(getString(R.string.notif_timeout_text)))
                .setAutoCancel(true)
                .setContentIntent(openApp())
                .build()
            postNotification(NOTIF_TIMEOUT, n)
        }
    }

    override fun onDestroy() {
        if (current === this) current = null
        mainHandler.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(power) }
        synchronized(this) { jobs.keys.toList() }.forEach { YoutubeDL.getInstance().destroyProcessById(it); Cheek.destroy(it) }
        scope.cancel()
        super.onDestroy()
    }

    // ---- notifications -------------------------------------------------------------

    @Synchronized
    private fun notifyProgress(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastNotify < 700) return
        lastNotify = now
        // The foreground notification has to stay; with Hamstr's switch off it just goes quiet.
        if (!mayPost()) return
        // pump() and stage changes ask often with nothing new to say: what it would say is compared
        // first, and only something new is built (with its PendingIntents) and sent to the system.
        val job = host
        val card = progressCard(this)
        shown.send(job to card) {
            val n = progressNotification(this, card)
            job?.update(n) ?: postNotification(NOTIF_PROGRESS, n)
        }
    }

    private fun progressNotification(): android.app.Notification = progressNotification(this)

    private fun resumed(t: DownloadTask) = resumedTask(t)


    /**
     * "Open when done", picked per download in the sheet: once it is in, and no other download
     * that wants the same is still on its way, Hamstr comes up playing it. From the background
     * Android only allows that with "Display over other apps"; otherwise it quietly stays with
     * the notification.
     */
    private fun openWhenDone(id: String) {
        if (DownloadStore.get(id)?.choice?.open != true) return
        if (!getSystemService(android.os.PowerManager::class.java).isInteractive) return
        if (DownloadStore.tasks.value.any { it.status.active && it.id != id && it.choice.open }) return
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN).putExtra(EXTRA_ID, id)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
        }
    }

    private suspend fun notifyDone(id: String) {
        val t = DownloadStore.get(id) ?: return
        if (!canNotify() || Seen.foreground) return
        val b = NotificationCompat.Builder(this, CH_DONE)
            .setSmallIcon(R.drawable.ic_stat_hamstr)
            .setColor(Prefs.settings.value.accent)
            .setContentTitle(getString(R.string.notif_done))
            .setContentText(t.title)
            .setAutoCancel(true)
            .setContentIntent(playIntent(this, t))
            .addAction(0, getString(R.string.notif_play), playIntent(this, t))
        shareIntent(this, t)?.let { b.addAction(0, getString(R.string.notif_share), it) }
        thumbnail(t.thumbnail)?.let { b.setLargeIcon(it) }
        postNotification(id.hashCode(), b.build())
    }

    private suspend fun thumbnail(url: String?): Bitmap? = url?.let {
        withTimeoutOrNull(4_000) {
            val request = ImageRequest.Builder(this@DownloadService).data(it).size(256).allowHardware(false).build()
            (imageLoader.execute(request) as? SuccessResult)?.image?.toBitmap()
        }
    }

    private fun notifyFailed(id: String) {
        val t = DownloadStore.get(id) ?: return
        if (!canNotify()) return
        val why = Failures.explain(t.error)
        val n = NotificationCompat.Builder(this, CH_DONE)
            .setSmallIcon(R.drawable.ic_stat_hamstr)
            .setColor(0xFFF08080.toInt())
            .setContentTitle(getString(R.string.notif_failed))
            .setContentText(t.title)
            .setStyle(NotificationCompat.BigTextStyle().bigText("${t.title}\n${getString(why.title)} · ${getString(why.hint)}"))
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .addAction(0, getString(R.string.notif_retry), action(this, ACTION_RESUME, id))
            .build()
        postNotification(id.hashCode(), n)
    }

    /** True when the system took it. */
    private fun postNotification(id: Int, notification: android.app.Notification): Boolean {
        if (!mayPost()) return false
        return try {
            NotificationManagerCompat.from(this).notify(id, notification)
            true
        } catch (_: SecurityException) {
            // The notification permission can be revoked after the check.
            false
        }
    }

    /** Android lets Hamstr post at all. */
    private fun mayPost() = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Android allows it and the switch in Hamstr's settings is on. */
    private fun canNotify() = mayPost() && Prefs.settings.value.notifications

    private fun openApp(): PendingIntent = openApp(this)

    /**
     * [files] into the media index in one call (one connection to the scanner, not one each); their
     * content URIs by path, for those that came back before the timeout.
     */
    private suspend fun scanAll(files: List<File>): Map<String, Uri> {
        val found = java.util.concurrent.ConcurrentHashMap<String, Uri>()
        val all = kotlinx.coroutines.CompletableDeferred<Unit>()
        val left = java.util.concurrent.atomic.AtomicInteger(files.size)
        MediaScannerConnection.scanFile(this, files.map { it.absolutePath }.toTypedArray(), files.map { mimeOf(it) }.toTypedArray()) { path, uri ->
            if (uri != null) found[path] = uri
            if (left.decrementAndGet() == 0) all.complete(Unit)
        }
        // Like [scan]: a scanner that never answers holds nothing for long.
        withTimeoutOrNull(10_000L + files.size * 2_000L) { all.await() }
        return HashMap(found)
    }

    private suspend fun scan(file: File): Uri? = withTimeoutOrNull(10_000) {
        // Indexing is optional; a missing scanner callback must not occupy a queue slot forever.
        suspendCancellableCoroutine { cont ->
            MediaScannerConnection.scanFile(this@DownloadService, arrayOf(file.absolutePath), arrayOf(mimeOf(file))) { _, uri ->
                if (cont.isActive) cont.resume(uri)
            }
        }
    }

    companion object {
        const val ACTION_START = "app.hamstr.START"

        /** The running service, if there is one; [send] hands it commands directly while a job holds it. */
        @Volatile internal var current: DownloadService? = null

        const val ACTION_PAUSE = "app.hamstr.PAUSE"
        const val ACTION_PAUSE_ALL = "app.hamstr.PAUSE_ALL"
        const val ACTION_RESUME = "app.hamstr.RESUME"
        const val ACTION_CANCEL = "app.hamstr.CANCEL"
        /** The alarm (Wakeups): the night began or a followed channel is due; with [FORCE] all channels are looked at. */
        const val ACTION_WAKE = "app.hamstr.WAKE_QUEUE"
        const val FORCE = "force"
        /** How often a running download's progress reaches the list (and every screen showing it). */
        private const val PROGRESS_MS = 500L
        private val controlScope = MainScope()
        const val EXTRA_ID = "id"
        const val CH_PROGRESS = "progress"
        const val CH_DONE = "done"
        const val CH_QUIET = "quiet"
        internal const val NOTIF_PROGRESS = 1
        private const val NOTIF_TIMEOUT = 2
        private val SPEED = Regex("""at\s+~?\s*([\d.]+\s*[KMG]?i?B/s)""")
        private val POSTPROCESSOR = Regex("""^\[(\w+)]""")
        // Seconds before each automatic retry of a broken connection.
        private val BACKOFF = listOf(5, 15, 30, 60, 120)

        fun channels(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CH_PROGRESS, context.getString(R.string.channel_progress), NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel(CH_DONE, context.getString(R.string.channel_done), NotificationManager.IMPORTANCE_DEFAULT))
            nm.createNotificationChannel(NotificationChannel(CH_QUIET, context.getString(R.string.channel_quiet), NotificationManager.IMPORTANCE_MIN))
        }

        /** Queues a link and wakes the service. Without [info] the title is filled in later. */
        fun stash(context: Context, url: String, info: MediaInfo?, choice: Choice) {
            DownloadStore.add(
                DownloadTask(
                    url = url,
                    title = info?.title ?: url,
                    thumbnail = info?.thumbnail,
                    source = info?.source,
                    durationSec = info?.durationSec ?: 0,
                    choice = choice,
                    uploader = info?.uploader,
                    mediaKey = info?.key,
                    channelUrl = info?.channelUrl,
                ),
            )
            send(context, ACTION_START)
        }

        /** Reuses the failed entry so its playlist membership and position remain intact. */
        fun stashAlternative(context: Context, id: String, url: String, info: MediaInfo, choice: Choice) {
            DownloadStore.update(id) { old ->
                if (old.status != Status.Failed) old else DownloadTask(
                    id = old.id, url = url, title = info.title, thumbnail = info.thumbnail,
                    source = info.source, durationSec = info.durationSec, choice = choice,
                    uploader = info.uploader, mediaKey = info.key, createdAt = old.createdAt,
                    group = old.group, groupTitle = old.groupTitle, versionOf = old.versionOf,
                    replaces = old.replaces, hidden = old.hidden,
                )
            }
            send(context, ACTION_START)
        }

        /** Queues the picked videos of a playlist, each its own download, together as one group. */
        fun stashEntries(context: Context, playlist: MediaInfo, entries: List<PlaylistEntry>, choice: Choice) {
            if (entries.isEmpty()) return
            val group = if (entries.size > 1) UUID.randomUUID().toString() else null
            val now = System.currentTimeMillis()
            DownloadStore.addAll(entries.mapIndexed { i, e ->
                DownloadTask(
                    url = e.url, title = e.title, thumbnail = e.thumbnail, source = playlist.source, durationSec = e.durationSec,
                    choice = choice, uploader = e.uploader, mediaKey = e.key, channelUrl = playlist.channelUrl,
                    // Kept in playlist order, which is also the order they start in.
                    createdAt = now + i, group = group, groupTitle = group?.let { playlist.title },
                )
            })
            send(context, ACTION_START)
        }

        /** Queues another quality of [of]; with [replace] the old file goes once the new one is done. */
        fun stashVersion(context: Context, of: DownloadTask, choice: Choice, replace: Boolean) {
            DownloadStore.add(
                DownloadTask(
                    url = of.url, title = of.title, thumbnail = of.thumbnail, source = of.source,
                    durationSec = of.durationSec, choice = choice, uploader = of.uploader, mediaKey = of.mediaKey,
                    versionOf = of.family, replaces = if (replace) of.id else null,
                ),
            )
            send(context, ACTION_START)
        }

        /** The same link again, as it was chosen the first time (for a file that went missing). */
        fun again(context: Context, t: DownloadTask) {
            DownloadStore.add(
                DownloadTask(
                    url = t.url, title = t.title, thumbnail = t.thumbnail, source = t.source, durationSec = t.durationSec,
                    choice = t.choice, uploader = t.uploader, mediaKey = t.mediaKey, versionOf = t.versionOf,
                ),
            )
            send(context, ACTION_START)
        }


        private fun action(context: Context, action: String, id: String): PendingIntent = PendingIntent.getService(
            context, (action + id).hashCode(),
            Intent(context, DownloadService::class.java).setAction(action).putExtra(EXTRA_ID, id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        /** The one ongoing notification: what runs, how far, and what waits. */
        /** What the progress notification says now, from the download list. */
        internal fun progressCard(context: Context): ProgressCard {
            // A tick leaves the list as it is: its running and queued downloads come from the summary of it.
            val summary = DownloadStore.summary()
            val live = DownloadStore.live.value
            val running = summary.running.map { DownloadStore.current(it, live) }
            val queued = summary.queued
            val s = Prefs.settings.value
            val card = ProgressCard(quiet = !s.notifications, accent = s.accent, title = "Hamstr", text = null)
            return when {
                running.size == 1 -> {
                    val t = running[0]
                    val pct = (t.progress * 100).toInt()
                    val stage = t.stage?.let { stageText(context, t) }
                    card.copy(
                        title = t.title,
                        text = stage ?: listOf("$pct %", t.speed, eta(t.etaSec), if (queued.isNotEmpty()) context.getString(R.string.notif_queued, queued.size) else "")
                            .filter { it.isNotEmpty() }.joinToString(" · "),
                        max = 100, progress = pct, indeterminate = t.progress <= 0f || t.stage?.waiting == false,
                        buttons = listOf(ACTION_PAUSE to t.id, ACTION_CANCEL to t.id),
                    )
                }
                running.size > 1 -> {
                    val avg = running.map { it.progress }.average().toFloat()
                    card.copy(
                        title = context.getString(R.string.notif_many, running.size),
                        text = running.joinToString(" · ") { "${(it.progress * 100).toInt()} % ${it.title.take(24)}" },
                        max = 100, progress = (avg * 100).toInt(),
                        buttons = listOf(ACTION_PAUSE_ALL to ""),
                    )
                }
                queued.any { it.stage != null } -> {
                    val t = queued.first { it.stage != null }
                    card.copy(
                        title = stageText(context, t),
                        text = if (queued.size == 1) context.getString(R.string.notif_waiting_one) else context.getString(R.string.notif_waiting_many, queued.size),
                    )
                }
                Subscriptions.checking.value -> card.copy(text = context.getString(R.string.follow_checking), indeterminate = true)
                else -> card.copy(text = context.getString(R.string.notif_preparing), indeterminate = true)
            }
        }

        internal fun progressNotification(context: Context, card: ProgressCard = progressCard(context)): android.app.Notification {
            val b = NotificationCompat.Builder(context, if (card.quiet) CH_QUIET else CH_PROGRESS)
                .setSmallIcon(R.drawable.ic_stat_hamstr)
                .setColor(card.accent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setContentIntent(openApp(context))
                .setContentTitle(card.title)
                .setContentText(card.text)
            if (card.max > 0 || card.indeterminate) b.setProgress(card.max, card.progress, card.indeterminate)
            card.buttons.forEach { (what, id) ->
                val label = when (what) {
                    ACTION_PAUSE -> R.string.notif_pause
                    ACTION_CANCEL -> R.string.notif_cancel
                    else -> R.string.notif_pause_all
                }
                b.addAction(0, context.getString(label), action(context, what, id))
            }
            return b.build()
        }

        fun send(context: Context, action: String, id: String? = null) {
            // A job holds the queue: the command goes straight in, no service start (and no foreground service).
            val held = current?.takeIf { it.host != null }
            if (held != null) {
                Handler(Looper.getMainLooper()).post { held.handle(action, id) }
                return
            }
            // Asked for with Hamstr open on Android 14+: a transfer job, which has no daily limit.
            if ((action == ACTION_START || action == ACTION_RESUME) && DownloadJob.usable(context)) {
                if (current == null) {
                    if (action == ACTION_RESUME && id != null) DownloadStore.update(id, block = ::resumedTask)
                    if (DownloadJob.schedule(context)) return
                } else {
                    // Already running as a foreground service: the job takes it over once it starts.
                    DownloadJob.schedule(context)
                }
            }
            val i = Intent(context, DownloadService::class.java).setAction(action)
            if (id != null) i.putExtra(EXTRA_ID, id)
            ContextCompat.startForegroundService(context, i)
        }

        /** A download set going again: queued, its error, stage and retries forgotten. */
        internal fun resumedTask(t: DownloadTask) = t.copy(status = Status.Queued, error = null, stage = null, attempts = 0, retryAt = 0)

        /** What a download is doing right now, when it's more than fetching bytes. */
        fun stageText(context: Context, t: DownloadTask): String? =
            stageString(t)?.let { (res, arg) -> if (arg == null) context.getString(res) else context.getString(res, arg) }

        /** The download queue as the shared screens see it. */
        fun control(context: Context) = object : DownloadControl {
            override fun pause(id: String) = send(context, ACTION_PAUSE, id)
            override fun resume(id: String) = send(context, ACTION_RESUME, id)

            // A playlist's Pause, on the main thread, where the service is created and takes its commands.
            override fun pauseAll(ids: List<String>) {
                val picked = ids.toHashSet()
                val run = Runnable {
                    val service = current
                    if (service != null) service.pauseMany(picked)
                    // No queue: nothing downloads, and none can start before this is done. What was
                    // held for the night may have been all the alarm waited for.
                    else {
                        DownloadStore.pauseWaiting(picked, emptySet())
                        Wakeups.plan(context)
                    }
                }
                if (Looper.myLooper() == Looper.getMainLooper()) run.run() else Handler(Looper.getMainLooper()).post(run)
            }

            // All queued in one step, then one start: the queue looks at the list once, not once per download.
            override fun resumeAll(ids: List<String>) {
                if (ids.size == 1) return resume(ids[0])
                val picked = ids.toHashSet()
                if (DownloadStore.updateAll(which = { it.id in picked && it.status != Status.Running }) { resumedTask(it) } > 0) send(context, ACTION_START)
            }
            override fun cancel(id: String) = send(context, ACTION_CANCEL, id)

            override fun retry(ids: List<String>, updateFirst: Boolean) {
                if (!updateFirst) return resumeAll(ids)
                controlScope.launch {
                    Downloads.updating.value = true
                    try { Engine.update(context) } finally { Downloads.updating.value = false }
                    resumeAll(ids)
                }
            }

            override suspend fun feed(url: String) = Engine.feed(url)

            override fun checkFeeds() = send(context, ACTION_WAKE, FORCE)

            override suspend fun channelOf(t: DownloadTask) = t.channelUrl ?: runCatching { Engine.info(t.url).channelUrl }.getOrNull()
        }

        fun mimeOf(file: File): String =
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: when (file.extension.lowercase()) {
                "opus" -> "audio/ogg"
                "mkv" -> "video/x-matroska"
                else -> "application/octet-stream"
            }

        fun viewIntent(context: Context, t: DownloadTask): Intent? {
            val file = t.filePath?.let(::File)?.takeIf { it.exists() } ?: return null
            val uri = t.contentUri?.let(Uri::parse)
                ?: FileProvider.getUriForFile(context, context.packageName + ".files", file)
            return Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, mimeOf(file))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        /** Opens the file in Hamstr's own player. */
        fun playIntent(context: Context, t: DownloadTask): PendingIntent = PendingIntent.getActivity(
            context, ("play" + t.id).hashCode(),
            Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN).putExtra(EXTRA_ID, t.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        /** The system share sheet with the file itself. */
        fun shareIntent(context: Context, t: DownloadTask): PendingIntent? {
            val file = t.filePath?.let(::File)?.takeIf { it.exists() } ?: return null
            val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
            val send = Intent(Intent.ACTION_SEND).setType(mimeOf(file)).putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply { clipData = ClipData.newRawUri(t.title, uri) }
            val chooser = Intent.createChooser(send, t.title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return PendingIntent.getActivity(context, ("share" + t.id).hashCode(), chooser, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        private fun prettySpeed(s: String) = s.replace("MiB/s", " MB/s").replace("KiB/s", " KB/s").replace("GiB/s", " GB/s").replace("  ", " ")

        fun eta(sec: Long) = app.hamstr.ui.eta(sec)

        private fun cleanError(msg: String?): String {
            val line = msg?.lines()?.lastOrNull { it.contains("ERROR") } ?: msg?.lines()?.lastOrNull { it.isNotBlank() } ?: "Unknown error"
            return line.substringAfter("ERROR: ").take(600)
        }
    }
}

/**
 * What the progress notification says, worked out before anything is built: the queue asks for it
 * far more often than it changes, and an unchanged card is neither built nor sent again. Each
 * button is an action and the download it acts on.
 */
internal data class ProgressCard(
    val quiet: Boolean,
    val accent: Int,
    val title: String?,
    val text: String?,
    val max: Int = 0,
    val progress: Int = 0,
    val indeterminate: Boolean = false,
    val buttons: List<Pair<String, String>> = emptyList(),
)
