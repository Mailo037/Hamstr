package app.hamstr.engine

sealed interface EngineVersionCheck {
    data object Idle : EngineVersionCheck
    data object Checking : EngineVersionCheck
    data object Current : EngineVersionCheck
    data class Available(val version: String) : EngineVersionCheck
    data class Failed(val message: String) : EngineVersionCheck
}

/** yt-dlp uses date versions, optionally followed by a numeric build component. */
internal fun newerYtDlpVersion(latest: String, installed: String): Boolean {
    fun parts(version: String): List<Int> {
        val normalized = version.trim().removePrefix("v")
        require(Regex("\\d{4}\\.\\d{1,2}\\.\\d{1,2}(?:\\.\\d+)?").matches(normalized)) {
            "Unrecognized yt-dlp version: $version"
        }
        return normalized.split('.').map { it.toInt() }
    }
    val candidate = parts(latest)
    val current = parts(installed)
    for (i in 0 until maxOf(candidate.size, current.size)) {
        val a = candidate.getOrElse(i) { 0 }
        val b = current.getOrElse(i) { 0 }
        if (a != b) return a > b
    }
    return false
}
