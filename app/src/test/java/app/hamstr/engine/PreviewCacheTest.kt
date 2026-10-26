package app.hamstr.engine

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import org.junit.Assert.*
import org.junit.Test

class PreviewCacheTest {
    @Test fun simultaneousCallersAndCanceledWaiterShareOneExtraction() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val cache = PreviewCache<String>(scope, weightOf = { it.length })
            val started = CompletableDeferred<Unit>()
            val result = CompletableDeferred<String>()
            var calls = 0
            val first = async(start = CoroutineStart.UNDISPATCHED) {
                cache.getOrLoad("url") { calls++; started.complete(Unit); result.await() }
            }
            started.await()
            val second = async(start = CoroutineStart.UNDISPATCHED) {
                cache.getOrLoad("url") { error("Started a duplicate extraction") }
            }
            first.cancel()
            result.complete("metadata")
            assertEquals("metadata", second.await())
            assertEquals("metadata", cache.get("url"))
            assertEquals(1, calls)
        } finally { scope.cancel() }
    }

    @Test fun expiredSignedUrlsAreNeverReused() = runBlocking {
        var time = 0L
        val cache = PreviewCache<String>(this, ttlMillis = 100, weightOf = { it.length }, now = { time })
        cache.getOrLoad("url") { "old" }
        time = 100
        assertNull(cache.get("url"))
        assertEquals("fresh", cache.getOrLoad("url") { "fresh" })
        cache.invalidate("url")
        assertNull(cache.get("url"))
    }

    @Test fun evictsLeastRecentlyUsedAndBoundsMemory() = runBlocking {
        val cache = PreviewCache<String>(this, maxEntries = 2, maxWeight = 6, weightOf = { it.length })
        cache.getOrLoad("a") { "aa" }
        cache.getOrLoad("b") { "bb" }
        assertEquals("aa", cache.get("a"))
        cache.getOrLoad("c") { "ccc" }
        assertNull(cache.get("b"))
        assertEquals("aa", cache.get("a"))
        cache.getOrLoad("huge") { "1234567" }
        assertNull(cache.get("huge"))
        assertEquals("ccc", cache.get("c"))
    }

    @Test fun liveResultsAndFailuresCanBeLoadedAgain() = runBlocking { supervisorScope {
        val cache = PreviewCache<String>(this, weightOf = { it.length }, cacheable = { it != "live" })
        assertEquals("live", cache.getOrLoad("url") { "live" })
        assertNull(cache.get("url"))
        try { cache.getOrLoad("url") { error("offline") }; fail("Expected failure") } catch (_: IllegalStateException) { }
        assertEquals("recovered", cache.getOrLoad("url") { "recovered" })
    } }
}
