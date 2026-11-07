package app.hamstr.desktop

import app.hamstr.data.Prefs
import app.hamstr.data.PropertiesFile
import java.io.File
import java.lang.reflect.Proxy
import java.util.Properties
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.sound.sampled.LineUnavailableException
import javax.sound.sampled.SourceDataLine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FfPlayerTest {
    private fun pcm16(vararg samples: Int) = ByteArray(samples.size * 2).also { bytes ->
        samples.forEachIndexed { index, sample ->
            bytes[index * 2] = sample.toByte()
            bytes[index * 2 + 1] = (sample shr 8).toByte()
        }
    }

    @Test
    fun scalesPositiveNegativeAndZeroPcmSamples() {
        val bytes = pcm16(32_767, -32_768, 1_024, -1_024, 257, -257, 1, -1, 0)

        FfPlayer.scalePcm16(bytes, bytes.size, 0.5f)

        assertContentEquals(pcm16(16_383, -16_384, 512, -512, 128, -128, 0, 0, 0), bytes)
    }

    @Test
    fun zeroVolumeSilencesPcm() {
        val bytes = pcm16(32_767, -32_768, 255, -255, 0)

        FfPlayer.scalePcm16(bytes, bytes.size, 0f)

        assertContentEquals(ByteArray(bytes.size), bytes)
    }

    @Test
    fun fullVolumeLeavesPcmUnchanged() {
        val bytes = pcm16(32_767, -32_768, 255, -255, 0)
        val original = bytes.copyOf()

        FfPlayer.scalePcm16(bytes, bytes.size, 1f)

        assertContentEquals(original, bytes)
    }

    @Test
    fun scalesOnlyTheRequestedPcmBytes() {
        val bytes = pcm16(1_024, -1_024, 32_767, -32_768)

        FfPlayer.scalePcm16(bytes, 4, 0.5f)

        assertContentEquals(pcm16(512, -512, 32_767, -32_768), bytes)
        FfPlayer.scalePcm16(bytes, 0, 0f)
        assertContentEquals(pcm16(512, -512, 32_767, -32_768), bytes)
    }

    @Test
    fun volumeInitializesFromPreferencesAndPersistsWithoutPlaying() {
        val dir = kotlin.io.path.createTempDirectory("hamstr-player-volume-").toFile()
        val settingsFile = File(dir, "settings.properties").apply { writeText("desktopVolume=0.35\n") }
        val settings = PropertiesFile(settingsFile)
        val players = mutableListOf<FfPlayer>()
        val info = FfPlayer.Info(duration = 60.0, width = 0, height = 0, audio = true, video = false)
        fun newPlayer() = FfPlayer(
            File(dir, "not-decoded.opus"), knownInfo = info,
            openAudio = { error("Changing volume must not open the audio device") },
        ).also { players += it }

        try {
            Prefs.init(settings)
            val player = newPlayer()
            assertSame(info, player.info, "Known metadata must bypass ffprobe")
            assertEquals(0.35f, player.loudness.value)
            assertFalse(player.playing.value)
            assertNull(player.error.value)

            player.setVolume(0.6f)
            assertEquals(0.6f, player.loudness.value)
            assertEquals(0.6f, Prefs.settings.value.desktopVolume)
            assertEquals("0.6", settings.getString("desktopVolume", null))
            assertTrue(settings.flush(), "Volume was not saved to disk")
            val persisted = Properties().apply { settingsFile.inputStream().use { load(it) } }
            assertEquals("0.6", persisted.getProperty("desktopVolume"))
            assertFalse(player.playing.value)
            assertNull(player.error.value)

            Prefs.init(PropertiesFile(settingsFile))
            val reopened = newPlayer()
            assertEquals(0.6f, reopened.loudness.value)
            assertFalse(reopened.playing.value)
            assertNull(reopened.error.value)
        } finally {
            players.forEach { it.close() }
            synchronized(Prefs) { settings.flush() }
            dir.deleteRecursively()
        }
    }

    @Test
    fun replacementKeepsTheLatestVolumeWhenOldAudioOpeningFinishes() {
        val dir = kotlin.io.path.createTempDirectory("hamstr-player-replacement-").toFile()
        val settingsFile = File(dir, "settings.properties").apply { writeText("desktopVolume=0.25\n") }
        val settings = PropertiesFile(settingsFile)
        Prefs.init(settings)
        val opening = CountDownLatch(1)
        val releaseOpen = CountDownLatch(1)
        val audio = FakeAudioLine()
        val replacement = AtomicReference<FfPlayer>()
        val caller = Executors.newSingleThreadExecutor { task ->
            Thread(task, "hamstr-test-volume-caller").apply { isDaemon = true }
        }
        val info = FfPlayer.Info(duration = 60.0, width = 0, height = 0, audio = true, video = false)
        val old = FfPlayer(
            File(dir, "old-not-decoded.opus"), knownInfo = info,
            openAudio = {
                opening.countDown()
                check(releaseOpen.await(60, TimeUnit.SECONDS)) { "Audio opening was never released" }
                audio.line
            },
        )
        try {
            val play = caller.submit { old.play() }
            assertTrue(opening.await(10, TimeUnit.SECONDS), "Old player did not try to open audio")
            play.get(10, TimeUnit.SECONDS)
            caller.submit {
                old.setVolume(0.4f)
                val next = FfPlayer(
                    File(dir, "replacement-not-decoded.opus"), knownInfo = info,
                    openAudio = { error("The replacement must not open audio without playing") },
                ).also { replacement.set(it) }
                assertEquals(0.4f, next.loudness.value, "Replacement must see the volume before old audio opens")
                assertEquals(0.4f, Prefs.settings.value.desktopVolume)
                assertEquals("0.4", settings.getString("desktopVolume", null))

                next.setVolume(0.8f)
                assertEquals(0.8f, next.loudness.value)
                assertEquals(0.8f, Prefs.settings.value.desktopVolume)
                old.pause()
                old.close()
            }.get(10, TimeUnit.SECONDS)
            assertEquals(1L, releaseOpen.count, "Replacement must not wait for the old audio opener")
            assertFalse(old.playing.value)

            releaseOpen.countDown()
            assertTrue(audio.closed.await(10, TimeUnit.SECONDS), "Old player did not finish closing")
            // Any volume work queued behind the old opener has now run, before its final close.
            assertEquals(0.8f, Prefs.settings.value.desktopVolume, "Old player overwrote the newer volume")
            assertEquals("0.8", settings.getString("desktopVolume", null))
            val next = assertNotNull(replacement.get())
            assertEquals(0.8f, next.loudness.value)
            assertFalse(next.playing.value)
            assertEquals(0, audio.starts.get(), "The closed old player must never start decoding")
            assertNull(old.error.value)
            assertNull(next.error.value)
        } finally {
            // Invalidate the session before unblocking its opener, including on assertion failure.
            old.close()
            releaseOpen.countDown()
            caller.shutdownNow()
            replacement.get()?.close()
            audio.closed.await(10, TimeUnit.SECONDS)
            synchronized(Prefs) { settings.flush() }
            dir.deleteRecursively()
        }
    }

    @Test
    fun playSeekPauseAndCloseReturnWhileAudioDeviceIsOpening() {
        val opening = CountDownLatch(1)
        val releaseOpen = CountDownLatch(1)
        val opens = AtomicInteger()
        val openingThread = AtomicReference<Thread>()
        val audio = FakeAudioLine()
        val caller = Executors.newSingleThreadExecutor { task ->
            Thread(task, "hamstr-test-player-caller").apply { isDaemon = true }
        }
        val player = FfPlayer(
            File("not-decoded.opus"),
            knownInfo = FfPlayer.Info(duration = 60.0, width = 0, height = 0, audio = true, video = false),
            openAudio = { format ->
                assertEquals(48_000f, format.sampleRate)
                assertEquals(16, format.sampleSizeInBits)
                assertEquals(2, format.channels)
                assertFalse(format.isBigEndian)
                opens.incrementAndGet()
                openingThread.set(Thread.currentThread())
                opening.countDown()
                check(releaseOpen.await(60, TimeUnit.SECONDS)) { "Audio opening was never released" }
                audio.line
            },
        )
        try {
            val play = caller.submit { player.play() }
            assertTrue(opening.await(10, TimeUnit.SECONDS), "Playback did not try to open audio")
            play.get(10, TimeUnit.SECONDS)
            assertTrue(openingThread.get().isDaemon, "Audio lifecycle must not keep the JVM alive")

            caller.submit {
                player.seek(12.0)
                player.pause()
                player.play()
                player.close()
                player.close()
            }.get(10, TimeUnit.SECONDS)

            assertEquals(1L, releaseOpen.count, "Commands must return before audio opening is released")
            assertEquals(12.0, player.position.value)
            assertFalse(player.playing.value)
            assertEquals(1L, audio.closed.count, "Device cleanup must wait on the control worker")

            // Closing supersedes the blocked session, so releasing it must not launch a decoder.
            releaseOpen.countDown()
            assertTrue(audio.closed.await(10, TimeUnit.SECONDS), "Close did not release the audio device")
            assertEquals(1, opens.get())
            assertEquals(1, audio.closes.get(), "Repeated close must not close the device twice")
            assertEquals(0, audio.starts.get(), "Superseded playback must not start the device")
            assertSame(openingThread.get(), audio.closedOn.get(), "Open and close must share the control worker")
            assertNull(player.error.value)
        } finally {
            releaseOpen.countDown()
            caller.shutdownNow()
            player.close()
            audio.closed.await(10, TimeUnit.SECONDS)
        }
    }

    private class FakeAudioLine {
        val closed = CountDownLatch(1)
        val closes = AtomicInteger()
        val starts = AtomicInteger()
        val closedOn = AtomicReference<Thread>()
        val line = Proxy.newProxyInstance(
            SourceDataLine::class.java.classLoader, arrayOf(SourceDataLine::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getLongFramePosition" -> 0L
                "start" -> { starts.incrementAndGet(); null }
                "stop", "flush" -> null
                "close" -> {
                    closedOn.set(Thread.currentThread())
                    closes.incrementAndGet()
                    closed.countDown()
                    null
                }
                else -> error("Unexpected audio operation: ${method.name}")
            }
        } as SourceDataLine
    }

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

    private fun makeClip(name: String): File {
        val clip = File(Paths.data, name)
        val made = ProcessBuilder(
            Tools.ffmpeg.path, "-hide_banner", "-loglevel", "error", "-y",
            "-f", "lavfi", "-i", "testsrc=size=320x180:rate=30:duration=4",
            "-f", "lavfi", "-i", "sine=frequency=440:duration=4",
            "-shortest", "-pix_fmt", "yuv420p", clip.path,
        ).inheritIO().start()
        assertTrue(made.waitFor(60, TimeUnit.SECONDS) && made.exitValue() == 0)
        return clip
    }

    @Test
    fun playsPicturesInTimeAndSeeks() {
        if (!tools()) return
        val clip = makeClip("clip.mp4")
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

    @Test
    fun playsPicturesSeeksAndEndsWhenAudioOutputFails() {
        if (!tools()) return
        val clip = makeClip("no-audio-output.mp4")
        val audioOpens = AtomicInteger()
        val player = FfPlayer(
            clip,
            knownInfo = FfPlayer.Info(duration = 4.0, width = 320, height = 180, audio = true, video = true),
            openAudio = {
                audioOpens.incrementAndGet()
                throw LineUnavailableException("No audio device in this test")
            },
        )
        try {
            player.play()
            runBlocking {
                withTimeout(15_000) {
                    player.frame.first { it != null }
                    player.position.first { it >= 0.25 }
                }
            }
            val beforeSeek = assertNotNull(player.frame.value, "Silent video must still produce pictures")
            assertTrue(audioOpens.get() >= 1, "Audio opening must have failed before video playback")
            assertNull(player.error.value, "Missing audio output must not fail video playback")

            player.seek(3.0)
            player.play()
            runBlocking {
                withTimeout(15_000) {
                    // seek() publishes 3.0 itself; a later position must come from a decoded picture.
                    player.position.first { it > 3.0 }
                    player.ended.first { it }
                }
            }
            assertNotSame(beforeSeek, assertNotNull(player.frame.value), "Seek must decode new pictures")
            assertTrue(player.position.value in 3.0..4.0, "Silent video's clock lost the seek position")
            assertTrue(audioOpens.get() >= 2, "The seek must restart playback despite audio opening failing again")
            assertTrue(player.ended.value, "Silent video must report the end without an audio thread")
            assertFalse(player.playing.value)
            assertNull(player.error.value)
        } finally {
            player.close()
        }
    }
}
