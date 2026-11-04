package app.hamstr.data

import app.hamstr.Log
import app.hamstr.engine.MediaInfo
import app.hamstr.engine.PlaylistEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.util.UUID

/** A channel or playlist Hamstr follows: what comes out there is stashed by itself. */
data class Subscription(
    val id: String = UUID.randomUUID().toString(),
    val url: String,
    val title: String,
    val thumbnail: String? = null,
    val mode: Mode = Mode.Video,
    // What was there already, or came since: only what isn't in here is new. Entry keys, or links.
    val seen: Set<String> = emptySet(),
    val addedAt: Long = System.currentTimeMillis(),
    val checkedAt: Long = 0,
    // Why the last look failed; null when it went through.
    val error: String? = null,
    // How many downloads it brought in so far.
    val stashed: Int = 0,
    // Just followed: its first look is still on the way.
    val pending: Boolean = false,
)

/** Reads a channel or playlist the way the platform's yt-dlp does: its videos, without their formats. */
typealias FeedReader = suspend (url: String) -> MediaInfo

/**
 * The channels and playlists Hamstr follows (Settings › Following), kept in subscriptions.json.
 * Following one remembers what is there already; every [EVERY] (and when asked) each is looked
 * at again and what is new goes into the queue, at most [MAX_NEW] at a time, in the format it
 * was followed in. The night and the charger (Schedule) hold those downloads like any other.
 */
object Subscriptions {
    /** How often a followed channel is looked at. */
    const val EVERY = 4 * 60 * 60 * 1000L
    /** At most this many new videos per look: a channel looked at again after weeks doesn't fill the phone. */
    const val MAX_NEW = 10
    /** How many entries are listed per look; a playlist (list=) is read whole, its new videos come last. */
    private const val LISTED = 50
    private const val KEEP_SEEN = 3000
    private const val TAG = "HamstrFollow"

    private val _all = MutableStateFlow<List<Subscription>>(emptyList())
    val all: StateFlow<List<Subscription>> = _all.asStateFlow()
    private val _checking = MutableStateFlow(false)
    /** True while Hamstr looks for new videos. */
    val checking: StateFlow<Boolean> = _checking.asStateFlow()

    private var file: File? = null
    private val saves = Channel<Unit>(Channel.CONFLATED)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()

    @Synchronized
    fun init(dir: File) {
        if (file != null) return
        val f = File(dir, "subscriptions.json")
        file = f
        val atomic = AtomicJson(f)
        _all.value = runCatching { if (f.exists() || File(f.path + ".bak").exists()) decode(atomic.read()) else emptyList() }
            .onFailure { Log.e(TAG, "Could not read the followed channels", it) }
            .getOrDefault(emptyList())
            // A look that a closed app cut short is tried again on the next one.
            .map { if (it.pending) it.copy(pending = false, checkedAt = 0) else it }
        scope.launch {
            for (ignored in saves) runCatching { atomic.write(encode()) }.onFailure { Log.e(TAG, "Could not save the followed channels", it) }
        }
    }

    private fun save() {
        if (file != null) saves.trySend(Unit)
    }

    /** The ones a look is for: not one still on its first, nor a link that is no channel. */
    private fun lookable() = _all.value.filter { !it.pending && it.error != NOT_A_FEED }

    /** One or more followed channels are due for a look. */
    fun due(now: Long = System.currentTimeMillis()) = lookable().any { now - it.checkedAt >= EVERY }

    /** When the next one is due, or null while nothing is followed. */
    fun nextCheck(): Long? = lookable().minOfOrNull { it.checkedAt + EVERY }

    /**
     * The link to read for [url]: a YouTube channel's page lists its tabs (videos, shorts, live), so
     * it is read as its videos tab.
     */
    fun feedUrl(url: String): String {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return url.trim()
        val host = uri.host?.lowercase().orEmpty()
        val path = uri.path.orEmpty().trimEnd('/')
        val channel = Regex("^/(@[^/]+|channel/[^/]+|c/[^/]+|user/[^/]+)$")
        return if ((host == "youtube.com" || host.endsWith(".youtube.com")) && channel.matches(path)) "https://www.youtube.com$path/videos" else url.trim()
    }

