package app.hamstr.data

import app.hamstr.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.UUID

enum class Mode { Video, Audio, Custom }

enum class Status { Queued, Running, Paused, Done, Failed, Canceled;
    val active get() = this == Queued || this == Running
}

/** What a download is doing when it isn't moving bytes. */
enum class Stage {
    Resolving, Merging, ExtractingAudio, Remuxing, Tagging,
    WaitingForNetwork, WaitingForWifi, Retrying, PausedBySystem,
    // Held back by Settings: downloads at night only, or only while the phone charges (Schedule).
    WaitingForNight, WaitingForCharger;

    /** Waiting stages keep the progress bar still; working stages animate it. */
    val waiting get() = this == WaitingForNetwork || this == WaitingForWifi || this == Retrying || this == PausedBySystem || held

    /** Waiting because the schedule says so, not because something is missing; "Start now" overrides it. */
    val held get() = this == WaitingForNight || this == WaitingForCharger

    /** What "Start now" can end: the schedule, or waiting for Wi-Fi when mobile data is there. */
    val startable get() = held || this == WaitingForWifi

    companion object {
        /** Cheek's stage events and yt-dlp's post-processor tags. */
        fun fromEngine(name: String): Stage? = when (name.lowercase()) {
            "merging", "merger" -> Merging
            "extracting audio", "extractaudio" -> ExtractingAudio
            "remuxing", "fixupm3u8", "fixupm4a", "fixupstretched", "fixuptimestamp", "videoremuxer", "videoconvertor" -> Remuxing
            "tagging", "embedthumbnail", "metadata", "embedsubtitle", "thumbnailsconvertor" -> Tagging
            else -> null
        }
    }
}

/** What the user picked in the share sheet; turned into yt-dlp options by DownloadOptions. */
data class Choice(
    val mode: Mode,
    val height: Int?, // null = best
    val container: String, // mp4 / mkv / webm
    val audioFormat: String, // opus / m4a / mp3
    val subtitles: Boolean,
    val customFormat: String = "",
    /** Hamstr comes up playing it once it is in ("Open when done"). */
    val open: Boolean = false,
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
    val uploader: String? = null, // channel or artist
    // The video behind the link, e.g. "Youtube:dQw4w9WgXcQ", however it was shared.
    val mediaKey: String? = null,
    // Off the library and the stash, but the file stays; Settings > Hidden brings it back or deletes it.
    val hidden: Boolean = false,
    // The file's SHA-256 as it was saved, with its size and modified time then (FileChecks), so a
    // change made to it outside Hamstr shows.
    val sha256: String? = null,
    val hashedSize: Long = 0,
    val hashedModified: Long = 0,
    // Started now on purpose: the night and the charger (Schedule) don't hold it back.
    val anytime: Boolean = false,
    // Started now while it waited for Wi-Fi: it may use mobile data even with "Wi-Fi only".
    val mobileData: Boolean = false,
    // Put off to the night from its menu: it waits for the night hours even with "Only at night" off.
    val tonight: Boolean = false,
    // The channel or artist page it came from, to follow it from the player.
    val channelUrl: String? = null,
    // Live only, never saved: what happens besides fetching bytes, and the retry state.
    val stage: Stage? = null,
    val attempts: Int = 0,
    val retryAt: Long = 0,
    /** How far the current [stage] is, 0 to 1, when the engine reports it; -1 otherwise. */
    val stageProgress: Float = -1f,
) {
    /** The first download of a link and all its other qualities share this key. */
    val family get() = versionOf ?: id
}

/** What a running download is doing this moment: changes a few times a second and is never saved. */
data class Live(val progress: Float, val speed: String, val etaSec: Long, val stageProgress: Float)

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
 * All downloads, as one observable list, and running downloads' progress beside it ([live]). Every
 * change to either goes through this object's lock, one at a time, so a tick can't slip in between
 * a download being paused and its progress being kept. Progress lives in memory only; the list is
 * saved by a single IO worker on status changes. Bursts share a pending write so
 * queue actions never serialize the entire history or wait for disk on the UI thread.
 */
object DownloadStore {
    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks: StateFlow<List<DownloadTask>> = _tasks.asStateFlow()
    @Volatile private var file: File? = null
    private var worker: Job? = null
    private val saves = Channel<Unit>(Channel.CONFLATED)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // The history being read ahead of [init] (see [preload]), with the file it reads.
    private var reading: Pair<File, java.util.concurrent.FutureTask<Pair<String?, Read?>>>? = null

    private fun load(target: File): Pair<String?, Read?> {
        val text = runCatching { AtomicJson(target).read() }.getOrNull()
        return text to text?.let { runCatching { read(it) }.getOrNull() }
    }

