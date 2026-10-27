package app.hamstr.data

import org.junit.Assert.assertEquals
import org.junit.Test
import org.json.JSONObject

class LyricsTest {
    @Test
    fun nullTimedLyricsFallBackToPlainText() {
        assertEquals("Words", Lyrics.textOf(JSONObject("""{"syncedLyrics":null,"plainLyrics":"Words"}""")))
        assertEquals("", Lyrics.textOf(JSONObject("""{"syncedLyrics":null,"plainLyrics":null}""")))
    }

    @Test
    fun timedLinesComeSortedWithTheirTimes() {
        val lines = Lyrics.parse("[ar:Someone]\n[00:12.50] second\n[00:01.00] first\n[01:02.3][01:05.00] twice")
        assertEquals(listOf(1_000L, 12_500L, 62_300L, 65_000L), lines.map { it.at })
        assertEquals(listOf("first", "second", "twice", "twice"), lines.map { it.text })
    }

    @Test
    fun plainLyricsKeepTheirOrder() {
        val lines = Lyrics.parse("\nOne\n\nTwo\n")
        assertEquals(listOf("One", "", "Two"), lines.map { it.text })
        assertEquals(-1L, lines.first().at)
    }

    @Test
    fun titlesLoseVideoNoise() {
        assertEquals("Song", Lyrics.cleanTitle("Song (Official Video) [4K]"))
        assertEquals("Artist", Lyrics.cleanArtist("Artist - Topic"))
    }
}
