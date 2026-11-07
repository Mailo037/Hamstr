package app.hamstr.cast

import app.hamstr.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Google Cast devices (Chromecast, Google TV, Nest speakers, TVs with Chromecast built in),
 * without Google's SDK: mDNS finds them ([Mdns]), and the Cast protocol (protobuf frames over TLS on
 * port 8009) starts the Default Media Receiver, which plays a link to the file.
 */
object Chromecast {
    private const val TAG = "Cast"

    internal const val SERVICE = "_googlecast._tcp.local"

    /** A Cast device from its mDNS records. */
    internal fun device(r: Mdns.Instance): CastDevice? {
        val t = r.txt
        val caps = t["ca"]?.toIntOrNull() ?: 5
        return CastDevice(
            id = "cast:" + (t["id"] ?: r.name),
            name = t["fn"]?.ifBlank { null } ?: r.label,
            protocol = CastProtocol.Chromecast,
            host = r.host, port = r.port.takeIf { it > 0 } ?: 8009,
            model = t["md"],
            video = caps and 1 != 0,
        )
    }

    /** One Cast device over its TLS connection. */
    class Link(private val device: CastDevice) : CastLink {
        private var socket: SSLSocket? = null
        private var out: OutputStream? = null
        private val ids = AtomicInteger(1)
        private val waiting = ConcurrentHashMap<Int, CompletableDeferred<JSONObject>>()
        private var transport: String? = null
        private var session: String? = null
        private var mediaSession: Int? = null
        private var volume: Float? = null
        private var duration = 0.0
        @Volatile private var closed = false

