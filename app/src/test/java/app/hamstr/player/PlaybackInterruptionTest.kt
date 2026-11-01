package app.hamstr.player

import org.junit.Assert.*
import org.junit.Test
import app.hamstr.player.PlaybackInterruption.Source.*

class PlaybackInterruptionTest {
    @Test fun musicOrUnknownPlaybackNeverTriggersReturnToHamstr() {
        val policy = PlaybackInterruption()
        policy.observe(listOf(MusicOrUnknown))
        policy.observe(emptyList())
        assertFalse(policy.mayResume)
    }

    @Test fun videoCanReturnButStartingMusicTakesPlaybackPermanently() {
        val policy = PlaybackInterruption()
        policy.observe(listOf(Video))
        assertTrue(policy.mayResume)
        policy.observe(listOf(MusicOrUnknown))
        policy.observe(listOf(Video))
        policy.observe(emptyList())
        assertFalse(policy.mayResume)
    }

    @Test fun noObservedPlaybackDoesNotCauseAnAutomaticRestart() {
        assertFalse(PlaybackInterruption().mayResume)
    }

    @Test fun newFocusLossStartsAFreshInterruption() {
        val policy = PlaybackInterruption()
        policy.observe(listOf(MusicOrUnknown))
        policy.reset()
        policy.observe(listOf(Video))
        assertTrue(policy.mayResume)
    }
}
