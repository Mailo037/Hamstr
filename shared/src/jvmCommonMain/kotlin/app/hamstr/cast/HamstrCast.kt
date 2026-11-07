package app.hamstr.cast

import app.hamstr.Log
import app.hamstr.nearby.Nearby
import app.hamstr.nearby.Peer
import app.hamstr.nearby.Via
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * "Play on" another Hamstr: the phone plays on the computer and the computer on the phone, each
 * the other's remote. The sender's side is a [CastLink] like a TV's; the file goes the same way,
 * from [MediaServer] under a random path, and the receiver streams it into its own player, so
 * its own controls, notification and keys work there too. Commands are Nearby messages ([Nearby.command]):
 * sealed to the receiver, signed, answered by a signed receipt. The first time a device that isn't
 * trusted wants to play, the receiver asks ("Allow once" counts until the app quits).
 *
 * The receiver tells its app what to play through a [Stage]; when its user plays something else
 * or closes it, the next status says so and the sender's player takes over again, paused there.
 */
object HamstrCast {
    private const val TAG = "Cast"
    /** Cast ids of Hamstr apps: this, then the Nearby id. */
    const val PREFIX = "hamstr:"
    /** Media ids in this app's player of what another Hamstr plays here. */
    const val REMOTE = "remote:"
    private val random = SecureRandom()

    // ---- sending ------------------------------------------------------------------------

    /** The Hamstr apps on this network that play what this one sends, as Nearby hears them. */
    fun devices(): Flow<List<CastDevice>> = Nearby.peers.map { all ->
        all.values.filter { it.plays && Via.Wifi in it.via }.mapNotNull { p ->
            val host = Nearby.address(p.id)?.hostAddress ?: return@mapNotNull null
            CastDevice(PREFIX + p.id, p.name, CastProtocol.Hamstr, host, 0, video = true, hamstr = p.kind)
        }.sortedBy { it.id }
    }.distinctUntilChanged()

    class Link(private val device: CastDevice) : CastLink {
        private val session = token()

        private fun peer(): Peer = Nearby.peers.value[device.id.removePrefix(PREFIX)] ?: throw IOException("${device.name} is away")

        private suspend fun call(cmd: String, timeout: Int = 8_000, fill: JSONObject.() -> Unit = {}): JSONObject =
            Nearby.command(peer(), JSONObject().put("cmd", cmd).put("session", session).apply(fill), timeout)
                ?: throw IOException("${device.name} didn't take $cmd")

        // The other side may ask its user first; Nearby gives that two minutes.
        override suspend fun connect() { call("hello", timeout = 130_000) }

        override suspend fun load(url: String, coverUrl: String?, media: CastMedia, at: Double, play: Boolean) {
            call("load") {
                put("url", url).put("cover", coverUrl ?: "").put("media", media.id).put("title", media.title)
                    .put("artist", media.artist ?: "").put("mime", media.mime).put("video", media.video).put("at", at).put("play", play)
            }
        }

        override suspend fun play() { call("play") }
        override suspend fun pause() { call("pause") }
        override suspend fun seek(seconds: Double) { call("seek") { put("at", seconds) } }
        override suspend fun setVolume(level: Float) { call("volume") { put("level", level.toDouble()) } }

        override suspend fun status(): CastStatus {
            val r = call("status")
            if (r.optBoolean("x_ended")) throw CastLink.Released()
            return CastStatus(
                state = runCatching { CastStatus.State.valueOf(r.optString("x_state")) }.getOrDefault(CastStatus.State.Idle),
                position = r.optDouble("x_position", 0.0).takeIf { it.isFinite() } ?: 0.0,
                duration = r.optDouble("x_duration", 0.0).takeIf { it.isFinite() } ?: 0.0,
                volume = r.optDouble("x_volume", -1.0).takeIf { it in 0.0..1.0 }?.toFloat(),
            )
        }

        override suspend fun stop() { runCatching { call("stop", timeout = 3_000) } }
        override fun close() {}
    }

    // ---- receiving ----------------------------------------------------------------------

    /** Something another Hamstr plays here: a stream from its [MediaServer]. */
    data class Incoming(
        /** The sender's session; a new connect is a new one. */
        val session: String,
        val from: Peer,
        /** The sender's id for it, e.g. its download's. */
        val id: String,
        val url: String,
        val title: String,
        val artist: String?,
        val mime: String,
        val video: Boolean,
        /** A local file (fetched from the sender) or an https link. */
        val cover: String?,
        val at: Double,
        val play: Boolean,
    ) {
        /** Its media id in this app's player, never one of a download. */
        val key: String get() = "$REMOTE${from.id}:$id"
        /** The file's name at the sender, which tells its kind. */
        val name: String get() = runCatching { java.net.URLDecoder.decode(url.substringAfterLast('/').substringBefore('?'), "UTF-8") }.getOrDefault("stream")
    }

    /** What the app here does with what another Hamstr plays on it. Called off the main thread. */
    interface Stage {
        /** Plays [item], instead of whatever plays here. */
        fun load(item: Incoming)
        fun play()
        fun pause()
        fun seek(seconds: Double)
        /** 0..1, the device's media volume. */
        fun setVolume(level: Float)
        fun volume(): Float?
        /** How [item] is doing here; null once something else plays or the player was closed. */
        fun status(item: Incoming): CastStatus?
        /** The sender took it back: stop it here. */
        fun stop(item: Incoming)
    }

    @Volatile private var stage: Stage? = null

