package app.hamstr.ui

import app.hamstr.data.Choice
import app.hamstr.data.DownloadStore
import app.hamstr.data.DownloadTask
import app.hamstr.data.Mode
import app.hamstr.data.Stage
import app.hamstr.data.Status
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StashListsTest {
    private fun task(id: String, status: Status, size: Long = 0, stage: Stage? = null) = DownloadTask(
        id = id, url = "https://example.com/$id", title = id, thumbnail = null, source = null, durationSec = 0,
        choice = Choice(Mode.Video, null, "mp4", "opus", false), status = status, sizeBytes = size, stage = stage,
    )

    @Test
    fun sortsTheDownloadsInOnePass() {
        val lists = StashLists(listOf(
            task("q2", Status.Queued), task("run", Status.Running), task("night", Status.Queued, stage = Stage.WaitingForNight),
            task("fail", Status.Failed), task("d1", Status.Done, 10), task("q1", Status.Queued), task("gone", Status.Canceled), task("d2", Status.Done, 5),
        ))
        assertEquals(listOf("q2", "run", "night", "fail", "q1"), lists.active.map { it.id })
        assertEquals(listOf("d1", "d2"), lists.done.map { it.id })
        assertEquals(listOf("night"), lists.held.map { it.id })
        assertEquals(listOf("fail"), lists.failed.map { it.id })
        assertEquals(15, lists.totalBytes)
        assertEquals(4, lists.running)
        assertEquals(1, lists.moving)
        // The oldest waiting one (last in the list) starts first.
        assertEquals(mapOf("q1" to 0, "q2" to 1), lists.line)
        assertTrue(!lists.empty)
        assertTrue(StashLists(listOf(task("x", Status.Canceled))).empty)
    }

    @Test
    fun measuresAProgressTickAndAStartOnALongHistory() {
        // Not a pass/fail timing: what a progress tick costs on 5000 downloads, from the store to
        // what the stash derives from the list, the way it was (the tick a new list: mapped, published,
        // partitioned again) and now (the tick in the live map; the list and its partitions stay).
        val history = (0 until 5000).map { task("h$it", Status.Done, 1000L + it) } + task("run", Status.Running)
        DownloadStore.replace(history)
        try {
            fun perCall(times: Int, block: (Int) -> Unit): Double {
                repeat(times) { block(it) } // warm up
                val start = System.nanoTime()
                repeat(times) { block(it) }
                return (System.nanoTime() - start) / times / 1000.0
            }
            val before = kotlinx.coroutines.flow.MutableStateFlow(history)
            val old = perCall(5_000) { n ->
                before.value = before.value.map { if (it.id != "run" || it.status != Status.Running) it else it.copy(progress = (n % 100) / 100f) }
                StashLists(before.value)
            }
            val lists = StashLists(DownloadStore.tasks.value)
            val now = perCall(5_000) { n -> DownloadStore.tick("run") { it.copy(progress = (n % 100) / 100f) } }
            assertTrue(lists.done.size == StashLists(DownloadStore.tasks.value).done.size)
            val saved = DownloadStore.encode()
            val start = perCall(50) { DownloadStore.decode(saved) }
            println("5000 downloads: progress tick before ${"%.1f".format(old)} µs, now ${"%.1f".format(now)} µs; reading the history at start ${"%.0f".format(start)} µs (${saved.length / 1024} KB)")
            assertEquals(5001, DownloadStore.decode(saved).size)
        } finally {
            DownloadStore.replace(emptyList())
        }
    }
}
