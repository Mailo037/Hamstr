package app.hamstr.data

import app.hamstr.Log
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * [KeyValues] in a properties file, written whole and swapped in after a change. Like Android's
 * SharedPreferences.apply, a change is in memory at once and reaches the disk on a writer thread
 * a moment later, so a switch flipped in the window doesn't wait for the disk; changes made
 * meanwhile go out with it. A write that fails is tried again by itself, a little later each time,
 * until the file has caught up; [flush] (and quitting) tries at once. Every PropertiesFile of the
 * same file shares one copy in memory, so an older one keeps working after the file is opened again.
 */
class PropertiesFile(file: File) : KeyValues {
    private val store = Store.open(file)

    override fun getString(key: String, default: String?): String? = store.values[key] ?: default
    override fun getInt(key: String, default: Int) = store.values[key]?.toIntOrNull() ?: default
    override fun getLong(key: String, default: Long) = store.values[key]?.toLongOrNull() ?: default
    override fun getBoolean(key: String, default: Boolean) = store.values[key]?.toBooleanStrictOrNull() ?: default
    override fun all(): Map<String, Any?> = store.values

    override fun edit(): KeyValues.Editor = object : KeyValues.Editor {
        private val changes = LinkedHashMap<String, String?>()
        override fun putString(key: String, value: String?) = apply { changes[key] = value }
        override fun putInt(key: String, value: Int) = apply { changes[key] = value.toString() }
        override fun putLong(key: String, value: Long) = apply { changes[key] = value.toString() }
        override fun putBoolean(key: String, value: Boolean) = apply { changes[key] = value.toString() }
        override fun remove(key: String) = apply { changes[key] = null }
        override fun apply() = store.save(changes)
    }

    /** The file has everything that was changed. */
    val saved: Boolean get() = store.saved

    /**
     * Writes what is pending now, without waiting for a retry's turn, and waits for it (and every
     * write asked for before it); true when the file has caught up.
     */
    fun flush(timeoutMs: Long = 10_000): Boolean = store.flush(timeoutMs)

    /** How many writes of this file were tried; for the tests. */
    internal val attempts: Int get() = store.attempts.get()

    /**
     * One file's contents in memory and the writes that bring the file up to them. The contents are
     * an immutable map swapped whole, so a reader never sees half of a change or of a reload.
     */
    private class Store(private val file: File, @Volatile var values: Map<String, String>) {
        // Guarded by this: a write is queued; the file is behind memory (until a write succeeds); the wait before a retry.
        private var queued = false
        private var behind = false
        private var retryMs = FIRST_RETRY_MS
        // Writes tried on this file, for the tests: failing flushes mustn't pile up retries.
        val attempts = java.util.concurrent.atomic.AtomicInteger()
        // The one write waiting for its time (a change's, gathering more; or a retry), if any: a flush takes its place.
        private var next: java.util.concurrent.ScheduledFuture<*>? = null
        // Counts the changes made in memory, so a reload can tell one came in while it read the file.
        private var version = 0L

        val saved: Boolean @Synchronized get() = !behind

        /** From the file as it is on disk now (it may have been replaced or deleted since); unsaved changes stay. */
        fun reload() {
            if (!flush(10_000)) return
            afterFlush?.invoke()
            // Only from a file that has everything: a change made since (not on disk yet) is newer, and stays.
            val before = synchronized(this) { if (behind) return; version }
            val fresh = runCatching { read(file) }.getOrElse { Log.w("HamstrSettings", "Could not read ${file.name} again", it); return }
            afterRead?.invoke()
            // A change made meanwhile (and maybe written already) is newer than what was read: it stays.
            synchronized(this) { if (!behind && version == before) values = fresh }
        }

        fun flush(timeoutMs: Long): Boolean {
            if (saved) return true
            synchronized(this) {
                // This write takes the waiting one's place; if it fails, it plans the next one itself.
                next?.cancel(false)
                next = null
                queued = true
            }
            runCatching { WRITER.submit(::write).get(timeoutMs, TimeUnit.MILLISECONDS) }
            return saved
        }

        @Synchronized
        fun save(changes: Map<String, String?>) {
            // Nothing new (a position saved twice, an edit that changed nothing): the file stays as it is.
            if (changes.all { (k, v) -> values[k] == v }) return
            values = LinkedHashMap(values).apply { changes.forEach { (k, v) -> if (v == null) remove(k) else put(k, v) } }
            version++
            behind = true
            if (queued) return
            queued = true
            // A moment later: a slider dragged or a burst of edits goes out in one write.
            next = WRITER.schedule(::write, GATHER_MS, TimeUnit.MILLISECONDS)
        }

        /** On the writer thread: everything changed so far, in one write. */
        private fun write() {
            val snapshot = synchronized(this) {
                queued = false
                // This write is the waiting one, or makes it unneeded: none stays waiting beside it.
                next?.cancel(false)
                next = null
                // A flush got there first.
                if (!behind) return
                values
            }
            attempts.incrementAndGet()
            try {
                file.parentFile?.mkdirs()
                val next = File(file.path + ".new")
                next.outputStream().use { Properties().apply { putAll(snapshot) }.store(it, "Hamstr") }
                Files.move(next.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                // Caught up, unless something changed while this was written (its own write is queued then).
                synchronized(this) {
                    if (!queued) behind = false
                    retryMs = FIRST_RETRY_MS
                }
            } catch (e: Exception) {
                Log.e("HamstrSettings", "Could not save ${file.name}; trying again", e)
                synchronized(this) {
                    if (queued) return
                    queued = true
                    next = WRITER.schedule(::write, retryMs, TimeUnit.MILLISECONDS)
                    retryMs = (retryMs * 2).coerceAtMost(LAST_RETRY_MS)
                }
            }
        }

        companion object {
            private val STORES = HashMap<String, Store>()

            fun read(file: File): Map<String, String> =
                if (!file.isFile) emptyMap()
                else Properties().apply { file.inputStream().use(::load) }.let { p -> p.stringPropertyNames().associateWith { p.getProperty(it) } }

            /** The file's store: loaded before anyone else can see it; opened again, it reads what the disk has now. */
            fun open(file: File): Store {
                val path = file.absoluteFile.normalize().path
                val known = synchronized(STORES) {
                    STORES[path] ?: return Store(file, read(file)).also { STORES[path] = it }
                }
                known.reload()
                return known
            }

            fun all(): List<Store> = synchronized(STORES) { STORES.values.toList() }
        }
    }

    companion object {
        // How long a change waits for others to go out with it.
        private const val GATHER_MS = 150L
        private const val FIRST_RETRY_MS = 500L
        private const val LAST_RETRY_MS = 30_000L

        // One thread for every file: writes happen in the order they were asked for.
        private val WRITER = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "hamstr-settings").apply { isDaemon = true } }

        init {
            // Quitting (exitProcess included) writes what is pending first.
            Runtime.getRuntime().addShutdownHook(Thread {
                if (!flushAll()) Log.w("HamstrSettings", "Quit before every setting was saved")
            })
        }

        // For the tests: steps of a reload where another thread's change can come in.
        internal var afterFlush: (() -> Unit)? = null
        internal var afterRead: (() -> Unit)? = null

        /** Holds the writer until the returned function is called; for the tests. */
        internal fun pauseWriter(): () -> Unit {
            val gate = java.util.concurrent.CountDownLatch(1)
            WRITER.execute { gate.await() }
            return { gate.countDown() }
        }

        /** [flush] for every file; true when all of them have caught up. */
        fun flushAll(timeoutMs: Long = 10_000): Boolean = Store.all().map { it.flush(timeoutMs) }.all { it }
    }
}