    private val _incoming = MutableStateFlow<Incoming?>(null)
    /** What another Hamstr plays here now. */
    val incoming: StateFlow<Incoming?> = _incoming.asStateFlow()

    // Devices "Allow once" let play, until the app quits.
    private val allowed = ConcurrentHashMap.newKeySet<String>()
    // When the sender of what plays here was last heard; it asks every second or two.
    @Volatile private var heard = 0L
    private var watch: Job? = null

    /** Lets other Hamstr apps play here, through [stage]. */
    @Synchronized
    fun install(stage: Stage) {
        this.stage = stage
        Nearby.onPlay = ::handle
        // A sender gone quiet (switched off, out of the network) leaves what plays here to this device.
        if (watch == null) watch = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            while (isActive) {
                delay(15_000)
                val now = _incoming.value
                if (now != null && System.currentTimeMillis() - heard > 45_000) _incoming.compareAndSet(now, null)
            }
        }
    }

    /** The user here played something else or closed it: the sender hears so on its next look. */
    fun release(item: Incoming? = _incoming.value) {
        if (item != null) _incoming.compareAndSet(item, null)
    }

    internal fun handle(from: Peer, m: JSONObject): JSONObject? {
        val stage = stage ?: return null
        val session = m.optString("session").takeIf { it.length in 8..64 } ?: return null
        val cmd = m.optString("cmd")
        if (cmd == "hello") {
            val ok = Nearby.trusted(from.id) || from.id in allowed || Nearby.askToPlay(from, null).also { if (it) allowed += from.id }
            return if (ok) JSONObject().put("volume", stage.volume()?.toDouble() ?: -1.0) else null
        }
        if (!Nearby.trusted(from.id) && from.id !in allowed) return null
        val now = _incoming.value?.takeIf { it.session == session && it.from.id == from.id }
        if (now != null || cmd == "load") heard = System.currentTimeMillis()
        val done = JSONObject()
        return when (cmd) {
            "load" -> {
                val url = m.optString("url").takeIf(::onThisNetwork) ?: return null
                val item = Incoming(
                    session, from, m.optString("media").take(200), url,
                    m.optString("title").take(300).ifBlank { "Hamstr" }, m.optString("artist").take(200).ifBlank { null },
                    m.optString("mime").take(100), m.optBoolean("video"), cover(m.optString("cover")),
                    m.optDouble("at", 0.0).takeIf { it.isFinite() }?.coerceAtLeast(0.0) ?: 0.0, m.optBoolean("play", true),
                )
                Log.i(TAG, "${from.name} plays ${item.name} here")
                _incoming.value = item
                stage.load(item)
                done
            }
            "play" -> now?.let { stage.play(); done } ?: ended()
            "pause" -> now?.let { stage.pause(); done } ?: ended()
            "seek" -> now?.let { stage.seek(m.optDouble("at", 0.0).takeIf { it.isFinite() }?.coerceAtLeast(0.0) ?: 0.0); done } ?: ended()
            "volume" -> {
                m.optDouble("level", -1.0).takeIf { it in 0.0..1.0 }?.let { stage.setVolume(it.toFloat()) }
                done
            }
            "status" -> {
                val s = now?.let(stage::status)
                if (s == null) {
                    if (now != null) _incoming.compareAndSet(now, null)
                    ended()
                } else JSONObject().put("state", s.state.name).put("position", s.positionNow()).put("duration", s.duration).put("volume", s.volume?.toDouble() ?: -1.0)
            }
            "stop" -> {
                if (now != null && _incoming.compareAndSet(now, null)) stage.stop(now)
                done
            }
            else -> null
        }
    }

    private fun ended() = JSONObject().put("ended", true)

    /** Only a stream from a device on this network: an http link to a private address, no names to look up. */
    fun onThisNetwork(url: String): Boolean = runCatching {
        val u = URI(url)
        if (u.scheme != "http" || u.rawUserInfo != null) return false
        val host = u.host?.removePrefix("[")?.removeSuffix("]") ?: return false
        if (!host.matches(Regex("""[0-9.]+|[0-9a-fA-F:]+(%\w+)?"""))) return false
        val a = InetAddress.getByName(host)
        a.isSiteLocalAddress || a.isLinkLocalAddress || a.isLoopbackAddress ||
            (a is Inet6Address && (a.address[0].toInt() and 0xfe) == 0xfc)
    }.getOrDefault(false)

    /** The sender's cover, fetched into the cache (this side can't open plain http everywhere), or its https link. */
    private fun cover(url: String): String? {
        if (url.startsWith("https://")) return url
        if (!onThisNetwork(url)) return null
        val dir = Cast.cache?.let { File(it, "covers") } ?: return null
        return runCatching {
            val r = LanHttp.request(url, timeout = 3_000)
            if (r.code != 200 || r.body.isEmpty() || r.body.size > 8 * 1024 * 1024) return null
            dir.mkdirs()
            dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(30)?.forEach { it.delete() }
            val png = r.body.size > 4 && r.body[1] == 'P'.code.toByte() && r.body[2] == 'N'.code.toByte()
            val name = MessageDigest.getInstance("SHA-1").digest(r.body).take(10).joinToString("") { "%02x".format(it) }
            File(dir, "$name.${if (png) "png" else "jpg"}").apply { if (!isFile) writeBytes(r.body) }.path
        }.onFailure { Log.d(TAG, "cover: ${it.message}") }.getOrNull()
    }

    private fun token() = ByteArray(12).also(random::nextBytes).joinToString("") { "%02x".format(it) }
}
