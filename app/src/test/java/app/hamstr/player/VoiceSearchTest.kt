package app.hamstr.player

import app.hamstr.data.Choice
import app.hamstr.data.DownloadTask
import app.hamstr.data.Mode
import app.hamstr.data.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceSearchTest {
    private fun song(id: String, title: String, artist: String?, at: Long = 0) = DownloadTask(
        id = id, url = "https://example.com/$id", title = title, thumbnail = null, source = "youtube", durationSec = 200,
        choice = Choice(Mode.Audio, null, "m4a", "opus", false), status = Status.Done, filePath = "/music/$id.m4a",
        uploader = artist, createdAt = at,
    )

    private val queen1 = song("q1", "Bohemian Rhapsody", "Queen", 1)
    private val queen2 = song("q2", "Don’t Stop Me Now", "Queen", 2)
    private val beyonce = song("b1", "Halo", "Beyoncé", 3)
    private val lofi = song("l1", "Rain at night", "Chill Cow", 4)
    private val library = listOf(queen1, queen2, beyonce, lofi)
    private val lists = listOf("Lernmusik" to listOf(lofi, beyonce))

    private fun find(q: String?, focus: VoiceSearch.Focus = VoiceSearch.Focus.Any, artist: String? = null) =
        VoiceSearch.find(q, library, lists, focus, artist = artist, shuffle = { it.reversed() })

    @Test fun aTitlePlaysThatSong() {
        assertEquals("q1", find("bohemian rhapsody")?.start?.id)
        assertEquals("q2", find("dont stop me now")?.start?.id)
    }

    @Test fun anArtistPlaysAllTheirSongsInOrder() {
        val hit = find("Queen")!!
        assertEquals(listOf("q1", "q2"), hit.queue.map { it.id })
        assertEquals("b1", find("musik von beyonce")?.start?.id)
        assertEquals(listOf("q1", "q2"), find("music by queen")!!.queue.map { it.id })
        assertEquals("q1", find(null, VoiceSearch.Focus.Artist, artist = "Queen")?.start?.id)
    }

    @Test fun aPlaylistPlaysInItsOrder() {
        assertEquals(listOf("l1", "b1"), find("Playlist Lernmusik")!!.queue.map { it.id })
    }

    @Test fun fillerWordsPlayEverythingShuffled() {
        for (q in listOf(null, "", "music", "spiele meine Downloads", "spiel was ich heruntergeladen habe", "my saved songs")) {
            assertEquals(q.toString(), listOf("l1", "b1", "q2", "q1"), find(q)!!.queue.map { it.id })
        }
    }

    @Test fun somethingElseFindsNothing() {
        assertNull(find("Taylor Swift"))
        assertNull(VoiceSearch.find("queen", emptyList()))
    }
}
