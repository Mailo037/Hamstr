package app.hamstr.cast

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Locale
import java.util.UUID

/**
 * AirPlay receivers that take a link (AirPlay's video protocol): older Apple TVs, TVs with
 * AirPlay built in and AirPlay apps on other devices. The connection that started a file has
 * to stay open while it plays, so every command goes over the same one. Devices that ask for
 * pairing first (newer HomePods, Apple TVs with tvOS 10.2 and later) can't play yet.
 */
object AirPlay {
    internal const val SERVICE = "_airplay._tcp.local"

    internal fun device(r: Mdns.Instance): CastDevice {
        // Bit 0 of the features: it plays video.
        val features = r.txt["features"]?.substringBefore(',')?.removePrefix("0x")?.toLongOrNull(16) ?: 1L
        return CastDevice(
            id = "airplay:" + (r.txt["deviceid"] ?: r.name),
            name = r.label,
            protocol = CastProtocol.AirPlay,
            host = r.host, port = r.port.takeIf { it > 0 } ?: 7000,
            model = r.txt["model"],
            video = features and 1L != 0L,
        )
    }

    /** The answer when a device wants to be paired first. */
    class PairingNeeded : IOException("This AirPlay device asks for pairing, which Hamstr can’t do yet")

    class Link(private val device: CastDevice) : CastLink {
        private val session = UUID.randomUUID().toString().uppercase()
        // The file plays as long as the connection that started it stays open; the status
        // poll has a connection of its own, so a tap never waits behind it.
        private val commands = Conn(device, session)
        private val polls = Conn(device, session)
        @Volatile private var played = false
        @Volatile private var stopped = false

        private suspend fun call(method: String, path: String, body: String? = null, type: String? = null) = commands.call(method, path, body, type)

        override suspend fun connect() { call("GET", "/server-info") }

        override suspend fun load(url: String, coverUrl: String?, media: CastMedia, at: Double, play: Boolean) {
            played = false; stopped = false
            call("POST", "/play", "Content-Location: $url\nStart-Position: 0\n", "text/parameters")
            if (at > 1) {
                repeat(10) {
                    kotlinx.coroutines.delay(500)
                    if (scrub(commands).first > 0) { seek(at); return@repeat }
                }
            }
            if (!play) pause()
        }

        override suspend fun play() { stopped = false; call("POST", "/rate?value=1.000000") }
        override suspend fun pause() { call("POST", "/rate?value=0.000000") }
        override suspend fun seek(seconds: Double) { call("POST", "/scrub?position=${"%.6f".format(Locale.ROOT, seconds)}") }
        override suspend fun setVolume(level: Float) {} // the video protocol has no volume

        /** Duration and position, from GET /scrub. */
        private suspend fun scrub(on: Conn = polls): Pair<Double, Double> {
            val text = on.call("GET", "/scrub").body
            fun field(name: String) = Regex("$name:\\s*([0-9.]+)").find(text)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
            return field("duration") to field("position")
        }

        override suspend fun status(): CastStatus {
            val (duration, position) = scrub()
            val info = runCatching { polls.call("GET", "/playback-info").body }.getOrDefault("")
            val rate = Regex("<key>rate</key>\\s*<real>([0-9.]+)</real>").find(info)?.groupValues?.get(1)?.toDoubleOrNull()
            val state = when {
                duration > 0 && (rate ?: 1.0) > 0 -> CastStatus.State.Playing.also { played = true }
                duration > 0 -> CastStatus.State.Paused
                stopped -> CastStatus.State.Idle
                played -> CastStatus.State.Finished
                else -> CastStatus.State.Loading
            }
            return CastStatus(state, position, duration, null)
        }

        override suspend fun stop() { stopped = true; runCatching { call("POST", "/stop") } }

        override fun close() { commands.close(); polls.close() }
    }

    class Answer(val code: Int, val body: String)

    /** One keep-alive connection to the device. */
    class Conn(private val device: CastDevice, private val session: String) {
            private var socket: Socket? = null
            private var input: BufferedInputStream? = null
            private val lock = Mutex()


            suspend fun call(method: String, path: String, body: String? = null, type: String? = null): Answer = lock.withLock {
                withContext(Dispatchers.IO) {
                    try {
                        send(method, path, body, type)
                    } catch (e: IOException) {
                        if (e is PairingNeeded) throw e
                        reopen()
                        send(method, path, body, type)
                    }
                }
            }

            private fun reopen() {
                runCatching { socket?.close() }
                val s = Socket()
                s.tcpNoDelay = true
                s.connect(InetSocketAddress(device.host, device.port), 4000)
                s.soTimeout = 8000
                socket = s
                input = BufferedInputStream(s.getInputStream())
            }

            private fun send(method: String, path: String, body: String?, type: String?): Answer {
                if (socket?.isClosed != false) reopen()
                val bytes = body?.toByteArray(Charsets.UTF_8)
                val head = buildString {
                    append("$method $path HTTP/1.1\r\n")
                    append("Host: ${device.host}:${device.port}\r\n")
                    append("User-Agent: MediaControl/1.0\r\n")
                    append("X-Apple-Session-ID: $session\r\n")
                    if (type != null) append("Content-Type: $type\r\n")
                    append("Content-Length: ${bytes?.size ?: 0}\r\n\r\n")
                }
                val out = socket!!.getOutputStream()
                out.write(head.toByteArray(Charsets.ISO_8859_1))
                bytes?.let(out::write)
                out.flush()
                val status = line() ?: throw IOException("no answer")
                val code = status.split(' ').getOrNull(1)?.toIntOrNull() ?: throw IOException("bad answer")
                var length = 0
                while (true) {
                    val l = line() ?: break
                    if (l.isEmpty()) break
                    if (l.startsWith("content-length:", true)) length = l.substringAfter(':').trim().toIntOrNull() ?: 0
                }
                val text = if (length > 0) String(LanHttp.readUpTo(input!!, length.coerceAtMost(1 shl 20)), Charsets.UTF_8) else ""
                if (code == 401 || code == 403 || code == 470) throw PairingNeeded()
                if (code !in 200..299) throw IOException("AirPlay: HTTP $code")
                return Answer(code, text)
            }

            private fun line(): String? {
                val sb = StringBuilder()
                while (true) {
                    val b = input!!.read()
                    if (b < 0) return if (sb.isEmpty()) null else sb.toString()
                    if (b == '\n'.code) return sb.toString().trimEnd('\r')
                    sb.append(b.toChar())
                }
            }

        fun close() { runCatching { socket?.close() }; socket = null }
    }
}