    /**
     * Starts reading [dir]'s history on its own thread, so the rest of startup (settings, playlists)
     * reads its files meanwhile; [init] then takes what it read instead of reading it again.
     */
    @Synchronized
    fun preload(dir: File) {
        if (file != null || reading != null) return
        val target = File(dir, "downloads.json")
        val task = java.util.concurrent.FutureTask { load(target) }
        reading = target to task
        Thread(task, "hamstr-history").apply { isDaemon = true }.start()
    }

    @Synchronized
    fun init(dir: File) {
        if (file != null) return
        val target = File(dir, "downloads.json")
        file = target
        val atomic = AtomicJson(target)
        val ahead = reading?.takeIf { it.first == target }?.second
        reading = null
        val (text, read) = ahead?.let { runCatching { it.get() }.getOrNull() } ?: load(target)
        // Whatever can't be read (a newer version's entry, a damaged file) is kept aside before
        // the next save writes the list without it.
        if (text != null && (read == null || read.skipped > 0)) {
            runCatching { File(dir, "downloads.unreadable-${System.currentTimeMillis()}.json").writeText(text) }
                .onFailure { Log.e("HamstrStore", "Could not keep the unreadable download history", it) }
            Log.w("HamstrStore", "Kept ${read?.skipped ?: "all"} unreadable downloads aside")
        }
        val loaded = read?.tasks.orEmpty()
        // A process death interrupts running work; it resumes as queued (yt-dlp continues .part files).
        _tasks.value = loaded.map { if (it.status == Status.Running) it.copy(status = Status.Queued, speed = "", etaSec = -1) else it }
        worker = scope.launch {
            for (ignored in saves) {
                try {
                    // With running downloads' latest progress: after a restart they show how far they got.
                    atomic.write(HistoryJson.write(snapshot()))
                } catch (e: Exception) {
                    Log.e("HamstrStore", "Could not save download history", e)
                }
            }
        }
    }

    // Running downloads' progress, apart from the list: a tick changes this small map, not [tasks]
    // (and nothing a screen derives from the whole list). [current] puts the two together.
    private val _live = MutableStateFlow<Map<String, Live>>(emptyMap())
    val live: StateFlow<Map<String, Live>> = _live.asStateFlow()

    /** [t] with its latest progress while it runs; anything else is as the list has it. */
    fun current(t: DownloadTask, live: Map<String, Live> = _live.value): DownloadTask = current(t, live[t.id])

    /** [t] with [live], its own entry of [live] (a screen may listen to that one only). */
    fun current(t: DownloadTask, live: Live?): DownloadTask {
        if (t.status != Status.Running || live == null) return t
        return t.copy(progress = live.progress, speed = live.speed, etaSec = live.etaSec, stageProgress = live.stageProgress)
    }

    /** What the notification and the widget show of the list, newest first as in the list (progress comes from [live]). */
    class Summary(
        val running: List<DownloadTask>,
        val queued: List<DownloadTask>,
        val done: Int,
        val doneBytes: Long,
        val newestDone: DownloadTask?,
    )

    // The last list's summary: a tick leaves the list as it is, so it doesn't scan the history again.
    @Volatile private var summarized: Pair<List<DownloadTask>, Summary>? = null

    /** [Summary] of the list now, worked out once per list. */
    fun summary(): Summary {
        val list = _tasks.value
        summarized?.let { (of, s) -> if (of === list) return s }
        val running = ArrayList<DownloadTask>()
        val queued = ArrayList<DownloadTask>()
        var done = 0
        var bytes = 0L
        var newest: DownloadTask? = null
        for (t in list) when (t.status) {
            Status.Running -> running += t
            Status.Queued -> queued += t
            Status.Done -> { done++; bytes += t.sizeBytes; if (newest == null) newest = t }
            else -> {}
        }
        return Summary(running, queued, done, bytes, newest).also { summarized = list to it }
    }

    /** The download as it is now, its latest progress included. */
    fun get(id: String): DownloadTask? = _tasks.value.let { list -> indexOf(list, id).takeIf { it >= 0 }?.let { current(list[it]) } }

    /**
     * A progress tick of a running download: [block] gets it as it is now and changes its progress,
     * speed, time left or stage progress, which go to [live] only. Anything else it changes (the
     * first bytes end "Resolving") goes on the list like an [update]. Not running: nothing happens.
     */
    @Synchronized
    fun tick(id: String, block: (DownloadTask) -> DownloadTask) {
        val now = get(id) ?: return
        if (now.status != Status.Running) return
        val next = block(now)
        if (next == now) return
        if (next.copy(progress = now.progress, speed = now.speed, etaSec = now.etaSec, stageProgress = now.stageProgress) != now) {
            update(id, persist = false, block)
            return
        }
        _live.update { it + (id to Live(next.progress, next.speed, next.etaSec, next.stageProgress)) }
    }

