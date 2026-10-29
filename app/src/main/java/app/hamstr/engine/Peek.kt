package app.hamstr.engine

import app.hamstr.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder

/**
 * A first look at a link: title and picture, before yt-dlp has read the formats. Music pages
 * also name the [artist] and the length, which finds a song locked with DRM elsewhere.
 */
data class Peek(val title: String?, val thumbnail: String?, val site: String?, val artist: String? = null, val durationSec: Int = 0)

/**
 * Reads a link's title and preview picture in a fraction of a second: from the site's
 * oEmbed endpoint where it has one, otherwise from the page's Open Graph tags. Only the site
 * itself is asked, like a browser would; nothing goes anywhere else.
 */
object Peeker {
    private const val MAX_PAGE = 512 * 1024

    suspend fun peek(url: String): Peek? = withTimeoutOrNull(5_000) {
        runInterruptible(Dispatchers.IO) {
            runCatching { oembed(url) ?: openGraph(url) }.getOrNull()?.takeIf { it.title != null || it.thumbnail != null }
        }
    }

    private fun host(url: String) = runCatching { URI(url).host?.lowercase()?.removePrefix("www.")?.removePrefix("m.") }.getOrNull().orEmpty()

    private fun oembed(url: String): Peek? {
        val h = host(url)
        val q = URLEncoder.encode(url, "UTF-8")
        val endpoint = when {
            h == "youtu.be" || h.endsWith("youtube.com") -> "https://www.youtube.com/oembed?format=json&url=$q"
            h.endsWith("tiktok.com") -> "https://www.tiktok.com/oembed?url=$q"
            h.endsWith("vimeo.com") -> "https://vimeo.com/api/oembed.json?url=$q"
            h.endsWith("soundcloud.com") -> "https://soundcloud.com/oembed?format=json&url=$q"
            h.endsWith("dailymotion.com") || h == "dai.ly" -> "https://www.dailymotion.com/services/oembed?format=json&url=$q"
            else -> return null
        }
        val o = JSONObject(fetch(endpoint, 64 * 1024) ?: return null)
        fun s(key: String) = o.optString(key).takeIf { it.isNotBlank() }
        // YouTube's hqdefault is 4:3 with black bars; mqdefault is the same picture at 16:9.
        val thumb = s("thumbnail_url")?.replace("/hqdefault.jpg", "/mqdefault.jpg")
        // SoundCloud names the uploader; its titles are the track's own.
        return Peek(s("title"), thumb, s("provider_name"), artist = s("author_name").takeIf { h.endsWith("soundcloud.com") })
    }

    private fun openGraph(url: String): Peek? {
        val page = fetch(url, MAX_PAGE) ?: return null
        fun meta(vararg names: String) = names.firstNotNullOfOrNull { name ->
            Regex("""<meta[^>]+(?:property|name)=["']$name["'][^>]*>""", RegexOption.IGNORE_CASE).find(page)?.value
                ?.let { Regex("""content=["']([^"']*)["']""", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1) }
                ?.let(::unescape)?.takeIf { it.isNotBlank() }
        }
        val title = meta("og:title", "twitter:title")
            ?: Regex("""<title[^>]*>([^<]+)</title>""", RegexOption.IGNORE_CASE).find(page)?.groupValues?.get(1)?.let(::unescape)?.trim()
        val image = meta("og:image", "og:image:url", "twitter:image")?.let { runCatching { URI(url).resolve(it).toString() }.getOrDefault(it) }
        // Spotify and Apple Music: music:musician_description or the artist in the description
        // ("Artist · Album · Song · 2021"), and music:duration in seconds. Elsewhere a schema.org
        // duration (PT3M25S) where the page has one.
        val artist = meta("music:musician_description", "music:musician", "og:audio:artist", "twitter:audio:artist_name")
            ?.takeUnless { it.startsWith("http") }
            ?: meta("og:description")?.split(" · ")?.takeIf { it.size >= 3 }?.first()?.trim()
        val duration = meta("music:duration", "og:video:duration", "video:duration")?.toIntOrNull()
            ?: Regex(""""duration"\s*:\s*"(PT[^"]+)"""").find(page)?.groupValues?.get(1)?.let(::isoSeconds)
            ?: Regex("""itemprop=["']duration["'][^>]*content=["'](PT[^"']+)["']""").find(page)?.groupValues?.get(1)?.let(::isoSeconds)
        return Peek(title, image, meta("og:site_name"), artist, duration ?: 0)
    }

    /** PT3M25S, PT1H2M or PT00H03M25S in seconds. */
    private fun isoSeconds(s: String): Int? {
        val m = Regex("""PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+(?:\.\d+)?)S)?""").matchEntire(s) ?: return null
        val (h, min, sec) = m.destructured
        return ((h.toIntOrNull() ?: 0) * 3600 + (min.toIntOrNull() ?: 0) * 60 + (sec.toDoubleOrNull() ?: 0.0).toInt()).takeIf { it > 0 }
    }

    private fun fetch(url: String, limit: Int): String? {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 3_000
            c.readTimeout = 3_000
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 15) Hamstr/${BuildConfig.VERSION_NAME}")
            c.setRequestProperty("Accept-Language", java.util.Locale.getDefault().toLanguageTag() + ",en;q=0.8")
            if (c.responseCode !in 200..299) return null
            val bytes = c.inputStream.use { input ->
                val buf = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(16 * 1024)
                while (buf.size() < limit) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    buf.write(chunk, 0, n)
                }
                buf.toByteArray()
            }
            return String(bytes, Charsets.UTF_8)
        } finally {
            c.disconnect()
        }
    }

    private fun unescape(s: String) = s
        .replace(Regex("&#(\\d+);")) { it.groupValues[1].toIntOrNull()?.let { n -> String(Character.toChars(n)) } ?: it.value }
        .replace(Regex("&#x([0-9a-fA-F]+);")) { it.groupValues[1].toIntOrNull(16)?.let { n -> String(Character.toChars(n)) } ?: it.value }
        .replace("&quot;", "\"").replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
}
