package app.hamstr.player

import android.view.KeyEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaButtonsTest {
    @Test fun playWhilePlayingPauses() {
        assertTrue(MediaButtons.flips(KeyEvent.KEYCODE_MEDIA_PLAY, playing = true, hasItems = true, ended = false))
    }

    @Test fun pauseWhilePausedPlays() {
        assertTrue(MediaButtons.flips(KeyEvent.KEYCODE_MEDIA_PAUSE, playing = false, hasItems = true, ended = false))
    }

    @Test fun matchingButtonsAreLeftToMedia3() {
        assertFalse(MediaButtons.flips(KeyEvent.KEYCODE_MEDIA_PLAY, playing = false, hasItems = true, ended = false))
        assertFalse(MediaButtons.flips(KeyEvent.KEYCODE_MEDIA_PAUSE, playing = true, hasItems = true, ended = false))
    }

    @Test fun pauseWithNothingToPlayStaysPaused() {
        assertFalse(MediaButtons.flips(KeyEvent.KEYCODE_MEDIA_PAUSE, playing = false, hasItems = false, ended = false))
        assertFalse(MediaButtons.flips(KeyEvent.KEYCODE_MEDIA_PAUSE, playing = false, hasItems = true, ended = true))
    }

    @Test fun togglesSkipsAndTheHeadsetButtonAreLeftToMedia3() {
        for (key in listOf(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS)) {
            assertFalse(MediaButtons.flips(key, playing = true, hasItems = true, ended = false))
            assertFalse(MediaButtons.flips(key, playing = false, hasItems = true, ended = false))
        }
    }
}
