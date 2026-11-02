package app.hamstr.desktop

import app.hamstr.Log
import app.hamstr.engine.Versions
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * yt-dlp, Cheek, FFmpeg and ffprobe. The installer brings them (desktop/build.gradle.kts,
 * fetchTools); on first start they are copied into the app's own folder, where they can be
 * run and where yt-dlp can update itself. Cheek's updates (ToolUpdates) go next to the bundled
 * copy, one folder per version, and `cheek.current` names the one that runs.
 */
object Tools {
    private const val TAG = "HamstrTools"
    private val exe = if (Paths.windows) ".exe" else ""
    val dir = File(Paths.data, "tools")

    val ytDlp get() = File(dir, "yt-dlp$exe")
    val ffmpeg get() = File(dir, "ffmpeg$exe")
    /** Hamstr's own Bluetooth helper (desktop/ble), for Nearby without a shared network. */
    val bluetooth get() = File(dir, "hamstr-ble$exe")

    /** The Cheek this Hamstr came with. */
    val bundledCheekVersion: String = System.getProperty("hamstr.cheekVersion", "")
    private val bundledCheek get() = File(dir, "cheek$exe")
    private val current get() = File(dir, "cheek.current")

    /** A downloaded Cheek that is newer than the bundled one and keeps its command line, if any. */
    private fun downloaded(): Pair<String, File>? {
        val version = runCatching { current.readText().trim() }.getOrNull() ?: return null
        val file = File(dir, "cheek-$version/cheek$exe")
        val usable = file.isFile && Versions.sameCheekLine(version, bundledCheekVersion) &&
            (bundledCheekVersion.isEmpty() || Versions.newer(version, bundledCheekVersion))
        return if (usable) version to file else null
    }

    /** The Cheek that runs: an update when there is one, else the bundled copy. */
    val cheek get() = downloaded()?.second ?: bundledCheek

    /** Its version, for the settings and for what Hamstr may ask of it. */
    val cheekVersion get() = downloaded()?.first ?: bundledCheekVersion

    val hasCheek get() = cheek.isFile

    /** Puts a downloaded Cheek in place once it has shown that it starts here. */
    fun installCheek(version: String, bytes: ByteArray) {
        val folder = File(dir, "cheek-$version").apply { deleteRecursively(); mkdirs() }
        val file = File(folder, "cheek$exe")
        file.writeBytes(bytes)
        check(file.setExecutable(true)) { "Couldn't mark Cheek $version as executable" }
        val p = ProcessBuilder(file.path, "--version").redirectErrorStream(true).start()
        try {
            check(p.waitFor(10, TimeUnit.SECONDS)) { "Cheek $version didn't start" }
            check(p.exitValue() == 0 && p.inputStream.bufferedReader().readText().trim().startsWith("cheek ")) { "Cheek $version doesn't run here" }
        } finally {
            if (p.isAlive) p.destroyForcibly()
        }
        File(dir, "cheek.current.tmp").apply { writeText(version) }.let { tmp ->
            java.nio.file.Files.move(tmp.toPath(), current.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
        // Older updates go; one still running a download keeps its file until next time (Windows).
        dir.listFiles().orEmpty().filter { it.isDirectory && it.name.startsWith("cheek-") && it.name != "cheek-$version" }
            .forEach { it.deleteRecursively() }
    }

    /** Copies the bundled tools in place when this version of the app hasn't yet. */
    fun install() {
        dir.mkdirs()
        val bundled = System.getProperty("compose.application.resources.dir")?.let(::File)?.takeIf { it.isDirectory }
        if (bundled == null) {
            Log.w(TAG, "No bundled tools; run ./gradlew :desktop:fetchTools")
            return
        }
        val files = bundled.listFiles().orEmpty().filter { it.isFile }
        // A new app version (or a new bundle) brings its own tools; yt-dlp's updates in between stay.
        val stamp = File(dir, "bundle.txt")
        val bundle = System.getProperty("hamstr.version", "dev") + " " + files.joinToString(",") { "${it.name}:${it.length()}" }
        if (stamp.isFile && stamp.readText() == bundle && files.all { File(dir, it.name).isFile }) return
        files.forEach { f ->
            val target = File(dir, f.name)
            // A yt-dlp that updated itself since this app was built is newer than the one it brings.
            if (f.name == ytDlp.name && target.isFile && keepsNewer(target, f)) return@forEach
            f.copyTo(target, overwrite = true)
            target.setExecutable(true)
        }
        stamp.writeText(bundle)
        Log.i(TAG, "Installed ${files.size} tools from $bundled")
    }

    /** True when the installed yt-dlp is newer than [bundled]; yt-dlp's versions are dates, 2026.10.26. */
    private fun keepsNewer(installed: File, bundled: File): Boolean {
        fun version(f: File) = runCatching {
            val p = ProcessBuilder(f.path, "--version").redirectErrorStream(true).start()
            if (!p.waitFor(20, TimeUnit.SECONDS)) { p.destroyForcibly(); null } else p.inputStream.bufferedReader().readText().trim().lines().lastOrNull()
        }.getOrNull()
        val have = version(installed) ?: return false
        val brought = version(bundled) ?: return true
        return Versions.newer(have, brought)
    }
}
