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
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** Tests the packaged release binary through Hamstr's real bridge, without yt-dlp fallback. */
@RunWith(AndroidJUnit4::class)
class CheekReleaseIntegrationTest {
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
            val response = Cheek.execute(task, info.toString(), root, root, paths, null)
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
