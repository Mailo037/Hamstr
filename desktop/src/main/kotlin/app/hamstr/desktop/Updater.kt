package app.hamstr.desktop

import app.hamstr.Log
import app.hamstr.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.ZipInputStream
import kotlin.system.exitProcess

/** A published Hamstr release and what this copy of Hamstr updates itself with, once the release has it. */
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
    /** Released, but its installer for this system is still being built (release.yml adds it later). */
    data class Coming(val version: String) : UpdateState
    data class Available(val release: Release) : UpdateState
    data class Downloading(val release: Release, val progress: Float) : UpdateState
    /** [restarts]: Hamstr closes and comes back as the new version; otherwise the system's installer is open. */
    data class Installing(val release: Release, val restarts: Boolean) : UpdateState
    /** [offline]: GitHub couldn't be reached at all. */
    data class Failed(val message: String, val release: Release?, val offline: Boolean = false) : UpdateState
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
 * The desktop's updates, from the same GitHub Releases as the phone's, checked against the
 * release's SHA256SUMS.txt. Hamstr installs them itself and opens again as the new version:
 * Windows runs the setup silently (or replaces a portable copy's files), macOS swaps the app
 * from the .dmg, Linux installs the .deb after the password prompt. A helper outside Hamstr
 * does the part that needs Hamstr gone, then starts it with [UPDATED_FLAG].
 */
object Updater {
    private const val TAG = "HamstrUpdater"
    const val REPO = "Mailo037/Hamstr"
    const val UPDATED_FLAG = "--updated="
    private const val CHECK_EVERY = 6 * 60 * 60 * 1000L
    val version: String = System.getProperty("hamstr.version", "dev")

    /** The release's name for this system, as release.yml's matrix calls it. */
    private val system = when {
        Paths.windows -> "windows"
        Paths.mac -> "macos"
        else -> "linux"
    } + "-" + if (System.getProperty("os.arch").lowercase().let { it == "aarch64" || it == "arm64" }) "arm64" else "x64"

    /** The launcher jpackage made (Hamstr.exe, …/Hamstr.app/Contents/MacOS/Hamstr, /opt/hamstr/bin/Hamstr); null from Gradle. */
    private val launcher: File? = System.getProperty("jpackage.app-path")?.let(::File)?.takeIf { it.exists() }

    /** How this Hamstr got here, which decides what it updates with. */
    private enum class Kind(val suffix: String) {
        Setup(".exe"), Portable("-portable.zip"), Dmg(".dmg"), Deb(".deb"),
        /** From Gradle: nothing to replace, so the installer just opens. */
        None(".exe"),
    }

    private val kind = when {
        launcher == null -> Kind.None
        // Inno Setup leaves its uninstaller next to the app; the portable zip has none.
        Paths.windows -> if (File(launcher.parentFile, "unins000.exe").exists()) Kind.Setup else Kind.Portable
        Paths.mac -> Kind.Dmg
        else -> Kind.Deb
    }

    private val dataEnv: String? = System.getenv("HAMSTR_DATA")
    private val updates get() = File(Paths.data, "updates")
    /** The helper's exit code for the setup or copy, read by the next start. */
    private val result get() = File(updates, "result.txt")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()
    private var job: Job? = null
    /** A newer release is out whose installer for this system isn't yet: look again soon. */
    @Volatile private var coming = false

    /**
     * Looks for a new release when the app starts and every few hours after (in the tray Hamstr
     * may run for days), every few minutes while a release's installer is still being built.
     */
    fun watch() {
        // For trying an update end to end (AGENTS.md): installs what HAMSTR_RELEASES offers, unasked.
        if (System.getenv("HAMSTR_UPDATE") == "now") scope.launch {
            check()
            job?.join()
            (state.value as? UpdateState.Available)?.let { install(it.release) { exitProcess(0) } }
        }
        scope.launch {
            while (true) {
                val s = Prefs.settings.value
                val quiet = state.value.let { it == UpdateState.Idle || it == UpdateState.UpToDate || it is UpdateState.Coming || (it is UpdateState.Failed && it.offline) }
                if (s.appUpdates && quiet && (coming || System.currentTimeMillis() - s.lastAppCheck >= CHECK_EVERY)) check()
                delay(if (coming) 10 * 60_000L else 30 * 60_000L)
            }
        }
    }

    fun check() {
        if (job?.isActive == true) return
        job = scope.launch {
            _state.value = UpdateState.Checking
            _state.value = runCatching { latest() }.fold(
                onSuccess = { r ->
                    coming = r != null && newer(r.version, version) && r.installer == null
                    // A release still being built is looked at again soon, so it isn't a finished check.
                    if (!coming) Prefs.edit { it.copy(lastAppCheck = System.currentTimeMillis()) }
                    when {
                        r == null || !newer(r.version, version) -> UpdateState.UpToDate
                        coming -> UpdateState.Coming(r.version)
                        else -> UpdateState.Available(r)
                    }
                },
                onFailure = {
                    Log.w(TAG, "No release info", it)
                    UpdateState.Failed(it.message ?: it.javaClass.simpleName, null, offline = true)
                },
            )
        }
    }

    /** Downloads the update, checks it and installs it; [quit] closes Hamstr, which then comes back as the new version. */
    fun install(release: Release, quit: () -> Unit) {
        val url = release.installer ?: return
        if (job?.isActive == true) return
        job = scope.launch {
            try {
                val file = download(release, url)
                release.sums?.let { verify(file, it) }
                _state.value = UpdateState.Installing(release, restarts = kind != Kind.None)
                when (kind) {
                    Kind.Setup -> {
                        // /OUTOFJOB: the helper is outside Hamstr's job already (see hamstr.iss).
                        startOutside(windowsScript(release.version) {
                            "try { \$code = (Start-Process -FilePath ${ps(file.path)} -ArgumentList '/VERYSILENT','/SUPPRESSMSGBOXES','/NORESTART','/OUTOFJOB' -Wait -PassThru).ExitCode } catch {}"
                        })
                        quit()
                    }
                    Kind.Portable -> {
                        val fresh = unzip(file)
                        startOutside(windowsScript(release.version) { portableSteps(launcher!!.parentFile, fresh) })
                        quit()
                    }
                    Kind.Dmg -> {
                        val bundle = generateSequence(launcher) { it.parentFile }.firstOrNull { it.name.endsWith(".app") }
                        if (bundle == null || !bundle.parentFile.canWrite()) {
                            // Somewhere this user can't write: the system's installer asks.
                            Files.open(file)
                            _state.value = UpdateState.Installing(release, restarts = false)
                        } else {
                            startUnix(macScript(bundle, file, release.version))
                            quit()
                        }
                    }
                    Kind.Deb -> installDeb(release, file, quit)
                    Kind.None -> Files.open(file)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Update failed", e)
                _state.value = UpdateState.Failed(e.message ?: e.javaClass.simpleName, release)
            }
        }
    }

    /** Started again by the update's helper: says so when the new version didn't arrive, and tidies up. */
    fun restarted(expected: String) {
        val code = runCatching { result.readText().trim() }.getOrNull()
        updates.deleteRecursively()
        if (!newer(expected, version)) return
        Log.w(TAG, "Still $version after updating to $expected (helper: $code)")
        _state.value = UpdateState.Failed(
            when (code) {
                "2", "5" -> "The update was cancelled"
                null -> "The update didn't install"
                else -> "The update didn't install (code $code)"
            },
            null,
        )
    }

    // ---- Windows -----------------------------------------------------------------------

    /** A PowerShell string literal. */
    private fun ps(s: String) = "'" + s.replace("'", "''") + "'"

    private fun encoded(script: String) = Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))

    /**
     * The helper: waits until this Hamstr is gone (its JVM and the launcher around it), runs
     * [steps], which set $code, notes the code for the next start and starts Hamstr again.
     */
    private fun windowsScript(version: String, steps: () -> String): String {
        val me = ProcessHandle.current()
        val launcherPid = me.parent().orElse(null)?.takeIf { p -> p.info().command().orElse("").endsWith("Hamstr.exe", ignoreCase = true) }?.pid()
        val pids = listOfNotNull(me.pid(), launcherPid).joinToString(",")
        return listOf(
            "\$ErrorActionPreference = 'SilentlyContinue'",
            "foreach (\$id in @($pids)) { \$p = Get-Process -Id \$id; if (\$p -and -not \$p.WaitForExit(30000)) { Stop-Process -Id \$id -Force } }",
            "Start-Sleep -Milliseconds 300",
            "\$code = 1",
            steps(),
            "Set-Content -LiteralPath ${ps(result.path)} -Value \$code",
            // A Hamstr with its own data folder keeps it (WMI starts the helper with the user's plain environment).
            dataEnv?.let { "\$env:HAMSTR_DATA = ${ps(it)}" }.orEmpty(),
            "Start-Process -FilePath ${ps(launcher!!.path)} -ArgumentList ${ps(UPDATED_FLAG + version)}",
        ).joinToString("\n")
    }

    /** A portable copy: the new app and runtime folders replace the old ones, which come back if copying fails. */
    private fun portableSteps(dir: File, fresh: File): String = listOf(
        "\$dir = ${ps(dir.path)}",
        "Remove-Item -LiteralPath \"\$dir\\app.old\", \"\$dir\\runtime.old\" -Recurse -Force",
        "try {",
        "  Move-Item -LiteralPath \"\$dir\\app\" -Destination \"\$dir\\app.old\" -ErrorAction Stop",
        "  Move-Item -LiteralPath \"\$dir\\runtime\" -Destination \"\$dir\\runtime.old\" -ErrorAction Stop",
        "  robocopy ${ps(fresh.path)} \$dir /E /R:2 /W:1 /NFL /NDL /NJH /NJS /NP | Out-Null",
        "  \$code = if (\$LASTEXITCODE -lt 8) { 0 } else { \$LASTEXITCODE }",
        "} catch { \$code = 2 }",
        "foreach (\$d in 'app', 'runtime') {",
        "  if (\$code -eq 0) { Remove-Item -LiteralPath \"\$dir\\\$d.old\" -Recurse -Force }",
        "  elseif (Test-Path -LiteralPath \"\$dir\\\$d.old\") { Remove-Item -LiteralPath \"\$dir\\\$d\" -Recurse -Force; Move-Item -LiteralPath \"\$dir\\\$d.old\" -Destination \"\$dir\\\$d\" }",
        "}",
    ).joinToString("\n")

    /**
     * Starts [script] through WMI. jpackage's launcher keeps Hamstr in a job that ends every
     * process Hamstr started once it quits; WMI starts the helper outside it, hidden.
     */
    private fun startOutside(script: String) {
        val helper = "powershell -NoProfile -NonInteractive -ExecutionPolicy Bypass -WindowStyle Hidden -EncodedCommand ${encoded(script)}"
        val start = listOf(
            "\$si = New-CimInstance -ClassName Win32_ProcessStartup -ClientOnly -Property @{ ShowWindow = [uint16]0 }",
            "\$r = Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{ CommandLine = ${ps(helper)}; ProcessStartupInformation = \$si }",
            "exit [int]\$r.ReturnValue",
        ).joinToString("\n")
        val p = ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded(start)).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().use { it.readText() }
        check(p.waitFor() == 0) { "Couldn't start the installer${out.trim().takeIf { it.isNotEmpty() }?.let { ": " + it.take(200) }.orEmpty()}" }
    }

    /** The portable zip's Hamstr folder, unpacked into updates/new. */
    private fun unzip(zip: File): File {
        val out = File(updates, "new").apply { deleteRecursively(); mkdirs() }
        val root = out.canonicalFile
        ZipInputStream(zip.inputStream().buffered()).use { z ->
            generateSequence { z.nextEntry }.forEach { e ->
                // Hamstr/app/… → app/…
                val name = e.name.replace('\\', '/').substringAfter('/', "")
                if (name.isEmpty()) return@forEach
                val target = File(out, name).canonicalFile
                check(target.path.startsWith(root.path + File.separator)) { "Bad path in the update: ${e.name}" }
                if (e.isDirectory) target.mkdirs() else {
                    target.parentFile.mkdirs()
                    target.outputStream().use { z.copyTo(it) }
                }
            }
        }
        check(File(out, "Hamstr.exe").exists()) { "The update has no Hamstr.exe" }
        return out
    }

    // ---- macOS and Linux ------------------------------------------------------------------

    /** A shell string literal. */
    private fun sh(s: String) = "'" + s.replace("'", "'\\''") + "'"

    /** Starts [script] apart from Hamstr; it waits for Hamstr to quit by itself. */
    private fun startUnix(script: String) {
        ProcessBuilder("/bin/sh", "-c", script)
            .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
    }

    private fun waitForMe() = "trap '' HUP\nwhile kill -0 ${ProcessHandle.current().pid()} 2>/dev/null; do sleep 0.3; done"

    /** The new app from the .dmg goes where this one is; this one comes back if moving it fails. */
    private fun macScript(bundle: File, dmg: File, version: String): String {
        val app = sh(bundle.path)
        val fresh = sh(bundle.path + ".new")
        val old = sh(bundle.path + ".old")
        return listOf(
            waitForMe(),
            "code=1",
            "mnt=\$(mktemp -d)",
            "if hdiutil attach -nobrowse -readonly -noautoopen -mountpoint \"\$mnt\" ${sh(dmg.path)} >/dev/null; then",
            "  src=\$(ls -d \"\$mnt\"/*.app | head -n 1)",
            "  rm -rf $fresh $old",
            "  if [ -n \"\$src\" ] && ditto \"\$src\" $fresh && mv $app $old; then",
            "    if mv $fresh $app; then code=0; rm -rf $old; else mv $old $app; fi",
            "  fi",
            "  rm -rf $fresh",
            "  hdiutil detach \"\$mnt\" -quiet",
            "fi",
            "echo \$code > ${sh(result.path)}",
            "open $app --args ${sh(UPDATED_FLAG + version)}",
        ).joinToString("\n")
    }

    private fun which(name: String) = System.getenv("PATH").orEmpty().split(File.pathSeparator)
        .map { File(it, name) }.firstOrNull { it.canExecute() }?.path

    /** dpkg installs over the running app (Linux keeps its open files); pkexec asks for the password. */
    private fun installDeb(release: Release, deb: File, quit: () -> Unit) {
        val pkexec = which("pkexec")
        val dpkg = which("dpkg")
        if (pkexec == null || dpkg == null || launcher?.path?.startsWith("/opt/") != true) {
            Files.open(deb)
            _state.value = UpdateState.Installing(release, restarts = false)
            return
        }
        val p = ProcessBuilder(pkexec, dpkg, "-i", deb.absolutePath).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().use { it.readText() }
        when (val code = p.waitFor()) {
            0 -> {
                startUnix(listOf(waitForMe(), "echo 0 > ${sh(result.path)}", "exec ${sh(launcher.path)} ${sh(UPDATED_FLAG + release.version)}").joinToString("\n"))
                quit()
            }
            // pkexec: the password prompt was closed, or the user may not install.
            126, 127 -> _state.value = UpdateState.Failed("The update was cancelled", release)
            else -> _state.value = UpdateState.Failed("dpkg: " + (out.lines().lastOrNull { it.isNotBlank() } ?: "exit $code"), release)
        }
    }

    // ---- GitHub ---------------------------------------------------------------------

    private fun open(url: String): HttpURLConnection = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 30_000
        setRequestProperty("User-Agent", "Hamstr/$version")
    }

    private fun latest(): Release? {
        val conn = open(System.getenv("HAMSTR_RELEASES") ?: "https://api.github.com/repos/$REPO/releases/latest")
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            if (conn.responseCode == 404) return null // nothing published yet
            if (conn.responseCode != 200) error("HTTP ${conn.responseCode}")
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val assets = json.getJSONArray("assets").let { a -> (0 until a.length()).map(a::getJSONObject) }
            // Hamstr-<version>-<system><suffix>; release.yml uploads them after the release is out.
            val installer = assets.firstOrNull { it.getString("name").endsWith("-$system${kind.suffix}") }
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
        val dir = updates.apply { deleteRecursively(); mkdirs() }
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
