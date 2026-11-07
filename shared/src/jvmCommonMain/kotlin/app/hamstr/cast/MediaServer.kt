package app.hamstr.cast

import app.hamstr.Log
import java.io.BufferedInputStream
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Hands the files Hamstr casts to the TV: a small HTTP server on this device that only answers
 * for files [share] put up, each under a long random path, with byte ranges so the TV can seek.
 * It runs while something is cast and stops with the session.
 */
class MediaServer {
    private val files = ConcurrentHashMap<String, Pair<File, String>>()
    private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "hamstr-cast-http").apply { isDaemon = true } }
    private val random = SecureRandom()

    val port: Int get() = server?.localPort ?: 0

    @Synchronized
    fun start() {
        if (server != null) return
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(InetSocketAddress(0))
        server = s
        pool.execute {
            while (!s.isClosed) {
                val client = runCatching { s.accept() }.getOrNull() ?: break
                pool.execute { runCatching { client.use(::answer) }.onFailure { Log.d(TAG, "request: ${it.message}") } }
            }
        }
    }

    @Synchronized
    fun stop() {
        runCatching { server?.close() }
        server = null
        files.clear()
    }

    /** A link [to] can open for [file], served as [mime]; null when the server isn't running. */
    fun share(file: File, mime: String, to: String): String? {
        start()
        val token = ByteArray(18).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        files[token] = file to mime
        val host = localAddressFor(to) ?: return null
        val name = URLEncoder.encode(file.name, "UTF-8").replace("+", "%20")
        return "http://${host.hostAddress}:$port/$token/$name"
    }

    private fun answer(socket: Socket) {
        socket.soTimeout = 30_000
        socket.tcpNoDelay = true
        val input = BufferedInputStream(socket.getInputStream())
        val request = readLine(input) ?: return
        val headers = generateSequence { readLine(input)?.takeIf { it.isNotEmpty() } }
            .mapNotNull { line -> line.indexOf(':').takeIf { it > 0 }?.let { line.substring(0, it).trim().lowercase() to line.substring(it + 1).trim() } }
            .toMap()
        val parts = request.split(' ')
        val method = parts.getOrNull(0) ?: return
        val token = parts.getOrNull(1)?.trimStart('/')?.substringBefore('/') ?: ""
        val out = socket.getOutputStream()
        val (file, mime) = files[token]?.takeIf { it.first.isFile } ?: return status(out, "404 Not Found")
        if (method != "GET" && method != "HEAD") return status(out, "405 Method Not Allowed")
        val size = file.length()
        val range = headers["range"]?.let { parseRange(it, size) }
        if (headers["range"] != null && range == null) {
            return write(out, "416 Range Not Satisfiable", listOf("Content-Range" to "bytes */$size", "Content-Length" to "0"))
        }
        val (from, to) = range ?: (0L to size - 1)
        val length = (to - from + 1).coerceAtLeast(0)
        val head = buildList {
            add("Content-Type" to mime)
            add("Content-Length" to length.toString())
            add("Accept-Ranges" to "bytes")
            if (range != null) add("Content-Range" to "bytes $from-$to/$size")
            // What DLNA TVs look for before they let a stream seek.
            add("transferMode.dlna.org" to if (mime.startsWith("image/")) "Interactive" else "Streaming")
            add("contentFeatures.dlna.org" to "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000")
            add("Access-Control-Allow-Origin" to "*")
        }
        write(out, if (range != null) "206 Partial Content" else "200 OK", head)
        if (method == "HEAD" || length == 0L) return
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(from)
            val buf = ByteArray(64 * 1024)
            var left = length
            while (left > 0) {
                val n = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n < 0) break
                out.write(buf, 0, n)
                left -= n
            }
        }
        out.flush()
    }

    private fun status(out: OutputStream, status: String) = write(out, status, listOf("Content-Length" to "0"))

    private fun write(out: OutputStream, status: String, headers: List<Pair<String, String>>) {
        val text = buildString {
            append("HTTP/1.1 ").append(status).append("\r\n")
            headers.forEach { (k, v) -> append(k).append(": ").append(v).append("\r\n") }
            append("Connection: close\r\n\r\n")
        }
        out.write(text.toByteArray(Charsets.ISO_8859_1))
        out.flush()
    }

    private fun readLine(input: BufferedInputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            if (sb.length > 8192) return null
            sb.append(b.toChar())
        }
    }

    companion object {
        private const val TAG = "Cast"

        /** "bytes=a-b", "bytes=a-" or "bytes=-n" of a file of [size] bytes; null when it is outside. */
        internal fun parseRange(header: String, size: Long): Pair<Long, Long>? {
            val spec = header.substringAfter("bytes=", "").substringBefore(',').trim()
            if (spec.isEmpty() || size <= 0) return null
            val a = spec.substringBefore('-').trim()
            val b = spec.substringAfter('-').trim()
            return when {
                a.isEmpty() -> b.toLongOrNull()?.takeIf { it > 0 }?.let { (size - it).coerceAtLeast(0) to size - 1 }
                else -> {
                    val from = a.toLongOrNull() ?: return null
                    val to = (b.toLongOrNull() ?: (size - 1)).coerceAtMost(size - 1)
                    if (from >= size || from > to) null else from to to
                }
            }
        }

        /** This device's address on the network that reaches [host]. */
        fun localAddressFor(host: String): InetAddress? = runCatching {
            DatagramSocket().use { s ->
                s.connect(InetAddress.getByName(host), 9)
                s.localAddress.takeUnless { it.isAnyLocalAddress }
            }
        }.getOrNull()
    }
}
