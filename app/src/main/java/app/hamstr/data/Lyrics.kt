package app.hamstr.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs

/**
 * Lyrics kept next to the download list, one file per song (filesDir/lyrics/<id>.lrc).
 * Timed lyrics are LRC ("[01:23.45] line"), so the player can follow along; plain text works
 * too. They come from LRCLIB when asked, or are typed in by hand.
 */
object Lyrics {
    /** One line of lyrics; [at] is -1 for plain lyrics without times. */
    data class Line(val at: Long, val text: String)

    private lateinit var dir: File
    private val _saved = MutableStateFlow<Map<String, String>>(emptyMap())
    /** The lyrics read so far, by download id. */
    val saved: StateFlow<Map<String, String>> = _saved.asStateFlow()

    fun init(context: Context) {
        dir = File(context.filesDir, "lyrics")
    }

    /** Reads the saved lyrics of [id] once; later reads come from [saved]. */
    suspend fun load(id: String): String? {
        _saved.value[id]?.let { return it }
        if (!::dir.isInitialized) return null
        val text = withContext(Dispatchers.IO) { File(dir, "$id.lrc").takeIf { it.isFile }?.readText() }
        if (text != null) _saved.update { it + (id to text) }
        return text
    }

    suspend fun save(id: String, text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return delete(id)
        _saved.update { it + (id to clean) }
        withContext(Dispatchers.IO) { dir.mkdirs(); File(dir, "$id.lrc").writeText(clean) }
    }

    suspend fun delete(id: String) {
        _saved.update { it - id }
        withContext(Dispatchers.IO) { File(dir, "$id.lrc").delete() }
    }

    /** Splits saved lyrics into lines; timed lines come sorted, plain ones keep their order. */
    fun parse(text: String): List<Line> {
        val timed = ArrayList<Line>()
        val plain = ArrayList<Line>()
        text.lines().forEach { raw ->
            val stamps = STAMP.findAll(raw).toList()
            val words = raw.replace(STAMP, "").trim()
            if (stamps.isEmpty()) {
                // Tags like [ar:…] or [length:…] carry no words to show.
                if (!raw.trim().matches(TAG)) plain += Line(-1, raw.trim())
            } else stamps.forEach { m ->
                val (min, sec, frac) = m.destructured
                val ms = min.toLong() * 60_000 + sec.toLong() * 1000 + (frac.padEnd(3, '0').take(3).toLongOrNull() ?: 0)
                timed += Line(ms, words)
            }
        }
        return if (timed.isNotEmpty()) timed.sortedBy { it.at } else plain.dropWhile { it.text.isEmpty() }.dropLastWhile { it.text.isEmpty() }
    }

    private val STAMP = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val TAG = Regex("""\[[a-zA-Z]+:.*]""")

    /** "Song (Official Video) [4K]" → "Song"; "Artist - Topic" → "Artist". */
    fun cleanTitle(title: String): String = title
        .replace(Regex("""\s*[(\[][^)\]]*(official|video|audio|lyric|visuali[sz]er|hd|4k|mv|m/v)[^)\]]*[)\]]""", RegexOption.IGNORE_CASE), "")
        .trim()

    fun cleanArtist(artist: String?): String? = artist?.removeSuffix(" - Topic")?.trim()?.ifEmpty { null }

    /**
     * Asks LRCLIB (lrclib.net, free and without an account) for the song's lyrics: timed ones
     * when there are, plain ones otherwise. The best match is the one closest in length.
     */
    suspend fun find(task: DownloadTask): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            var title = cleanTitle(task.title)
            var artist = cleanArtist(task.uploader)
            // "Artist - Song" titles name the artist themselves.
            if (" - " in title) {
                val (a, t) = title.split(" - ", limit = 2)
                if (artist == null || artist.equals(a.trim(), true) || task.source?.contains("youtube", true) == true) { artist = a.trim(); title = t.trim() }
            }
            val queries = buildList {
                add("track_name=${enc(title)}" + (artist?.let { "&artist_name=${enc(it)}" } ?: ""))
                add("q=${enc(listOfNotNull(artist, title).joinToString(" "))}")
            }.distinct()
            for (q in queries) {
                val hits = JSONArray(get("https://lrclib.net/api/search?$q"))
                val best = (0 until hits.length()).map { hits.getJSONObject(it) }
                    .filter { !it.optBoolean("instrumental") && textOf(it).isNotBlank() }
                    .minByOrNull { o ->
                        val d = if (task.durationSec > 0) abs(o.optDouble("duration", 0.0) - task.durationSec) else 0.0
                        // Timed lyrics win over plain ones of the same length.
                        d + if (o.isNull("syncedLyrics") || o.optString("syncedLyrics").isBlank()) 4 else 0
                    } ?: continue
                if (task.durationSec > 0 && abs(best.optDouble("duration", 0.0) - task.durationSec) > 20) continue
                return@runCatching textOf(best)
            }
            error("No lyrics found")
        }
    }

    internal fun textOf(hit: JSONObject): String =
        (if (hit.isNull("syncedLyrics")) "" else hit.optString("syncedLyrics")).ifBlank {
            if (hit.isNull("plainLyrics")) "" else hit.optString("plainLyrics")
        }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 15_000
        c.setRequestProperty("User-Agent", "Hamstr (https://github.com/Mailo037/Hamstr)")
        try {
            check(c.responseCode == 200) { "LRCLIB answered ${c.responseCode}" }
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }
}
