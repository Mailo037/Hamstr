package app.hamstr.cast

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Music Player Daemon (MPD), on a Raspberry Pi, a NAS or a hi-fi streamer (Volumio, moOde):
 * its text protocol on port 6600 takes a link, plays it and says how far it is.
 */
object Mpd {
    internal const val SERVICE = "_mpd._tcp.local"

    internal fun device(r: Mdns.Instance) = CastDevice(
        id = "mpd:${r.host}:${r.port}",
        name = r.label.ifBlank { "MPD" },
        protocol = CastProtocol.Mpd,
        host = r.host, port = r.port.takeIf { it > 0 } ?: 6600,
        model = "MPD",
        video = false,
    )

    class Link(private val device: CastDevice) : CastLink {
        // Commands and the status poll each have a connection, so a tap never waits for a poll.
        private val commands = Conn(device)
        private val polls = Conn(device)
        @Volatile private var song: String? = null
        @Volatile private var played = false
        @Volatile private var stopped = false

        private suspend fun run(command: String) = commands.run(command)

        override suspend fun connect() { commands.run("ping") }

        override suspend fun load(url: String, coverUrl: String?, media: CastMedia, at: Double, play: Boolean) {
            played = false; stopped = false
            // One round trip for the lot: MPD runs a command list in one go.
            val id = commands.run(listOf("command_list_ok_begin", "clear", "addid ${quote(url)}", "command_list_end").joinToString("\n"))["id"]
            song = id
            commands.run(listOfNotNull("command_list_begin", "playid ${id ?: 0}", "pause 1".takeIf { !play }, "command_list_end").joinToString("\n"))
            if (at > 1) runCatching { run("seekcur ${"%.1f".format(java.util.Locale.ROOT, at)}") }
        }

        override suspend fun play() { stopped = false; run("pause 0") }
        override suspend fun pause() { run("pause 1") }
        override suspend fun seek(seconds: Double) { run("seekcur ${"%.1f".format(java.util.Locale.ROOT, seconds)}") }
        override suspend fun setVolume(level: Float) { run("setvol ${(level * 100).toInt().coerceIn(0, 100)}") }

        override suspend fun status(): CastStatus {
            val s = polls.run("status")
            val volume = s["volume"]?.toIntOrNull()?.takeIf { it >= 0 }?.div(100f)
            val ours = song == null || s["songid"] == song
            val state = when (s["state"]) {
                "play" -> if (ours) CastStatus.State.Playing.also { played = true } else CastStatus.State.Idle
                "pause" -> CastStatus.State.Paused
                else -> when {
                    stopped -> CastStatus.State.Idle
                    played -> CastStatus.State.Finished
                    else -> CastStatus.State.Loading
                }
            }
            return CastStatus(
                state = state,
                position = s["elapsed"]?.toDoubleOrNull() ?: 0.0,
                duration = s["duration"]?.toDoubleOrNull() ?: s["time"]?.substringAfter(':')?.toDoubleOrNull() ?: 0.0,
                volume = volume,
            )
        }

        override suspend fun stop() { stopped = true; runCatching { run("stop") } }

        override fun close() { commands.close(); polls.close() }

        private fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }

    /** One connection to MPD, opened on first use and again when MPD hung up. */
    private class Conn(private val device: CastDevice) {
        private var socket: Socket? = null
        private var reader: BufferedReader? = null
        private var out: OutputStream? = null
        private val lock = Mutex()

        private fun open() {
            close()
            val s = Socket()
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(device.host, device.port), 4000)
            s.soTimeout = 6000
            val r = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
            val hello = r.readLine() ?: throw IOException("no answer")
            if (!hello.startsWith("OK MPD")) throw IOException("not MPD: $hello")
            socket = s; reader = r; out = s.getOutputStream()
        }

        /** One command and its answer as key-value lines; reconnects once when MPD hung up. */
        suspend fun run(command: String): Map<String, String> = lock.withLock {
            withContext(Dispatchers.IO) {
                try {
                    if (socket?.isClosed != false) open()
                    exchange(command)
                } catch (e: IOException) {
                    if (e is MpdError) throw e
                    open()
                    exchange(command)
                }
            }
        }

        private class MpdError(message: String) : IOException(message)

        private fun exchange(command: String): Map<String, String> {
            out!!.write((command + "\n").toByteArray(Charsets.UTF_8))
            out!!.flush()
            val map = LinkedHashMap<String, String>()
            while (true) {
                val line = reader!!.readLine() ?: throw IOException("closed")
                if (line == "OK") return map
                if (line.startsWith("ACK")) throw MpdError(line.substringAfter("} ").ifEmpty { line })
                val i = line.indexOf(": ")
                if (i > 0) map[line.substring(0, i).lowercase()] = line.substring(i + 2)
            }
        }

        fun close() {
            runCatching { socket?.close() }
            socket = null
        }
    }
}