    /** Entries changed or gone: their ticks so far are in the list now (or no longer needed). */
    private fun settled(ids: Collection<String>) {
        if (ids.isEmpty() || _live.value.keys.none { it in ids }) return
        _live.update { it - ids.toSet() }
    }

    /** Where each id is in one list. Progress ticks only replace entries in place, so it carries over to the next list. */
    private class Index(val list: List<DownloadTask>, val at: Map<String, Int>)

    @Volatile private var index: Index? = null

    /** [id]'s place in [list], from the index (made once per new order of the list) instead of a search. */
    private fun indexOf(list: List<DownloadTask>, id: String): Int {
        val known = index?.takeIf { it.list === list }
            ?: Index(list, HashMap<String, Int>(list.size * 2).also { m -> list.forEachIndexed { i, t -> m.putIfAbsent(t.id, i) } }).also { index = it }
        return known.at[id] ?: -1
    }

    /** [next] holds [list]'s entries at the same places, some replaced: [list]'s index fits it too. */
    private fun samePlaces(list: List<DownloadTask>, next: List<DownloadTask>) {
        index?.takeIf { it.list === list }?.let { index = Index(next, it.at) }
    }

    fun add(task: DownloadTask) = addAll(listOf(task))

    /**
     * Adds several entries in one step, each in front of the one before as [add] would put them:
     * a playlist of a hundred videos copies the list once instead of a hundred times.
     */
    @Synchronized
    fun addAll(tasks: List<DownloadTask>) {
        if (tasks.isEmpty()) return
        // A new entry made from another (a playlist's next file, a song from a video) has its own file.
        val fresh = tasks.asReversed().map { it.unhashed() }
        _tasks.update { fresh + it }
        save()
    }

    /**
     * Changes one entry. When [block] hands the entry back as it was (most guarded updates do,
     * e.g. progress for a download that was paused meanwhile), the list isn't copied and no save
     * is asked for.
     */
    @Synchronized
    fun update(id: String, persist: Boolean = true, block: (DownloadTask) -> DownloadTask) {
        var changed = false
        _tasks.update { list ->
            changed = false
            val i = indexOf(list, id)
            if (i < 0) return@update list
            // As it is now, its latest progress included: that progress is kept in the list from here.
            val now = current(list[i])
            val next = rehashed(now, block(now))
            if (next == now) return@update list
            changed = true
            list.toMutableList().also { it[i] = next; samePlaces(list, it) }
        }
        if (changed) settled(listOf(id))
        if (changed && persist) save()
    }

    /**
     * A playlist's Pause, the list's part: of [ids], what only waits is paused in one step; what is
     * [busy] (a queue's job had it) is returned for the queue to stop. [busy] is one look at the
     * queue's jobs, taken under the lock it starts downloads with: a job that ends meanwhile is
     * still stopped (which pauses it), and none starts in between to slip past the Pause.
     */
    @Synchronized
    fun pauseWaiting(ids: Set<String>, busy: Set<String>): List<String> {
        updateAll(which = { it.id in ids && it.status == Status.Queued && it.id !in busy }) {
            it.copy(status = Status.Paused, speed = "", etaSec = -1, stage = null, retryAt = 0)
        }
        return ids.filter { it in busy }
    }

    /**
     * Changes every entry [which] picks in one step: one new list, one emission and at most one
     * save, where a loop of [update]s would copy the list, redraw and write once per entry.
     * Returns how many entries changed.
     */
    @Synchronized
    fun updateAll(persist: Boolean = true, which: (DownloadTask) -> Boolean, block: (DownloadTask) -> DownloadTask): Int {
        var changed = 0
        val ids = ArrayList<String>()
        _tasks.update { list ->
            changed = 0
            ids.clear()
            var out: MutableList<DownloadTask>? = null
            val live = _live.value
            list.forEachIndexed { i, t ->
                if (!which(t)) return@forEachIndexed
                val now = current(t, live)
                val next = rehashed(now, block(now))
                if (next == now) return@forEachIndexed
                (out ?: list.toMutableList().also { out = it })[i] = next
                ids += t.id
                changed++
            }
            out?.also { samePlaces(list, it) } ?: list
        }
        settled(ids)
        if (changed > 0 && persist) save()
        return changed
    }

    // Downloaded again: the hash belongs to the file before, FileChecks takes the new one.
    private fun rehashed(before: DownloadTask, next: DownloadTask) =
        if (next.sha256 != null && (next.status != Status.Done || before.status != Status.Done)) next.unhashed() else next

    private fun DownloadTask.unhashed() = if (sha256 == null) this else copy(sha256 = null, hashedSize = 0, hashedModified = 0)

