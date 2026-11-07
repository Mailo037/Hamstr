package app.hamstr.cast

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URL
import java.util.Collections
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CastTest {
    private fun tempFile(bytes: Int = 1000) = File.createTempFile("hamstr-cast-", ".mp3").apply {
        deleteOnExit()
        writeBytes(ByteArray(bytes) { (it % 251).toByte() })
    }

    @Test fun rangesAreReadLikeHttpSaysSo() {
        assertEquals(0L to 99L, MediaServer.parseRange("bytes=0-99", 1000))
        assertEquals(500L to 999L, MediaServer.parseRange("bytes=500-", 1000))
        assertEquals(900L to 999L, MediaServer.parseRange("bytes=-100", 1000))
        assertEquals(990L to 999L, MediaServer.parseRange("bytes=990-5000", 1000))
        assertNull(MediaServer.parseRange("bytes=1000-", 1000))
        assertNull(MediaServer.parseRange("lines=1-2", 1000))
    }

    @Test fun theServerOnlyHandsOutSharedFilesAndSeeks() {
        val file = tempFile()
        val server = MediaServer()
        try {
            val url = assertNotNull(server.share(file, "audio/mpeg", "127.0.0.1"))
            val whole = URL(url).openConnection() as HttpURLConnection
            assertEquals(200, whole.responseCode)
            assertEquals("audio/mpeg", whole.contentType)
            assertEquals(file.readBytes().toList(), whole.inputStream.readBytes().toList())

            val part = URL(url).openConnection() as HttpURLConnection
            part.setRequestProperty("Range", "bytes=100-199")
            assertEquals(206, part.responseCode)
            assertEquals("bytes 100-199/1000", part.getHeaderField("Content-Range"))
            assertEquals(file.readBytes().copyOfRange(100, 200).toList(), part.inputStream.readBytes().toList())

            // Another path on the same server: nothing.
            val other = URL(url.substringBeforeLast('/').dropLast(4) + "0000/x.mp3").openConnection() as HttpURLConnection
            assertEquals(404, other.responseCode)
        } finally {
            server.stop()
        }
    }

    private val description = """<?xml version="1.0"?>
        <root xmlns="urn:schemas-upnp-org:device-1-0">
          <device>
            <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
            <friendlyName>[TV] Living room</friendlyName>
            <manufacturer>Samsung Electronics</manufacturer>
            <modelName>QE55Q80</modelName>
            <UDN>uuid:1234</UDN>
            <serviceList>
              <service><serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType><controlURL>/upnp/control/RenderingControl1</controlURL></service>
              <service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType><controlURL>upnp/control/AVTransport1</controlURL></service>
            </serviceList>
          </device>
        </root>"""

    @Test fun aRendererDescriptionNamesItsServices() {
        val d = assertNotNull(Dlna.parseDescription("http://192.168.1.20:9197/dmr", description.toByteArray()))
        assertEquals("[TV] Living room", d.name)
        assertEquals("uuid:1234", d.id)
        assertEquals("http://192.168.1.20:9197/upnp/control/AVTransport1", d.transport)
        assertEquals("http://192.168.1.20:9197/upnp/control/RenderingControl1", d.rendering)
        assertTrue(d.video)
        assertEquals(205.0, Dlna.seconds("0:03:25"))
        assertEquals("1:02:03", Dlna.clock(3723.4))
    }

    /** A TV that keeps what it is told, as a DLNA renderer does. */
    private class FakeTv {
        val actions = Collections.synchronizedList(mutableListOf<String>())
        var state = "STOPPED"
        var uri = ""
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/av") { ex ->
                val action = ex.requestHeaders.getFirst("SOAPACTION").substringAfter('#').trim('"')
                val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
                actions += action
                when (action) {
                    "SetAVTransportURI" -> uri = Dlna.tag(body, "CurrentURI").orEmpty()
                    "Play" -> state = "PLAYING"
                    "Pause" -> state = "PAUSED_PLAYBACK"
                    "Stop" -> state = "STOPPED"
                }
                val answer = when (action) {
                    "GetTransportInfo" -> "<CurrentTransportState>$state</CurrentTransportState>"
                    "GetPositionInfo" -> "<RelTime>0:00:42</RelTime><TrackDuration>0:03:00</TrackDuration>"
                    else -> ""
                }
                val xml = """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><u:${action}Response>$answer</u:${action}Response></s:Body></s:Envelope>""".toByteArray()
                ex.sendResponseHeaders(200, xml.size.toLong())
                ex.responseBody.use { it.write(xml) }
            }
            start()
        }
        val device = CastDevice("uuid:fake", "Fake TV", CastProtocol.Dlna, "127.0.0.1", server.address.port, transport = "http://127.0.0.1:${server.address.port}/av")
    }

    @Test fun aDlnaTvGetsTheLinkAndPlays() = runBlocking {
        val tv = FakeTv()
        try {
            val link = Dlna.Link(tv.device)
            link.connect()
            val m = CastMedia("1", tempFile(), "Song & Dance", "Band", "audio/mpeg", video = false)
            link.load("http://127.0.0.1:1/x.mp3", null, m, 0.0, play = true)
            assertEquals("http://127.0.0.1:1/x.mp3", tv.uri)
            assertEquals(listOf("GetTransportInfo", "Stop", "SetAVTransportURI", "Play"), tv.actions.toList())
            val s = link.status()
            assertEquals(CastStatus.State.Playing, s.state)
            assertEquals(42.0, s.position)
            assertEquals(180.0, s.duration)
            link.pause()
            assertEquals(CastStatus.State.Paused, link.status().state)
            // It played and stopped by itself: the song is over.
            tv.state = "STOPPED"
            assertEquals(CastStatus.State.Finished, link.status().state)
        } finally {
            tv.server.stop(0)
        }
    }

    @Test fun mpdGetsItsCommandsAsAList() = runBlocking {
        val server = ServerSocket(0)
        val lines = Collections.synchronizedList(mutableListOf<String>())
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val s = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) {
                    s.use {
                        val r = BufferedReader(InputStreamReader(it.getInputStream()))
                        val w = it.getOutputStream()
                        w.write("OK MPD 0.23.5\n".toByteArray())
                        var listing = false
                        while (true) {
                            val l = r.readLine() ?: break
                            lines += l
                            when {
                                l.startsWith("command_list") && l != "command_list_end" -> listing = true
                                l == "command_list_end" -> { listing = false; w.write("Id: 7\nOK\n".toByteArray()) }
                                listing -> {}
                                l == "status" -> w.write("volume: 40\nstate: play\nsongid: 7\nelapsed: 12.5\nduration: 200.0\nOK\n".toByteArray())
                                else -> w.write("OK\n".toByteArray())
                            }
                        }
                    }
                }
            }
        }
        try {
            val link = Mpd.Link(CastDevice("mpd", "Pi", CastProtocol.Mpd, "127.0.0.1", server.localPort))
            link.connect()
            link.load("http://h/a b.mp3", null, CastMedia("1", tempFile(), "x", null, "audio/mpeg", false), 0.0, true)
            assertContains(lines, "addid \"http://h/a b.mp3\"")
            assertContains(lines, "playid 7")
            val s = link.status()
            assertEquals(CastStatus.State.Playing, s.state)
            assertEquals(12.5, s.position)
            assertEquals(0.4f, s.volume)
            link.close()
        } finally {
            server.close()
        }
    }

    @Test fun castMessagesGoThereAndBack() {
        val frame = Chromecast.encode("sender-0", "receiver-0", "urn:x-cast:com.google.cast.receiver", """{"type":"GET_STATUS","requestId":1}""")
        assertEquals("urn:x-cast:com.google.cast.receiver" to """{"type":"GET_STATUS","requestId":1}""", Chromecast.decode(frame))
    }

    @Test fun mdnsAnswersBecomeDevices() {
        // A Chromecast's answer: PTR, SRV, TXT and A in one message.
        val out = ByteArrayOutputStream()
        fun name(n: String) { n.split('.').forEach { out.write(it.length); out.write(it.toByteArray()) }; out.write(0) }
        fun u16(v: Int) { out.write(v shr 8); out.write(v and 0xFF) }
        fun record(owner: String, type: Int, data: ByteArray) { name(owner); u16(type); u16(1); u16(0); u16(120); u16(data.size); out.write(data) }
        fun nameBytes(n: String) = ByteArrayOutputStream().apply { n.split('.').forEach { write(it.length); write(it.toByteArray()) }; write(0) }.toByteArray()
        out.write(byteArrayOf(0, 0, 0x84.toByte(), 0, 0, 0, 0, 4, 0, 0, 0, 0))
        val inst = "Chromecast-abc._googlecast._tcp.local"
        record("_googlecast._tcp.local", 12, nameBytes(inst))
        record(inst, 33, byteArrayOf(0, 0, 0, 0, 0x1F, 0x49) + nameBytes("abc.local"))
        val txt = ByteArrayOutputStream().apply { listOf("id=abc", "fn=Living room TV", "md=Chromecast", "ca=4101").forEach { write(it.length); write(it.toByteArray()) } }.toByteArray()
        record(inst, 16, txt)
        record("abc.local", 1, byteArrayOf(192.toByte(), 168.toByte(), 1, 50))
        val r = Mdns.Records(listOf(Chromecast.SERVICE))
        r.read(out.toByteArray())
        val i = r.instances(null).single()
        val d = assertNotNull(Chromecast.device(i))
        assertEquals("Living room TV", d.name)
        assertEquals("192.168.1.50", d.host)
        assertEquals(8009, d.port)
        assertTrue(d.video)
    }

    /** A device that takes a while for each command, and remembers them. */
    private class SlowLink : CastLink {
        val calls = Collections.synchronizedList(mutableListOf<String>())
        override suspend fun connect() {}
        override suspend fun load(url: String, coverUrl: String?, media: CastMedia, at: Double, play: Boolean) { calls += "load ${media.id}"; delay(50) }
        override suspend fun play() { calls += "play"; delay(50) }
        override suspend fun pause() { calls += "pause"; delay(50) }
        override suspend fun seek(seconds: Double) { calls += "seek ${seconds.toInt()}"; delay(50) }
        override suspend fun setVolume(level: Float) { calls += "volume $level"; delay(50) }
        override suspend fun status() = CastStatus(CastStatus.State.Playing)
        override suspend fun stop() {}
        override fun close() {}
    }

    @Test fun quickTapsSendOnlyWhatCounts() = runBlocking {
        val link = SlowLink()
        val session = CastSession(CastDevice("x", "X", CastProtocol.Dlna, "127.0.0.1", 1), link)
        val file = tempFile()
        // Skipping through five songs loads the last one.
        for (i in 1..5) session.load(CastMedia("$i", file, "$i", null, "audio/mpeg", false))
        // Dragging the bar seeks once, where it was let go.
        for (s in 10..60 step 10) session.seek(s.toDouble())
        // Pause, play, pause: the UI follows each tap at once.
        session.pause(); session.play(); session.pause()
        assertEquals(CastStatus.State.Paused, session.status.value.state)
        delay(800)
        val sent = link.calls.toList()
        assertEquals(1, sent.count { it.startsWith("load") }, "$sent")
        assertTrue(sent.count { it.startsWith("seek") } <= 1, "$sent")
        assertEquals("pause", sent.last(), "$sent")
        assertTrue(sent.size <= 4, "$sent")
        session.end()
    }

    private val ffmpegSays = """
        Input #0, matroska,webm, from 'song.webm':
          Duration: 00:03:25.12, start: -0.007000, bitrate: 133 kb/s
          Stream #0:0(eng): Audio: opus, 48000 Hz, stereo, fltp (default)
        Input #1, mov,mp4,m4a,3gp,3g2,mj2, from 'clip.mp4':
    """.trimIndent()

    @Test fun ffmpegsDescriptionSaysWhatIsInside() {
        val p = Convert.parse("webm", ffmpegSays)
        assertEquals(Convert.Probe("webm", null, "opus", 205.12), p)
        val video = Convert.parse("mkv", listOf("  Duration: 00:00:10.00", "  Stream #0:0: Video: vp9 (Profile 0), yuv420p", "  Stream #0:1: Audio: aac (LC), 44100 Hz").joinToString("\n"))
        assertEquals("vp9", video.video)
        assertEquals("aac", video.audio)
        // A song's cover is no video.
        val song = Convert.parse("m4a", listOf("  Stream #0:0: Audio: aac (LC)", "  Stream #0:1: Video: mjpeg (Baseline), yuvj420p, 600x600 (attached pic)").joinToString("\n"))
        assertNull(song.video)
    }

    @Test fun eachDeviceGetsWhatItPlays() {
        val opus = Convert.Probe("webm", null, "opus", 200.0)
        assertEquals(Convert.Plan.Direct, Convert.plan(CastProtocol.Chromecast, opus, picture = false))
        assertEquals(Convert.Plan.Sound(copy = false, ext = "m4a"), Convert.plan(CastProtocol.Dlna, opus, picture = false))
        assertEquals(Convert.Plan.Direct, Convert.plan(CastProtocol.Mpd, opus, picture = false))
        val m4a = Convert.Probe("m4a", null, "aac", 200.0)
        assertEquals(Convert.Plan.Direct, Convert.plan(CastProtocol.AirPlay, m4a, picture = false))
        val vp9 = Convert.Probe("webm", "vp9", "opus", 60.0)
        assertEquals(Convert.Plan.Direct, Convert.plan(CastProtocol.Chromecast, vp9, picture = true))
        assertEquals(Convert.Plan.Encode, Convert.plan(CastProtocol.Dlna, vp9, picture = true))
        // A speaker gets the sound of a video.
        assertEquals(Convert.Plan.Sound(copy = false, ext = "m4a"), Convert.plan(CastProtocol.Dlna, vp9, picture = false))
        val mkv = Convert.Probe("mkv", "h264", "aac", 60.0)
        assertEquals(Convert.Plan.Remux(copyAudio = true), Convert.plan(CastProtocol.Dlna, mkv, picture = true))
        assertEquals(Convert.Plan.Direct, Convert.plan(CastProtocol.Dlna, Convert.Probe("mp4", "h264", "aac", 60.0), picture = true))
    }

    /** With the FFmpeg fetchTools put into desktop/build/tools: an Opus song becomes AAC for a TV. */
    @Test fun anOpusSongBecomesAacForATv() {
        val exe = if (System.getProperty("os.name").lowercase().startsWith("windows")) "ffmpeg.exe" else "ffmpeg"
        val ff = generateSequence(File("").absoluteFile) { it.parentFile }.flatMap { d -> File(d, "desktop/build/tools").listFiles().orEmpty().map { File(it, exe) } + File(d, "desktop/build/tools/$exe") }.firstOrNull { it.isFile } ?: return
        Cast.ffmpeg = { args -> ProcessBuilder(listOf(ff.path) + args) }
        Cast.cache = File(System.getProperty("java.io.tmpdir"), "hamstr-cast-test-" + System.nanoTime())
        val song = File(Cast.cache, "song.webm").apply { parentFile.mkdirs() }
        val made = ProcessBuilder(ff.path, "-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi", "-i", "sine=frequency=440:duration=3", "-c:a", "libopus", song.path).inheritIO().start()
        assertTrue(made.waitFor() == 0)
        val tv = CastDevice("t", "TV", CastProtocol.Dlna, "127.0.0.1", 1)
        var last = 0f
        val (file, mime) = Convert.prepare(CastMedia("1", song, "Song", null, "audio/webm", false), tv, { last = it }, { true })
        assertEquals("audio/mp4", mime)
        assertEquals("aac", Convert.probe(file)?.audio)
        assertTrue(last > 0.5f, "progress $last")
        // The second time the copy is there already.
        val again = Convert.prepare(CastMedia("1", song, "Song", null, "audio/webm", false), tv, {}, { true })
        assertEquals(file, again.first)
    }
}
