package app.hamstr.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import app.hamstr.cast.HamstrCast
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Plain HTTP for what another Hamstr on this network plays here ("Play on"), over a socket of
 * its own: the phone's HTTP stack refuses unencrypted links, and only these streams need one
 * (the other Hamstr's media server, on a private address, with byte ranges so seeking works).
 */
@UnstableApi
internal class LanDataSource : BaseDataSource(true) {
    private var socket: Socket? = null
    private var input: InputStream? = null
    private var uri: Uri? = null
    private var left = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        val url = dataSpec.uri.toString()
        if (!HamstrCast.onThisNetwork(url)) throw IOException("Only streams from this network")
        transferInitializing(dataSpec)
        uri = dataSpec.uri
        val host = dataSpec.uri.host ?: throw IOException("No host")
        val port = dataSpec.uri.port.takeIf { it > 0 } ?: 80
        val path = (dataSpec.uri.encodedPath ?: "/").ifEmpty { "/" }
        val s = Socket().apply {
            tcpNoDelay = true
            connect(InetSocketAddress(host, port), 5_000)
            soTimeout = 15_000
        }
        socket = s
        val end = if (dataSpec.length != C.LENGTH_UNSET.toLong()) "${dataSpec.position + dataSpec.length - 1}" else ""
        val head = "GET $path HTTP/1.1\r\nHost: $host:$port\r\nRange: bytes=${dataSpec.position}-$end\r\nConnection: close\r\nUser-Agent: Hamstr\r\n\r\n"
        s.getOutputStream().apply { write(head.toByteArray(Charsets.ISO_8859_1)); flush() }
        val inp = BufferedInputStream(s.getInputStream(), 64 * 1024)
        val status = line(inp) ?: throw IOException("No answer")
        val code = status.split(' ').getOrNull(1)?.toIntOrNull() ?: throw IOException("Bad answer: $status")
        var length = -1L
        while (true) {
            val l = line(inp) ?: break
            if (l.isEmpty()) break
            val i = l.indexOf(':')
            if (i > 0 && l.substring(0, i).trim().equals("content-length", true)) length = l.substring(i + 1).trim().toLongOrNull() ?: -1
        }
        if (code != 200 && code != 206) throw IOException("HTTP $code")
        // A server that ignored the range sends the whole file: skip to the spot.
        if (code == 200 && dataSpec.position > 0) {
            var skip = dataSpec.position
            while (skip > 0) { val n = inp.skip(skip); if (n <= 0) throw IOException("Cut short"); skip -= n }
            if (length >= 0) length -= dataSpec.position
        }
        if (dataSpec.length != C.LENGTH_UNSET.toLong()) length = if (length >= 0) minOf(length, dataSpec.length) else dataSpec.length
        input = inp
        left = if (length >= 0) length else Long.MAX_VALUE
        opened = true
        transferStarted(dataSpec)
        return if (length >= 0) length else C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (left == 0L) return C.RESULT_END_OF_INPUT
        val n = (input ?: throw IOException("Not open")).read(buffer, offset, minOf(length.toLong(), left).toInt())
        if (n < 0) {
            if (left != Long.MAX_VALUE) throw IOException("Cut short")
            return C.RESULT_END_OF_INPUT
        }
        if (left != Long.MAX_VALUE) left -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        runCatching { socket?.close() }
        socket = null
        input = null
        uri = null
        if (opened) {
            opened = false
            transferEnded()
        }
    }

    private fun line(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            if (sb.length > 8192) throw IOException("Line too long")
            sb.append(b.toChar())
        }
    }

    class Factory : DataSource.Factory {
        override fun createDataSource(): DataSource = LanDataSource()
    }
}
