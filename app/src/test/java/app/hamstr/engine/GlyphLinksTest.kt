package app.hamstr.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlyphLinksTest {
    @Test fun recognizesSharesAndSongs() {
        listOf("https://glyph.my/p/UrPYWeQ4", "https://www.glyph.my/embed/p/UrPYWeQ4/",
            "https://glyph.my/p/UrPYWeQ4/listen/1?json=1", "https://glyph.my/p/UrPYWeQ41234").forEach {
            assertTrue(it, GlyphLinks.matches(it))
        }
    }

    @Test fun rejectsOtherHostsAndPaths() {
        listOf("https://glyph.my.evil.test/p/UrPYWeQ4", "https://glyph.my/p/bad", "ftp://glyph.my/p/UrPYWeQ4",
            "https://glyph.my/p/UrPYWeQ4/cover", "https://glyph.my/p/UrPYWeQ4/listen/-1").forEach {
            assertFalse(it, GlyphLinks.matches(it))
        }
    }
}
