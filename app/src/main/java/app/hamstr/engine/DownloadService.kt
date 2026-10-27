package app.hamstr.engine

import java.util.UUID
import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
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
import app.hamstr.data.Prefs
import app.hamstr.data.Stage
import app.hamstr.data.Status
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
 * Runs the download queue in the foreground with one progress notification.
 * Pause = stop the yt-dlp process (its .part file stays); resume runs it again.
 * Without a usable network, queued downloads wait and start on their own once it's back;
 * a connection that breaks mid-download is retried a few times with growing pauses.
 */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = mutableMapOf<String, Job>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val retryTick = Runnable { pump() }
    private var lastStartId = 0
    private var lastNotify = 0L
    // Set once Android's daily limit for background downloads ran out, until the next start.
    @Volatile private var halted = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // Network changes (and the Wi-Fi-only switch) start waiting downloads or hold running ones.
        scope.launch {
            combine(Network.state, Prefs.settings) { _, s -> s.wifiOnly to Network.allows(s.wifiOnly) }
                .distinctUntilChanged()
                .drop(1)
                .collect { (_, allowed) -> mainHandler.post { if (!allowed) holdRunning() else wake() } }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        halted = false
        goForeground()
        val id = intent?.getStringExtra(EXTRA_ID)
        when (intent?.action) {
            ACTION_PAUSE -> id?.let { stop(it, Status.Paused) }
            ACTION_PAUSE_ALL -> synchronized(this) { jobs.keys.toList() }.forEach { stop(it, Status.Paused) }
            ACTION_CANCEL -> id?.let { stop(it, Status.Canceled) }
            ACTION_RESUME -> id?.let { DownloadStore.update(it) { t -> t.copy(status = Status.Queued, error = null, stage = null, attempts = 0, retryAt = 0) } }
            ACTION_START -> DownloadStore.tasks.value.filter { it.status == Status.Queued && it.stage == Stage.PausedBySystem }
                .forEach { t -> DownloadStore.update(t.id, persist = false) { it.copy(stage = null) } }
        }
        pump()
        return START_NOT_STICKY
    }

    private fun goForeground() {
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
        val queued = DownloadStore.tasks.value.filter { it.status == Status.Queued && it.id !in jobs }.reversed() // oldest first
        for (task in queued) {
            if (waiting != null) {
                if (task.stage != waiting) DownloadStore.update(task.id, persist = false) { it.copy(stage = waiting) }
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
        // The next retry wakes the queue by itself.
        mainHandler.removeCallbacks(retryTick)
        if (waiting == null) {
            DownloadStore.tasks.value.filter { it.status == Status.Queued && it.retryAt > now }.minOfOrNull { it.retryAt }
                ?.let { mainHandler.postDelayed(retryTick, it - now + 50) }
        }
        notifyProgress(force = true)
        if (jobs.isEmpty()) finishIfIdle()
    }

    /** The network is back (or Wi-Fi is): waiting and retrying downloads go at once. */
    private fun wake() {
        DownloadStore.tasks.value.filter { it.status == Status.Queued && (it.stage?.waiting == true && it.stage != Stage.PausedBySystem) }
            .forEach { t -> DownloadStore.update(t.id, persist = false) { it.copy(stage = null, retryAt = 0, attempts = 0) } }
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
            var lastProgress = 0L
            val res = Engine.execute(task) { progress, eta, line ->
                if (runJob.isActive) {
                    when {
                        line.startsWith(Engine.METADATA_PREFIX) -> updateMetadata(task.id, line.removePrefix(Engine.METADATA_PREFIX))
                        line.startsWith(Engine.STAGE_PREFIX) -> setStage(task.id, Stage.fromEngine(line.removePrefix(Engine.STAGE_PREFIX)))
                        line.startsWith("[download]") -> {
                            val now = SystemClock.elapsedRealtime()
                            if (now - lastProgress >= 500) {
                                lastProgress = now
                                val speed = SPEED.find(line)?.groupValues?.get(1)?.let(::prettySpeed) ?: ""
                                DownloadStore.update(task.id, persist = false) {
                                    if (it.status != Status.Running) it else it.copy(
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
                        line.startsWith("[") -> POSTPROCESSOR.find(line)?.groupValues?.get(1)?.let(Stage::fromEngine)?.let { setStage(task.id, it) }
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
            DownloadStore.update(task.id) {
                if (it.status != Status.Running) it else it.copy(status = Status.Done, progress = 1f, filePath = path, contentUri = uri?.toString(),
                    sizeBytes = file?.length() ?: 0, videoHeight = height, speed = "", etaSec = -1, finishedAt = System.currentTimeMillis(),
                    title = if (grouped) main?.title ?: it.title else it.title,
                    thumbnail = if (grouped) main?.thumbnail ?: it.thumbnail else it.thumbnail,
                    group = if (grouped) task.id else null, groupTitle = groupTitle, stage = null, attempts = 0)
            }
            if (grouped && DownloadStore.get(task.id)?.status == Status.Done) {
                val base = DownloadStore.get(task.id) ?: task
                entries.drop(1).forEachIndexed { i, e ->
                    val f = File(e.path)
                    DownloadStore.add(base.copy(
                        id = UUID.randomUUID().toString(), title = e.title ?: f.nameWithoutExtension, thumbnail = e.thumbnail,
                        durationSec = e.duration, filePath = e.path, contentUri = scan(f)?.toString(), sizeBytes = f.length(),
                        createdAt = base.createdAt + i + 1, mediaKey = null,
                    ))
                }
            }
            if (DownloadStore.get(task.id)?.status == Status.Done) {
                Engine.clearTemporaryFiles(task)
                notifyDone(task.id)
                replaceOld(task.id)
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

    private fun setStage(id: String, stage: Stage?) {
        stage ?: return
        DownloadStore.update(id, persist = false) { if (it.status == Status.Running) it.copy(stage = stage, speed = "", etaSec = -1) else it }
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
            )
        }
        notifyProgress(force = true)
    }

    private fun stop(id: String, status: Status, stage: Stage? = null) {
        val task = DownloadStore.get(id) ?: return
        if (status == Status.Canceled && !task.status.active && task.status != Status.Paused) return
        DownloadStore.update(id) { it.copy(status = status, speed = "", etaSec = -1, stage = stage, retryAt = 0) }
        val job = synchronized(this) { jobs[id] }
        YoutubeDL.getInstance().destroyProcessById(id)
        Cheek.destroy(id)
        job?.cancel()
        if (status == Status.Canceled && job == null) scope.launch { Engine.clearTemporaryFiles(task) }
        notifyProgress(force = true)
    }

    private fun finishIfIdle() {
        // Serialize shutdown with incoming commands. An old worker must not stop a
        // newly started foreground service before its start command is handled.
        mainHandler.post {
            synchronized(this) {
                if (scope.isActive && jobs.isEmpty() && DownloadStore.tasks.value.none { it.status == Status.Queued && it.stage != Stage.PausedBySystem } &&
                    stopSelfResult(lastStartId)
                ) {
                    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
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
        DownloadStore.tasks.value.filter { it.status == Status.Queued }
            .forEach { t -> DownloadStore.update(t.id, persist = false) { it.copy(stage = Stage.PausedBySystem) } }
        mainHandler.removeCallbacks(retryTick)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
        if (canNotify()) {
            val n = NotificationCompat.Builder(this, CH_DONE)
                .setSmallIcon(R.drawable.ic_stat_hamstr)
                .setColor(0xFFFF9A3C.toInt())
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
        mainHandler.removeCallbacksAndMessages(null)
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
        if (mayPost()) postNotification(NOTIF_PROGRESS, progressNotification())
    }

    private fun progressNotification(): android.app.Notification {
        val tasks = DownloadStore.tasks.value
        val running = tasks.filter { it.status == Status.Running }
        val queued = tasks.filter { it.status == Status.Queued }
        val b = NotificationCompat.Builder(this, if (Prefs.settings.value.notifications) CH_PROGRESS else CH_QUIET)
            .setSmallIcon(R.drawable.ic_stat_hamstr)
            .setColor(0xFFFF9A3C.toInt())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(openApp())
        when {
            running.size == 1 -> {
                val t = running[0]
                val pct = (t.progress * 100).toInt()
                val stage = t.stage?.let { stageText(this, t) }
                b.setContentTitle(t.title)
                    .setContentText(
                        stage ?: listOf("$pct %", t.speed, eta(t.etaSec), if (queued.isNotEmpty()) getString(R.string.notif_queued, queued.size) else "")
                            .filter { it.isNotEmpty() }.joinToString(" · "),
                    )
                    .setProgress(100, pct, t.progress <= 0f || (t.stage != null && !t.stage.waiting))
                    .addAction(0, getString(R.string.notif_pause), action(ACTION_PAUSE, t.id))
                    .addAction(0, getString(R.string.notif_cancel), action(ACTION_CANCEL, t.id))
            }
            running.size > 1 -> {
                val avg = running.map { it.progress }.average().toFloat()
                b.setContentTitle(getString(R.string.notif_many, running.size))
                    .setContentText(running.joinToString(" · ") { "${(it.progress * 100).toInt()} % ${it.title.take(24)}" })
                    .setProgress(100, (avg * 100).toInt(), false)
                    .addAction(0, getString(R.string.notif_pause_all), action(ACTION_PAUSE_ALL, ""))
            }
            queued.any { it.stage != null } -> {
                val t = queued.first { it.stage != null }
                b.setContentTitle(stageText(this, t))
                    .setContentText(if (queued.size == 1) getString(R.string.notif_waiting_one) else getString(R.string.notif_waiting_many, queued.size))
            }
            else -> b.setContentTitle("Hamstr").setContentText(getString(R.string.notif_preparing)).setProgress(0, 0, true)
        }
        return b.build()
    }

    private suspend fun notifyDone(id: String) {
        val t = DownloadStore.get(id) ?: return
        if (!canNotify()) return
        val b = NotificationCompat.Builder(this, CH_DONE)
            .setSmallIcon(R.drawable.ic_stat_hamstr)
            .setColor(0xFFFF9A3C.toInt())
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
            .addAction(0, getString(R.string.notif_retry), action(ACTION_RESUME, id))
            .build()
        postNotification(id.hashCode(), n)
    }

    private fun postNotification(id: Int, notification: android.app.Notification) {
        if (!mayPost()) return
        try {
            NotificationManagerCompat.from(this).notify(id, notification)
        } catch (_: SecurityException) {
            // The notification permission can be revoked after the check.
        }
    }

    /** Android lets Hamstr post at all. */
    private fun mayPost() = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Android allows it and the switch in Hamstr's settings is on. */
    private fun canNotify() = mayPost() && Prefs.settings.value.notifications

    private fun action(action: String, id: String): PendingIntent = PendingIntent.getService(
        this, (action + id).hashCode(),
        Intent(this, DownloadService::class.java).setAction(action).putExtra(EXTRA_ID, id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

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
        const val ACTION_PAUSE = "app.hamstr.PAUSE"
        const val ACTION_PAUSE_ALL = "app.hamstr.PAUSE_ALL"
        const val ACTION_RESUME = "app.hamstr.RESUME"
        const val ACTION_CANCEL = "app.hamstr.CANCEL"
        const val EXTRA_ID = "id"
        const val CH_PROGRESS = "progress"
        const val CH_DONE = "done"
        const val CH_QUIET = "quiet"
        private const val NOTIF_PROGRESS = 1
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
                ),
            )
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

        fun send(context: Context, action: String, id: String? = null) {
            val i = Intent(context, DownloadService::class.java).setAction(action)
            if (id != null) i.putExtra(EXTRA_ID, id)
            ContextCompat.startForegroundService(context, i)
        }

        /** What a download is doing right now, when it's more than fetching bytes. */
        fun stageText(context: Context, t: DownloadTask): String? = when (t.stage) {
            null -> null
            Stage.Resolving -> context.getString(R.string.stage_resolving)
            Stage.Merging -> context.getString(R.string.stage_merging)
            Stage.ExtractingAudio -> context.getString(R.string.stage_extracting)
            Stage.Remuxing -> context.getString(R.string.stage_remuxing)
            Stage.Tagging -> context.getString(R.string.stage_tagging)
            Stage.WaitingForNetwork -> context.getString(R.string.stage_waiting_network)
            Stage.WaitingForWifi -> context.getString(R.string.stage_waiting_wifi)
            Stage.PausedBySystem -> context.getString(R.string.stage_paused_by_system)
            Stage.Retrying -> {
                val left = (t.retryAt - System.currentTimeMillis()) / 1000
                if (left > 0) context.getString(R.string.stage_retrying, "$left s") else context.getString(R.string.stage_retrying_now)
            }
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

        fun eta(sec: Long) = when {
            sec <= 0 -> ""
            sec >= 3600 -> "%d:%02d:%02d".format(sec / 3600, sec / 60 % 60, sec % 60)
            else -> "%d:%02d".format(sec / 60, sec % 60)
        }

        private fun cleanError(msg: String?): String {
            val line = msg?.lines()?.lastOrNull { it.contains("ERROR") } ?: msg?.lines()?.lastOrNull { it.isNotBlank() } ?: "Unknown error"
            return line.substringAfter("ERROR: ").take(600)
        }
    }
}
