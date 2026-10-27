package app.hamstr.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async

/** Short-lived extraction results. Concurrent callers share one Python process. */
internal class PreviewCache<T>(
    private val scope: CoroutineScope,
    private val ttlMillis: Long = 120_000,
    private val maxEntries: Int = 8,
    private val maxWeight: Int = 4 * 1024 * 1024,
    private val weightOf: (T) -> Int,
    private val cacheable: (T) -> Boolean = { true },
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private data class Entry<T>(val value: T, val expires: Long, val weight: Int)
    private val lock = Any()
    private val entries = LinkedHashMap<String, Entry<T>>(16, 0.75f, true)
    private val loading = mutableMapOf<String, Deferred<T>>()

    fun get(key: String): T? = synchronized(lock) {
        prune()
        entries[key]?.value
    }

    fun invalidate(key: String) = synchronized(lock) { entries.remove(key); Unit }

    /** Stores a result that didn't come from [getOrLoad], as if it had just been loaded. */
    fun put(key: String, value: T) = synchronized(lock) {
        entries[key] = Entry(value, now() + ttlMillis, weightOf(value))
        prune()
    }

    suspend fun getOrLoad(key: String, load: suspend () -> T): T {
        val deferred = synchronized(lock) {
            prune()
            entries[key]?.let { return it.value }
            loading.getOrPut(key) {
                scope.async(start = CoroutineStart.LAZY) {
                    try {
                        load().also { value ->
                            val weight = weightOf(value)
                            if (cacheable(value) && weight in 0..maxWeight) synchronized(lock) {
                                entries[key] = Entry(value, now() + ttlMillis, weight)
                                prune()
                                while (entries.size > maxEntries || entries.values.sumOf { it.weight.toLong() } > maxWeight) {
                                    entries.remove(entries.keys.first())
                                }
                            }
                        }
                    } finally {
                        synchronized(lock) { loading.remove(key) }
                    }
                }
            }
        }
        // Cancellation of one sheet must not cancel another caller's extraction.
        return deferred.await()
    }

    private fun prune() {
        val time = now()
        entries.entries.removeAll { it.value.expires <= time }
    }
}
