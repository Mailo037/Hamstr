package app.hamstr.engine

import org.junit.Assert.*
import org.junit.Test

class CineSrcTargetTest {
    @Test fun movieInputsShareTheSameTarget() {
        for (link in listOf("cinesrc:movie:42", "cineby:movie:42", "https://cineby.rocks/movie/42", "https://cineby.rocks/watch/movie/42", "https://cinesrc.st/embed/movie/42")) {
            assertEquals(CineSrcTarget(42), CineSrcTarget.parse(link))
        }
    }

    @Test fun episodesRequireBothNumbersAndSupportSpecials() {
        assertEquals("https://cinesrc.st/embed/tv/1396?s=0&e=2", CineSrcTarget.parse("cineby:tv:1396:0:2")?.embed)
        assertEquals(CineSrcTarget(1396, 1, 2), CineSrcTarget.parse("https://cineby.rocks/tv/1396?season=1&episode=2"))
        assertEquals(CineSrcTarget(1396, 1, 2), CineSrcTarget.parse("https://cineby.rocks/watch/tv/1396/1/2"))
        for (input in listOf("cinesrc:tv:42", "cinesrc:tv:42:1:0", "cinesrc:tv:42:-1:1", "cinesrc:movie:0", "cinesrc:movie:no", "cinesrc:movie:42:1")) {
            assertThrows(input, IllegalArgumentException::class.java) { CineSrcTarget.parse(input) }
        }
    }

    @Test fun unrelatedAndLookalikeHostsNeverSelectThisResolver() {
        for (input in listOf("https://example.com/movie/42", "https://cineby.rocks.evil.test/movie/42", "https://cineby.rocks@evil.test/movie/42", "42")) assertNull(CineSrcTarget.parse(input))
    }

    @Test fun pasteAndShareAcceptIdsAndKeepOrdinaryLinks() {
        assertEquals("cinesrc:tv:1396:1:2", MediaLinks.find("Watch cinesrc:tv:1396:1:2."))
        assertEquals("https://example.com/watch?id=42", MediaLinks.find("Watch (https://example.com/watch?id=42)"))
        assertNull(MediaLinks.find("1396"))
    }
}
