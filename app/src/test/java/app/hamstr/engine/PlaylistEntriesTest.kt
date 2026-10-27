package app.hamstr.engine

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PlaylistEntriesTest {
    private val flat = JSONObject(
        """
        {"_type": "playlist", "title": "Mix", "uploader": "Someone", "entries": [
          {"_type": "url", "ie_key": "Youtube", "id": "aaa", "url": "https://www.youtube.com/watch?v=aaa", "title": "First", "duration": 61.5,
           "thumbnails": [{"url": "https://i.ytimg.com/a/default.jpg", "width": 120}, {"url": "https://i.ytimg.com/a/mq.jpg", "width": 320}, {"url": "https://i.ytimg.com/a/max.jpg", "width": 1280}]},
          {"_type": "url", "ie_key": "Youtube", "id": "bbb", "url": "https://www.youtube.com/watch?v=bbb", "title": "[Private video]", "duration": null},
          {"_type": "url", "ie_key": "Youtube", "id": "aaa", "url": "https://www.youtube.com/watch?v=aaa", "title": "First again"},
          {"_type": "url", "id": "ccc", "url": "not-a-link"}
        ]}
        """,
    )

    @Test fun readsEntriesInOrder() {
        val entries = Engine.playlistEntries(flat)
        assertEquals(listOf("First", "[Private video]"), entries.map { it.title })
        val first = entries[0]
        assertEquals("https://www.youtube.com/watch?v=aaa", first.url)
        assertEquals("Youtube:aaa", first.key)
        assertEquals(61, first.durationSec)
        assertEquals("https://i.ytimg.com/a/mq.jpg", first.thumbnail)
        assertEquals("Someone", first.uploader)
        assertTrue(first.available)
    }

    @Test fun marksPrivateVideosUnavailable() {
        assertFalse(Engine.playlistEntries(flat)[1].available)
    }

    @Test fun emptyWithoutEntries() {
        assertTrue(Engine.playlistEntries(JSONObject("""{"_type": "playlist"}""")).isEmpty())
    }
}