        override suspend fun connect() = withContext(Dispatchers.IO) {
            // Cast devices show a certificate of their own; the connection is only on this network.
            val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            })
            val ctx = SSLContext.getInstance("TLS").apply { init(null, trustAll, null) }
            val s = ctx.socketFactory.createSocket() as SSLSocket
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(device.host, device.port), 5000)
            s.startHandshake()
            socket = s
            out = s.outputStream
            send(RECEIVER, NS_CONNECTION, JSONObject().put("type", "CONNECT"))
            Thread({ readLoop(DataInputStream(s.inputStream)) }, "hamstr-cast-read").apply { isDaemon = true }.start()
            Thread({
                while (!closed) {
                    runCatching { send(RECEIVER, NS_HEARTBEAT, JSONObject().put("type", "PING")) }
                    Thread.sleep(5000)
                }
            }, "hamstr-cast-ping").apply { isDaemon = true }.start()
            val status = request(RECEIVER, NS_RECEIVER, JSONObject().put("type", "GET_STATUS"))
            readReceiver(status)
        }

        override suspend fun load(url: String, coverUrl: String?, media: CastMedia, at: Double, play: Boolean) {
            val app = transport ?: launch()
            val meta = JSONObject()
                .put("metadataType", if (media.video) 0 else 3)
                .put("title", media.title)
            media.artist?.let { meta.put("artist", it).put("subtitle", it) }
            coverUrl?.let { meta.put("images", JSONArray().put(JSONObject().put("url", it))) }
            val answer = request(
                app, NS_MEDIA,
                JSONObject().put("type", "LOAD")
                    .put("sessionId", session)
                    .put("autoplay", play)
                    .put("currentTime", at)
                    .put("media", JSONObject().put("contentId", url).put("contentUrl", url).put("streamType", "BUFFERED").put("contentType", media.mime).put("metadata", meta)),
                timeout = 20_000,
            )
            if (answer.optString("type") == "LOAD_FAILED" || answer.optString("type") == "LOAD_CANCELLED") throw java.io.IOException("The device couldn’t play the file")
            readMedia(answer)
        }

        private suspend fun launch(): String {
            val answer = request(RECEIVER, NS_RECEIVER, JSONObject().put("type", "LAUNCH").put("appId", APP), timeout = 20_000)
            readReceiver(answer)
            var app = transport
            // Some devices only answer once the app is up: ask again.
            repeat(10) {
                if (app != null) return@repeat
                kotlinx.coroutines.delay(500)
                readReceiver(request(RECEIVER, NS_RECEIVER, JSONObject().put("type", "GET_STATUS")))
                app = transport
            }
            val id = app ?: throw java.io.IOException("The device didn’t start its player")
            send(id, NS_CONNECTION, JSONObject().put("type", "CONNECT"))
            return id
        }

        override suspend fun play() = media("PLAY")
        override suspend fun pause() = media("PAUSE")
        override suspend fun seek(seconds: Double) = media("SEEK") { put("currentTime", seconds) }

        private suspend fun media(type: String, extra: JSONObject.() -> Unit = {}) {
            val app = transport ?: return
            val id = mediaSession ?: return
            readMedia(request(app, NS_MEDIA, JSONObject().put("type", type).put("mediaSessionId", id).apply(extra)))
        }

        override suspend fun setVolume(level: Float) {
            readReceiver(request(RECEIVER, NS_RECEIVER, JSONObject().put("type", "SET_VOLUME").put("volume", JSONObject().put("level", level.coerceIn(0f, 1f).toDouble()))))
        }

        override suspend fun status(): CastStatus {
            val app = transport ?: return CastStatus(volume = volume)
            val answer = request(app, NS_MEDIA, JSONObject().put("type", "GET_STATUS"))
            return readMedia(answer)
        }

        override suspend fun stop() {
            val s = session ?: return
            runCatching { request(RECEIVER, NS_RECEIVER, JSONObject().put("type", "STOP").put("sessionId", s)) }
            transport = null; session = null; mediaSession = null
        }

        override fun close() {
            closed = true
            runCatching { socket?.close() }
            waiting.values.forEach { it.cancel() }
        }

        private fun readReceiver(msg: JSONObject) {
            val status = msg.optJSONObject("status") ?: return
            status.optJSONObject("volume")?.let { v -> if (v.has("level")) volume = v.optDouble("level").toFloat() }
            val apps = status.optJSONArray("applications") ?: JSONArray()
            val ours = (0 until apps.length()).map { apps.getJSONObject(it) }.firstOrNull { it.optString("appId") == APP }
            if (ours != null) {
                transport = ours.optString("transportId").ifEmpty { null }
                session = ours.optString("sessionId").ifEmpty { null }
            } else {
                // Something else took the TV over (or it went back to its home screen).
                transport = null; session = null; mediaSession = null
            }
        }

        private fun readMedia(msg: JSONObject): CastStatus {
            val list = msg.optJSONArray("status")
            val s = if (list != null && list.length() > 0) list.getJSONObject(0) else return CastStatus(CastStatus.State.Idle, volume = volume)
            if (s.has("mediaSessionId")) mediaSession = s.getInt("mediaSessionId")
            s.optJSONObject("media")?.optDouble("duration")?.takeIf { !it.isNaN() && it > 0 }?.let { duration = it }
            val state = when (s.optString("playerState")) {
                "PLAYING" -> CastStatus.State.Playing
                "PAUSED" -> CastStatus.State.Paused
                "BUFFERING", "LOADING" -> CastStatus.State.Loading
                "IDLE" -> when (s.optString("idleReason")) {
                    "FINISHED" -> CastStatus.State.Finished
                    "ERROR" -> CastStatus.State.Failed
                    else -> CastStatus.State.Idle
                }
                else -> CastStatus.State.Idle
            }
            s.optJSONObject("volume")?.let { v -> if (v.has("level")) volume = v.optDouble("level").toFloat() }
            return CastStatus(state, s.optDouble("currentTime", 0.0), duration, volume)
        }

        private suspend fun request(to: String, ns: String, msg: JSONObject, timeout: Long = 8000): JSONObject {
            val id = ids.getAndIncrement()
            val wait = CompletableDeferred<JSONObject>()
            waiting[id] = wait
            try {
                withContext(Dispatchers.IO) { send(to, ns, msg.put("requestId", id)) }
                return withTimeout(timeout) { wait.await() }
            } finally {
                waiting.remove(id)
            }
        }

        @Synchronized
        private fun send(to: String, ns: String, msg: JSONObject) {
            val frame = encode(SENDER, to, ns, msg.toString())
            val o = out ?: throw java.io.IOException("not connected")
            o.write(byteArrayOf((frame.size ushr 24).toByte(), (frame.size ushr 16).toByte(), (frame.size ushr 8).toByte(), frame.size.toByte()))
            o.write(frame)
            o.flush()
        }

        private fun readLoop(input: DataInputStream) {
            try {
                while (!closed) {
                    val size = input.readInt()
                    if (size <= 0 || size > 1 shl 20) break
                    val frame = ByteArray(size).also(input::readFully)
                    val (ns, payload) = decode(frame) ?: continue
                    val msg = runCatching { JSONObject(payload) }.getOrNull() ?: continue
                    when {
                        ns == NS_HEARTBEAT && msg.optString("type") == "PING" -> runCatching { send(RECEIVER, NS_HEARTBEAT, JSONObject().put("type", "PONG")) }
                        msg.has("requestId") && msg.optInt("requestId") != 0 && waiting.containsKey(msg.optInt("requestId")) ->
                            waiting[msg.optInt("requestId")]?.complete(msg)
                        // Pushed without asking: keep up with the volume and the app.
                        ns == NS_RECEIVER -> readReceiver(msg)
                        ns == NS_MEDIA && msg.optString("type") == "MEDIA_STATUS" -> readMedia(msg)
                        ns == NS_CONNECTION && msg.optString("type") == "CLOSE" -> { transport = null; session = null; mediaSession = null }
                    }
                }
            } catch (e: Exception) {
                if (!closed) Log.d(TAG, "cast connection: ${e.message}")
            }
            waiting.values.forEach { it.completeExceptionally(java.io.IOException("connection closed")) }
        }
    }

    private const val APP = "CC1AD845" // Google's Default Media Receiver
    private const val SENDER = "sender-hamstr"
    private const val RECEIVER = "receiver-0"
    private const val NS_CONNECTION = "urn:x-cast:com.google.cast.tp.connection"
    private const val NS_HEARTBEAT = "urn:x-cast:com.google.cast.tp.heartbeat"
    private const val NS_RECEIVER = "urn:x-cast:com.google.cast.receiver"
    private const val NS_MEDIA = "urn:x-cast:com.google.cast.media"

    /** A CastMessage protobuf: version 0, source, destination, namespace, a string payload. */
    internal fun encode(source: String, destination: String, ns: String, payload: String): ByteArray {
        val out = ByteArrayOutputStream()
        fun varint(v: Long) { var x = v; while (true) { if (x and 0x7F.inv().toLong() == 0L) { out.write(x.toInt()); return }; out.write(((x and 0x7F) or 0x80).toInt()); x = x ushr 7 } }
        fun string(field: Int, s: String) { val b = s.toByteArray(Charsets.UTF_8); varint((field shl 3 or 2).toLong()); varint(b.size.toLong()); out.write(b) }
        varint(1 shl 3); varint(0)
        string(2, source); string(3, destination); string(4, ns)
        varint(5 shl 3); varint(0)
        string(6, payload)
        return out.toByteArray()
    }

    /** The namespace and the string payload of a CastMessage. */
    internal fun decode(b: ByteArray): Pair<String, String>? {
        var i = 0
        fun varint(): Long { var shift = 0; var r = 0L; while (true) { val x = b[i++].toInt() and 0xFF; r = r or ((x and 0x7F).toLong() shl shift); if (x and 0x80 == 0) return r; shift += 7 } }
        var ns: String? = null
        var payload: String? = null
        while (i < b.size) {
            val key = varint().toInt()
            when (key and 7) {
                0 -> varint()
                2 -> {
                    val len = varint().toInt()
                    val s = String(b, i, len, Charsets.UTF_8)
                    when (key ushr 3) { 4 -> ns = s; 6 -> payload = s }
                    i += len
                }
                1 -> i += 8
                5 -> i += 4
                else -> return null
            }
        }
        return if (ns != null && payload != null) ns!! to payload!! else null
    }
}