    @Synchronized
    fun remove(id: String) {
        _tasks.update { list -> list.filterNot { it.id == id } }
        settled(listOf(id))
        save()
    }

    /** Takes entries off the list and returns them with their places, for [restore]. */
    @Synchronized
    fun removeAll(ids: Set<String>): List<IndexedValue<DownloadTask>> {
        var removed = emptyList<IndexedValue<DownloadTask>>()
        _tasks.update { list ->
            removed = list.withIndex().filter { it.value.id in ids }
            list.filterNot { it.id in ids }
        }
        settled(ids)
        save()
        return removed
    }

    /** Puts entries from [removeAll] back where they were (Undo). */
    @Synchronized
    fun restore(removed: List<IndexedValue<DownloadTask>>) {
        _tasks.update { list ->
            val out = list.toMutableList()
            removed.sortedBy { it.index }.forEach { (i, t) -> if (out.none { it.id == t.id }) out.add(i.coerceAtMost(out.size), t) }
            out
        }
        save()
    }

    /** Hides finished files from the lists or brings them back; the files stay where they are. */
    fun setHidden(ids: Set<String>, hidden: Boolean) {
        if (ids.isNotEmpty()) updateAll(which = { it.id in ids }) { it.copy(hidden = hidden) }
    }

    /** Deleting a physical file also removes duplicate entries that reference it. */
    @Synchronized
    fun removeFile(file: File) {
        val path = file.absoluteFile.normalize()
        val gone = ArrayList<String>()
        _tasks.update { list ->
            gone.clear()
            list.filterNot { task -> (task.filePath?.let { File(it).absoluteFile.normalize() == path } == true).also { if (it) gone += task.id } }
        }
        settled(gone)
        save()
    }

    /** A renamed file: every entry that pointed at [from] now points at [to] and carries its name. */
    fun moveFile(from: File, to: File, title: String, contentUri: String?) {
        val path = from.absoluteFile.normalize()
        updateAll(which = { task -> task.filePath?.let { File(it).absoluteFile.normalize() == path } == true }) {
            it.copy(filePath = to.absolutePath, title = title, contentUri = contentUri)
        }
    }

    /** Swaps the whole list at once; the debug build's demo mode uses it to stage and restore data. */
    @Synchronized
    fun replace(tasks: List<DownloadTask>) {
        _tasks.value = tasks
        _live.value = emptyMap()
        save()
    }

    /** [tasks] the way downloads.json keeps them, for a backup; [decode] reads it back. */
    /** The list with every running download's latest progress, as one consistent copy (for saving and backups). */
    @Synchronized
    fun snapshot(): List<DownloadTask> {
        val list = _tasks.value
        val live = _live.value
        return if (live.isEmpty()) list else list.map { current(it, live) }
    }

    fun encode(tasks: List<DownloadTask> = snapshot()): String = HistoryJson.write(tasks)

    /**
     * Adds entries from a backup that aren't on the list yet, after the ones that are. Nothing
     * starts downloading by itself: what was waiting or running there waits here, paused.
     */
    @Synchronized
    fun merge(incoming: List<DownloadTask>): Int {
        var added = 0
        _tasks.update { list ->
            val known = list.mapTo(HashSet()) { it.id }
            val fresh = incoming.filter { known.add(it.id) }.map { if (it.status.active) it.copy(status = Status.Paused) else it }
            added = fresh.size
            list + fresh
        }
        if (added > 0) save()
        return added
    }

    /** The saved list, as read from downloads.json or a copy of it; entries it can't read are left out. */
    fun decode(text: String): List<DownloadTask> = read(text).tasks

    class Read(val tasks: List<DownloadTask>, val skipped: Int)

    /** Reads every entry on its own, so one it doesn't understand can't take the others with it. */
    fun read(text: String): Read = HistoryJson.read(text).let { Read(it.tasks, it.skipped) }

    /** Where the list is saved, once [init] ran; for the tests. */
    internal val location: File? get() = file

    /** Stops saving and forgets the file, so a test can [init] the list from a folder of its own. */
    internal fun close() {
        val running = synchronized(this) {
            worker.also {
                worker = null
                file = null
                reading = null
            }
        }
        // Waits for a write that is under way, so it can't land in the next test's folder; outside
        // the lock, which the write needs for its snapshot.
        running?.let { runBlocking { it.cancelAndJoin() } }
    }

    /** How many times a save was asked for; the tests check that changes without effect ask for none. */
    internal val saveRequests = java.util.concurrent.atomic.AtomicInteger()

    private fun save() {
        saveRequests.incrementAndGet()
        if (file == null) return
        saves.trySend(Unit)
    }
}
