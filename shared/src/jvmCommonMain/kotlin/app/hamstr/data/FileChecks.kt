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
            DownloadStore.tasks.map { list -> list.filter { it.status == Status.Done && it.filePath != null && it.sha256 == null }.map { it.id } }
                .distinctUntilChanged()
                .collect { if (it.isNotEmpty()) refresh() }
        }
    }

    fun refresh() {
        runs.trySend(Unit)
    }

    /** The file's SHA-256 in hex; null when it can't be read. Slow for big files: off the main thread. */
    fun sha256(file: File): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()

    private fun check() {
        val done = DownloadStore.tasks.value.filter { it.status == Status.Done && it.filePath != null }
        // Gone files first: that needs no hashing.
        _missing.value = done.filter { !File(it.filePath!!).exists() }.map { it.id }.toSet()
        val changed = HashSet<String>()
        for (t in done) {
            val file = File(t.filePath!!)
            if (!file.isFile) continue
            val size = file.length()
            val modified = file.lastModified()
            val saved = t.sha256
            if (saved == null) {
                if (file.path in unreadable) continue
                val hash = sha256(file) ?: run { unreadable += file.path; continue }
                // Still the same file it was when hashing started (not renamed or replaced meanwhile).
                DownloadStore.update(t.id) {
                    if (it.filePath == t.filePath && it.sha256 == null) it.copy(sha256 = hash, hashedSize = size, hashedModified = modified) else it
                }
                continue
            }
            when {
                size != t.hashedSize -> changed += t.id
                modified == t.hashedModified -> {}
                else -> {
                    val seen = verdicts[t.id]?.takeIf { it.size == size && it.modified == modified }
                    val differs = seen?.changed ?: (sha256(file) ?: continue).let { it != saved }.also { verdicts[t.id] = Verdict(size, modified, it) }
                    if (differs) changed += t.id
                    // Only touched: the same bytes, a new time.
                    else DownloadStore.update(t.id) { if (it.sha256 == saved) it.copy(hashedModified = modified) else it }
                }
            }
        }
        _changed.value = changed
    }
}