    /** How many entries a look lists for [url]: all of a playlist, the newest [LISTED] of anything else. */
    fun listed(url: String): Int? = if ("list=" in url) null else LISTED

    /** An entry under a channel that is a list itself (a tab, a playlist), not a video. */
    private fun PlaylistEntry.nested() = key?.substringBefore(':')?.let { it.endsWith("Tab") || it.endsWith("Playlist") } == true

    fun keyOf(e: PlaylistEntry) = e.key ?: e.url

    /**
     * Follows [url]: it shows at once, and once [reader] has looked at it, it carries its name and
     * what is there already counts as seen. A link that isn't a channel or playlist stays with
     * its error, to be removed. Returns its id, or that of the one following it already.
     */
    fun follow(url: String, mode: Mode, reader: FeedReader): String {
        val link = feedUrl(url)
        _all.value.firstOrNull { it.url == link }?.let { return it.id }
        val sub = Subscription(url = link, title = link, mode = mode, pending = true)
        _all.update { it + sub }
        save()
        scope.launch {
            lock.withLock {
                val read = runCatching { reader(link) }
                set(sub.id) { s ->
                    read.fold(
                        { info ->
                            val entries = info.entries
                            if (entries == null) s.copy(pending = false, checkedAt = System.currentTimeMillis(), error = NOT_A_FEED)
                            else s.copy(
                                title = info.title.takeIf { it != link } ?: info.uploader ?: link,
                                thumbnail = info.thumbnail ?: entries.firstNotNullOfOrNull { it.thumbnail },
                                seen = entries.mapTo(LinkedHashSet()) { keyOf(it) },
                                pending = false, checkedAt = System.currentTimeMillis(), error = null,
                            )
                        },
                        { e -> s.copy(pending = false, checkedAt = System.currentTimeMillis(), error = e.message ?: e.toString()) },
                    )
                }
            }
        }
        return sub.id
    }

    /** What [Subscription.error] says when the link is one video, not a channel or playlist. */
    const val NOT_A_FEED = "not a channel or playlist"

    fun unfollow(id: String) {
        _all.update { list -> list.filterNot { it.id == id } }
        save()
    }

    /** New videos of [id] come as video or as audio from now on. */
    fun setMode(id: String, mode: Mode) = set(id) { it.copy(mode = mode) }

    private fun set(id: String, block: (Subscription) -> Subscription) {
        _all.update { list -> list.map { if (it.id == id) block(it) else it } }
        save()
    }

    /**
     * Looks at every followed channel that is due ([force]: at all of them) and hands what is new
     * to [stash] as downloads, one call per channel. Returns how many came.
     */
    suspend fun check(reader: FeedReader, force: Boolean = false, stash: (List<DownloadTask>) -> Unit): Int = lock.withLock {
        val now = System.currentTimeMillis()
        val due = lookable().filter { force || now - it.checkedAt >= EVERY }
        if (due.isEmpty()) 0 else look(due, reader, stash)
    }

    private suspend fun look(due: List<Subscription>, reader: FeedReader, stash: (List<DownloadTask>) -> Unit): Int {
        _checking.value = true
        var total = 0
        try {
            for (sub in due) {
                val info = try {
                    reader(sub.url)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Could not look at ${sub.url}: ${e.message}")
                    set(sub.id) { it.copy(checkedAt = System.currentTimeMillis(), error = e.message ?: e.toString()) }
                    continue
                }
                val current = _all.value.firstOrNull { it.id == sub.id } ?: continue
                // Stashed by hand in the meantime: not twice.
                val have = DownloadStore.tasks.value.flatMapTo(HashSet()) { listOfNotNull(it.mediaKey, it.url) }
                val fresh = fresh(current, info.entries.orEmpty()).filterNot { e -> e.url in have || e.key?.let { it in have } == true }
                val tasks = tasksFor(current, fresh)
                set(sub.id) { s ->
                    s.copy(
                        seen = (s.seen + info.entries.orEmpty().map { keyOf(it) }).toList().takeLast(KEEP_SEEN).toSet(),
                        checkedAt = System.currentTimeMillis(), error = null, stashed = s.stashed + tasks.size,
                        thumbnail = s.thumbnail ?: info.thumbnail,
                    )
                }
                if (tasks.isNotEmpty()) stash(tasks)
                total += tasks.size
            }
        } finally {
            _checking.value = false
        }
        return total
    }

