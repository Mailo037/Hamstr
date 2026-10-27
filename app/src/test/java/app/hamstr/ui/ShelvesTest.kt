package app.hamstr.ui

import app.hamstr.data.Choice
import app.hamstr.data.DownloadTask
import app.hamstr.data.Mode
import app.hamstr.data.Status
import org.junit.Assert.*
import org.junit.Test

class ShelvesTest {
    private fun task(id: String, group: String? = null, at: Long = 0) = DownloadTask(
        id = id, url = "https://example.com/$id", title = id, thumbnail = null,
        source = null, durationSec = 0,
        choice = Choice(Mode.Video, null, "mp4", "opus", false),
        status = Status.Done, group = group, groupTitle = "Playlist $group", createdAt = at,
    )

    @Test fun groupsKeepNewestPositionAndChronologicalPlaybackOrder() {
        val tiles = shelves(listOf(task("a2", "a", 2), task("single"), task("b2", "b", 4), task("a1", "a", 1), task("b1", "b", 3)))
        assertEquals(listOf("group:a", "single", "group:b"), tiles.map { it.key })
        assertEquals(listOf("a1", "a2"), (tiles[0] as Shelf.Group).items.map { it.id })
        assertEquals("Playlist a", (tiles[0] as Shelf.Group).title)
        assertEquals(listOf("b1", "b2"), (tiles[2] as Shelf.Group).items.map { it.id })
    }

    @Test fun filteringAGroupToOneMemberKeepsTheFileAccessible() {
        val item = task("only", "filtered")
        assertEquals(listOf(Shelf.One(item)), shelves(listOf(item)))
        assertTrue(shelves(emptyList()).isEmpty())
    }
}
