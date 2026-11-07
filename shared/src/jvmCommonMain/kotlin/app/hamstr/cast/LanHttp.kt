package app.hamstr.cast

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI

/**
 * Plain HTTP to a device on this network (a TV's description and its SOAP commands), over a
 * socket of its own: the phone's HTTP stack refuses unencrypted links, and only these local
 * devices need one.
 */
internal object LanHttp {
    class Response(val code: Int, val body: ByteArray) {
        val text get() = String(body, Charsets.UTF_8)
    }

    fun request(url: String, method: String = "GET", headers: Map<String, String> = emptyMap(), body: ByteArray? = null, timeout: Int = 5000): Response {
        val u = URI(url)
        require(u.scheme == "http") { "only http" }
        val port = if (u.port > 0) u.port else 80
        val path = (u.rawPath?.ifEmpty { "/" } ?: "/") + (u.rawQuery?.let { "?$it" } ?: "")
        Socket().use { s ->
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(u.host, port), timeout)
            s.soTimeout = timeout
            val head = buildString {
                append("$method $path HTTP/1.1\r\n")
                append("Host: ${u.host}:$port\r\n")
                append("Connection: close\r\n")
                append("User-Agent: Hamstr/1 UPnP/1.0 DLNADOC/1.50\r\n")
                headers.forEach { (k, v) -> append("$k: $v\r\n") }
                if (body != null) append("Content-Length: ${body.size}\r\n")
                append("\r\n")
            }
            val out = s.getOutputStream()
            out.write(head.toByteArray(Charsets.ISO_8859_1))
            body?.let(out::write)
            out.flush()
            val input = BufferedInputStream(s.getInputStream())
            val status = line(input) ?: throw IOException("no answer")
            val code = status.split(' ').getOrNull(1)?.toIntOrNull() ?: throw IOException("bad answer: $status")
            val fields = HashMap<String, String>()
            while (true) {
                val l = line(input) ?: break
                if (l.isEmpty()) break
                val i = l.indexOf(':')
                if (i > 0) fields[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
            }
            val data = when {
                fields["transfer-encoding"]?.contains("chunked", true) == true -> chunked(input)
                fields["content-length"] != null -> readUpTo(input, fields.getValue("content-length").toInt().coerceIn(0, MAX))
                else -> readUpTo(input, MAX)
            }
            return Response(code, data)
        }
    }

    private const val MAX = 2 shl 20

    /** Downloads [url] on this network into [target], without the small-response cap. */
    fun download(url: String, target: java.io.File, timeout: Int = 15_000, progress: (Float) -> Unit = {}) {
        val u = URI(url)
        require(u.scheme == "http") { "only http" }
        val port = if (u.port > 0) u.port else 80
        val path = (u.rawPath?.ifEmpty { "/" } ?: "/") + (u.rawQuery?.let { "?$it" } ?: "")
        Socket().use { s ->
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(u.host, port), timeout)
            s.soTimeout = timeout
            val head = "GET $path HTTP/1.1\r\nHost: ${u.host}:$port\r\nConnection: close\r\nUser-Agent: Hamstr/1\r\n\r\n"
            val out = s.getOutputStream()
            out.write(head.toByteArray(Charsets.ISO_8859_1))
            out.flush()
            val input = BufferedInputStream(s.getInputStream(), 64 * 1024)
            val status = line(input) ?: throw IOException("no answer")
            val code = status.split(' ').getOrNull(1)?.toIntOrNull() ?: throw IOException("bad answer: $status")
            if (code != 200) throw IOException("HTTP $code")
            val fields = HashMap<String, String>()
            while (true) {
                val l = line(input) ?: break
                if (l.isEmpty()) break
                val i = l.indexOf(':')
                if (i > 0) fields[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
            }
            val total = fields["content-length"]?.toLongOrNull() ?: -1L
            target.outputStream().buffered(64 * 1024).use { file ->
                val buf = ByteArray(64 * 1024)
                var got = 0L
                while (total < 0 || got < total) {
                    val want = if (total > 0) minOf(buf.size.toLong(), total - got).toInt() else buf.size
                    val r = input.read(buf, 0, want)
                    if (r < 0) break
                    file.write(buf, 0, r)
                    got += r
                    if (total > 0) progress((got.toFloat() / total).coerceIn(0f, 1f))
                }
                if (total > 0 && got < total) throw IOException("truncated: $got / $total")
            }
        }
    }

    /** Up to [n] bytes, fewer when the stream ends (InputStream.readNBytes is newer than old phones). */
    fun readUpTo(input: InputStream, n: Int): ByteArray {
        val out = ByteArrayOutputStream(minOf(n, 64 * 1024))
        val buf = ByteArray(8192)
        var left = n
        while (left > 0) {
            val r = input.read(buf, 0, minOf(buf.size, left))
            if (r < 0) break
            out.write(buf, 0, r)
            left -= r
        }
        return out.toByteArray()
    }

    private fun chunked(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val size = line(input)?.substringBefore(';')?.trim()?.toIntOrNull(16) ?: break
            if (size == 0) break
            out.write(readUpTo(input, size))
            line(input)
            if (out.size() > MAX) break
        }
        return out.toByteArray()
    }

    private fun line(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            if (sb.length > 16_384) throw IOException("line too long")
            sb.append(b.toChar())
        }
    }
}
