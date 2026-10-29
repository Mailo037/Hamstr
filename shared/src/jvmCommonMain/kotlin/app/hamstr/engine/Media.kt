package app.hamstr.engine

import app.hamstr.data.Choice
import app.hamstr.data.DownloadTask
import app.hamstr.data.Mode
import org.json.JSONObject

/** Metadata shown in the share sheet before downloading. */
data class MediaInfo(
    val url: String,
    val title: String,
    val thumbnail: String?,
    val source: String?,
    val durationSec: Int,
    val uploader: String?,
    val heights: List<Int>, // video heights on offer, descending
    val approxBytes: Long,
    val videoBytes: Map<Int, Long> = emptyMap(), // per height, with the best audio added where needed
    val audioBytes: Long = 0,
    val id: String? = null,
    // A playlist, album or channel: its videos as yt-dlp lists them, without their formats. Null for one video.
    val entries: List<PlaylistEntry>? = null,
) {
    /** The same video under any of its links: extractor and ID. */
    val key get() = if (id != null && source != null) "$source:$id" else null

    /** Roughly what "up to [height]" weighs, or 0 when the site doesn't say. */
    fun bytesFor(height: Int?): Long {
        val h = if (height == null) heights.firstOrNull() else heights.firstOrNull { it <= height } ?: heights.lastOrNull()
        return h?.let { videoBytes[it] } ?: 0
    }
}

/** One video of a playlist; [available] is false for private and deleted ones. */
data class PlaylistEntry(
    val url: String,
    val title: String,
    val thumbnail: String?,
    val durationSec: Int,
    val uploader: String?,
    val key: String?,
    val available: Boolean = true,
)

sealed interface EngineState {
    data object Starting : EngineState
    data class Ready(val version: String) : EngineState
    data class Broken(val message: String) : EngineState
}

/** Reads yt-dlp's (and Cheek's) JSON the same way on every platform. */
object MediaJson {
    fun info(url: String, v: JSONObject): MediaInfo {
        fun string(key: String) = if (v.isNull(key)) null else v.optString(key).takeIf { it.isNotBlank() }
        val formats = v.optJSONArray("formats")
        val all = (0 until (formats?.length() ?: 0)).mapNotNull { formats?.optJSONObject(it) }
        // Not "has": JSONObject.has(key) is a member and would win over an extension of that name.
        fun JSONObject.hasCodec(codec: String) = !isNull(codec) && optString(codec) != "none"
        fun JSONObject.bytes() = optLong("filesize").takeIf { it > 0 } ?: optLong("filesize_approx")
        val video = all.filter { it.hasCodec("vcodec") && it.optInt("height") > 0 }
        val entries = if (v.optString("_type") == "playlist") playlistEntries(v) else null
        val heights = video.map { it.optInt("height") }.distinct().sortedDescending()
        val audioBytes = all.filter { !it.hasCodec("vcodec") && it.hasCodec("acodec") }.maxOfOrNull { it.bytes() } ?: 0
        val videoBytes = heights.associateWith { h ->
            video.filter { it.optInt("height") == h && it.bytes() > 0 }
                .maxOfOrNull { it.bytes() + if (it.hasCodec("acodec")) 0 else audioBytes } ?: 0
        }
        return MediaInfo(
            url = string("webpage_url") ?: url,
            title = string("title") ?: string("fulltitle") ?: url,
            thumbnail = string("thumbnail"),
            // A playlist's extractor ("YoutubeTab") is not the site its videos come from.
            source = entries?.firstNotNullOfOrNull { it.key?.substringBefore(':') } ?: string("extractor_key") ?: string("extractor"),
            durationSec = v.optDouble("duration", 0.0).toInt(),
            uploader = string("uploader") ?: string("channel") ?: string("artist"),
            heights = heights,
            approxBytes = v.optLong("filesize").takeIf { it > 0 } ?: v.optLong("filesize_approx"),
            videoBytes = videoBytes,
            audioBytes = audioBytes,
            id = string("id"),
            entries = entries,
        )
    }

    private val unavailableTitle = Regex("""^\[(Private|Deleted|Unavailable) video]$""", RegexOption.IGNORE_CASE)

    /** The videos of a flat playlist, in its order. Nested playlists (a channel's tabs) stay links. */
    fun playlistEntries(v: JSONObject): List<PlaylistEntry> {
        val list = v.optJSONArray("entries") ?: return emptyList()
        val owner = if (v.isNull("uploader")) v.optString("channel") else v.optString("uploader")
        return (0 until list.length()).mapNotNull { i ->
            val e = list.optJSONObject(i) ?: return@mapNotNull null
            fun string(key: String) = if (e.isNull(key)) null else e.optString(key).takeIf { it.isNotBlank() }
            val url = string("webpage_url") ?: string("url")?.takeIf { it.startsWith("http") } ?: return@mapNotNull null
            val id = string("id")
            val extractor = string("ie_key") ?: string("extractor_key")
            val title = string("title") ?: url
            val thumbs = e.optJSONArray("thumbnails")
            // The smallest picture that is still sharp in a list row.
            val thumbnail = string("thumbnail") ?: thumbs?.let { a ->
                val all = (0 until a.length()).mapNotNull { a.optJSONObject(it) }.filter { it.optString("url").startsWith("http") }
                (all.filter { it.optInt("width") in 240..640 }.minByOrNull { it.optInt("width") } ?: all.lastOrNull())?.optString("url")
            }
            PlaylistEntry(
                url = url, title = title, thumbnail = thumbnail,
                durationSec = e.optDouble("duration", 0.0).toInt(),
                uploader = string("uploader") ?: string("channel") ?: owner.takeIf { it.isNotBlank() },
                key = if (id != null && extractor != null) "$extractor:$id" else null,
                available = !unavailableTitle.matches(title) && string("availability") != "private",
            )
        }.distinctBy { it.key ?: it.url }
    }
}

/** How a choice becomes yt-dlp options: the same for both engines and every platform. */
object Formats {
    /** Another quality of a stashed link gets its own name, so it can't overwrite the first file. */
    fun outputTemplate(task: DownloadTask): String {
        val tag = if (task.versionOf == null) "" else " " + when (task.choice.mode) {
            Mode.Audio -> "audio"
            else -> task.choice.height?.let { "${it}p" } ?: "best"
        }
        return "%(title).150B [%(id)s]$tag.%(ext)s"
    }

    /** The yt-dlp format selector for a choice, shared by both engines. */
    fun formatSelector(c: Choice): String = when (c.mode) {
        // Prefer an existing stream in the requested codec: remuxing avoids
        // a lossy, CPU-heavy re-encode. Keep the original fallback for other sites.
        Mode.Audio -> when (c.audioFormat) {
            "m4a" -> "ba[ext=m4a]/ba[acodec^=mp4a]/ba[acodec=aac]/ba[ext=mp4]/ba/b"
            "opus" -> "ba[acodec^=opus]/ba[ext=opus]/ba[ext=ogg]/ba/b"
            "mp3" -> "ba[ext=mp3]/ba/b"
            else -> "ba/b"
        }
        Mode.Video -> c.height.let { h -> if (h != null) "bv*[height<=$h]+ba/b[height<=$h]/bv*+ba/b" else "bv*+ba/b" }
        Mode.Custom -> c.customFormat.ifBlank { "bv*+ba/b" }
    }
}
