package app.hamstr.engine

/** Input accepted by paste, clipboard and Android's share target. */
object MediaLinks {
    private val link = Regex("""https?://[^\s"'<>]+|(?:cinesrc|cineby):[^\s"'<>]+""")
    /** These links need no Python extractor before Cheek; responses still validate the media. */
    fun prefersNativeResolution(url: String): Boolean = GlyphLinks.matches(url) || runCatching {
        val uri = java.net.URI(url)
        uri.scheme?.lowercase() in listOf("https", "http") &&
            uri.path.substringAfterLast('.').lowercase() in setOf(
                "mp3", "m4a", "aac", "opus", "ogg", "flac", "wav", "mp4", "webm", "mkv", "mov", "m3u8",
            )
    }.getOrDefault(false)

    fun find(text: String): String? = link.find(text)?.value?.trimEnd('.', ',', ')', ']')
    fun needsEpisodeSelection(url: String): Boolean = runCatching { CineSrcTarget.parse(url)?.needsEpisodes == true }.getOrDefault(false)

    /** What a link to many videos is: someone's channel or page, or a playlist. */
    enum class Feed { Channel, Playlist }

    private val youtubeChannel = Regex("^/(@[^/]+|channel/[^/]+|c/[^/]+|user/[^/]+)(/(videos|shorts|streams|featured|releases|playlists))?$")
    private val notSoundcloudUsers = setOf("discover", "search", "stream", "upload", "you", "charts", "stations", "pages", "terms-of-use", "jobs")

    /**
     * A link to a channel, an artist or a playlist, from how it looks, before anything is resolved:
     * the share sheet and the + sheet offer to follow it. Null for one video or a song.
     */
    fun feed(url: String): Feed? = runCatching {
        val uri = java.net.URI(url.trim())
        val host = uri.host?.lowercase()?.removePrefix("www.")?.removePrefix("m.") ?: return@runCatching null
        val path = uri.path.orEmpty().trimEnd('/')
        val query = uri.rawQuery.orEmpty()
        when {
            host == "youtube.com" || host == "music.youtube.com" -> when {
                youtubeChannel.matches(path) || path.startsWith("/browse/UC") -> Feed.Channel
                path == "/playlist" && "list=" in query -> Feed.Playlist
                path.startsWith("/browse/VL") || path.startsWith("/browse/MPRE") -> Feed.Playlist
                else -> null
            }
            host == "soundcloud.com" -> {
                val parts = path.trim('/').split('/').filter { it.isNotEmpty() }
                when {
                    parts.size == 1 && parts[0] !in notSoundcloudUsers -> Feed.Channel
                    parts.size == 2 && parts[1] in setOf("tracks", "popular-tracks", "albums") -> Feed.Channel
                    parts.size == 3 && parts[1] == "sets" -> Feed.Playlist
                    else -> null
                }
            }
            host.endsWith(".bandcamp.com") -> when {
                path.isEmpty() || path == "/music" -> Feed.Channel
                path.startsWith("/album/") -> Feed.Playlist
                else -> null
            }
            host == "tiktok.com" -> if (Regex("^/@[^/]+$").matches(path)) Feed.Channel else null
            host == "twitch.tv" -> if (Regex("^/[^/]+/videos$").matches(path)) Feed.Channel else null
            else -> null
        }
    }.getOrNull()
}
