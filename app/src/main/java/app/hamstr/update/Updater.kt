package app.hamstr.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.IntentCompat
import androidx.core.content.ContextCompat
import app.hamstr.BuildConfig
import app.hamstr.MainActivity
import app.hamstr.R
import app.hamstr.data.Prefs
import app.hamstr.engine.Seen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** A published Hamstr release and the APK in it that fits this phone. */
data class Release(
    val version: String,
    val notes: String,
    val apkUrl: String,
    val apkName: String,
    val size: Long,
    val page: String,
) {
    companion object
}

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: Release) : UpdateState
    data class Downloading(val release: Release, val progress: Float) : UpdateState
    data class Ready(val release: Release) : UpdateState
    data class Installing(val release: Release) : UpdateState
    data class Failed(val message: String, val release: Release?) : UpdateState
}

/**
 * Hamstr updates itself from its GitHub Releases: it looks at the latest release, downloads
 * the APK for this phone's ABI in a foreground service, then saves it until the user
 * chooses to install. Once Hamstr installed itself, Android 12+ can skip the system's
 * confirmation on later updates.
 */
object Updater {
    const val REPO = "Mailo037/Hamstr"
    internal const val CH_UPDATES = "updates"
    private const val LAUNCH_CHECK_EVERY = 6 * 60 * 60 * 1000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()
    private var job: Job? = null

