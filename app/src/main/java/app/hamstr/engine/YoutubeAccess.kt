package app.hamstr.engine

import com.yausername.youtubedl_android.YoutubeDLException
import java.net.URI

/** Retry guest playback refusals with other public clients, without account credentials. */
internal object YoutubeAccess {
    // Keep HD-capable guest playback ahead of VR, which can expose only 360p.
    private val clients = listOf(null, "visionos", "android_vr")
    private val guestRefusal = Regex(
        "not a bot|sign[ -]?in|log[ -]?in|login required|empty media response|The page needs to be reloaded",
        RegexOption.IGNORE_CASE,
    )
    private val restricted = Regex(
        "private video|video is private|members[- ]only|join this channel|" +
            "confirm your age|age[- ]restricted|inappropriate for some users|" +
            "not available in your country|geo.?restrict|not available from your location",
        RegexOption.IGNORE_CASE,
    )
    private val unavailableClient = Regex(
        "Requested format is not available|No video formats found|Unsupported YouTube client",
        RegexOption.IGNORE_CASE,
    )

    private fun isYoutube(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        if (uri.scheme?.lowercase() !in listOf("http", "https")) return false
        val host = uri.host?.lowercase() ?: return false
        return host == "youtu.be" || host == "youtube.com" || host.endsWith(".youtube.com") ||
            host == "youtube-nocookie.com" || host.endsWith(".youtube-nocookie.com")
    }

    /** Default extraction stays first. Never retry completed files or unrelated failures. */
    suspend fun <T> run(
        url: String,
        mayRetry: () -> Boolean = { true },
        extract: suspend (client: String?) -> T,
    ): T {
        for ((index, client) in clients.withIndex()) {
            try {
                return extract(client)
            } catch (e: YoutubeDLException) {
                val message = e.message.orEmpty()
                if (index == clients.lastIndex || !isYoutube(url) || !mayRetry() ||
                    restricted.containsMatchIn(message) ||
                    !(guestRefusal.containsMatchIn(message) || index > 0 && unavailableClient.containsMatchIn(message))) throw e
            }
        }
        error("No YouTube clients configured")
    }
}
