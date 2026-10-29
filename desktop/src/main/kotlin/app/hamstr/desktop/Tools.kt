package app.hamstr.desktop

import app.hamstr.Log
import java.io.File

/**
 * yt-dlp, Cheek, FFmpeg and ffprobe. The installer brings them (desktop/build.gradle.kts,
 * fetchTools); on first start they are copied into the app's own folder, where they can be
 * run and where yt-dlp can update itself.
 */
object Tools {
    private const val TAG = "HamstrTools"
    private val exe = if (Paths.windows) ".exe" else ""
    val dir = File(Paths.data, "tools")

    val ytDlp get() = File(dir, "yt-dlp$exe")
    val cheek get() = File(dir, "cheek$exe")
    val ffmpeg get() = File(dir, "ffmpeg$exe")

    val hasCheek get() = cheek.isFile

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
            f.copyTo(target, overwrite = true)
            target.setExecutable(true)
        }
        stamp.writeText(bundle)
        Log.i(TAG, "Installed ${files.size} tools from $bundled")
    }
}
