package app.hamstr.update

import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import app.hamstr.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection

/** A user-started update download keeps running when the sheet or the app goes away. */
class UpdateDownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var work: Job? = null
    @Volatile private var connection: HttpURLConnection? = null
    private var cancelled = false
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == CANCEL) {
            cancelled = true
            work?.cancel()
            disconnect()
            if (work == null) stopSelf()
            return START_NOT_STICKY
        }
        if (work != null) return START_NOT_STICKY
        val release = runCatching { Release.fromJson(JSONObject(intent?.getStringExtra(EXTRA_RELEASE).orEmpty())) }.getOrNull()
        if (release == null) { stopSelf(); return START_NOT_STICKY }
        try {
            ServiceCompat.startForeground(this, UpdateNotifications.PROGRESS, UpdateNotifications.progress(this, release, 0f),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Hamstr:UpdateDownload")
                .apply { acquire(6 * 60 * 60_000L) }
        } catch (e: Exception) {
            Updater.failed(this, release, e.message ?: getString(R.string.upd_failed))
            stopSelf()
            return START_NOT_STICKY
        }
        work = scope.launch {
            try {
                runInterruptible(Dispatchers.IO) {
                    Updater.fetch(this@UpdateDownloadService, release, { connection = it }) { progress ->
                        if (work?.isActive == true) {
                            Updater.downloading(release, progress)
                            runCatching { getSystemService(NotificationManager::class.java).notify(
                                UpdateNotifications.PROGRESS, UpdateNotifications.progress(this@UpdateDownloadService, release, progress)) }
                        }
                    }
                }
                Updater.ready(this@UpdateDownloadService, release)
            } catch (e: Exception) {
                if (cancelled) {
                    Updater.pending(this@UpdateDownloadService).clear()
                    Updater.available(release)
                } else Updater.failed(this@UpdateDownloadService, release,
                    if (e is CancellationException) getString(R.string.upd_interrupted) else e.message ?: getString(R.string.upd_failed))
            } finally {
                withContext(NonCancellable + Dispatchers.Main.immediate) {
                    connection = null
                    releaseWakeLock()
                    ServiceCompat.stopForeground(this@UpdateDownloadService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    if (Updater.state.value is UpdateState.Ready) UpdateNotifications.ready(this@UpdateDownloadService, release)
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        work?.cancel()
        disconnect()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        work?.cancel()
        disconnect()
        scope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    private fun releaseWakeLock() { wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null }

    private fun disconnect() {
        val active = connection ?: return
        connection = null
        // A socket may be waiting for a read; closing it must not block the UI thread.
        CoroutineScope(Dispatchers.IO).launch { runCatching { active.disconnect() } }
    }

    companion object {
        internal const val EXTRA_RELEASE = "release"
        internal const val CANCEL = "app.hamstr.update.CANCEL"
    }
}
