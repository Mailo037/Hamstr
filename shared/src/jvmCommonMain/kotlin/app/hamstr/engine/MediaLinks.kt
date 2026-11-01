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
}
