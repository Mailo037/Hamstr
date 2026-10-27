package app.hamstr.engine

import java.net.URI

/** The public share and song links understood by Cheek's Glyph extractor. */
internal object GlyphLinks {
    private const val ID = "[A-Za-z0-9]{8}(?:[A-Za-z0-9]{4})?"
    private val path = Regex("""/(?:p/$ID(?:/listen/\d+)?|embed/p/$ID)/?""")

    fun matches(url: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme?.lowercase() in listOf("http", "https") &&
            uri.host?.lowercase() in listOf("glyph.my", "www.glyph.my") && path.matches(uri.path)
    }.getOrDefault(false)
}
