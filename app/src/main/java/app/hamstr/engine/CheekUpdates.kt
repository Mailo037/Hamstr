package app.hamstr.engine

import android.util.Log
import app.hamstr.BuildConfig
import app.hamstr.data.Prefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Cheek updates itself from its own GitHub releases, the way yt-dlp does, so a new Cheek
 * doesn't need a new Hamstr release. The copy inside the APK stays as the fallback.
 */
object CheekUpdates {
    private const val TAG = "HamstrCheek"
    private const val REPO = "Mailo037/cheek"
    private val _check = MutableStateFlow<EngineVersionCheck>(EngineVersionCheck.Idle)
    val check: StateFlow<EngineVersionCheck> = _check.asStateFlow()
    private val _updating = MutableStateFlow(false)
    val updating: StateFlow<Boolean> = _updating.asStateFlow()
    private val mutex = Mutex()

    private class Latest(val version: String, val binary: String, val sums: String)

    /** Only looks; nothing is downloaded. */
    suspend fun checkVersion() = withContext(Dispatchers.IO) { mutex.withLock {
        _check.value = EngineVersionCheck.Checking
        try {
            val latest = latest()
            _check.value = if (Cheek.accepts(latest.version)) EngineVersionCheck.Available(latest.version) else EngineVersionCheck.Current
        } catch (e: CancellationException) {
            _check.value = EngineVersionCheck.Idle
            throw e
        } catch (e: Exception) {
            _check.value = EngineVersionCheck.Failed(e.message ?: "Version check failed")
        }
    } }

    /** Installs the newest compatible release, if there is one, and returns the version that runs. */
    suspend fun update(): Result<String> = withContext(Dispatchers.IO) { mutex.withLock {
        _updating.value = true
        try {
            val latest = latest()
            if (Cheek.accepts(latest.version)) {
                val sums = String(download(latest.sums)).lineSequence().filter { it.isNotBlank() }.associate { line ->
                    val (hash, name) = line.trim().split(Regex("\\s+"), limit = 2)
                    name.removePrefix("*") to hash.lowercase()
                }
                val bytes = download(latest.binary)
                val name = latest.binary.substringAfterLast('/')
                val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                check(hash == sums[name]) { "Cheek ${latest.version} didn't match its checksum" }
                Cheek.install(latest.version, bytes)
            }
            _check.value = EngineVersionCheck.Current
            Prefs.edit { it.copy(lastCheekCheck = System.currentTimeMillis()) }
            Result.success(Cheek.active.value)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "update failed", e)
            Result.failure(e)
        } finally {
            _updating.value = false
        }
    } }

    private suspend fun latest(): Latest {
        val release = JSONObject(String(download("https://api.github.com/repos/$REPO/releases/latest", json = true)))
        val version = release.getString("tag_name").trim().removePrefix("v")
        val assets = release.getJSONArray("assets").let { a -> List(a.length()) { a.getJSONObject(it) } }
        fun url(name: String) = assets.firstOrNull { it.optString("name") == name }?.optString("browser_download_url")
            ?: error("Cheek $version has no $name")
        return Latest(version, url("cheek-android-${Cheek.abi}"), url("SHA256SUMS.txt"))
    }

    private suspend fun download(url: String, json: Boolean = false): ByteArray = runInterruptible {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("User-Agent", "Hamstr/${BuildConfig.VERSION_NAME}")
            if (json) connection.setRequestProperty("Accept", "application/vnd.github+json")
            check(connection.responseCode == 200) { "GitHub answered HTTP ${connection.responseCode}" }
            connection.inputStream.use { it.readBytes() }
        } finally { connection.disconnect() }
    }
}