    /**
     * The videos of a look that weren't there before, at most [MAX_NEW]: a channel lists its newest
     * first, a playlist adds new ones at its end, so they are taken from the end where they start.
     */
    fun fresh(sub: Subscription, entries: List<PlaylistEntry>): List<PlaylistEntry> {
        val videos = entries.filter { it.available && !it.nested() }
        val new = videos.filter { keyOf(it) !in sub.seen }
        if (new.size <= MAX_NEW) return new
        return if (videos.indexOf(new.first()) == 0) new.take(MAX_NEW) else new.takeLast(MAX_NEW)
    }

    /** [entries] as downloads in [sub]'s format; several at once arrive together, under its name. */
    fun tasksFor(sub: Subscription, entries: List<PlaylistEntry>, settings: Settings = Prefs.settings.value): List<DownloadTask> {
        if (entries.isEmpty()) return emptyList()
        val group = if (entries.size > 1) UUID.randomUUID().toString() else null
        val now = System.currentTimeMillis()
        val choice = settings.copy(mode = sub.mode).defaultChoice().copy(open = false)
        return entries.mapIndexed { i, e ->
            DownloadTask(
                url = e.url, title = e.title, thumbnail = e.thumbnail, source = e.key?.substringBefore(':'),
                durationSec = e.durationSec, choice = choice, uploader = e.uploader, mediaKey = e.key,
                // In the order they are listed, which is also the order they start in.
                createdAt = now + i, group = group, groupTitle = group?.let { sub.title },
            )
        }
    }

    /** The followed channels as subscriptions.json keeps them, for a backup; [merge] takes them in. */
    fun encode(list: List<Subscription> = _all.value): String = JSONArray().apply {
        list.forEach { s ->
            put(JSONObject().apply {
                put("id", s.id); put("url", s.url); put("title", s.title); put("thumbnail", s.thumbnail)
                put("mode", s.mode.name); put("seen", JSONArray(s.seen.toList())); put("addedAt", s.addedAt)
                put("checkedAt", s.checkedAt); put("error", s.error); put("stashed", s.stashed); put("pending", s.pending)
            })
        }
    }.toString()

    fun decode(text: String): List<Subscription> {
        val a = JSONArray(text)
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            runCatching {
                val seen = o.optJSONArray("seen")
                Subscription(
                    id = o.getString("id"), url = o.getString("url"), title = o.optString("title", o.getString("url")),
                    thumbnail = if (o.isNull("thumbnail")) null else o.optString("thumbnail").ifEmpty { null },
                    mode = runCatching { Mode.valueOf(o.optString("mode")) }.getOrDefault(Mode.Video),
                    seen = (0 until (seen?.length() ?: 0)).mapTo(LinkedHashSet()) { seen!!.getString(it) },
                    addedAt = o.optLong("addedAt"), checkedAt = o.optLong("checkedAt"),
                    error = if (o.isNull("error")) null else o.optString("error").ifEmpty { null },
                    stashed = o.optInt("stashed"), pending = o.optBoolean("pending"),
                )
            }.getOrNull()
        }
    }

    /** Adds the channels of a backup that aren't followed here yet. Returns how many. */
    fun merge(incoming: List<Subscription>): Int {
        var added = 0
        _all.update { list ->
            val known = list.mapTo(HashSet()) { it.url }
            val fresh = incoming.filter { known.add(it.url) }.map { it.copy(pending = false) }
            added = fresh.size
            list + fresh
        }
        if (added > 0) save()
        return added
    }

    /** Replaces the whole list; for tests. */
    internal fun replace(list: List<Subscription>) {
        _all.value = list
        save()
    }
}
