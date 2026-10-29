package app.hamstr.engine

/** Input accepted by paste, clipboard and Android's share target. */
object MediaLinks {
    private val link = Regex("""https?://[^\s"'<>]+|(?:cinesrc|cineby):[^\s"'<>]+""")
    fun find(text: String): String? = link.find(text)?.value?.trimEnd('.', ',', ')', ']')
    fun needsEpisodeSelection(url: String): Boolean = runCatching { CineSrcTarget.parse(url)?.needsEpisodes == true }.getOrDefault(false)
}
