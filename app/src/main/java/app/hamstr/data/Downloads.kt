package app.hamstr.data

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

enum class Mode { Video, Audio, Custom }

enum class Status { Queued, Running, Paused, Done, Failed, Canceled;
    val active get() = this == Queued || this == Running
}

/** What the user picked in the share sheet; turned into yt-dlp options by DownloadOptions. */
data class Choice(
    val mode: Mode,
    val height: Int?, // null = best
    val container: String, // mp4 / mkv / webm
    val audioFormat: String, // opus / m4a / mp3
    val subtitles: Boolean,
    val customFormat: String = "",
)

data class DownloadTask(
    val id: String = UUID.randomUUID().toString(),
    val url: String,
    val title: String,
    val thumbnail: String?,
    val source: String?, // extractor, e.g. "youtube"
    val durationSec: Int,
    val choice: Choice,
    val status: Status = Status.Queued,
    val progress: Float = 0f, // 0..1
    val speed: String = "",
    val etaSec: Long = -1,
    val filePath: String? = null,
    val contentUri: String? = null,
    val sizeBytes: Long = 0,
    val error: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val finishedAt: Long = 0,
    // Files that arrived together (a playlist, a batch of imports) share a group.
    val group: String? = null,
    val groupTitle: String? = null,
    // Other qualities of a stashed link point at the first download; [replaces] goes once this one is in.
    val versionOf: String? = null,
    val replaces: String? = null,
    val videoHeight: Int = 0, // measured from the finished file, 0 = audio or unknown
) {
    /** The first download of a link and all its other qualities share this key. */
    val family get() = versionOf ?: id
}

/** Each family of qualities once, as its first download (or the newest left), with all its files. */
fun collapseVersions(done: List<DownloadTask>): List<Pair<DownloadTask, List<DownloadTask>>> {
    if (done.none { it.versionOf != null }) return done.map { it to listOf(it) }
    val families = done.groupBy { it.family }
    val seen = HashSet<String>()
    return done.mapNotNull { t ->
        if (!seen.add(t.family)) return@mapNotNull null
        val all = families.getValue(t.family)
        (all.firstOrNull { it.versionOf == null } ?: t) to all
    }
}

/**
 * All downloads, as one observable list. Progress lives in memory only; the list is
 * saved by a single IO worker on status changes. Bursts share a pending write so
 * queue actions never serialize the entire history or wait for disk on the UI thread.
 */
object DownloadStore {
    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks: StateFlow<List<DownloadTask>> = _tasks.asStateFlow()
    private lateinit var file: File
    private val saves = Channel<Unit>(Channel.CONFLATED)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Synchronized
    fun init(context: Context) {
        if (::file.isInitialized) return
        file = File(context.filesDir, "downloads.json")
        val atomic = AtomicFile(file)
        val loaded = runCatching { JSONArray(atomic.openRead().bufferedReader().use { it.readText() }).let { a -> List(a.length()) { fromJson(a.getJSONObject(it)) } } }
            .getOrDefault(emptyList())
        // A process death interrupts running work; it resumes as queued (yt-dlp continues .part files).
        _tasks.value = loaded.map { if (it.status == Status.Running) it.copy(status = Status.Queued, speed = "", etaSec = -1) else it }
        scope.launch {
            for (ignored in saves) {
                try {
                    val snapshot = _tasks.value
                    val arr = JSONArray()
                    snapshot.forEach { arr.put(toJson(it)) }
                    val bytes = arr.toString().toByteArray(Charsets.UTF_8)
                    val output = atomic.startWrite()
                    try {
                        output.write(bytes)
                        atomic.finishWrite(output)
                    } catch (e: Exception) {
                        atomic.failWrite(output)
                        throw e
                    }
                } catch (e: Exception) {
                    Log.e("HamstrStore", "Could not save download history", e)
                }
            }
        }
    }

    fun get(id: String) = _tasks.value.firstOrNull { it.id == id }

    fun add(task: DownloadTask) {
        _tasks.update { listOf(task) + it }
        save()
    }

    fun update(id: String, persist: Boolean = true, block: (DownloadTask) -> DownloadTask) {
        _tasks.update { list -> list.map { if (it.id == id) block(it) else it } }
        if (persist) save()
    }

    fun remove(id: String) {
        _tasks.update { list -> list.filterNot { it.id == id } }
        save()
    }

    /** Deleting a physical file also removes duplicate entries that reference it. */
    fun removeFile(file: File) {
        val path = file.absoluteFile.normalize()
        _tasks.update { list -> list.filterNot { task ->
            task.filePath?.let { File(it).absoluteFile.normalize() == path } == true
        } }
        save()
    }

    private fun save() {
        if (!::file.isInitialized) return
        saves.trySend(Unit)
    }

    private fun toJson(t: DownloadTask) = JSONObject().apply {
        put("id", t.id); put("url", t.url); put("title", t.title)
        put("thumbnail", t.thumbnail); put("source", t.source); put("duration", t.durationSec)
        put("mode", t.choice.mode.name); put("height", t.choice.height ?: 0); put("container", t.choice.container)
        put("audioFormat", t.choice.audioFormat); put("subtitles", t.choice.subtitles); put("customFormat", t.choice.customFormat)
        put("status", t.status.name); put("progress", t.progress.toDouble())
        put("filePath", t.filePath); put("contentUri", t.contentUri); put("size", t.sizeBytes)
        put("error", t.error); put("createdAt", t.createdAt); put("finishedAt", t.finishedAt)
        put("group", t.group); put("groupTitle", t.groupTitle)
        put("versionOf", t.versionOf); put("replaces", t.replaces); put("videoHeight", t.videoHeight)
    }

    private fun fromJson(o: JSONObject) = DownloadTask(
        id = o.getString("id"),
        url = o.getString("url"),
        title = o.optString("title"),
        thumbnail = o.optStringOrNull("thumbnail"),
        source = o.optStringOrNull("source"),
        durationSec = o.optInt("duration"),
        choice = Choice(
            mode = Mode.valueOf(o.optString("mode", "Video")),
            height = o.optInt("height").takeIf { it > 0 },
            container = o.optString("container", "mp4"),
            audioFormat = o.optString("audioFormat", "opus"),
            subtitles = o.optBoolean("subtitles"),
            customFormat = o.optString("customFormat"),
        ),
        status = Status.valueOf(o.optString("status", "Queued")),
        progress = o.optDouble("progress", 0.0).toFloat(),
        filePath = o.optStringOrNull("filePath"),
        contentUri = o.optStringOrNull("contentUri"),
        sizeBytes = o.optLong("size"),
        error = o.optStringOrNull("error"),
        createdAt = o.optLong("createdAt"),
        finishedAt = o.optLong("finishedAt"),
        group = o.optStringOrNull("group"),
        groupTitle = o.optStringOrNull("groupTitle"),
        versionOf = o.optStringOrNull("versionOf"),
        replaces = o.optStringOrNull("replaces"),
        videoHeight = o.optInt("videoHeight"),
    )

    private fun JSONObject.optStringOrNull(key: String) = if (isNull(key)) null else optString(key).ifEmpty { null }
}