    fun init(context: Context) {
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CH_UPDATES, context.getString(R.string.channel_updates), NotificationManager.IMPORTANCE_DEFAULT))
        UpdateNotifications.createChannel(context)
        val saved = pending(context).read()
        _state.value = when {
            saved == null -> UpdateState.Idle
            !newer(saved.first.version, BuildConfig.VERSION_NAME) -> {
                pending(context).clear()
                UpdateState.Idle
            }
            saved.second -> UpdateState.Ready(saved.first)
            else -> UpdateState.Failed(context.getString(R.string.upd_interrupted), saved.first)
        }
        // Before 0.8.1 updates were downloaded into the cache and installed immediately.
        scope.launch { File(context.cacheDir, "updates").listFiles()?.forEach { it.delete() } }
    }

    /** Quietly looks for a new release when the app starts, at most every few hours. */
    fun checkOnLaunch(context: Context) {
        val s = Prefs.settings.value
        if (!s.appUpdates || System.currentTimeMillis() - s.lastAppCheck < LAUNCH_CHECK_EVERY) return
        check(context)
    }

    fun check(context: Context) {
        if (job?.isActive == true || state.value is UpdateState.Downloading || state.value is UpdateState.Installing || state.value is UpdateState.Ready) return
        job = scope.launch {
            _state.value = UpdateState.Checking
            _state.value = runCatching { latest() }.fold(
                onSuccess = { r ->
                    Prefs.edit { it.copy(lastAppCheck = System.currentTimeMillis()) }
                    if (r != null && newer(r.version, BuildConfig.VERSION_NAME)) UpdateState.Available(r) else UpdateState.UpToDate
                },
                onFailure = { UpdateState.Failed(context.getString(R.string.upd_no_github, it.message ?: it.javaClass.simpleName), null) },
            )
        }
    }

    /** Starts a background download, or installs the already verified APK on an explicit tap. */
    fun install(context: Context, release: Release) {
        if (job?.isActive == true || state.value is UpdateState.Downloading || state.value is UpdateState.Installing) return
        val app = context.applicationContext
        val saved = pending(app).read()
        if (saved?.first == release && saved.second) {
            _state.value = UpdateState.Installing(release)
            UpdateNotifications.cancelResult(app)
            job = scope.launch {
                try {
                    verify(app, pending(app).apk)
                    commit(app, pending(app).apk)
                } catch (e: Exception) {
                    failed(app, release, e.message ?: app.getString(R.string.upd_failed))
                }
            }
            return
        }
        try {
            pending(app).save(release, ready = false)
            downloading(release, 0f)
            ContextCompat.startForegroundService(app, Intent(app, UpdateDownloadService::class.java)
                .putExtra(UpdateDownloadService.EXTRA_RELEASE, release.toJson().toString()))
        } catch (e: Exception) {
            failed(app, release, e.message ?: app.getString(R.string.upd_failed))
        }
    }

    internal fun pending(context: Context) = PendingUpdate(File(context.filesDir, "updates"))
    internal fun downloading(release: Release, progress: Float) { _state.value = UpdateState.Downloading(release, progress) }
    internal fun ready(context: Context, release: Release) {
        pending(context).save(release, ready = true)
        // Download completion is news even if the earlier release offer was dismissed.
        Prefs.edit { if (it.seenRelease == release.version) it.copy(seenRelease = "") else it }
        _state.value = UpdateState.Ready(release)
    }
    internal fun available(release: Release) { _state.value = UpdateState.Available(release) }
    internal fun failed(context: Context, release: Release?, message: String) {
        _state.value = UpdateState.Failed(message, release)
        UpdateNotifications.failed(context, message)
    }

    /** A service calls this; completion never starts an installer or interrupts the current app. */
    internal fun fetch(context: Context, release: Release, connection: (HttpURLConnection) -> Unit, progress: (Float) -> Unit) {
        val store = pending(context)
        val apk = download(context, release, connection, progress)
        try {
            verify(context, apk)
            Files.move(apk.toPath(), store.apk.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            apk.delete()
            throw e
        }
    }

    /** The banner for this release was closed; it stays in Settings > About. */
    fun dismiss(release: Release) = Prefs.edit { it.copy(seenRelease = release.version) }

    // ---- GitHub ---------------------------------------------------------------------

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 30_000
        setRequestProperty("User-Agent", "Hamstr/${BuildConfig.VERSION_NAME}")
    }

    private fun latest(): Release? {
        val conn = open("https://api.github.com/repos/$REPO/releases/latest")
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            if (conn.responseCode == 404) return null // nothing published yet
            if (conn.responseCode != 200) error("HTTP ${conn.responseCode}")
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val assets = json.getJSONArray("assets").let { a -> (0 until a.length()).map(a::getJSONObject) }
                .filter { it.getString("name").endsWith(".apk") }
            // The split for this phone's ABI, else the universal one.
            val apk = Build.SUPPORTED_ABIS.firstNotNullOfOrNull { abi -> assets.firstOrNull { it.getString("name").endsWith("-$abi.apk") } }
                ?: assets.firstOrNull { it.getString("name").endsWith("-universal.apk") }
                ?: return null
            return Release(
                version = json.getString("tag_name").removePrefix("v"),
                notes = json.optString("body").trim(),
                apkUrl = apk.getString("browser_download_url"),
                apkName = apk.getString("name"),
                size = apk.optLong("size"),
                page = json.getString("html_url"),
            )
        } finally {
            conn.disconnect()
        }
    }

    // ---- download and install --------------------------------------------------------

    private fun download(context: Context, release: Release, connection: (HttpURLConnection) -> Unit, progress: (Float) -> Unit): File {
        val out = pending(context).partial
        val conn = open(release.apkUrl) // github.com redirects to its file host; same scheme, so it follows
        connection(conn)
        try {
            if (conn.responseCode != 200) error("Download failed: HTTP ${conn.responseCode}")
            val total = release.size.takeIf { it > 0 } ?: conn.contentLengthLong
            var done = 0L
            conn.inputStream.use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var shown = -1
                    while (true) {
                        if (Thread.currentThread().isInterrupted) throw InterruptedException()
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        val pct = if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else -1
                        if (pct != shown) {
                            shown = pct
                            progress(if (pct < 0) -1f else pct / 100f)
                        }
                    }
                }
            }
            if (total > 0 && done != total) error(context.getString(R.string.upd_incomplete))
        } catch (e: Exception) {
            out.delete()
            throw e
        } finally {
            conn.disconnect()
        }
        return out
    }

    /** Catches a wrong or differently signed APK before Android rejects it with a vague error. */
    private fun verify(context: Context, apk: File) {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        val incoming = pm.getPackageArchiveInfo(apk.path, flags) ?: error(context.getString(R.string.upd_bad_apk))
        if (incoming.packageName != context.packageName) error(context.getString(R.string.upd_not_hamstr))
        val installed = pm.getPackageInfo(context.packageName, flags)
        if (signers(incoming) != signers(installed)) {
            apk.delete()
            error(context.getString(R.string.upd_other_key))
        }
    }

    @Suppress("DEPRECATION")
    private fun signers(info: PackageInfo): Set<String> {
        val sigs = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        return sigs.orEmpty().map { it.toCharsString() }.toSet()
    }

    private fun commit(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            setInstallReason(PackageManager.INSTALL_REASON_USER)
            // Skips the confirmation once Hamstr is the app that installed itself.
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        try { installer.openSession(id).use { session ->
            session.openWrite("hamstr.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val result = PendingIntent.getBroadcast(
                context, id,
                Intent(context, InstallReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE, // the installer fills in the status
            )
            session.commit(result.intentSender)
        } } catch (e: Exception) { installer.abandonSession(id); throw e }
    }

    internal fun onResult(context: Context, intent: Intent) {
        val release = (state.value as? UpdateState.Installing)?.release ?: pending(context).read()?.first
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                    ?.let {
                        if (Seen.foreground) context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        else UpdateNotifications.confirm(context, it)
                    }
            }
            PackageInstaller.STATUS_SUCCESS -> { pending(context).clear(); _state.value = UpdateState.Idle }
            else -> {
                val why = when (status) {
                    PackageInstaller.STATUS_FAILURE_ABORTED -> context.getString(R.string.upd_cancelled)
                    PackageInstaller.STATUS_FAILURE_STORAGE -> context.getString(R.string.upd_no_storage)
                    PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> context.getString(R.string.upd_refused)
                    else -> intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: context.getString(R.string.upd_failed)
                }
                failed(context, release, why)
            }
        }
    }

    /** After the swap the old process is gone; a notification brings the user back. */
    internal fun onUpdated(context: Context) {
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, CH_UPDATES)
            .setSmallIcon(R.drawable.ic_stat_hamstr)
            .setContentTitle(context.getString(R.string.upd_installed, BuildConfig.VERSION_NAME))
            .setContentText(context.getString(R.string.upd_tap_open))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(CH_UPDATES.hashCode(), n) }
    }

    /** True when [a] is a later version than [b] (1.10.0 > 1.9.2). */
    fun newer(a: String, b: String) = app.hamstr.engine.Versions.newer(a, b)
}

class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Updater.onResult(context, intent)
}

class UpdatedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) Updater.onUpdated(context)
    }
}
