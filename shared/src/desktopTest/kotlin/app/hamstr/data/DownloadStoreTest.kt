package app.hamstr.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DownloadStoreTest {
    private fun entry(id: String, status: String = "Done", mode: String = "Video") = JSONObject()
        .put("id", id).put("url", "https://example.com/$id").put("title", "Video $id")
        .put("status", status).put("mode", mode).put("size", 1234)

    @Test
    fun oneUnreadableEntryKeepsTheOthers() {
        val text = JSONArray()
            .put(entry("a"))
            .put(JSONObject().put("url", "https://example.com/no-id")) // no id: can't be read
            .put(entry("b", status = "Scheduled", mode = "Karaoke")) // from a newer Hamstr
            .toString()
        val read = DownloadStore.read(text)
        assertEquals(listOf("a", "b"), read.tasks.map { it.id })
        assertEquals(1, read.skipped)
        // Unknown states wait to be tried again instead of breaking the list.
        assertEquals(Status.Paused, read.tasks[1].status)
        assertEquals(Mode.Video, read.tasks[1].choice.mode)
    }

    @Test
    fun unreadableHistoryIsKeptAsideBeforeItIsOverwritten() {
        val dir = File(System.getProperty("java.io.tmpdir"), "hamstr-store-" + System.nanoTime()).apply { mkdirs() }
        val broken = JSONArray().put(entry("a")).put(JSONObject().put("nope", true)).toString()
        File(dir, "downloads.json").writeText(broken)
        DownloadStore.close()
        DownloadStore.init(dir)
        try {
            assertEquals(listOf("a"), DownloadStore.tasks.value.map { it.id })
            val kept = dir.listFiles().orEmpty().filter { it.name.startsWith("downloads.unreadable-") }
            assertEquals(1, kept.size)
            assertEquals(broken, kept.single().readText())
            assertTrue(File(dir, "downloads.json").readText().contains("\"a\""))
        } finally {
            // The list is the process's own: tests after this one (the tour's first start) expect it empty.
            DownloadStore.replace(emptyList())
            DownloadStore.close()
        }
    }

    @Test
    fun aHistoryReadAheadIsTheOneInitTakes() {
        val dir = File(System.getProperty("java.io.tmpdir"), "hamstr-store-" + System.nanoTime()).apply { mkdirs() }
        val other = File(System.getProperty("java.io.tmpdir"), "hamstr-store-" + System.nanoTime()).apply { mkdirs() }
        File(dir, "downloads.json").writeText(JSONArray().put(entry("a")).put(entry("b", status = "Running")).toString())
        File(other, "downloads.json").writeText(JSONArray().put(entry("x")).toString())
        DownloadStore.close()
        try {
            // Read ahead for another folder: init reads its own.
            DownloadStore.preload(other)
            DownloadStore.init(dir)
            assertEquals(listOf("a", "b"), DownloadStore.tasks.value.map { it.id })
            DownloadStore.close()
            // Read ahead for this folder: init takes what was read, finished or not.
            DownloadStore.preload(dir)
            Thread.sleep(200)
            DownloadStore.init(dir)
            assertEquals(listOf("a", "b"), DownloadStore.tasks.value.map { it.id })
            // A download that ran when the app died waits in the queue again.
            assertEquals(Status.Queued, DownloadStore.tasks.value[1].status)
        } finally {
            DownloadStore.replace(emptyList())
            DownloadStore.close()
            dir.deleteRecursively()
            other.deleteRecursively()
        }
    }

    @Test
    fun aPlaylistPauseStopsOnlyWhatIsBusyAndPausesTheRestInOneStep() = observing(
        listOf(task("wait"), task("busy"), task("starting"), task("done", Status.Done), task("other")),
    ) { seen ->
        val before = seen.size
        // "starting" has a job but isn't Running yet: the queue stops it, the list doesn't pause it under it.
        val busy = DownloadStore.pauseWaiting(setOf("wait", "busy", "starting", "done"), setOf("busy", "starting"))
        assertEquals(setOf("busy", "starting"), busy.toSet())
        assertEquals(Status.Paused, DownloadStore.get("wait")!!.status)
        assertEquals(Status.Queued, DownloadStore.get("busy")!!.status)
        assertEquals(Status.Queued, DownloadStore.get("starting")!!.status)
        assertEquals(Status.Done, DownloadStore.get("done")!!.status)
        assertEquals(Status.Queued, DownloadStore.get("other")!!.status)
        assertEquals(before + 1, seen.size, "one new list")
    }

    @Test
    fun theSummaryIsWorkedOutOncePerListAndTicksKeepIt() = observing(
        listOf(task("r", Status.Running), task("q"), task("d1", Status.Done), task("d2", Status.Done), task("f", Status.Failed)),
    ) {
        val first = DownloadStore.summary()
        assertEquals(listOf("r"), first.running.map { it.id })
        assertEquals(listOf("q"), first.queued.map { it.id })
        assertEquals(2, first.done)
        assertEquals("d1", first.newestDone?.id)
        // A tick changes the progress only: the same summary, no new scan.
        DownloadStore.tick("r") { it.copy(progress = 0.5f) }
        assertSame(first, DownloadStore.summary())
        // A new list: a new summary.
        DownloadStore.update("q") { it.copy(status = Status.Paused) }
        assertTrue(DownloadStore.summary().queued.isEmpty())
    }

    @Test
    fun aBackupHasTheLatestProgressOfRunningDownloads() = observing(listOf(task("r", Status.Running), task("q"))) {
        DownloadStore.tick("r") { it.copy(progress = 0.8f, speed = "2 MiB/s") }
        val back = DownloadStore.decode(DownloadStore.encode())
        assertEquals(0.8f, back.first { it.id == "r" }.progress)
        // Restored, it waits paused, where it left off.
        DownloadStore.replace(emptyList())
        DownloadStore.merge(back)
        assertEquals(Status.Paused, DownloadStore.get("r")!!.status)
        assertEquals(0.8f, DownloadStore.get("r")!!.progress)
    }

    @Test
    fun removingAFileTakesItsProgressWithIt() {
        val file = File(System.getProperty("java.io.tmpdir"), "hamstr-gone-" + System.nanoTime() + ".mp4")
        observing(listOf(task("r", Status.Running).copy(filePath = file.path), task("q"))) {
            DownloadStore.tick("r") { it.copy(progress = 0.5f) }
            assertEquals(setOf("r"), DownloadStore.live.value.keys)
            DownloadStore.removeFile(file)
            assertNull(DownloadStore.get("r"))
            assertTrue(DownloadStore.live.value.isEmpty())
        }
    }

    private fun task(id: String, status: Status = Status.Queued, group: String? = null) = DownloadTask(
        id = id, url = "https://example.com/$id", title = id, thumbnail = null, source = null, durationSec = 0,
        choice = Choice(Mode.Video, null, "mp4", "opus", false), status = status, group = group,
    )

    /** Runs [block] with the list set to [start], recording every list the store publishes meanwhile. */
    private fun observing(start: List<DownloadTask>, block: (emitted: List<List<DownloadTask>>) -> Unit) = runBlocking {
        DownloadStore.replace(start)
        val emitted = mutableListOf<List<DownloadTask>>()
        // Unconfined: the collector runs inside each update, so no emission is conflated away.
        val watch = launch(Dispatchers.Unconfined) { DownloadStore.tasks.drop(1).collect { emitted += it } }
        try {
            block(emitted)
        } finally {
            watch.cancel()
            DownloadStore.replace(emptyList())
        }
    }

    @Test
    fun anUpdateThatChangesNothingCopiesAndSavesNothing() = observing(listOf(task("a", Status.Paused), task("b"))) { emitted ->
        val before = DownloadStore.tasks.value
        val saves = DownloadStore.saveRequests.get()
        // Guarded progress for a download that was paused meanwhile: handed back as it was.
        DownloadStore.update("a") { if (it.status == Status.Running) it.copy(progress = 0.5f) else it }
        DownloadStore.update("a") { it.copy() }
        DownloadStore.update("nobody") { it.copy(title = "x") }
        DownloadStore.updateAll(which = { it.status == Status.Paused }) { it }
        assertSame(before, DownloadStore.tasks.value)
        assertEquals(saves, DownloadStore.saveRequests.get(), "no save asked for")
        assertTrue(emitted.isEmpty())

        DownloadStore.update("b") { it.copy(progress = 0.5f) }
        assertEquals(saves + 1, DownloadStore.saveRequests.get())
        assertEquals(1, emitted.size)
        assertEquals(0.5f, DownloadStore.get("b")?.progress)
        // The entries that didn't change are the same objects, so the screens skip them.
        assertSame(before[0], emitted.single()[0])
    }

    @Test
    fun updateAllChangesEveryPickedEntryInOneEmissionAndOneSave() =
        observing(listOf(task("a", group = "g"), task("b"), task("c", group = "g"), task("d", Status.Done, group = "g"))) { emitted ->
            val before = DownloadStore.tasks.value
            val saves = DownloadStore.saveRequests.get()
            val n = DownloadStore.updateAll(which = { it.group == "g" && it.status == Status.Queued }) { it.copy(groupTitle = "List") }
            assertEquals(2, n)
            assertEquals(1, emitted.size, "one list for both changes")
            assertEquals(saves + 1, DownloadStore.saveRequests.get(), "one save for both changes")
            assertEquals(listOf("List", null, "List", null), DownloadStore.tasks.value.map { it.groupTitle })
            assertSame(before[1], DownloadStore.tasks.value[1])
            // Nothing left to change: nothing more is published or saved.
            assertEquals(0, DownloadStore.updateAll(which = { it.group == "g" && it.status == Status.Queued }) { it.copy(groupTitle = "List") })
            assertEquals(1, emitted.size)
            assertEquals(saves + 1, DownloadStore.saveRequests.get())
        }

    @Test
    fun addAllPutsEntriesWhereAddingThemOneByOneWouldInOneStep() = observing(listOf(task("old"))) { emitted ->
        val saves = DownloadStore.saveRequests.get()
        DownloadStore.addAll(listOf(task("1"), task("2"), task("3").copy(sha256 = "abc")))
        assertEquals(listOf("3", "2", "1", "old"), DownloadStore.tasks.value.map { it.id })
        assertEquals(1, emitted.size)
        assertEquals(saves + 1, DownloadStore.saveRequests.get())
        // A new entry has its own file, not the hash of the one it was made from.
        assertNull(DownloadStore.get("3")?.sha256)
        DownloadStore.addAll(emptyList())
        assertEquals(1, emitted.size)
    }

    @Test
    fun updateAllStillDropsTheHashOfAFileDownloadedAgain() =
        observing(listOf(task("a", Status.Done).copy(sha256 = "abc", hashedSize = 3, hashedModified = 4))) {
            DownloadStore.updateAll(persist = false, which = { true }) { it.copy(status = Status.Queued) }
            val t = DownloadStore.get("a")!!
            assertNull(t.sha256)
            assertEquals(0, t.hashedSize)
        }

    @Test
    fun concurrentUpdatesAreNotLost() = observing((0 until 50).map { task("t$it") }) {
        runBlocking(Dispatchers.Default) {
            (0 until 50).map { i ->
                launch {
                    repeat(20) { DownloadStore.update("t$i", persist = false) { it.copy(attempts = it.attempts + 1) } }
                    DownloadStore.updateAll(persist = false, which = { it.id == "t$i" }) { it.copy(etaSec = 7) }
                }
            }.joinAll()
        }
        assertTrue(DownloadStore.tasks.value.all { it.attempts == 20 && it.etaSec == 7L })
    }

    @Test
    fun concurrentChangesAllReachTheDisk() {
        // A folder of its own, whichever test ran first.
        DownloadStore.close()
        DownloadStore.init(File(System.getProperty("java.io.tmpdir"), "hamstr-store-" + System.nanoTime()).apply { mkdirs() })
        val file = DownloadStore.location!!
        try { observing((0 until 40).map { task("p$it") }) {
            runBlocking(Dispatchers.Default) {
                (0 until 40).map { i ->
                    launch {
                        repeat(10) { DownloadStore.update("p$i") { it.copy(attempts = it.attempts + 1) } }
                        DownloadStore.updateAll(which = { it.id == "p$i" }) { it.copy(status = Status.Paused) }
                    }
                }.joinAll()
                // The saves are conflated on their worker; the last one has everything.
                withTimeout(10_000) {
                    while (true) {
                        val saved = runCatching { DownloadStore.read(file.readText()).tasks }.getOrNull()
                        if (saved != null && saved.size == 40 && saved.all { it.status == Status.Paused }) break
                        delay(20)
                    }
                }
            }
            assertTrue(DownloadStore.tasks.value.all { it.attempts == 10 && it.status == Status.Paused })
        } } finally {
            DownloadStore.close()
        }
    }

    @Test
    fun aPlaylistGoesOnALongListInOneCopy() {
        // 300 videos queued onto a history of 5000: one by one makes (and publishes) 300 lists.
        val history = (0 until 5000).map { task("h$it", Status.Done) }
        val playlist = (0 until 300).map { task("v$it") }
        var oneByOne: List<String> = emptyList()
        observing(history) { emitted ->
            playlist.forEach(DownloadStore::add)
            assertEquals(300, emitted.size)
            oneByOne = DownloadStore.tasks.value.map { it.id }
        }
        observing(history) { emitted ->
            DownloadStore.addAll(playlist)
            assertEquals(1, emitted.size)
            assertEquals(oneByOne, DownloadStore.tasks.value.map { it.id }, "the same order as one by one")
        }
    }

    @Test
    fun lookupsStayRightAcrossEveryKindOfChange() = observing((0 until 10).map { task("t$it") }) {
        fun check() = DownloadStore.tasks.value.forEach { assertSame(it, DownloadStore.get(it.id)) }
        DownloadStore.update("t3") { it.copy(progress = 0.3f) }; check()
        DownloadStore.updateAll(which = { it.id < "t5" }) { it.copy(etaSec = 9) }; check()
        DownloadStore.addAll(listOf(task("n1"), task("n2"))); check()
        DownloadStore.update("t3") { it.copy(progress = 0.6f) }
        assertEquals(0.6f, DownloadStore.get("t3")?.progress)
        val removed = DownloadStore.removeAll(setOf("t1", "n2")); check()
        assertNull(DownloadStore.get("t1"))
        DownloadStore.update("t9") { it.copy(title = "nine") }
        assertEquals("nine", DownloadStore.get("t9")?.title)
        DownloadStore.restore(removed); check()
        DownloadStore.remove("t0"); check()
        DownloadStore.replace(listOf(task("x"))); check()
        assertNull(DownloadStore.get("t3"))
        DownloadStore.update("x") { it.copy(title = "ex") }
        assertEquals(listOf("ex"), DownloadStore.tasks.value.map { it.title })
    }

    @Test
    fun ticksGoToTheLiveMapNotTheList() = observing(listOf(task("run", Status.Running), task("p", Status.Paused))) { emitted ->
        val list = DownloadStore.tasks.value
        DownloadStore.tick("run") { it.copy(progress = 0.4f, speed = "1 MB/s", etaSec = 12) }
        DownloadStore.tick("run") { it.copy(progress = 0.5f) }
        assertSame(list, DownloadStore.tasks.value, "the list stays as it was")
        assertTrue(emitted.isEmpty())
        assertEquals(Live(0.5f, "1 MB/s", 12, -1f), DownloadStore.live.value["run"])
        // Whoever asks for the download gets it as it is now.
        assertEquals(0.5f, DownloadStore.get("run")?.progress)
        assertEquals(0f, DownloadStore.tasks.value[0].progress)
        // Not running: a tick does nothing.
        DownloadStore.tick("p") { it.copy(progress = 0.9f) }
        assertNull(DownloadStore.live.value["p"])
    }

    @Test
    fun anUpdateKeepsTheLatestTickInTheList() = observing(listOf(task("run", Status.Running))) {
        DownloadStore.tick("run") { it.copy(progress = 0.7f, speed = "2 MB/s") }
        DownloadStore.update("run") { it.copy(status = Status.Paused, speed = "") }
        val t = DownloadStore.tasks.value.single()
        assertEquals(Status.Paused, t.status)
        assertEquals(0.7f, t.progress, "paused where it was")
        assertTrue(DownloadStore.live.value.isEmpty())
        // A tick that came in after the pause doesn't come back with the next start.
        DownloadStore.update("run") { it.copy(status = Status.Running) }
        assertEquals(0.7f, DownloadStore.get("run")?.progress)
    }

    @Test
    fun aTickThatChangesMoreThanProgressGoesOnTheList() = observing(listOf(task("run", Status.Running).copy(stage = Stage.Resolving))) { emitted ->
        DownloadStore.tick("run") { it.copy(progress = 0.1f, stage = null) }
        assertEquals(1, emitted.size)
        assertNull(DownloadStore.tasks.value.single().stage)
        assertEquals(0.1f, DownloadStore.tasks.value.single().progress)
    }

    @Test
    fun removedDownloadsLeaveNoTicksBehind() = observing(listOf(task("a", Status.Running), task("b", Status.Running))) {
        DownloadStore.tick("a") { it.copy(progress = 0.2f) }
        DownloadStore.tick("b") { it.copy(progress = 0.3f) }
        DownloadStore.removeAll(setOf("a"))
        assertEquals(setOf("b"), DownloadStore.live.value.keys)
        DownloadStore.replace(emptyList())
        assertTrue(DownloadStore.live.value.isEmpty())
    }

    @Test
    fun ticksRacingPausesNeverLeaveProgressBehind() = observing(listOf(task("run", Status.Running))) {
        // One thread ticks as fast as it can, another pauses and resumes: whenever the download
        // isn't running there is no tick for it, and its progress never goes backwards.
        repeat(20) { round ->
            DownloadStore.update("run") { it.copy(status = Status.Running, progress = 0f) }
            val stop = java.util.concurrent.atomic.AtomicBoolean()
            val ticker = Thread {
                var p = 0f
                while (!stop.get()) {
                    p = (p + 0.001f).coerceAtMost(1f)
                    val next = p
                    DownloadStore.tick("run") { it.copy(progress = next.coerceAtLeast(it.progress)) }
                }
            }.apply { isDaemon = true; start() }
            try {
                var last = 0f
                repeat(200) {
                    DownloadStore.update("run") { it.copy(status = Status.Paused) }
                    assertNull(DownloadStore.live.value["run"], "round $round: a paused download has no tick")
                    val kept = DownloadStore.get("run")!!.progress
                    assertTrue(kept >= last, "round $round: progress went back from $last to $kept")
                    last = kept
                    DownloadStore.update("run") { it.copy(status = Status.Running) }
                }
            } finally {
                // Stopped even when an assertion failed, so it can't go on changing the list under later tests.
                stop.set(true)
                ticker.join(5_000)
            }
        }
    }
}
