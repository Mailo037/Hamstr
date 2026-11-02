package app.hamstr.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
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
        DownloadStore.init(dir)
        assertEquals(listOf("a"), DownloadStore.tasks.value.map { it.id })
        val kept = dir.listFiles().orEmpty().filter { it.name.startsWith("downloads.unreadable-") }
        assertEquals(1, kept.size)
        assertEquals(broken, kept.single().readText())
        assertTrue(File(dir, "downloads.json").readText().contains("\"a\""))
    }
}
