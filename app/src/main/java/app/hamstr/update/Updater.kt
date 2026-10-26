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
import app.hamstr.BuildConfig
import app.hamstr.MainActivity
import app.hamstr.R
import app.hamstr.data.Prefs
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

/** A published Hamstr release and the APK in it that fits this phone. */
data class Release(
    val version: String,
    val notes: String,
    val apkUrl: String,
    val apkName: String,
    val size: Long,
    val page: String,
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: Release) : UpdateState
    data class Downloading(val release: Release, val progress: Float) : UpdateState
    data class Installing(val release: Release) : UpdateState
    data class Failed(val message: String, val release: Release?) : UpdateState
}

/**
 * Hamstr updates itself from its GitHub Releases: it looks at the latest release, downloads
 * the APK for this phone's ABI and hands it to the system installer, without a detour
 * through the browser. Once Hamstr installed itself, Android 12+ lets later updates go
 * through without asking again.
 */
object Updater {
    const val REPO = "Mailo037/Hamstr"
    private const val CH_UPDATES = "updates"
    private const val LAUNCH_CHECK_EVERY = 6 * 60 * 60 * 1000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()
    private var job: Job? = null

    fun init(context: Context) {
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CH_UPDATES, "App updates", NotificationManager.IMPORTANCE_DEFAULT))
        // An APK left over from an update that already went through.
        scope.launch { dir(context).listFiles()?.forEach { it.delete() } }
    }

    /** Quietly looks for a new release when the app starts, at most every few hours. */
    fun checkOnLaunch(context: Context) {
        val s = Prefs.settings.value
        if (!s.appUpdates || System.currentTimeMillis() - s.lastAppCheck < LAUNCH_CHECK_EVERY) return
        check(context)
    }

    fun check(context: Context) {
        if (job?.isActive == true) return
        job = scope.launch {
            _state.value = UpdateState.Checking
            _state.value = runCatching { latest() }.fold(
                onSuccess = { r ->
                    Prefs.edit { it.copy(lastAppCheck = System.currentTimeMillis()) }
                    if (r != null && newer(r.version, BuildConfig.VERSION_NAME)) UpdateState.Available(r) else UpdateState.UpToDate
                },
                onFailure = { UpdateState.Failed("Couldn't reach GitHub: ${it.message ?: it.javaClass.simpleName}", null) },
            )
        }
    }

    /** Downloads the release's APK and starts the system install. */
    fun install(context: Context, release: Release) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        job = scope.launch {
            try {
                val apk = download(app, release)
                verify(app, apk)
                _state.value = UpdateState.Installing(release)
                commit(app, apk)
            } catch (e: Exception) {
                _state.value = UpdateState.Failed(e.message ?: e.javaClass.simpleName, release)
            }
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

    private fun dir(context: Context) = File(context.cacheDir, "updates").apply { mkdirs() }

    private fun download(context: Context, release: Release): File {
        val out = File(dir(context), release.apkName)
        _state.value = UpdateState.Downloading(release, 0f)
        val conn = open(release.apkUrl) // github.com redirects to its file host; same scheme, so it follows
        try {
            if (conn.responseCode != 200) error("Download failed: HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: release.size
            conn.inputStream.use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var shown = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        val pct = if (total > 0) (done * 100 / total).toInt() else 0
                        if (pct != shown) {
                            shown = pct
                            _state.value = UpdateState.Downloading(release, pct / 100f)
                        }
                    }
                }
            }
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
        val incoming = pm.getPackageArchiveInfo(apk.path, flags) ?: error("The download isn't a valid APK")
        if (incoming.packageName != context.packageName) error("The download isn't Hamstr")
        val installed = pm.getPackageInfo(context.packageName, flags)
        if (signers(incoming) != signers(installed)) {
            apk.delete()
            error("This copy of Hamstr is signed with a different key (a debug build?). Install the release from GitHub once, after that it updates itself.")
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
        installer.openSession(id).use { session ->
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
        }
    }

    internal fun onResult(context: Context, intent: Intent) {
        val release = (state.value as? UpdateState.Installing)?.release
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                    ?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            PackageInstaller.STATUS_SUCCESS -> _state.value = UpdateState.Idle
            else -> {
                val why = when (status) {
                    PackageInstaller.STATUS_FAILURE_ABORTED -> "Install cancelled"
                    PackageInstaller.STATUS_FAILURE_STORAGE -> "Not enough storage"
                    PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "Android refused the update"
                    else -> intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Install failed"
                }
                _state.value = UpdateState.Failed(why, release)
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
            .setContentTitle("Hamstr ${BuildConfig.VERSION_NAME} is installed")
            .setContentText("Tap to open")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(CH_UPDATES.hashCode(), n) }
    }

    /** True when [a] is a later version than [b] (1.10.0 > 1.9.2). */
    fun newer(a: String, b: String): Boolean {
        fun parts(v: String) = v.removePrefix("v").substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val x = parts(a)
        val y = parts(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }
}

class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Updater.onResult(context, intent)
}

class UpdatedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) Updater.onUpdated(context)
    }
}
