package app.hamstr.desktop

import app.hamstr.Log
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
import java.net.URI
import java.security.MessageDigest

/** A published Hamstr release and its installer for this system, if it has one. */
data class Release(
    val version: String,
    val page: String,
    val installer: String?,
    val installerName: String,
    val size: Long,
    val sums: String?,
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

val UpdateState.release: Release?
    get() = when (this) {
        is UpdateState.Available -> release
        is UpdateState.Downloading -> release
        is UpdateState.Installing -> release
        is UpdateState.Failed -> release
        else -> null
    }

/**
 * The desktop's updates, from the same GitHub Releases as the phone's: the release carries an
 * installer per system (release.yml), checked against the release's SHA256SUMS.txt. On Windows
 * the setup replaces the app in place, so Hamstr closes for it; elsewhere the system's installer opens.
 */
object Updater {
    private const val TAG = "HamstrUpdater"
    const val REPO = "Mailo037/Hamstr"
    private const val LAUNCH_CHECK_EVERY = 6 * 60 * 60 * 1000L
    val version: String = System.getProperty("hamstr.version", "dev")

    /** The release's name for this system, as release.yml's matrix calls it. */
    private val system = when {
        Paths.windows -> "windows"
        Paths.mac -> "macos"
        else -> "linux"
    } + "-" + if (System.getProperty("os.arch").lowercase().let { it == "aarch64" || it == "arm64" }) "arm64" else "x64"
    private val extension = when {
        Paths.windows -> "exe"
        Paths.mac -> "dmg"
        else -> "deb"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()
    private var job: Job? = null

    /** Quietly looks for a new release when the app starts, at most every few hours. */
    fun checkOnLaunch() {
        val s = Prefs.settings.value
        if (!s.appUpdates || System.currentTimeMillis() - s.lastAppCheck < LAUNCH_CHECK_EVERY) return
        check()
    }

    fun check() {
        if (job?.isActive == true) return
        job = scope.launch {
            _state.value = UpdateState.Checking
            _state.value = runCatching { latest() }.fold(
                onSuccess = { r ->
                    Prefs.edit { it.copy(lastAppCheck = System.currentTimeMillis()) }
                    if (r != null && newer(r.version, version)) UpdateState.Available(r) else UpdateState.UpToDate
                },
                onFailure = {
                    Log.w(TAG, "No release info", it)
                    UpdateState.Failed(it.message ?: it.javaClass.simpleName, null)
                },
            )
        }
    }

    /** Downloads the installer, checks it and starts it; [quit] closes Hamstr where the installer needs it gone. */
    fun install(release: Release, quit: () -> Unit) {
        val url = release.installer ?: return Files.browse(release.page)
        if (job?.isActive == true) return
        job = scope.launch {
            try {
                val file = download(release, url)
                release.sums?.let { verify(file, it) }
                _state.value = UpdateState.Installing(release)
                if (Paths.windows) {
                    // Hamstr's own files are in use while it runs; the setup takes over from here.
                    ProcessBuilder(file.absolutePath).start()
                    quit()
                } else {
                    Files.open(file)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Update failed", e)
                _state.value = UpdateState.Failed(e.message ?: e.javaClass.simpleName, release)
            }
        }
    }

    // ---- GitHub ---------------------------------------------------------------------

    private fun open(url: String): HttpURLConnection = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 30_000
        setRequestProperty("User-Agent", "Hamstr/$version")
    }

    private fun latest(): Release? {
        val conn = open("https://api.github.com/repos/$REPO/releases/latest")
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            if (conn.responseCode == 404) return null // nothing published yet
            if (conn.responseCode != 200) error("HTTP ${conn.responseCode}")
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val assets = json.getJSONArray("assets").let { a -> (0 until a.length()).map(a::getJSONObject) }
            // Hamstr-<version>-<system>.<exe|dmg|deb>; no installer for this system leaves the release page.
            val installer = assets.firstOrNull { it.getString("name").endsWith("-$system.$extension") }
            val sums = assets.firstOrNull { it.getString("name") == "SHA256SUMS.txt" }
            return Release(
                version = json.getString("tag_name").removePrefix("v"),
                page = json.getString("html_url"),
                installer = installer?.getString("browser_download_url"),
                installerName = installer?.getString("name").orEmpty(),
                size = installer?.optLong("size") ?: 0,
                sums = sums?.getString("browser_download_url"),
            )
        } finally {
            conn.disconnect()
        }
    }

    // ---- download and check ----------------------------------------------------------

    private fun download(release: Release, url: String): File {
        val dir = File(Paths.data, "updates").apply { deleteRecursively(); mkdirs() }
        val out = File(dir, release.installerName)
        _state.value = UpdateState.Downloading(release, 0f)
        val conn = open(url) // github.com redirects to its file host; same scheme, so it follows
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

    /** The installer's hash against the "<hash>  <name>" line its release published. */
    private fun verify(file: File, sumsUrl: String) {
        val conn = open(sumsUrl)
        val sums = try {
            if (conn.responseCode != 200) error("Checksums: HTTP ${conn.responseCode}")
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
        val expected = sums.lineSequence().map { it.trim().split(Regex("\\s+"), limit = 2) }
            .firstOrNull { it.size == 2 && it[1].removePrefix("*") == file.name }?.get(0)?.lowercase()
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (expected != actual) {
            file.delete()
            error("The download doesn't match its checksum")
        }
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
