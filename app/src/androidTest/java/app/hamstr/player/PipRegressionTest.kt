package app.hamstr.player

import android.accessibilityservice.AccessibilityService
import android.app.PictureInPictureParams
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import app.hamstr.MainActivity
import app.hamstr.data.Choice
import app.hamstr.data.DownloadTask
import app.hamstr.data.Mode
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.reflect.Proxy

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 33)
class PipRegressionTest {
    private fun task(ext: String = "mp4", id: String = "video") = DownloadTask(
        id = id, url = "https://example.com/file.$ext", title = "PiP fixture", thumbnail = null,
        source = null, durationSec = 1, filePath = "/fixture.$ext",
        choice = Choice(Mode.Video, null, "mp4", "opus", false),
    )

    @Test fun pauseCloseAudioAndDetachedControllersCannotLeaveAutoEnterEnabled() {
        val fake = FakePlayer()
        var latest: PictureInPictureParams? = null
        var writes = 0
        val pip = PipController { latest = it; writes++ }
        fun assertAutoEnter(expected: Boolean) {
            assertEquals(expected, pip.eligible)
            assertEquals(expected, latest!!.isAutoEnterEnabled)
        }
        pip.bind(null, fake.player)
        assertAutoEnter(false)
        pip.bind(task(), fake.player)
        assertAutoEnter(true)
        fake.setPlaying(false)
        assertAutoEnter(false)
        fake.setPlaying(true)
        assertAutoEnter(true)
        pip.bind(null, fake.player) // The old UI returned before resetting PiP here.
        assertAutoEnter(false)
        pip.bind(task("m4a"), fake.player)
        assertAutoEnter(false)
        pip.bind(task("jpg"), fake.player)
        assertAutoEnter(false)
        pip.bind(task(id = "different-video"), fake.player)
        assertAutoEnter(false)
        pip.bind(task(), fake.player)
        assertAutoEnter(true)
        pip.bind(task(), null)
        assertAutoEnter(false)
        pip.bind(task(), fake.player)
        assertAutoEnter(true)
        pip.close()
        assertAutoEnter(false)
        val before = writes
        fake.setPlaying(false)
        assertEquals("Destroyed activities must detach the player listener", before, writes)
    }

    @Test fun leavingTheAppWithoutPlaybackDoesNotPinTheActivity() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { Playback.close() }
            instrumentation.waitForIdleSync()
            assertTrue(instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME))
            Thread.sleep(800) // Let Android finish the Home/PiP transition.
            scenario.onActivity { assertFalse("The empty app must not become a PiP window", it.isInPictureInPictureMode) }
        }
    }

    private class FakePlayer {
        private var playing = true
        private val listeners = mutableListOf<Player.Listener>()
        val player: Player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { proxy, method, args ->
            when (method.name) {
                "isPlaying" -> playing
                "getCurrentMediaItem" -> MediaItem.Builder().setMediaId("video").build()
                "getVideoSize" -> VideoSize(1920, 1080)
                "addListener" -> { listeners.add(args!![0] as Player.Listener); null }
                "removeListener" -> { listeners.remove(args!![0] as Player.Listener); null }
                "equals" -> proxy === args!![0]
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "PiP test player"
                else -> error("Unexpected Player call: ${method.name}")
            }
        } as Player

        fun setPlaying(value: Boolean) {
            playing = value
            listeners.toList().forEach { it.onIsPlayingChanged(value) }
        }
    }
}
