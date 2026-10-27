package app.hamstr.engine

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.hamstr.data.Choice
import app.hamstr.data.DownloadStore
import app.hamstr.data.DownloadTask
import app.hamstr.data.DownloadEngine
import app.hamstr.data.Mode
import app.hamstr.data.Prefs
import app.hamstr.data.Status
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.UUID
import kotlin.math.sin

/** Real bundled Python/yt-dlp/FFmpeg against a deterministic on-device HTTP server. */
@RunWith(AndroidJUnit4::class)
class EnginePerformanceTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var server: FixtureServer
    private val tasks = mutableListOf<DownloadTask>()
    private val originalSettings = Prefs.settings.value

    @Before fun setUp() = runBlocking {
        Engine.awaitReady()
        Prefs.edit { it.copy(autoUpdate = false, playlists = false, parallel = 2, engine = DownloadEngine.YtDlp) }
        // Startup may already have scheduled an automatic update before ready was observed.
        delay(200)
        withTimeout(60_000) { Engine.updating.first { !it } }
        server = FixtureServer()
    }

    @After fun tearDown() {
        server.close()
        tasks.forEach { task ->
            Engine.pathFile(task).takeIf { it.exists() }?.readLines()?.forEach { File(it).delete() }
            DownloadStore.get(task.id)?.filePath?.let { File(it).delete() }
            Engine.clearTemporaryFiles(task)
            DownloadStore.remove(task.id)
        }
        Prefs.edit { originalSettings }
    }

    @Test fun previewIsSharedAndDownloadDoesNotExtractPageAgain(): Unit = runBlocking {
        val url = server.url("/preview.html")
        val start = SystemClock.elapsedRealtime()
        val info = listOf(async { Engine.info(url) }, async { Engine.info(url) }).awaitAll()
        val cold = SystemClock.elapsedRealtime() - start
        val cachedStart = SystemClock.elapsedRealtime()
        Engine.info(url)
        val cached = SystemClock.elapsedRealtime() - cachedStart
        assertEquals(info[0], info[1])
        assertEquals(1, server.gets("/preview.html"))
        val task = task(url)
        val request = Engine.request(task)
        assertTrue(request.hasOption("--load-info-json"))
        val response = Engine.execute(task)
        assertEquals(0, response.exitCode)
        assertEquals(1, server.gets("/preview.html"))
        assertOutput(task)
        Log.i("HamstrPerf", "preview: cold=${cold}ms cached=${cached}ms; one extraction for two callers + download")
    }

    @Test fun stalePreviewFallsBackToFreshExtraction() = runBlocking {
        val url = server.url("/expires.html")
        Engine.info(url)
        server.expired = true
        val task = task(url)
        val response = Engine.execute(task)
        assertEquals(0, response.exitCode)
        assertTrue(server.gets("/expires.html") >= 2)
        assertTrue(server.gets("/fresh.wav") > 0)
        assertOutput(task)
    }

    @Test fun playlistRequestKeepsOriginalUrlAndBypassesSingleVideoPreview() = runBlocking {
        val url = server.url("/playlist.html")
        Engine.info(url)
        Prefs.edit { it.copy(playlists = true) }
        val command = Engine.request(task(url)).buildCommand()
        assertFalse(command.contains("--load-info-json"))
        assertTrue(command.contains(url))
        assertFalse(command.contains("--no-playlist"))
    }

    @Test fun quickDownloadExtractsOnceAndRetainsMetadata() = runBlocking {
        val task = task(server.url("/quick.html"))
        DownloadStore.add(task)
        DownloadService.send(context, DownloadService.ACTION_START)
        val done = withTimeout(60_000) {
            while (DownloadStore.get(task.id)?.status?.active == true) delay(100)
            DownloadStore.get(task.id)!!
        }
        assertEquals(done.error, Status.Done, done.status)
        assertTrue(done.title.startsWith("Hamstr fixture"))
        assertEquals(1, server.gets("/quick.html"))
        assertTrue(File(done.filePath!!).length() > 0)
        withTimeout(10_000) { while (File(context.cacheDir, "parts/${task.id}").exists()) delay(50) }
        assertFalse(File(context.cacheDir, "parts/${task.id}").exists())
    }

    @Test fun parallelFragmentsProduceCompleteMedia() {
        server.segment = makeAac()
        val timings = mutableMapOf<Int, Long>()
        for (workers in listOf(1, 4)) {
            val task = task(server.url("/stream$workers.m3u8"), Mode.Custom)
            val request = Engine.request(task).apply { addOption("--concurrent-fragments", workers) }
            val start = SystemClock.elapsedRealtime()
            val response = YoutubeDL.getInstance().execute(request, task.id)
            timings[workers] = SystemClock.elapsedRealtime() - start
            assertEquals(0, response.exitCode)
            assertOutput(task)
            assertEquals(12, (0 until 12).sumOf { server.gets("/stream$workers-$it.aac") })
        }
        // Log timings rather than make emulator CPU load a flaky assertion.
        Log.i("HamstrPerf", "12 HLS fragments with 150ms response latency: serial=${timings[1]}ms parallel=${timings[4]}ms")
    }

    @Test fun matchingAudioFormatAvoidsTranscoding() = runBlocking {
        server.audio["/source.m4a"] = makeAudio("m4a", "aac")
        server.audio["/source.opus"] = makeAudio("opus", "libopus")
        val url = server.url("/formats.html")
        Engine.info(url)
        for (format in listOf("m4a", "opus")) {
            val base = task(url)
            val task = base.copy(choice = base.choice.copy(audioFormat = format))
            val request = Engine.request(task).apply {
                addOption("--verbose")
                addOption("--no-quiet")
            }
            val response = YoutubeDL.getInstance().execute(request, task.id)
            assertEquals(0, response.exitCode)
            assertOutput(task)
            val output = File(Engine.pathFile(task).readLines().last { it.isNotBlank() })
            assertEquals(format, output.extension)
            // Container remuxing can drop priming packets, so byte hashes are not
            // reliable here. Check every actual FFmpeg invocation uses stream copy.
            val commands = (response.out + "\n" + response.err).lineSequence()
                .filter { it.contains("ffmpeg command line:") && it.contains("libffmpeg.so") }.toList()
            assertTrue("Missing FFmpeg diagnostics for $format", commands.isNotEmpty())
            commands.forEach { command -> assertTrue(command, Regex("(?:-c|-c:a|-acodec)\\s+copy(?:\\s|$)").containsMatchIn(command)) }
            // Reuse the same preview with a different choice; format selection must run again.
            Engine.pathFile(task).readLines().forEach { File(it).delete() }
            Engine.clearTemporaryFiles(task)
        }
    }

    @Test fun pauseResumesFragmentsAndCancelStopsPreparation() = runBlocking {
        server.segment = makeAac()
        server.segmentDelay = 500
        val task = task(server.url("/resume.m3u8"), Mode.Custom)
        DownloadStore.add(task)
        DownloadService.send(context, DownloadService.ACTION_START)
        withTimeout(60_000) { while ((0 until 12).sumOf { server.gets("/resume-$it.aac") } < 8) delay(50) }
        DownloadService.send(context, DownloadService.ACTION_PAUSE, task.id)
        withTimeout(10_000) { while (DownloadStore.get(task.id)?.status != Status.Paused) delay(50) }
        delay(800)
        assertTrue(File(context.cacheDir, "parts/${task.id}").walkTopDown().any { it.name.endsWith(".part") && it.length() > 0 })
        DownloadService.send(context, DownloadService.ACTION_RESUME, task.id)
        withTimeout(60_000) { while (DownloadStore.get(task.id)?.status != Status.Done) {
            assertNotEquals(DownloadStore.get(task.id)?.error, Status.Failed, DownloadStore.get(task.id)?.status)
            delay(100)
        } }
        assertTrue(File(DownloadStore.get(task.id)!!.filePath!!).length() > 0)

        val canceled = task(server.url("/canceled.html"))
        DownloadStore.add(canceled)
        DownloadService.send(context, DownloadService.ACTION_START)
        withTimeout(10_000) { while (DownloadStore.get(canceled.id)?.status != Status.Running) delay(10) }
        DownloadService.send(context, DownloadService.ACTION_CANCEL, canceled.id)
        withTimeout(10_000) { while (DownloadStore.get(canceled.id)?.status != Status.Canceled) delay(10) }
        delay(1000)
        assertEquals(0, server.gets("/canceled.html"))
        assertFalse(File(context.cacheDir, "parts/${canceled.id}").exists())
    }

    private fun task(url: String, mode: Mode = Mode.Audio): DownloadTask = DownloadTask(
        url = url, title = url, thumbnail = null, source = null, durationSec = 0,
        choice = Choice(mode, 1080, "mp4", "opus", false, "b"),
    ).also { tasks += it }

    private fun assertOutput(task: DownloadTask) {
        val path = Engine.pathFile(task).readLines().last { it.isNotBlank() }
        assertTrue("Output missing or empty: $path", File(path).length() > 0)
    }

    private fun makeAac() = makeAudio("aac", "aac")

    private fun makeAudio(extension: String, codec: String): ByteArray {
        val input = File(context.cacheDir, "hamstr-test.wav").apply { writeBytes(server.wav) }
        val output = File(context.cacheDir, "hamstr-test.$extension")
        ffmpeg("-y", "-i", input.absolutePath, "-ar", "48000", "-c:a", codec, output.absolutePath)
        return output.readBytes().also { input.delete(); output.delete() }
    }

    private fun ffmpeg(vararg args: String): String {
        val process = ProcessBuilder(listOf(File(context.applicationInfo.nativeLibraryDir, "libffmpeg.so").absolutePath) + args).apply {
            environment()["LD_LIBRARY_PATH"] = listOf(
                File(context.noBackupFilesDir, "youtubedl-android/packages/ffmpeg/usr/lib").absolutePath,
                File(context.noBackupFilesDir, "youtubedl-android/packages/python/usr/lib").absolutePath,
                context.applicationInfo.nativeLibraryDir,
            ).joinToString(":")
            redirectErrorStream(true)
        }.start()
        val log = process.inputStream.bufferedReader().readText()
        assertEquals(log, 0, process.waitFor())
        return log
    }

    private class FixtureServer : AutoCloseable {
        private val prefix = UUID.randomUUID().toString()
        private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        private val executor = Executors.newCachedThreadPool()
        private val counts = ConcurrentHashMap<String, AtomicInteger>()
        @Volatile var expired = false
        @Volatile var segment = byteArrayOf()
        @Volatile var segmentDelay = 150L
        val audio = ConcurrentHashMap<String, ByteArray>()
        val wav: ByteArray = ByteBuffer.allocate(44 + 16000).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + 16000); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(1); putInt(8000); putInt(16000); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(16000)
            repeat(8000) { putShort((sin(it * 2 * Math.PI * 440 / 8000) * 1000).toInt().toShort()) }
        }.array()

        init {
            executor.execute {
                while (!socket.isClosed) {
                    val client = try { socket.accept() } catch (_: Exception) { break }
                    executor.execute {
                        client.use {
                            val reader = client.getInputStream().bufferedReader()
                            val request = reader.readLine()?.split(" ") ?: return@use
                            while (!reader.readLine().isNullOrEmpty()) { }
                            val rawPath = request[1].substringBefore('?')
                            val path = if (rawPath.startsWith("/$prefix-")) "/" + rawPath.removePrefix("/$prefix-") else rawPath
                            if (request[0] == "GET") counts.getOrPut(path) { AtomicInteger() }.incrementAndGet()
                            val forbidden = expired && path == "/old.wav"
                            val type: String
                            val body: ByteArray
                            when {
                                path == "/formats.html" -> {
                                    type = "text/html"
                                    body = "<html><head><title>Hamstr codecs</title></head><body><audio><source src='/source.m4a' type='audio/mp4'><source src='/source.opus' type='audio/ogg'></audio></body></html>".toByteArray()
                                }
                                audio.containsKey(path) -> { type = if (path.endsWith(".m4a")) "audio/mp4" else "audio/ogg"; body = audio.getValue(path) }
                                path.endsWith(".html") -> {
                                    type = "text/html"
                                    val media = if (path == "/expires.html" && !expired) "old.wav" else "fresh.wav"
                                    body = "<html><head><title>Hamstr fixture</title></head><body><video controls src='$media'></video></body></html>".toByteArray()
                                }
                                path.endsWith(".m3u8") -> {
                                    type = "application/vnd.apple.mpegurl"
                                    val base = path.removeSuffix(".m3u8")
                                    body = ("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:0\n" +
                                        (0 until 12).joinToString("") { "#EXTINF:1.0,\n$base-$it.aac\n" } + "#EXT-X-ENDLIST\n").toByteArray()
                                }
                                path.endsWith(".aac") -> { type = "audio/aac"; body = segment; Thread.sleep(segmentDelay) }
                                else -> { type = "audio/wav"; body = wav }
                            }
                            try {
                                val output = client.getOutputStream()
                                val status = if (forbidden) "403 Forbidden" else "200 OK"
                                output.write("HTTP/1.1 $status\r\nContent-Type: $type\r\nContent-Length: ${if (forbidden) 0 else body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                                if (request[0] != "HEAD" && !forbidden) output.write(body)
                                output.flush()
                            } catch (_: Exception) { /* Extractors may close after sniffing a media header. */ }
                        }
                    }
                }
            }
        }

        // Downloads in shared storage can outlive an app reinstall; avoid old ownership
        // and existing-file shortcuts when Gradle reinstalls the app for a new test run.
        fun url(path: String) = "http://127.0.0.1:${socket.localPort}/$prefix-${path.removePrefix("/")}"
        fun gets(path: String) = counts[path]?.get() ?: 0
        override fun close() { socket.close(); executor.shutdownNow() }
    }
}
