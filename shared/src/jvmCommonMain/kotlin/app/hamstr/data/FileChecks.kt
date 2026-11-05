package app.hamstr.data

import app.hamstr.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Finished downloads whose file was deleted outside Hamstr, and those whose file was changed
 * there. Every saved file gets its SHA-256 once it is in; later checks compare size and modified
 * time first and only hash again when those moved, so they stay cheap enough for every return
 * to the app.
 */
object FileChecks {
    private const val TAG = "HamstrFileChecks"
    // How many hashed files go on the list at once; a long check still shows its first results soon.
    private const val BATCH = 16
    private const val BATCH_NS = 2_000_000_000L
    // A file this big takes a noticeable moment to hash (tens of MB read).
    private const val BIG = 32L shl 20
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val runs = Channel<Unit>(Channel.CONFLATED)
    private val _missing = MutableStateFlow<Set<String>>(emptySet())
    val missing: StateFlow<Set<String>> = _missing.asStateFlow()
    private val _changed = MutableStateFlow<Set<String>>(emptySet())
    /** Files that are no longer what was saved. */
    val changed: StateFlow<Set<String>> = _changed.asStateFlow()

    /** A file hashed again after its time moved: what came out, so the next check doesn't hash it once more. */
    private class Verdict(val size: Long, val modified: Long, val changed: Boolean)
    private val verdicts = ConcurrentHashMap<String, Verdict>()
    /** Files Hamstr may not read (on a phone, after a reinstall); they keep no hash. */
    private val unreadable = ConcurrentHashMap.newKeySet<String>()

    init {
        // One check at a time; asks that come in meanwhile make one more.
        scope.launch { for (ignored in runs) runCatching { check() }.onFailure { Log.w(TAG, "File check failed", it) } }
        // A file that just came in is hashed right away, not on the next return to the app.
        scope.launch {
            // Which files wait for a hash: a progress tick makes no list, and a new file changes the set.
            // Only a newcomer asks for a check; the set shrinking is a check's own results coming in.
            var known = emptySet<String>()
            DownloadStore.tasks.map { list ->
                list.mapNotNullTo(HashSet()) { t -> t.id.takeIf { t.status == Status.Done && t.filePath != null && t.sha256 == null } }
            }
                .distinctUntilChanged()
                .collect { waiting ->
                    if (waiting.any { it !in known }) refresh()
                    known = waiting
                }
        }
    }

    fun refresh() {
        runs.trySend(Unit)
    }

    /** The file's SHA-256 in hex; null when it can't be read. Slow for big files: off the main thread. */
    fun sha256(file: File): String? = sha256(file, ByteArray(BUFFER))

    /** [sha256] reading through [buffer]: a check hashes all its files with one. */
    internal fun sha256(file: File, buffer: ByteArray): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        val bytes = digest.digest()
        val hex = CharArray(bytes.size * 2)
        bytes.forEachIndexed { i, b -> hex[i * 2] = HEX[(b.toInt() shr 4) and 15]; hex[i * 2 + 1] = HEX[b.toInt() and 15] }
        String(hex)
    }.getOrNull()

    private const val BUFFER = 1 shl 20
    private val HEX = "0123456789abcdef".toCharArray()

    // Hashed during one check, by path, size and time: downloads that share a file read it once.
    private data class Seen(val path: String, val size: Long, val modified: Long)

    private fun check() {
        val done = DownloadStore.tasks.value.filter { it.status == Status.Done && it.filePath != null }
        // Gone files first: that needs no hashing.
        _missing.value = done.filter { !File(it.filePath!!).exists() }.map { it.id }.toSet()
        val changed = HashSet<String>()
        // Hashes found go on the list in batches: one list and one save for many small files, not
        // one each. A batch never waits behind a big file's hash (it goes out first) nor longer than
        // a couple of seconds, and what was found is kept even when the check ends early.
        val found = HashMap<String, (DownloadTask) -> DownloadTask>()
        var since = 0L
        fun keep() {
            if (found.isEmpty()) return
            val batch = HashMap(found)
            found.clear()
            DownloadStore.updateAll(which = { it.id in batch }) { batch.getValue(it.id)(it) }
        }
        fun record(id: String, change: (DownloadTask) -> DownloadTask) {
            if (found.isEmpty()) since = System.nanoTime()
            found[id] = change
            if (found.size >= BATCH) keep()
        }
        fun due() = found.isNotEmpty() && System.nanoTime() - since >= BATCH_NS
        val hashed = HashMap<Seen, String?>()
        // One read buffer for the whole check, not a new megabyte per file; it goes when the check ends.
        val buffer by lazy { ByteArray(BUFFER) }
        fun hash(file: File, size: Long, modified: Long) = hashed.getOrPut(Seen(file.path, size, modified)) { sha256(file, buffer) }
        try {
            for (t in done) {
                if (due()) keep()
                val file = File(t.filePath!!)
                if (!file.isFile) continue
                val size = file.length()
                // Hashing it takes a while: what is found so far goes out first.
                if (size > BIG && found.isNotEmpty()) keep()
                val modified = file.lastModified()
                val saved = t.sha256
                if (saved == null) {
                    if (file.path in unreadable) continue
                    val hash = hash(file, size, modified) ?: run { unreadable += file.path; continue }
                    // Still the same download of the same file as when hashing started (not renamed,
                    // replaced or downloaded again meanwhile).
                    record(t.id) { if (it.same(t) && it.sha256 == null) it.copy(sha256 = hash, hashedSize = size, hashedModified = modified) else it }
                    continue
                }
                when {
                    size != t.hashedSize -> changed += t.id
                    modified == t.hashedModified -> {}
                    else -> {
                        val seen = verdicts[t.id]?.takeIf { it.size == size && it.modified == modified }
                        val differs = seen?.changed ?: (hash(file, size, modified) ?: continue).let { it != saved }.also { verdicts[t.id] = Verdict(size, modified, it) }
                        if (differs) changed += t.id
                        // Only touched: the same bytes, a new time.
                        else record(t.id) { if (it.same(t) && it.sha256 == saved) it.copy(hashedModified = modified) else it }
                    }
                }
            }
        } finally {
            keep()
        }
        _changed.value = changed
    }

    /** The same finished download of the same file as [seen]: a new download (even to the same path) finishes anew. */
    private fun DownloadTask.same(seen: DownloadTask) =
        status == Status.Done && filePath == seen.filePath && finishedAt == seen.finishedAt && sizeBytes == seen.sizeBytes
}
