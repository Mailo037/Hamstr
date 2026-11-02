package app.hamstr.nearby

import app.hamstr.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Hamstr's Bluetooth LE service, the same on the phone and the computer. A device advertises
 * [SERVICE] (a phone also its id, as maker data) and runs a GATT server with four
 * characteristics: [INFO] to read its signed hello, [INBOX] to write a sealed link into, in
 * pieces, [RECEIPT] to read the signed receipt for it, and [STREAM], a connection for files
 * that carries whole messages both ways. The platforms only move bytes ([GattLink] for the
 * side that connects, [BleServer] for the side that is connected to); what they mean is here.
 *
 * Every write starts with a flag byte: [LAST] marks the last piece of a message and, on
 * [STREAM], [START] the first message of an exchange. Every [STREAM] read returns one piece of
 * what the server has to say, flagged [MORE] or [END], or [NOTHING] yet.
 */
object Ble {
    const val SERVICE = "6d3a0001-5a1e-4c7b-9f3e-48616d737472"
    const val INFO = "6d3a0002-5a1e-4c7b-9f3e-48616d737472"
    const val INBOX = "6d3a0003-5a1e-4c7b-9f3e-48616d737472"
    const val RECEIPT = "6d3a0004-5a1e-4c7b-9f3e-48616d737472"
    const val STREAM = "6d3a0005-5a1e-4c7b-9f3e-48616d737472"
    /** The Bluetooth SIG's id for tests and unregistered makers; the payload is Hamstr's 8-byte id. */
    const val MAKER = 0xFFFF
    const val MAX_MESSAGE = 64 * 1024
    /** Files cross Bluetooth in frames this big, so one arrives well within a read's timeout. */
    const val CHUNK = 32 * 1024

    internal const val LAST = 1
    internal const val START = 2
    internal const val NOTHING: Byte = 0
    internal const val MORE: Byte = 1
    internal const val END: Byte = 2
    /** A piece read back from [STREAM]: under ATT's 512 bytes per value, with its flag. */
    internal const val PIECE = 480

    private const val TAG = "Nearby"
    private val NO = "no".toByteArray()

    /** Who is behind [link], when its hello checks out. */
    suspend fun hello(link: GattLink): Peer? {
        val bytes = link.read(INFO) ?: return null
        val m = runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }.getOrNull() ?: return null
        return Nearby.checkHello(m)
    }

    /** Writes [message] into [link]'s inbox and passes its receipt to [accept]. */
    suspend fun send(link: GattLink, message: ByteArray, accept: (ByteArray) -> Boolean): Boolean {
        val size = (link.writeSize - 1).coerceAtLeast(19)
        val pieces = message.toList().chunked(size)
        val written = pieces.withIndex().all { (i, part) ->
            link.write(INBOX, byteArrayOf(if (i == pieces.lastIndex) LAST.toByte() else 0) + part.toByteArray())
        }
        return written && link.read(RECEIPT)?.let(accept) == true
    }

    /** A [Wire] over [link]'s [STREAM], for files. Its calls block, like a socket's. */
    fun wire(link: GattLink): Wire = ClientWire(link)

    private class ClientWire(private val link: GattLink) : Wire {
        override var timeout = 30_000
        private var started = false

        override fun write(bytes: ByteArray) = runBlocking {
            val size = (link.writeSize - 1).coerceAtLeast(19)
            var at = 0
            do {
                val end = minOf(bytes.size, at + size)
                val last = end == bytes.size
                // Pieces go without waiting for an answer where they can; the last one waits, and once
                // it is answered the server has them all, since a connection keeps their order.
                val flag = (if (last) LAST else 0) or (if (started) 0 else START)
                started = true
                if (!link.write(STREAM, byteArrayOf(flag.toByte()) + bytes.copyOfRange(at, end), fast = !last && link.fastWrites)) {
                    throw IOException("Bluetooth write failed")
                }
                at = end
            } while (!last)
        }

        override fun read(max: Int): ByteArray = runBlocking { receive(max) }

        private suspend fun receive(max: Int): ByteArray {
            val out = ByteArrayOutputStream()
            val until = System.currentTimeMillis() + timeout
            while (true) {
                val piece = link.read(STREAM) ?: throw IOException("Bluetooth read failed")
                when (piece.firstOrNull()) {
                    MORE, END -> {
                        out.write(piece, 1, piece.size - 1)
                        if (out.size() > max) throw IOException("A message of more than $max bytes")
                        if (piece[0] == END) return out.toByteArray()
                    }
                    else -> {
                        if (System.currentTimeMillis() > until) throw SocketTimeoutException("Nothing over Bluetooth")
                        delay(150)
                    }
                }
            }
        }
    }

    internal val no get() = NO
    internal fun log(message: String, e: Throwable? = null) = if (e == null) Log.i(TAG, message) else Log.w(TAG, message, e)
}

/** One connection to another device's Hamstr service, from the side that connected. */
interface GattLink {
    /** The most one write may carry: the connection's MTU less ATT's three bytes. */
    val writeSize: Int
    /** Whether [Ble.STREAM] takes writes without a response, several to a connection event. */
    val fastWrites: Boolean get() = false
    suspend fun read(characteristic: String): ByteArray?
    suspend fun write(characteristic: String, bytes: ByteArray, fast: Boolean = false): Boolean
}

/**
 * What a device's Hamstr service answers, on any platform: reads and writes from each device
 * connected to it, by an id the platform gives that device. A link is answered in place; an
 * exchange on [Ble.STREAM] gets a thread of its own, since files wait for the user and then
 * run for a while.
 */
