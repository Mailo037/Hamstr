package app.hamstr.desktop

import app.hamstr.Log
import app.hamstr.data.DownloadStore
import app.hamstr.data.Prefs
import app.hamstr.engine.ReleaseKeys
import app.hamstr.engine.Versions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI

/**
 * Keeps yt-dlp and Cheek fresh while Hamstr sits in the tray for days, like the phone does:
 * once a day, when nothing is downloading, if Settings › Engine allows it. Cheek comes from
 * its own releases, only signed ones that keep the command line this Hamstr speaks.
 */
object ToolUpdates {
    private const val TAG = "HamstrTools"
    private const val DAY = 24 * 3600_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    private val _cheekUpdating = MutableStateFlow(false)
    val cheekUpdating: StateFlow<Boolean> = _cheekUpdating.asStateFlow()
    private val _cheekError = MutableStateFlow<String?>(null)
    /** Why the last Cheek update failed, or null. */
    val cheekError: StateFlow<String?> = _cheekError.asStateFlow()
    private val _cheekVersion = MutableStateFlow(Tools.cheekVersion)
    val cheekVersion: StateFlow<String> = _cheekVersion.asStateFlow()

    /** The Cheek this computer runs: x86_64 or Apple silicon, as cheek's release names them. */
    private val asset: String? = run {
        val arm = System.getProperty("os.arch").lowercase().let { it == "aarch64" || it == "arm64" }
        when {
            Paths.windows -> if (arm) null else "cheek-x86_64-windows.exe"
            Paths.mac -> if (arm) "cheek-aarch64-macos" else "cheek-x86_64-macos"
            else -> if (arm) "cheek-aarch64-linux" else "cheek-x86_64-linux"
        }
    }

    fun watch() = scope.launch {
        delay(30_000)
        while (true) {
            // An update in the middle of a download would pull the tool out from under it.
            if (DownloadStore.tasks.value.none { it.status.active }) {
                val s = Prefs.settings.value
                val now = System.currentTimeMillis()
                if (s.autoUpdate && now - s.lastUpdateCheck > DAY) {
                    Queue.updateYtDlpNow()
                    Prefs.edit { it.copy(lastUpdateCheck = System.currentTimeMillis()) }
                }
                if (s.cheekAutoUpdate && now - s.lastCheekCheck > DAY) updateCheek()
            }
            delay(30 * 60_000L)
        }
    }

    /** Installs the newest signed, compatible Cheek, if it is newer than the one that runs. */
    suspend fun updateCheek(): Result<String> = mutex.withLock {
        _cheekUpdating.value = true
        _cheekError.value = null
        try {
            val name = asset ?: error("Cheek has no build for this computer")
            val release = JSONObject(String(fetch("https://api.github.com/repos/Mailo037/cheek/releases/latest", json = true)))
            val version = release.getString("tag_name").trim().removePrefix("v")
            if (Versions.sameCheekLine(version, Tools.bundledCheekVersion) && Versions.newer(version, Tools.cheekVersion)) {
                val assets = release.getJSONArray("assets").let { a -> List(a.length()) { a.getJSONObject(it) } }
                fun url(asset: String) = assets.firstOrNull { it.optString("name") == asset }?.optString("browser_download_url")
                val sums = fetch(url("SHA256SUMS.txt") ?: error("Cheek $version has no checksums"))
                val signature = url("SHA256SUMS.txt.sig") ?: error("Cheek $version isn't signed")
                check(ReleaseKeys.verify(sums, fetch(signature), ReleaseKeys.CHEEK)) { "Cheek $version has no valid signature" }
                val bytes = fetch(url(name) ?: error("Cheek $version has no $name"))
                check(ReleaseKeys.sha256(bytes) == ReleaseKeys.sums(String(sums))[name]) { "Cheek $version didn't match its checksum" }
                Tools.installCheek(version, bytes)
                Log.i(TAG, "Cheek $version installed")
            }
            Prefs.edit { it.copy(lastCheekCheck = System.currentTimeMillis()) }
            _cheekVersion.value = Tools.cheekVersion
            Result.success(Tools.cheekVersion)
        } catch (e: Exception) {
            Log.w(TAG, "Cheek update failed", e)
            _cheekError.value = e.message ?: e.toString()
            Result.failure(e)
        } finally {
            _cheekUpdating.value = false
        }
    }

    /** Starts [updateCheek] from a button, in this object's own scope. */
    fun updateCheekSoon() {
        if (!_cheekUpdating.value) scope.launch { updateCheek() }
    }

    private suspend fun fetch(url: String, json: Boolean = false): ByteArray = runInterruptible {
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("User-Agent", "Hamstr/${Updater.version}")
            if (json) conn.setRequestProperty("Accept", "application/vnd.github+json")
            check(conn.responseCode == 200) { "GitHub answered HTTP ${conn.responseCode}" }
            conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }
}
