package app.hamstr.engine

import java.net.URI
import java.net.URLDecoder

/** A single TMDB movie or episode, shared by input validation and Android resolution. */
internal data class CineSrcTarget(val tmdbId: Long, val season: Int? = null, val episode: Int? = null) {
    val id get() = if (season == null) "movie-$tmdbId" else "tv-$tmdbId-s${season}e$episode"
    val embed get() = if (season == null) "https://cinesrc.st/embed/movie/$tmdbId" else "https://cinesrc.st/embed/tv/$tmdbId?s=$season&e=$episode"

    companion object {
        fun parse(input: String): CineSrcTarget? {
            val short = input.startsWith("cinesrc:") || input.startsWith("cineby:")
            val query: Map<String, String>
            val parts: List<String>
            if (short) {
                parts = input.substringAfter(':').split(':')
                query = emptyMap()
            } else {
                val uri = runCatching { URI(input) }.getOrNull() ?: return null
                if (uri.host?.lowercase() !in setOf("cineby.rocks", "www.cineby.rocks", "cinesrc.st", "www.cinesrc.st")) return null
                require(uri.scheme in setOf("http", "https")) { "CineSrc requires an HTTP or HTTPS link" }
                val path = uri.path.trim('/')
                parts = (if (path.startsWith("embed/") || path.startsWith("watch/")) path.substringAfter('/') else path).split('/')
                query = uri.rawQuery.orEmpty().split('&').filter { '=' in it }.associate {
                    URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
                }
            }
            require(parts.size >= 2 && parts[0] in setOf("movie", "tv")) { "Use cinesrc:movie:TMDB_ID or cinesrc:tv:TMDB_ID:SEASON:EPISODE" }
            val id = parts[1].takeIf { it.all(Char::isDigit) }?.toLongOrNull()
            require(id != null && id > 0) { "TMDB ID must be a positive number" }
            if (parts[0] == "movie") {
                require(parts.size == 2) { "A movie needs only its TMDB ID" }
                return CineSrcTarget(id)
            }
            require(parts.size == 2 || parts.size == 4) { "A TV link needs a season and episode" }
            val s = (parts.getOrNull(2) ?: query["s"] ?: query["season"])?.toIntOrNull()
            val e = (parts.getOrNull(3) ?: query["e"] ?: query["episode"])?.toIntOrNull()
            require(s != null && s >= 0 && e != null && e > 0) { "A TV link needs a season (s) and episode (e); season 0 supports specials" }
            return CineSrcTarget(id, s, e)
        }
    }
}
