package app.hamstr.desktop

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

    /** Finished files, like Download/Hamstr on the phone. */
    val downloads = File(home, "Downloads/Hamstr")

    /** Parts of downloads that are still running, one folder per download. */
    val parts = File(data, "parts")

    /** yt-dlp's extractor cache. */
    val cache = File(data, "cache/yt-dlp")
}
