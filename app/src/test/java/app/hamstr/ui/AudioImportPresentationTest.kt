package app.hamstr.ui

import app.hamstr.engine.MediaInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioImportPresentationTest {
    private fun info(source: String, playlist: Boolean = false, heights: List<Int> = emptyList()) = MediaInfo(
        url = "https://example.com/media", title = "Media", thumbnail = null,
        source = source, durationSec = 0, uploader = null, heights = heights,
        approxBytes = 0, entries = if (playlist) emptyList() else null,
    )

    @Test fun flatVideoPlaylistsDoNotBecomeAudioJustBecauseFormatsAreMissing() {
        assertFalse(looksLikeAudio("https://www.youtube.com/playlist?list=test", info("YoutubeTab", playlist = true)))
    }

    @Test fun musicPlaylistsAndSoundOnlyFilesUseAudioPresentation() {
        assertTrue(looksLikeAudio("https://soundcloud.com/artist/sets/album", info("SoundCloud", playlist = true)))
        assertTrue(looksLikeAudio("https://music.youtube.com/playlist?list=test", info("YoutubeTab", playlist = true)))
        assertTrue(looksLikeAudio("https://example.com/song.mp3", info("Generic")))
        assertFalse(looksLikeAudio("https://example.com/video.mp4", info("Generic", heights = listOf(1080))))
    }
}