class BleServer {
    private class Exchange {
        val incoming = LinkedBlockingQueue<ByteArray>()
        val outgoing = LinkedBlockingQueue<ByteArray>()
        @Volatile var closed = false
    }

    private class Client {
        val inbox = ByteArrayOutputStream()
        val stream = ByteArrayOutputStream()
        @Volatile var receipt: ByteArray? = null
        // What a long read gets for the rest of its value, fixed when it starts.
        @Volatile var hello: ByteArray? = null
        @Volatile var piece: ByteArray? = null
        @Volatile var exchange: Exchange? = null
        @Volatile var heard = System.currentTimeMillis()
    }

    private val clients = ConcurrentHashMap<String, Client>()

    private fun client(id: String): Client {
        val now = System.currentTimeMillis()
        // Some platforms never say a device left; one quiet for long enough is forgotten.
        if (clients.size > 8) clients.entries.removeIf { (_, c) -> now - c.heard > 15 * 60_000L && c.exchange == null }
        return clients.getOrPut(id) { Client() }.also { it.heard = now }
    }

    /** The value for a read of [characteristic] from [offset] on, or null when there is none. */
    fun read(client: String, characteristic: String, offset: Int): ByteArray? {
        val c = client(client)
        val value = when (characteristic.lowercase()) {
            // A fresh hello for each read from the start; the rest of a long read gets the same one.
            Ble.INFO -> if (offset == 0) Nearby.hello().toString().toByteArray(Charsets.UTF_8).also { c.hello = it } else c.hello ?: ByteArray(0)
            Ble.RECEIPT -> c.receipt ?: Ble.no
            Ble.STREAM -> if (offset == 0) (c.exchange?.outgoing?.poll() ?: byteArrayOf(Ble.NOTHING)).also { c.piece = it } else c.piece ?: ByteArray(0)
            else -> return null
        }
        return value.copyOfRange(offset.coerceIn(0, value.size), value.size)
    }

    /** Takes a write of [value] to [characteristic]; false answers it with an error. */
    fun write(client: String, characteristic: String, value: ByteArray): Boolean {
        if (value.isEmpty()) return false
        val c = client(client)
        val flag = value[0].toInt()
        return when (characteristic.lowercase()) {
            Ble.INBOX -> synchronized(c) {
                c.inbox.write(value, 1, value.size - 1)
                when {
                    c.inbox.size() > Ble.MAX_MESSAGE -> { c.inbox.reset(); false }
                    flag and Ble.LAST != 0 -> {
                        val message = c.inbox.toByteArray().also { c.inbox.reset() }
                        val receipt = runCatching { Nearby.receive(message) }.getOrNull()?.takeIf { it.isNotEmpty() }
                        c.receipt = receipt ?: Ble.no
                        receipt != null
                    }
                    else -> true
                }
            }
            Ble.STREAM -> synchronized(c) {
                if (flag and Ble.START != 0) {
                    // A new exchange ends whatever an earlier one on this connection left behind.
                    c.exchange?.closed = true
                    c.stream.reset()
                    c.exchange = Exchange().also { start(c, it) }
                }
                val exchange = c.exchange ?: return@synchronized false
                c.stream.write(value, 1, value.size - 1)
                when {
                    c.stream.size() > Ble.CHUNK + Ble.MAX_MESSAGE -> { c.stream.reset(); exchange.closed = true; false }
                    flag and Ble.LAST != 0 -> { exchange.incoming.put(c.stream.toByteArray()); c.stream.reset(); true }
                    else -> true
                }
            }
            else -> false
        }
    }

    /** The platform saw [client] go; a running exchange ends. */
    fun disconnected(client: String) {
        clients.remove(client)?.exchange?.closed = true
    }

    private fun start(c: Client, x: Exchange) {
        val wire = ExchangeWire(x)
        thread(name = "nearby-ble", isDaemon = true) {
            try {
                val first = wire.read(Ble.MAX_MESSAGE)
                when (val reply = Nearby.receive(first, wire)) {
                    null -> wire.write(Ble.no)
                    else -> if (reply.isNotEmpty()) wire.write(reply)
                }
            } catch (e: Exception) {
                if (!x.closed) Ble.log("Bluetooth exchange ended", e)
            } finally {
                // What is left to read still goes out; the next exchange replaces it.
                if (c.exchange === x && x.outgoing.isEmpty()) c.exchange = null
            }
        }
    }

    private class ExchangeWire(private val x: Exchange) : Wire {
        override var timeout = 30_000

        override fun read(max: Int): ByteArray {
            val until = System.currentTimeMillis() + timeout
            while (true) {
                x.incoming.poll(250, TimeUnit.MILLISECONDS)?.let {
                    if (it.size > max) throw IOException("A message of ${it.size} bytes")
                    return it
                }
                if (x.closed) throw EOFException("The other device left")
                if (System.currentTimeMillis() > until) throw SocketTimeoutException("Nothing over Bluetooth")
            }
        }

        override fun write(bytes: ByteArray) {
            if (x.closed) throw EOFException("The other device left")
            var at = 0
            do {
                val end = minOf(bytes.size, at + Ble.PIECE)
                val last = end == bytes.size
                x.outgoing.put(byteArrayOf(if (last) Ble.END else Ble.MORE) + bytes.copyOfRange(at, end))
                at = end
            } while (!last)
        }
    }
}
