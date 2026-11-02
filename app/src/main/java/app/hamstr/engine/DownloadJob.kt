package app.hamstr.engine

import android.Manifest
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import app.hamstr.data.Prefs

/**
 * Android 14's user-initiated data transfer job, holding the download queue instead of a
 * foreground service: Android 15 gives a data-sync service six hours a day in the background,
 * a job the user started runs as long as its downloads do. It binds [DownloadService], which
 * keeps doing the work, and shows its notification. Such a job may only be scheduled while
 * Hamstr is on screen; everything else (a link from another device, the widget) still starts
 * the foreground service, which a job then takes over the next time Hamstr is open.
 */
@RequiresApi(34)
class DownloadJob : JobService() {
    private var params: JobParameters? = null
    private var service: DownloadService? = null
    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val s = (binder as? DownloadService.Local)?.service ?: return
            if (params == null) return
            service = s
            s.attach(this@DownloadJob)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    override fun onStartJob(p: JobParameters): Boolean {
        params = p
        running = this
        // Android wants the job's notification at once; the service keeps it up to date.
        setNotification(p, DownloadService.NOTIF_PROGRESS, DownloadService.progressNotification(this), JOB_END_NOTIFICATION_POLICY_REMOVE)
        bound = bindService(Intent(this, DownloadService::class.java), connection, Context.BIND_AUTO_CREATE)
        if (!bound) {
            Log.w(TAG, "Couldn't reach the download service")
            release()
            jobFinished(p, false)
            // The foreground service does it instead.
            DownloadService.send(this, DownloadService.ACTION_START)
        }
        return bound
    }

    override fun onStopJob(p: JobParameters): Boolean {
        val again = service?.jobStopped(this, p.stopReason) ?: false
        release()
        return again
    }

    /** A newer progress notification, for the job's own. */
    fun update(n: android.app.Notification) {
        val p = params ?: return
        runCatching { setNotification(p, DownloadService.NOTIF_PROGRESS, n, JOB_END_NOTIFICATION_POLICY_REMOVE) }
            .onFailure { Log.w(TAG, "Couldn't update the notification", it) }
    }

    /** The queue is empty: the job ends and lets go of the service. */
    fun done() {
        val p = params ?: return
        release()
        jobFinished(p, false)
    }

    private fun release() {
        service?.detach(this)
        service = null
        params = null
        if (running === this) running = null
        if (bound) runCatching { unbindService(connection) }
        bound = false
    }

    companion object {
        private const val TAG = "HamstrJob"
        private const val ID = 4711
        @Volatile private var running: DownloadJob? = null

        /** Android 14+, allowed in Settings, and Hamstr is on screen, as such a job needs. */
        fun usable(context: Context): Boolean = Build.VERSION.SDK_INT >= 34 &&
            Prefs.settings.value.transferJobs &&
            context.checkSelfPermission(Manifest.permission.RUN_USER_INITIATED_JOBS) == PackageManager.PERMISSION_GRANTED &&
            ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

        /** Schedules the job unless it runs or waits already; false when Android refused. */
        fun schedule(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < 34) return false
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return false
            if (running != null || scheduler.getPendingJob(ID) != null) return true
            return runCatching {
                val info = JobInfo.Builder(ID, ComponentName(context, DownloadJob::class.java))
                    .setUserInitiated(true)
                    // A transfer job needs a network; Wi-Fi only stays the queue's own decision.
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .build()
                scheduler.schedule(info) == JobScheduler.RESULT_SUCCESS
            }.onFailure { Log.w(TAG, "No transfer job", it) }.getOrDefault(false)
        }
    }
}
