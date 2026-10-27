package app.hamstr.engine

import java.net.URI
import java.net.URLDecoder

/** A TMDB movie, series, season or episode, shared by input and Android resolution. */
internal data class CineSrcTarget(val tmdbId: Long, val season: Int? = null, val episode: Int? = null, val isTv: Boolean = season != null) {
    val needsEpisodes get() = isTv && episode == null
    val id get() = if (!isTv) "movie-$tmdbId" else "tv-$tmdbId" + (season?.let { "-s$it" } ?: "") + (episode?.let { "e$it" } ?: "")
    val embed: String get() {
        check(!needsEpisodes) { "Select an episode before resolving a series" }
        return if (!isTv) "https://cinesrc.st/embed/movie/$tmdbId" else "https://cinesrc.st/embed/tv/$tmdbId?s=$season&e=$episode"
    }

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
            require(parts.size in 2..4) { "A TV link accepts a season and episode" }
            val seasonText = parts.getOrNull(2) ?: query["s"] ?: query["season"]
            val episodeText = parts.getOrNull(3) ?: query["e"] ?: query["episode"]
            val s = seasonText?.toIntOrNull()
            val e = episodeText?.toIntOrNull()
            require(seasonText == null || s != null && s >= 0) { "Season must be non-negative" }
            require(episodeText == null || e != null && e > 0 && s != null) { "An episode needs a season and a positive episode number" }
            return CineSrcTarget(id, s, e, isTv = true)
        }
    }
}
