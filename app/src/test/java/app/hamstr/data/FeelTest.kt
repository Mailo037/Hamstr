package app.hamstr.data

import app.hamstr.R
import app.hamstr.engine.Failures
import app.hamstr.engine.Fix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeelTest {
    @Test
    fun cleanNamesDropTheIdButKeepTheQualityTag() {
        assertEquals("Song", DownloadFiles.cleanName("Song [dQw4w9WgXcQ]"))
        assertEquals("Song 720p", DownloadFiles.cleanName("Song [dQw4w9WgXcQ] 720p"))
        assertEquals("Song audio", DownloadFiles.cleanName("Song [dQw4w9WgXcQ] audio"))
        // Brackets that belong to the title stay.
        assertEquals("Song [Official Video]", DownloadFiles.cleanName("Song [Official Video] [abc123]"))
        assertEquals("No id here", DownloadFiles.cleanName("No id here"))
        assertEquals("[only]", DownloadFiles.cleanName("[only]"))
    }

    @Test
    fun failuresInPlainWords() {
        assertEquals(R.string.err_private, Failures.explain("[youtube] abc: Private video. Sign in if you've been granted access").title)
        assertEquals(R.string.err_age, Failures.explain("Sign in to confirm your age. This video may be inappropriate for some users.").title)
        assertEquals(R.string.err_login, Failures.explain("Sign in to confirm you're not a bot").title)
        assertEquals(R.string.err_gone, Failures.explain("Video unavailable. This video has been removed by the uploader").title)
        assertEquals(R.string.err_unsupported, Failures.explain("Unsupported URL: https://example.com").title)
        assertEquals(Fix.UpdateYtDlp, Failures.explain("[youtube] abc: Unable to extract nsig function").fix)
        assertEquals(R.string.err_full, Failures.explain("OSError: [Errno 28] No space left on device").title)
        assertEquals(R.string.err_unknown, Failures.explain("").title)
    }

    @Test
    fun onlyConnectionTroubleIsRetriedOnItsOwn() {
        assertTrue(Failures.isNetwork("Unable to download webpage: <urlopen error [Errno 7] No address associated with hostname>"))
        assertTrue(Failures.isNetwork("downloading 251: bytes 0-1048575: error sending request for url"))
        assertTrue(Failures.isNetwork("Read timed out"))
        assertFalse(Failures.isNetwork("Unable to download webpage: HTTP Error 404: Not Found"))
        assertFalse(Failures.isNetwork("Private video"))
    }

    @Test
    fun enginesNameTheirStages() {
        assertEquals(Stage.Merging, Stage.fromEngine("merging"))
        assertEquals(Stage.Merging, Stage.fromEngine("Merger"))
        assertEquals(Stage.ExtractingAudio, Stage.fromEngine("ExtractAudio"))
        assertEquals(Stage.Tagging, Stage.fromEngine("EmbedThumbnail"))
        assertEquals(Stage.Remuxing, Stage.fromEngine("FixupM3u8"))
        assertEquals(null, Stage.fromEngine("download"))
        assertEquals(null, Stage.fromEngine("already downloaded"))
    }

    @Test
    fun undoPutsEntriesBackInPlace() {
        fun task(id: String) = DownloadTask(id = id, url = "https://example.com/$id", title = id, thumbnail = null, source = null, durationSec = 0,
            choice = Choice(Mode.Video, null, "mp4", "opus", false), status = Status.Done)
        listOf("d", "c", "b", "a").forEach { DownloadStore.add(task(it)) } // newest first: a b c d
        val removed = DownloadStore.removeAll(setOf("b", "d"))
        assertEquals(listOf("a", "c"), DownloadStore.tasks.value.map { it.id })
        DownloadStore.restore(removed)
        assertEquals(listOf("a", "b", "c", "d"), DownloadStore.tasks.value.map { it.id })
        DownloadStore.removeAll(setOf("a", "b", "c", "d"))
    }
}
