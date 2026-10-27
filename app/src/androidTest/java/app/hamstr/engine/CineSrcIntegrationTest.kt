package app.hamstr.engine

import android.util.Log
import android.media.MediaMetadataRetriever
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancelAndJoin
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL
import java.net.URI
import java.net.ServerSocket
import java.net.InetAddress
import java.net.SocketException
import java.io.File
import java.util.UUID
import kotlin.concurrent.thread
import app.hamstr.data.Choice
import app.hamstr.data.Mode
import app.hamstr.data.DownloadTask
import org.json.JSONArray

/** Opt-in live provider checks: pass -e cineSrcLive true. */
@RunWith(AndroidJUnit4::class)
class CineSrcIntegrationTest {
    @Test fun suppliedWatchLinkResolvesThroughTheNormalEngine(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("cineSrcLive") == "true")
        val info = Engine.info("https://cineby.rocks/watch/movie/1384216")
        assertEquals("CineSrc", info.source)
        assertTrue(info.heights.isNotEmpty())
        Log.i("HamstrCineSrc", "User's watch link resolved: ${info.title}; heights=${info.heights}")
    }

    @Test fun movieAndEpisodeResolveOnAndroidWithoutDesktopChrome(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("cineSrcLive") == "true")
        Engine.awaitReady()
        val canceled = launch { CineSrc.resolve(CineSrcTarget(1084242)) }
        delay(200)
        canceled.cancelAndJoin() // Cleanup and the resolver slot must survive cancellation.
        for (target in listOf(CineSrcTarget(1084242), CineSrcTarget(1396, 1, 1))) {
            val text = CineSrc.resolve(target)
            val info = JSONObject(text)
            assertEquals(target.id, info.getString("id"))
            assertFalse(info.getString("title").startsWith("CineSrc"))
            val formats = info.getJSONArray("formats")
            assertTrue(formats.length() > 0)
            val heights = (0 until formats.length()).map { formats.getJSONObject(it).optInt("height") }
            assertEquals(heights.sorted(), heights)
            val headers = info.getJSONObject("http_headers")
            assertFalse(headers.has("Cookie"))
            // The WebView is already destroyed; the native downloader can still access it.
            val connection = URL(formats.getJSONObject(0).getString("url")).openConnection() as HttpURLConnection
            try {
                headers.keys().forEach { key -> connection.setRequestProperty(key, headers.getString(key)) }
                connection.connectTimeout = 15_000
                connection.readTimeout = 15_000
                assertEquals(200, connection.responseCode)
                assertTrue(connection.inputStream.bufferedReader().use { it.readText() }.startsWith("#EXTM3U"))
            } finally { connection.disconnect() }
            Log.i("HamstrCineSrc", "Resolved ${target.id}: ${info.getString("title")}, ${formats.length()} qualities; native HLS access OK")
            if (target.season == null) checkNativeSample(info)
        }
    }

    private fun checkNativeSample(info: JSONObject) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val formats = info.getJSONArray("formats")
        val selected = (0 until formats.length()).map { formats.getJSONObject(it) }.minBy { it.optInt("height") }
        val base = selected.getString("url")
        val headers = info.getJSONObject("http_headers")
        val connection = URL(base).openConnection() as HttpURLConnection
        val body = try {
            headers.keys().forEach { connection.setRequestProperty(it, headers.getString(it)) }
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally { connection.disconnect() }
        assertFalse("Only an unencrypted sample is tested", body.contains("#EXT-X-KEY"))
        val segments = body.lineSequence().map(String::trim).filter { it.isNotEmpty() && !it.startsWith('#') }.take(2).toList()
        assertEquals(2, segments.size)
        val map = Regex("""#EXT-X-MAP:URI="([^"]+)"""").find(body)?.groupValues?.get(1)
        val sample = buildString {
            append("#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-TARGETDURATION:30\n")
            map?.let { append("#EXT-X-MAP:URI=\"").append(URI(base).resolve(it)).append("\"\n") }
            segments.forEach { append("#EXTINF:10,\n").append(URI(base).resolve(it)).append('\n') }
            append("#EXT-X-ENDLIST\n")
        }.toByteArray()
        val root = File(context.cacheDir, "cinesrc-probe-${UUID.randomUUID()}").apply { mkdirs() }
        val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        val acceptor = thread(isDaemon = true) {
            try {
                while (!server.isClosed) server.accept().use { socket ->
                    socket.soTimeout = 5_000
                    val reader = socket.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) { }
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\nContent-Length: ${sample.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(sample)
                        flush()
                    }
                }
            } catch (_: SocketException) { /* Test cleanup. */ }
        }
        try {
            val sampleInfo = JSONObject(info.toString()).put("formats", JSONArray().put(JSONObject(selected.toString()).put("url", "http://127.0.0.1:${server.localPort}/sample.m3u8")))
            val choice = Choice(Mode.Video, 720, "mp4", "m4a", false)
            val task = DownloadTask(url = info.getString("webpage_url"), title = "CineSrc probe", thumbnail = null, source = "CineSrc", durationSec = 0, choice = choice)
            val paths = File(root, "paths.jsonl")
            val response = Cheek.execute(task, sampleInfo.toString(), Engine.formatSelector(choice), root, root, paths, null)
            assertEquals(response.err, 0, response.exitCode)
            val output = File(JSONObject(paths.readLines().single()).getString("filepath"))
            val metadata = MediaMetadataRetriever()
            try {
                metadata.setDataSource(output.absolutePath)
                assertTrue(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)!!.toInt() > 0)
                assertTrue(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong() > 0)
            } finally { metadata.release() }
            Log.i("HamstrCineSrc", "Native Cheek downloaded and remuxed 2 live HLS segments: ${output.length()} bytes; Android decoded the MP4 metadata")
        } finally {
            server.close()
            acceptor.join(2_000)
            root.deleteRecursively()
        }
    }
}
