package app.hamstr.engine

import java.util.UUID
import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
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
import app.hamstr.data.Choice
import app.hamstr.data.DownloadStore
import app.hamstr.data.DownloadTask
import app.hamstr.data.Prefs
import app.hamstr.data.Status
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
 */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = mutableMapOf<String, Job>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastStartId = 0
    private var lastNotify = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        goForeground()
        val id = intent?.getStringExtra(EXTRA_ID)
        when (intent?.action) {
            ACTION_PAUSE -> id?.let { stop(it, Status.Paused) }
            ACTION_CANCEL -> id?.let { stop(it, Status.Canceled) }
            ACTION_RESUME -> id?.let { DownloadStore.update(it) { t -> t.copy(status = Status.Queued, error = null) } }
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
        if (!scope.isActive) return
        val max = Prefs.settings.value.parallel.coerceIn(1, 3)
        val queued = DownloadStore.tasks.value.filter { it.status == Status.Queued && it.id !in jobs }.reversed() // oldest first
        for (task in queued) {
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
        if (jobs.isEmpty()) finishIfIdle()
    }

    private suspend fun run(task: DownloadTask) {
        try {
            currentCoroutineContext().ensureActive()
            if (DownloadStore.get(task.id)?.status != Status.Queued) return
            DownloadStore.update(task.id) { if (it.status == Status.Queued) it.copy(status = Status.Running, error = null) else it }
            if (DownloadStore.get(task.id)?.status != Status.Running) return
            notifyProgress(force = true)
            Engine.awaitReady()
            currentCoroutineContext().ensureActive()
            val pathFile = Engine.pathFile(task).also { it.delete() }
            val runJob = currentCoroutineContext()[Job]!!
            var lastProgress = 0L
            val res = Engine.execute(task) { progress, eta, line ->
                if (runJob.isActive) {
                    if (line.startsWith(Engine.METADATA_PREFIX)) {
                        updateMetadata(task.id, line.removePrefix(Engine.METADATA_PREFIX))
                    } else if (line.startsWith("[download]")) {
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastProgress >= 500) {
                            lastProgress = now
                            val speed = SPEED.find(line)?.groupValues?.get(1)?.let(::prettySpeed) ?: ""
                            DownloadStore.update(task.id, persist = false) {
                                if (it.status != Status.Running) it else it.copy(
                                    progress = (progress / 100f).coerceIn(0f, 1f).coerceAtLeast(it.progress),
                                    etaSec = eta, speed = speed.ifEmpty { it.speed },
                                )
                            }
                            notifyProgress()
                        }
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            if (res.exitCode != 0) error(res.err.lines().lastOrNull { it.isNotBlank() } ?: "yt-dlp exit ${res.exitCode}")
            val entries = pathFile.takeIf { it.exists() }?.readLines().orEmpty().filter { it.isNotBlank() }.map(::finishedEntry)
            val main = entries.firstOrNull()
            val path = main?.path
            val file = path?.let(::File)
            val uri = file?.let { scan(it) }
            currentCoroutineContext().ensureActive()
            // A playlist becomes a group: this task keeps the first entry, the rest join it.
            val grouped = entries.size > 1
            val groupTitle = if (grouped) entries.firstNotNullOfOrNull { it.playlist } ?: task.title else null
            DownloadStore.update(task.id) {
                if (it.status != Status.Running) it else it.copy(status = Status.Done, progress = 1f, filePath = path, contentUri = uri?.toString(),
                    sizeBytes = file?.length() ?: 0, speed = "", etaSec = -1, finishedAt = System.currentTimeMillis(),
                    title = if (grouped) main?.title ?: it.title else it.title,
                    thumbnail = if (grouped) main?.thumbnail ?: it.thumbnail else it.thumbnail,
                    group = if (grouped) task.id else null, groupTitle = groupTitle)
            }
            if (grouped && DownloadStore.get(task.id)?.status == Status.Done) {
                val base = DownloadStore.get(task.id) ?: task
                entries.drop(1).forEachIndexed { i, e ->
                    val f = File(e.path)
                    DownloadStore.add(base.copy(
                        id = UUID.randomUUID().toString(), title = e.title ?: f.nameWithoutExtension, thumbnail = e.thumbnail,
                        durationSec = e.duration, filePath = e.path, contentUri = scan(f)?.toString(), sizeBytes = f.length(),
                        createdAt = base.createdAt + i + 1,
                    ))
                }
            }
            if (DownloadStore.get(task.id)?.status == Status.Done) {
                Engine.clearTemporaryFiles(task)
                notifyDone(task.id)
            }
        } catch (e: CancellationException) {
            DownloadStore.update(task.id) {
                if (it.status == Status.Running) it.copy(status = Status.Paused, speed = "", etaSec = -1) else it
            }
            throw e
        } catch (e: YoutubeDL.CanceledException) {
            // Status was already set to Paused/Canceled by stop().
        } catch (e: Throwable) {
            val current = DownloadStore.get(task.id)?.status
            if (current == Status.Running) {
                DownloadStore.update(task.id) { if (it.status == Status.Running) it.copy(status = Status.Failed, error = cleanError(e.message), speed = "") else it }
                if (DownloadStore.get(task.id)?.status == Status.Failed) notifyFailed(task.id)
            }
        }
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
        DownloadStore.update(id) {
            if (it.status != Status.Running) it else it.copy(
                title = string("title") ?: it.title,
                thumbnail = string("thumbnail") ?: it.thumbnail,
                source = string("extractor_key") ?: it.source,
                durationSec = metadata.optDouble("duration", it.durationSec.toDouble()).toInt(),
            )
        }
        notifyProgress(force = true)
    }

    private fun stop(id: String, status: Status) {
        val task = DownloadStore.get(id) ?: return
        if (status == Status.Canceled && !task.status.active && task.status != Status.Paused) return
        DownloadStore.update(id) { it.copy(status = status, speed = "", etaSec = -1) }
        val job = synchronized(this) { jobs[id] }
        YoutubeDL.getInstance().destroyProcessById(id)
        job?.cancel()
        if (status == Status.Canceled && job == null) scope.launch { Engine.clearTemporaryFiles(task) }
        notifyProgress(force = true)
    }

    private fun finishIfIdle() {
        // Serialize shutdown with incoming commands. An old worker must not stop a
        // newly started foreground service before its start command is handled.
        mainHandler.post {
            synchronized(this) {
                if (scope.isActive && jobs.isEmpty() && DownloadStore.tasks.value.none { it.status == Status.Queued } && stopSelfResult(lastStartId)) {
                    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                }
            }
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        synchronized(this) { jobs.keys.toList() }.forEach { YoutubeDL.getInstance().destroyProcessById(it) }
        scope.cancel()
        super.onDestroy()
    }

    // ---- notifications -------------------------------------------------------------

    @Synchronized
    private fun notifyProgress(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastNotify < 700) return
        lastNotify = now
        if (canNotify()) postNotification(NOTIF_PROGRESS, progressNotification())
    }

    private fun progressNotification(): android.app.Notification {
        val running = DownloadStore.tasks.value.filter { it.status == Status.Running }
        val queued = DownloadStore.tasks.value.count { it.status == Status.Queued }
        val b = NotificationCompat.Builder(this, CH_PROGRESS)
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
                b.setContentTitle(t.title)
                    .setContentText(listOf("$pct %", t.speed, eta(t.etaSec), if (queued > 0) "+$queued queued" else "").filter { it.isNotEmpty() }.joinToString(" · "))
                    .setProgress(100, pct, t.progress <= 0f)
                    .addAction(0, "Pause", action(ACTION_PAUSE, t.id))
                    .addAction(0, "Cancel", action(ACTION_CANCEL, t.id))
            }
            running.size > 1 -> {
                val avg = running.map { it.progress }.average().toFloat()
                b.setContentTitle("Hamstr is stuffing ${running.size} downloads")
                    .setContentText(running.joinToString(" · ") { "${(it.progress * 100).toInt()} % ${it.title.take(24)}" })
                    .setProgress(100, (avg * 100).toInt(), false)
            }
            else -> b.setContentTitle("Hamstr").setContentText("Preparing download …").setProgress(0, 0, true)
        }
        return b.build()
    }

    private fun notifyDone(id: String) {
        val t = DownloadStore.get(id) ?: return
        if (!canNotify()) return
        val n = NotificationCompat.Builder(this, CH_DONE)
            .setSmallIcon(R.drawable.ic_stat_hamstr)
            .setColor(0xFFFF9A3C.toInt())
            .setContentTitle("Stashed")
            .setContentText(t.title)
            .setAutoCancel(true)
            .setContentIntent(openFileIntent(this, t) ?: openApp())
            .build()
        postNotification(id.hashCode(), n)
    }

    private fun notifyFailed(id: String) {
        val t = DownloadStore.get(id) ?: return
        if (!canNotify()) return
        val n = NotificationCompat.Builder(this, CH_DONE)
            .setSmallIcon(R.drawable.ic_stat_hamstr)
            .setColor(0xFFF08080.toInt())
            .setContentTitle("Download failed")
            .setContentText(t.title)
            .setStyle(NotificationCompat.BigTextStyle().bigText("${t.title}\n${t.error ?: ""}"))
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .addAction(0, "Retry", action(ACTION_RESUME, id))
            .build()
        postNotification(id.hashCode(), n)
    }

    private fun postNotification(id: Int, notification: android.app.Notification) {
        if (!canNotify()) return
        try {
            NotificationManagerCompat.from(this).notify(id, notification)
        } catch (_: SecurityException) {
            // The notification permission can be revoked after the check.
        }
    }

    private fun canNotify() = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

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
        const val ACTION_RESUME = "app.hamstr.RESUME"
        const val ACTION_CANCEL = "app.hamstr.CANCEL"
        const val EXTRA_ID = "id"
        const val CH_PROGRESS = "progress"
        const val CH_DONE = "done"
        private const val NOTIF_PROGRESS = 1
        private val SPEED = Regex("""at\s+~?\s*([\d.]+\s*[KMG]?i?B/s)""")

        fun channels(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CH_PROGRESS, "Active downloads", NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel(CH_DONE, "Finished downloads", NotificationManager.IMPORTANCE_DEFAULT))
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
                ),
            )
            send(context, ACTION_START)
        }

        fun send(context: Context, action: String, id: String? = null) {
            val i = Intent(context, DownloadService::class.java).setAction(action)
            if (id != null) i.putExtra(EXTRA_ID, id)
            ContextCompat.startForegroundService(context, i)
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

        fun openFileIntent(context: Context, t: DownloadTask): PendingIntent? = viewIntent(context, t)?.let {
            PendingIntent.getActivity(context, t.id.hashCode(), it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        private fun prettySpeed(s: String) = s.replace("MiB/s", " MB/s").replace("KiB/s", " KB/s").replace("GiB/s", " GB/s").replace("  ", " ")

        fun eta(sec: Long) = if (sec <= 0) "" else "%d:%02d".format(sec / 60, sec % 60)

        private fun cleanError(msg: String?): String {
            val line = msg?.lines()?.lastOrNull { it.contains("ERROR") } ?: msg?.lines()?.lastOrNull { it.isNotBlank() } ?: "Unknown error"
            return line.substringAfter("ERROR: ").take(300)
        }
    }
}
