package app.hamstr.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.hamstr.BuildConfig
import app.hamstr.data.Choice
import app.hamstr.data.DownloadTask
import app.hamstr.data.Mode
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking

/** Tests the packaged release binary through Hamstr's real bridge, without yt-dlp fallback. */
@RunWith(AndroidJUnit4::class)
class CheekReleaseIntegrationTest {
    @Test fun mediaDownloadConvertsAudioAndEmbedsMetadata(): Unit = runBlocking {
        Engine.awaitReady()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "cheek-media-${UUID.randomUUID()}").apply { mkdirs() }
        val samples = 8000
        val wav = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + samples * 2); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(1); putInt(8000); putInt(16000); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(samples * 2)
            repeat(samples) { putShort(0) }
        }.array()
        val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val acceptor = thread(isDaemon = true) {
            try {
                while (!server.isClosed) server.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) { }
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: audio/wav\r\nContent-Length: ${wav.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(wav); flush()
                    }
                }
            } catch (_: SocketException) { /* Closed by cleanup. */ }
        }
        try {
            val url = "http://127.0.0.1:${server.localPort}/song.wav"
            val info = JSONObject().put("id", "media-test").put("title", "Release audio fixture")
                .put("url", url).put("ext", "wav").put("vcodec", "none").put("acodec", "pcm_s16le")
            val task = DownloadTask(url = url, title = "Release audio fixture", thumbnail = null, source = null,
                durationSec = 1, choice = Choice(Mode.Audio, null, "mp4", "m4a", false))
            val paths = File(root, "paths.jsonl")
            val response = Cheek.execute(task, info.toString(), null, root, root, paths, null)
            assertEquals(response.err, 0, response.exitCode)
            val output = File(JSONObject(paths.readLines().single()).getString("filepath"))
            assertEquals("m4a", output.extension)
            android.media.MediaMetadataRetriever().run {
                try {
                    setDataSource(output.absolutePath)
                    assertEquals("yes", extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO))
                    assertEquals("Release audio fixture", extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE))
                } finally { release() }
            }
        } finally {
            server.close(); acceptor.join(2000); root.deleteRecursively()
        }
    }

    @Test fun versionCheckReadsTheInstalledExecutable(): Unit = runBlocking {
        assertEquals(BuildConfig.CHEEK_VERSION, Cheek.version())
    }

    @Test fun packagedVersionAndDashFragmentsMatchTheRelease() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val binary = File(context.applicationInfo.nativeLibraryDir, "libcheek.so")
        val version = ProcessBuilder(binary.path, "--version").start()
        assertEquals("cheek ${BuildConfig.CHEEK_VERSION}", version.inputStream.bufferedReader().readText().trim())
        assertEquals(0, version.waitFor())

        val root = File(context.cacheDir, "cheek-release-test-${UUID.randomUUID()}").apply { mkdirs() }
        val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val pool = Executors.newFixedThreadPool(8)
        val hits = AtomicInteger()
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val parts = (0..5).map { i -> ByteArray(70_000 + i) { i.toByte() } }
        val acceptor = thread(isDaemon = true) {
            try {
                while (!server.isClosed) {
                    val socket = server.accept()
                    pool.execute {
                        socket.use { connection ->
                            val reader = connection.getInputStream().bufferedReader()
                            val index = reader.readLine().split(' ')[1].trimStart('/').toInt()
                            while (!reader.readLine().isNullOrEmpty()) { }
                            hits.incrementAndGet()
                            val current = active.incrementAndGet()
                            peak.updateAndGet { maxOf(it, current) }
                            try {
                                Thread.sleep(if (index == 0) 150 else 30)
                                val bytes = parts[index]
                                val header = "HTTP/1.1 200 OK\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                                connection.getOutputStream().apply { write(header.toByteArray()); write(bytes); flush() }
                            } finally { active.decrementAndGet() }
                        }
                    }
                }
            } catch (_: SocketException) { /* Closed by test cleanup. */ }
        }
        try {
            val base = "http://127.0.0.1:${server.localPort}/"
            val info = JSONObject().put("id", "dash-test").put("title", "DASH release fixture")
                .put("url", "${base}manifest.mpd").put("fragment_base_url", base)
                .put("protocol", "http_dash_segments").put("vcodec", "none").put("acodec", "aac").put("ext", "m4a")
                .put("fragments", JSONArray().apply { parts.indices.forEach { put(JSONObject().put("path", it.toString())) } })
            val task = DownloadTask(
                url = "${base}manifest.mpd", title = "DASH release fixture", thumbnail = null, source = null, durationSec = 0,
                choice = Choice(Mode.Audio, null, "mp4", "m4a", false),
            )
            val paths = File(root, "paths.jsonl")
            // Synthetic fragments test byte transport; they are not a decodable audio file.
            val response = Cheek.execute(task, info.toString(), Engine.formatSelector(task.choice), root, root, paths, null, mediaFile = false)
            assertEquals(response.err, 0, response.exitCode)
            val output = File(JSONObject(paths.readLines().single()).getString("filepath"))
            assertArrayEquals(parts.reduce { a, b -> a + b }, output.readBytes())
            assertEquals(parts.size, hits.get())
            assertTrue("Fragments should download concurrently", peak.get() > 1)
        } finally {
            server.close()
            acceptor.join(2000)
            pool.shutdownNow()
            root.deleteRecursively()
        }
    }
}
