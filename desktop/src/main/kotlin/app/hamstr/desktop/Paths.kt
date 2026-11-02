package app.hamstr.desktop

import app.hamstr.data.Prefs
import java.io.File

/** Where the desktop app keeps its things, in each system's usual place. */
object Paths {
    private val os = System.getProperty("os.name").lowercase()
    val windows = os.startsWith("windows")
    val mac = os.startsWith("mac")
    private val home = File(System.getProperty("user.home"))

    /**
     * Settings, the download list and the tools: %APPDATA%\Hamstr, ~/Library/Application Support/Hamstr
     * or ~/.local/share/hamstr. HAMSTR_DATA puts them elsewhere, e.g. for a second window to test Nearby.
     */
    val data: File = System.getenv("HAMSTR_DATA")?.let(::File) ?: when {
        windows -> File(System.getenv("APPDATA") ?: home.path, "Hamstr")
        mac -> File(home, "Library/Application Support/Hamstr")
        else -> File(System.getenv("XDG_DATA_HOME") ?: "$home/.local/share", "hamstr")
    }

    /** Hamstr's own folder for finished files, like Download/Hamstr on the phone. */
    val defaultDownloads: File by lazy { File(systemDownloads(), "Hamstr") }

    /** Finished files: the folder picked in Settings › Downloads, else [defaultDownloads]. */
    val downloads: File get() = Prefs.settings.value.downloadFolder.takeIf { it.isNotBlank() }?.let(::File) ?: defaultDownloads

    /** Parts of downloads that are still running, one folder per download. */
    val parts = File(data, "parts")

    /** yt-dlp's extractor cache. */
    val cache = File(data, "cache/yt-dlp")

    /** Files from other devices, until they are complete. */
    val inbox = File(data, "nearby-inbox")

    /**
     * The system's Downloads folder, also where the user moved it: Windows keeps it as a known
     * folder in the registry, Linux desktops in user-dirs.dirs (and in their own language).
     */
    private fun systemDownloads(): File = when {
        windows -> runCatching {
            val p = ProcessBuilder("reg", "query", "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\Shell Folders",
                "/v", "{374DE290-123F-4565-9164-39C4925E467B}").redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor()
            Regex("""REG_SZ\s+(.+)""").find(out)?.groupValues?.get(1)?.trim()?.let(::File)?.takeIf { it.isDirectory }
        }.getOrNull()
        mac -> null
        else -> runCatching {
            val dirs = File(System.getenv("XDG_CONFIG_HOME") ?: "$home/.config", "user-dirs.dirs")
            Regex("""^XDG_DOWNLOAD_DIR="(.+)"""", RegexOption.MULTILINE).find(dirs.readText())?.groupValues?.get(1)
                ?.replace("\$HOME", home.path)?.let(::File)?.takeIf { it.isDirectory }
        }.getOrNull()
    } ?: File(home, "Downloads")
}
