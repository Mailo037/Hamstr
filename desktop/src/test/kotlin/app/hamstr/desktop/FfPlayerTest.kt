package app.hamstr.desktop

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FfPlayerTest {
    /** The FFmpeg fetchTools left in build/tools, copied where the app looks for its tools; null without one. */
    private fun tools(): Boolean {
        val fetched = System.getProperty("hamstr.testTools")?.let(::File)?.takeIf { it.isDirectory } ?: return false
        val exe = if (Paths.windows) ".exe" else ""
        val ffmpeg = fetched.walk().firstOrNull { it.name == "ffmpeg$exe" } ?: return false
        Tools.dir.mkdirs()
        for (name in listOf("ffmpeg$exe", "ffprobe$exe")) {
            val src = File(ffmpeg.parentFile, name).takeIf { it.isFile } ?: return false
            src.copyTo(File(Tools.dir, name), overwrite = true).setExecutable(true)
        }
        return true
    }

    @Test
    fun playsPicturesInTimeAndSeeks() {
        if (!tools()) return
        val clip = File(Paths.data, "clip.mp4")
        val made = ProcessBuilder(
            Tools.ffmpeg.path, "-hide_banner", "-loglevel", "error", "-y",
            "-f", "lavfi", "-i", "testsrc=size=320x180:rate=30:duration=4",
            "-f", "lavfi", "-i", "sine=frequency=440:duration=4",
            "-shortest", "-pix_fmt", "yuv420p", clip.path,
        ).inheritIO().start()
        assertTrue(made.waitFor(60, TimeUnit.SECONDS) && made.exitValue() == 0)

        val player = FfPlayer(clip)
        val info = assertNotNull(player.info)
        assertTrue(info.video && info.audio)
        assertEquals(320, info.width)
        assertTrue(info.duration in 3.5..4.5)
        try {
            player.play()
            Thread.sleep(1_500)
            assertNotNull(player.frame.value, "no picture after 1.5 s")
            val at = player.position.value
            assertTrue(at in 0.5..2.5, "at $at s after 1.5 s of playing")

            player.pause()
            val paused = player.position.value
            Thread.sleep(500)
            assertTrue(player.position.value - paused < 0.3, "kept going while paused")

            player.seek(3.0)
            player.play()
            Thread.sleep(600)
            assertTrue(player.position.value >= 3.0 || player.ended.value, "seek went to ${player.position.value}")
        } finally {
            player.close()
        }
    }
}
