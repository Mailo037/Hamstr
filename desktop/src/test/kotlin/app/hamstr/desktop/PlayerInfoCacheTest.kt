package app.hamstr.desktop

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PlayerInfoCacheTest {
    private lateinit var dir: File

    @BeforeTest
    fun setUp() {
        dir = kotlin.io.path.createTempDirectory("hamstr-player-info-").toFile()
    }

    @AfterTest
    fun tearDown() { dir.deleteRecursively() }

    private fun file(name: String = "clip.opus") = File(dir, name).apply { writeText("first") }

    private fun info(duration: Double = 60.0) =
        FfPlayer.Info(duration = duration, width = 0, height = 0, audio = true, video = false)

    @Test
    fun unchangedFileAndNormalizedPathShareOneLoad() {
        val file = file()
        val cache = InfoCache()
        val expected = info()
        var loads = 0
        assertSame(expected, cache.get(file) { loads++; expected })
        assertSame(expected, cache.get(file) { loads++; info(120.0) })

        assertTrue(File(dir, "sub").mkdir())
        val alias = File(dir, "sub/../${file.name}")
        assertSame(expected, cache.get(alias) { loads++; info(180.0) })
        assertEquals(1, loads, "Unchanged metadata must not be probed again under a normalized alias")
    }

    @Test
    fun sizeChangeInvalidatesEvenWhenModificationTimeIsUnchanged() {
        val file = file()
        val cache = InfoCache()
        val original = info()
        assertSame(original, cache.get(file) { original })
        val size = file.length()
        val modified = file.lastModified()

        file.appendText("more")
        assertTrue(file.setLastModified(modified))
        assertEquals(modified, file.lastModified())
        assertNotEquals(size, file.length())
        val replacement = info(120.0)
        assertSame(replacement, cache.get(file) { replacement })
        assertSame(replacement, cache.get(file) { error("Changed metadata should now be cached") })
    }

    @Test
    fun modificationTimeChangeInvalidatesEvenWhenSizeIsUnchanged() {
        val file = file()
        val cache = InfoCache()
        val original = info()
        assertSame(original, cache.get(file) { original })
        val size = file.length()
        val modified = file.lastModified()

        file.writeText("other")
        assertTrue(file.setLastModified(modified + 10_000))
        assertEquals(size, file.length())
        assertNotEquals(modified, file.lastModified())
        val replacement = info(120.0)
        assertSame(replacement, cache.get(file) { replacement })
        assertSame(replacement, cache.get(file) { error("Changed metadata should now be cached") })
    }

    @Test
    fun capacityEvictsLeastRecentlyUsedRatherThanOldestLoaded() {
        val cache = InfoCache(capacity = 2)
        val first = file("first.opus")
        val second = file("second.opus")
        val third = file("third.opus")
        val firstInfo = info(10.0)
        val secondInfo = info(20.0)
        val thirdInfo = info(30.0)
        assertSame(firstInfo, cache.get(first) { firstInfo })
        assertSame(secondInfo, cache.get(second) { secondInfo })
        assertSame(firstInfo, cache.get(first) { error("First entry should still be cached") })
        assertSame(thirdInfo, cache.get(third) { thirdInfo })

        assertSame(firstInfo, cache.get(first) { error("A cache hit must refresh recency") })
        assertSame(thirdInfo, cache.get(third) { error("Newest entry must remain cached") })
        val reloaded = info(25.0)
        var loads = 0
        assertSame(reloaded, cache.get(second) { loads++; reloaded })
        assertEquals(1, loads, "The least recently used entry must be loaded again")
        assertSame(reloaded, cache.get(second) { error("Reloaded entry should be cached") })
    }

    @Test
    fun defaultCapacityKeepsThirtyTwoEntries() {
        val cache = InfoCache()
        val files = List(33) { file("clip-$it.opus") }
        val metadata = List(33) { info(it.toDouble()) }
        for (index in 0 until 32) {
            assertSame(metadata[index], cache.get(files[index]) { metadata[index] })
        }
        for (index in 0 until 32) {
            assertSame(metadata[index], cache.get(files[index]) { error("Default capacity must hold 32 entries") })
        }
        assertSame(metadata[32], cache.get(files[32]) { metadata[32] })
        for (index in 1..32) {
            assertSame(metadata[index], cache.get(files[index]) { error("Only the oldest entry should be evicted") })
        }
        var loads = 0
        assertSame(metadata[0], cache.get(files[0]) { loads++; metadata[0] })
        assertEquals(1, loads, "The 33rd entry must evict the least recently used one")
    }

    @Test
    fun failedLoadsAreRetriedUntilMetadataIsAvailable() {
        val file = file()
        val cache = InfoCache()
        var loads = 0
        repeat(2) {
            assertNull(cache.get(file) { loads++; null })
        }
        assertEquals(2, loads, "A failed probe must not be cached")

        val expected = info()
        assertSame(expected, cache.get(file) { loads++; expected })
        assertSame(expected, cache.get(file) { error("A successful retry must be cached") })
        assertEquals(3, loads)
    }

    @Test
    fun throwingLoaderDoesNotPreventASuccessfulRetry() {
        val file = file()
        val cache = InfoCache()
        val failure = IllegalStateException("Probe failed")
        assertSame(failure, assertFailsWith<IllegalStateException> { cache.get(file) { throw failure } })

        val expected = info()
        assertSame(expected, cache.get(file) { expected })
        assertSame(expected, cache.get(file) { error("The retry's successful metadata must be cached") })
    }
}
