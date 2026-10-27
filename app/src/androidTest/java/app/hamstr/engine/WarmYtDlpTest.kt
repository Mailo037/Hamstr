package app.hamstr.engine

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.hamstr.data.Choice
import app.hamstr.data.DownloadEngine
import app.hamstr.data.DownloadTask
import app.hamstr.data.Mode
import app.hamstr.data.Prefs
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** The warm yt-dlp answers like a fresh one, only sooner; cheek takes links no extractor knows. */
@RunWith(AndroidJUnit4::class)
class WarmYtDlpTest {
    private lateinit var server: Server
    private val originalSettings = Prefs.settings.value
    private val tasks = mutableListOf<DownloadTask>()

    @Before fun setUp() = runBlocking {
        Engine.awaitReady()
        Prefs.edit { it.copy(autoUpdate = false, cheekAutoUpdate = false, playlists = false) }
        WarmYtDlp.stop()
        server = Server()
    }

    @After fun tearDown() {
        server.close()
        tasks.forEach { task ->
            Engine.pathFile(task).takeIf { it.exists() }?.readLines()?.forEach { line ->
                runCatching { File(JSONObject(line).getString("filepath")).delete() }
            }
            Engine.clearTemporaryFiles(task)
        }
        Prefs.edit { originalSettings }
    }

    private fun request(url: String) = YoutubeDLRequest(url).apply {
        addOption("--dump-single-json")
        addOption("--no-playlist")
    }

    @Test fun answersLikeAFreshProcessButSooner() {
        val url = server.url("/page.html")
        var start = SystemClock.elapsedRealtime()
        val cold = YoutubeDL.getInstance().execute(request(url))
        val coldMs = SystemClock.elapsedRealtime() - start
        start = SystemClock.elapsedRealtime()
        val first = WarmYtDlp.execute(request(url))!!
        val firstMs = SystemClock.elapsedRealtime() - start
        start = SystemClock.elapsedRealtime()
        val warm = WarmYtDlp.execute(request(url))!!
        val warmMs = SystemClock.elapsedRealtime() - start
        Log.i("HamstrWarmTest", "fresh process $coldMs ms, warm process: first $firstMs ms, then $warmMs ms")
        for (out in listOf(first.out, warm.out)) {
            val a = JSONObject(cold.out)
            val b = JSONObject(out)
            assertEquals(a.getString("title"), b.getString("title"))
            assertEquals(a.getString("url"), b.getString("url"))
        }
        assertTrue("warm $warmMs ms should beat a fresh process at $coldMs ms", warmMs < coldMs)
    }

    @Test fun errorsThrowLikeAFreshProcess() {
        val e = assertThrows(YoutubeDLException::class.java) { WarmYtDlp.execute(request(server.url("/missing.html"))) }
        assertTrue(e.message, e.message!!.contains("404"))
    }

    @Test fun requestsRunSideBySideWithTheirOwnOutput(): Unit = runBlocking {
        val urls = (1..4).map { server.url("/page$it.html") }
        val answers = urls.map { url ->
            async(Dispatchers.IO) { JSONObject(WarmYtDlp.execute(request(url))!!.out).getString("webpage_url") }
        }.awaitAll()
        assertEquals(urls, answers)
    }

    @Test fun namesTheExtractorAndStartsAgainAfterStop() {
        assertEquals("Youtube", WarmYtDlp.extractor("https://www.youtube.com/watch?v=aqz-KE-bpKQ"))
        assertEquals("Generic", WarmYtDlp.extractor(server.url("/clip.wav")))
        WarmYtDlp.stop()
        assertEquals("Instagram", WarmYtDlp.extractor("https://www.instagram.com/reel/C0000000000/"))
    }

    @Test fun cheekResolvesAndDownloadsPlainLinksWithoutYtDlp(): Unit = runBlocking {
        Prefs.edit { it.copy(engine = DownloadEngine.Cheek) }
        val url = server.url("/clip.wav")
        val info = Engine.info(url)
        assertTrue(info.title, info.title.endsWith("clip"))
        assertEquals(server.wav.size.toLong(), info.approxBytes)
        // cheek probes with a one-byte range request; yt-dlp doesn't.
        assertTrue(server.ranged.get() > 0)
        val task = DownloadTask(
            url = url, title = url, thumbnail = null, source = null, durationSec = 0,
            choice = Choice(Mode.Video, null, "mp4", "opus", false),
        ).also { tasks += it }
        val response = Engine.execute(task)
        assertEquals(response.err, 0, response.exitCode)
        val output = File(JSONObject(Engine.pathFile(task).readLines().last { it.isNotBlank() }).getString("filepath"))
        assertEquals(server.wav.size.toLong(), output.length())
    }

    /** `*.html` pages with a `<video>`, a WAV file for everything else, 404 for `/missing*`. */
    private class Server : AutoCloseable {
        private val prefix = UUID.randomUUID().toString()
        private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        private val executor = Executors.newCachedThreadPool()
        val ranged = AtomicInteger()
        val wav = ByteArray(44 + 16000).also { "RIFF".toByteArray().copyInto(it) }

        init {
            executor.execute {
                while (!socket.isClosed) {
                    val client = try { socket.accept() } catch (_: Exception) { break }
                    executor.execute {
                        client.use {
                            val reader = client.getInputStream().bufferedReader()
                            val request = reader.readLine()?.split(" ") ?: return@use
                            var range: String? = null
                            while (true) {
                                val header = reader.readLine()
                                if (header.isNullOrEmpty()) break
                                if (header.startsWith("range:", ignoreCase = true)) range = header.substringAfter(':').trim()
                            }
                            val path = request[1].substringBefore('?').removePrefix("/$prefix-").let { if (it.startsWith("/")) it else "/$it" }
                            val (status, type, body) = when {
                                path.startsWith("/missing") -> Triple("404 Not Found", "text/plain", ByteArray(0))
                                path.endsWith(".html") -> Triple("200 OK", "text/html",
                                    "<html><head><title>Warm fixture</title></head><body><video controls src='clip.wav'></video></body></html>".toByteArray())
                                else -> Triple("200 OK", "audio/wav", wav)
                            }
                            try {
                                val out = client.getOutputStream()
                                val bytes = range?.let { Regex("""bytes=(\d+)-(\d*)""").matchEntire(it) }?.takeIf { body === wav }
                                if (bytes != null) {
                                    if (range == "bytes=0-0") ranged.incrementAndGet()
                                    val first = bytes.groupValues[1].toInt()
                                    val last = (bytes.groupValues[2].toIntOrNull() ?: wav.lastIndex).coerceAtMost(wav.lastIndex)
                                    out.write("HTTP/1.1 206 Partial Content\r\nContent-Type: $type\r\nContent-Range: bytes $first-$last/${wav.size}\r\nContent-Length: ${last - first + 1}\r\nConnection: close\r\n\r\n".toByteArray())
                                    if (request[0] != "HEAD") out.write(wav, first, last - first + 1)
                                } else {
                                    out.write("HTTP/1.1 $status\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                                    if (request[0] != "HEAD") out.write(body)
                                }
                                out.flush()
                            } catch (_: Exception) { /* Clients may hang up after sniffing. */ }
                        }
                    }
                }
            }
        }

        fun url(path: String) = "http://127.0.0.1:${socket.localPort}/$prefix-${path.removePrefix("/")}"

        override fun close() {
            socket.close()
            executor.shutdownNow()
        }
    }
}
