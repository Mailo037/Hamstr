package app.hamstr.nearby

import app.hamstr.Log
import app.hamstr.data.AtomicJson
import app.hamstr.data.DownloadStore
import app.hamstr.data.KeyValues
import app.hamstr.data.Prefs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.UUID

enum class DeviceKind { Phone, Desktop }

/** How a device is reachable right now. */
enum class Via { Wifi, Bluetooth }

/**
 * Another Hamstr. Its id is the start of its public key's hash, so a device can't pass for
 * another one: every message is signed with the key behind the id.
 */
data class Peer(
    val id: String,
    val name: String,
    val kind: DeviceKind,
    val key: String,
    /** The last time a transport heard it; 0 for a remembered device not seen since start. */
    val seen: Long = 0,
    val via: Set<Via> = emptySet(),
    /** Its links go straight into the queue, without asking. */
    val trusted: Boolean = false,
    /** It plays what another Hamstr sends it ("Play on"), from 0.10.11 on. */
    val plays: Boolean = false,
) {
    val online get() = via.isNotEmpty()
}

/** A file another device offers. */
data class Offer(val name: String, val size: Long)

/**
 * A link (or, with [files], some files) another device wants to send, waiting for "Accept"
 * because the sender isn't trusted yet. With [play] it wants to play something here instead.
 */
data class Request(val id: String, val from: Peer, val url: String, val title: String?, val files: List<Offer> = emptyList(), val play: Boolean = false)

/** What happened to a link or files sent with [Nearby.send] or [Nearby.sendFiles]. */
enum class SendResult { Sent, Queued, Failed, Declined }

/** One connection to another device: whole messages, each way, until it closes. */
interface Wire {
    fun read(max: Int): ByteArray
    fun write(bytes: ByteArray)
    /** How long a [read] may wait, in milliseconds. */
    var timeout: Int
}

/** Something that finds devices and carries messages: Wi-Fi on both apps, Bluetooth on the phone. */
interface Transport {
    val via: Via
    fun start(scope: CoroutineScope)
    fun stop()
    /** Look harder for others, e.g. while the send sheet is open. */
    fun discover(on: Boolean) {}
    fun reaches(id: String): Boolean
    /**
     * Hands [message] to device [id] and passes its reply to [accept]. Places that answer
     * with something [accept] refuses (anyone can claim to be [id] on a network) don't count:
     * the next place it was heard at is tried.
     */
    suspend fun send(id: String, message: ByteArray, accept: (ByteArray) -> Boolean): Boolean
}

/** A transport that can also hold a connection open for more than one message: files. */
interface StreamTransport : Transport {
    /** The largest piece of a file per frame: smaller where the bytes go slower. */
    val chunk: Int get() = 256 * 1024
    /** Runs [block] on a connection to [id], trying each place it was heard at until [block] returns true. */
    suspend fun exchange(id: String, block: (Wire) -> Boolean): Boolean
}

/**
 * Sending links and files between Hamstr apps nearby, like AirDrop: devices on the same Wi-Fi
 * or in Bluetooth range show up by themselves. The first link from a device asks; "Always
 * allow" remembers it. A link for a device that is away waits in the outbox until it shows up.
 *
 * Who a device is gets proven, not claimed: its beacons are signed, a message is sealed to the
 * receiver's key so nobody else on the network can read it, and it only counts as delivered
 * when the receiver signs for it. Messages name their receiver and expire, so one can't be
 * played again to another device.
 */
object Nearby {
    private const val TAG = "Nearby"
    private const val GONE_AFTER = 15_000L
    /** How old a signed beacon or message may be; also how long nonces are remembered. */
    internal const val MAX_AGE = 10 * 60_000L
    private const val PROTOCOL = 2
    /** The largest piece of a file per frame. */
    private const val CHUNK = 256 * 1024
    private const val MAX_REPLY = 16 * 1024
    /** How long a device that isn't trusted yet waits for "Accept" on files. */
    private const val ASK_FOR = 120_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var store: KeyValues
    private lateinit var file: AtomicJson
    private lateinit var keys: KeyPair
    /** The device's own name, when the user didn't give it one. */
    lateinit var defaultName: String
        private set
    lateinit var id: String
        private set
    lateinit var kind: DeviceKind
        private set
    val publicKey: String get() = b64(keys.public.encoded)
    val name: String get() = Prefs.settings.value.deviceName.ifBlank { defaultName }

    /** Where files from other devices land until they are complete; the app moves them on. */
    @Volatile var inbox: File? = null

    private var transports: List<Transport> = emptyList()
    private var available = true
    private var running = false
    private var sweeper: Job? = null

    /** Called with a link to stash, from a trusted device or after "Accept". */
    var onLink: (url: String, title: String?, from: Peer) -> Unit = { _, _, _ -> }
    /** Called only after all file bytes and negotiated metadata arrived complete. */
    var onFiles: (files: List<ReceivedFile>, from: Peer) -> Unit = { files, _ -> files.forEach { it.delete() } }
    /** Called when an untrusted device asks; the app tells the user (a notification, the window). */
    var onRequest: (Request) -> Unit = {}
    /**
     * Called with a "Play on" command from another Hamstr ([app.hamstr.cast.HamstrCast]); what it
     * returns goes back in the signed receipt, null refuses.
     */
    var onPlay: (from: Peer, m: JSONObject) -> JSONObject? = { _, _ -> null }

    private val _peers = MutableStateFlow<Map<String, Peer>>(emptyMap())
    /** Devices around now first, then remembered ones that are away. */
    val peers: StateFlow<Map<String, Peer>> = _peers.asStateFlow()

    private val _requests = MutableStateFlow<List<Request>>(emptyList())
    val requests: StateFlow<List<Request>> = _requests.asStateFlow()

    private val _outbox = MutableStateFlow<List<Outgoing>>(emptyList())
    /** Links waiting for a device that is away. */
    val outbox: StateFlow<List<Outgoing>> = _outbox.asStateFlow()

    private val _known = MutableStateFlow<List<Peer>>(emptyList())
    /** Remembered devices: sent to, or accepted from. */
    val known: StateFlow<List<Peer>> = _known.asStateFlow()

    data class Outgoing(val to: String, val url: String, val title: String?, val at: Long)

    // Nonces seen in the last MAX_AGE, oldest first.
    private val nonces = LinkedHashMap<String, Long>()
    // Answers to file offers that wait for the user, by request id.
    private val decisions = HashMap<String, CompletableDeferred<Boolean>>()
    private val saves = Channel<Unit>(Channel.CONFLATED)
    private val random = SecureRandom()

    fun init(store: KeyValues, dir: File, kind: DeviceKind, defaultName: String, transports: List<Transport>, initiallyAvailable: Boolean = true) {
        available = initiallyAvailable
        this.store = store
        this.kind = kind
        this.defaultName = defaultName
        this.transports = transports
        if (inbox == null) inbox = File(dir, "nearby-inbox")
        file = AtomicJson(File(dir, "nearby.json"))
        keys = loadKeys()
        id = idOf(keys.public.encoded)
        load()
        // One writer, off the window's thread: devices are remembered from every transport at once.
        scope.launch { for (ignored in saves) writeNow() }
        scope.launch {
            Prefs.settings.map { it.nearby }.distinctUntilChanged().collect { updateAvailability() }
        }
    }

    /** Suspend radios when the host is in the background, without changing the user's setting. */
    @Synchronized
    fun setAvailable(value: Boolean) {
        available = value
        updateAvailability()
    }

    @Synchronized
    private fun updateAvailability() {
        if (available && Prefs.settings.value.nearby) start() else stop()
    }

    private fun start() {
        if (running) return
        running = true
        transports.forEach { t -> runCatching { t.start(scope) }.onFailure { Log.w(TAG, "${t.via} didn't start", it) } }
        sweeper = scope.launch {
            while (true) {
                delay(3_000)
                val now = System.currentTimeMillis()
                _peers.update { all ->
                    all.mapValues { (_, p) -> if (p.online && now - p.seen > GONE_AFTER) p.copy(via = emptySet()) else p }
                        .filter { (_, p) -> p.online || remembered(p.id) }
                }
            }
        }
    }

    private fun stop() {
        if (!running) return
        running = false
        sweeper?.cancel()
        transports.forEach { runCatching { it.stop() } }
        _peers.update { all -> all.mapValues { it.value.copy(via = emptySet()) }.filter { remembered(it.key) } }
    }

    /** Start or stop looking harder, while the send sheet is open. */
    fun discover(on: Boolean) = transports.forEach { runCatching { it.discover(on) } }

    // Remembered devices: the ones sent to or accepted from, kept in nearby.json.
    private val devices = mutableMapOf<String, Peer>()

    private fun remembered(id: String) = synchronized(devices) { id in devices }

    /** Whether files can go to [peer] now: over the shared network, or slower over Bluetooth. */
    fun takesFiles(peer: Peer) = transports.any { it is StreamTransport && it.reaches(peer.id) }

    /** Whether files to [peer] would have to go over Bluetooth, because there is no shared network. */
    fun filesOverBluetooth(peer: Peer) = transports.filterIsInstance<StreamTransport>().filter { it.reaches(peer.id) }.let { ts ->
        ts.isNotEmpty() && ts.none { it.via == Via.Wifi }
    }

    /**
     * A transport heard a device whose [hello] checked out. Its waiting links go out now.
     * [plays] is what its network beacon says; Bluetooth's hello doesn't say, so it keeps the last.
     */
    fun seen(id: String, name: String, kind: DeviceKind, key: String, via: Via, plays: Boolean? = null) {
        if (id == this.id || idOf(unb64(key) ?: return) != id) return
        val now = System.currentTimeMillis()
        var fresh = false
        _peers.update { all ->
            val old = all[id]
            fresh = old == null || via !in old.via
            all + (id to Peer(id, name, kind, key, now, (old?.via ?: emptySet()) + via, trusted = trusted(id), plays = plays ?: old?.plays ?: false))
        }
        synchronized(devices) {
            devices[id]?.let { k -> if (k.name != name) { devices[id] = k.copy(name = name); save() } }
        }
        if (fresh && _outbox.value.any { it.to == id }) scope.launch { flush(id) }
    }

    /** A transport lost a device, e.g. its Hamstr said goodbye; it's away without waiting for the timeout. */
    fun gone(id: String, via: Via) {
        _peers.update { all ->
            val p = all[id] ?: return@update all
            val rest = p.copy(via = p.via - via)
            if (rest.online || remembered(id)) all + (id to rest) else all - id
        }
    }

    /** Sends a link; if the device is away or doesn't answer, it waits in the outbox. */
    // Runs in Nearby's own scope, so closing the sheet (or the share window) doesn't cut it off.
    suspend fun send(peer: Peer, url: String, title: String?): SendResult = scope.async {
        remember(peer)
        if (deliver(peer.id, peer.key, url, title)) return@async SendResult.Sent
        _outbox.update { it + Outgoing(peer.id, url, title, System.currentTimeMillis()) }
        save()
        SendResult.Queued
    }.await()

    private suspend fun deliver(to: String, key: String, url: String, title: String?): Boolean {
        val publicKey = Seal.publicKey(key) ?: return false
        val nonce = nonce()
        val message = Seal.seal(publicKey, sign(JSONObject().put("t", "link").put("to", to).put("url", url).put("title", title ?: ""), nonce)).bytes
        for (t in transports.sortedBy { it.via.ordinal }) {
            if (!t.reaches(to)) continue
            val ok = runCatching { t.send(to, message) { reply -> receipt(reply, to, nonce) != null } }
                .onFailure { Log.w(TAG, "${t.via} send failed", it) }.getOrDefault(false)
            if (ok) return true
        }
        return false
    }

    /** Where [id] was last heard on the shared network, for "Play on". */
    fun address(id: String): java.net.InetAddress? = transports.filterIsInstance<LanTransport>().firstNotNullOfOrNull { it.address(id) }

    /**
     * A "Play on" command for [peer], over the shared network only: sealed and signed like a link,
     * answered by a signed receipt that carries the reply's fields (as `x_<name>`). Null when it
     * didn't arrive or was refused. [timeout] is how long the other side may take (it may ask its
     * user first).
     */
    suspend fun command(peer: Peer, body: JSONObject, timeout: Int = 8_000): JSONObject? = scope.async {
        val lan = transports.filterIsInstance<StreamTransport>().firstOrNull { it.via == Via.Wifi && it.reaches(peer.id) } ?: return@async null
        val publicKey = Seal.publicKey(peer.key) ?: return@async null
        val nonce = nonce()
        val message = Seal.seal(publicKey, sign(body.put("t", "play").put("to", peer.id), nonce)).bytes
        var answer: JSONObject? = null
        runCatching {
            lan.exchange(peer.id) { wire ->
                wire.write(message)
                wire.timeout = timeout
                answer = receipt(wire.read(MAX_REPLY), peer.id, nonce) ?: return@exchange false
                true
            }
        }.onFailure { Log.d(TAG, "Play command to ${peer.name} failed", it) }
        answer?.takeIf { it.optBoolean("ok") }
    }.await()

    /** Asks the user whether [from] may play [title] here, for up to two minutes; "Always allow" trusts it. Blocks. */
    fun askToPlay(from: Peer, title: String?): Boolean {
        val r = Request(UUID.randomUUID().toString(), from, "", title, play = true)
        val decision = CompletableDeferred<Boolean>()
        synchronized(decisions) { decisions[r.id] = decision }
        _requests.update { it + r }
        onRequest(r)
        val answer = runBlocking { withTimeoutOrNull(ASK_FOR) { decision.await() } } ?: false
        synchronized(decisions) { decisions.remove(r.id) }
        _requests.update { list -> list.filterNot { it.id == r.id } }
        return answer
    }

    /**
     * Sends [files] over the shared network, or over Bluetooth when there is none. A device that
     * doesn't trust this one yet asks first; [progress] runs from 0 to 1 while the bytes go.
     */
    suspend fun sendFiles(peer: Peer, files: List<File>, progress: (Float) -> Unit = {}): SendResult = scope.async {
        val lan = transports.filterIsInstance<StreamTransport>().sortedBy { it.via.ordinal }.firstOrNull { it.reaches(peer.id) } ?: return@async SendResult.Failed
        val publicKey = Seal.publicKey(peer.key) ?: return@async SendResult.Failed
        val present = files.filter { it.isFile }
        if (present.isEmpty()) return@async SendResult.Failed
        remember(peer)
        val nonce = nonce()
        val sizes = present.map { it.length() }
        // The list is newest first. Duplicate entries for one file must not pick older details;
        // File keys also respect the platform's path case rules (unlike String keys on Windows).
        val tasks = DownloadStore.tasks.value.asReversed().filter { it.filePath != null }.associateBy { File(it.filePath!!).absoluteFile.normalize() }
        val list = JSONArray(present.mapIndexed { i, f -> JSONObject().put("name", f.name).put("size", sizes[i]) })
        // The list goes in as text: a signature has to cover exactly what was sent.
        // Old receivers ignore metadata; only a signed opt-in adds attachment streams.
        val sealed = Seal.seal(publicKey, sign(JSONObject().put("t", "files").put("to", peer.id).put("files", list.toString()).put("metadata", 1), nonce))
        if (sealed.bytes.size > Ble.MAX_MESSAGE) return@async SendResult.Failed
        val total = sizes.sum().coerceAtLeast(1)
        var result = SendResult.Failed
        runCatching {
            lan.exchange(peer.id) { wire ->
                wire.write(sealed.bytes)
                wire.timeout = (ASK_FOR + 10_000).toInt()
                val answer = receipt(wire.read(MAX_REPLY), peer.id, nonce) ?: return@exchange false
                if (!answer.optBoolean("ok")) {
                    result = SendResult.Declined
                    return@exchange true
                }
                wire.timeout = 30_000
                val frames = Frames(sealed.dataKey)
                val chunk = lan.chunk.coerceIn(1024, CHUNK)
                val buf = ByteArray(chunk)
                var sent = 0L
                for ((i, f) in present.withIndex()) {
                    var left = sizes[i]
                    f.inputStream().use { input ->
                        do {
                            val wanted = minOf(chunk.toLong(), left).toInt()
                            val n = fill(input, buf, wanted)
                            check(n == wanted) { "File changed while sending" }
                            left -= n
                            // Check EOF before ending the stream: the receiver may adopt it as soon
                            // as it sees the last frame, even if this side then discovers extra bytes.
                            if (left == 0L) check(input.read() == -1) { "File changed while sending" }
                            wire.write(frames.seal(buf, n, last = left == 0L))
                            sent += n
                            progress((sent.toFloat() / total).coerceIn(0f, 1f))
                        } while (left > 0)
                    }
                    if (answer.optLong("x_metadata") == 1L) {
                        val attachment = FileAttachment.encode(tasks[f.absoluteFile.normalize()])
                        var at = 0
                        do {
                            val n = minOf(chunk, attachment.size - at)
                            wire.write(frames.seal(attachment.copyOfRange(at, at + n), n, last = at + n == attachment.size))
                            at += n
                        } while (at < attachment.size)
                    }
                }
                wire.timeout = 60_000
                val done = receipt(wire.read(MAX_REPLY), peer.id, nonce)
                result = if (done?.optBoolean("ok") == true && done.optBoolean("done")) SendResult.Sent else SendResult.Failed
                true
            }
        }.onFailure { Log.w(TAG, "Sending files failed", it) }
        result
    }.await()

    /** Reads up to [n] bytes, as many as there are. */
    private fun fill(input: java.io.InputStream, buf: ByteArray, n: Int): Int {
        var got = 0
        while (got < n) {
            val r = input.read(buf, got, n - got)
            if (r < 0) break
            got += r
        }
        return got
    }

    private suspend fun flush(to: String) {
        val key = synchronized(devices) { devices[to]?.key } ?: _peers.value[to]?.key ?: return
        for (o in _outbox.value.filter { it.to == to }) {
            if (!deliver(o.to, key, o.url, o.title)) return
            _outbox.update { it - o }
            save()
        }
    }

    /** Takes a link back out of the outbox. */
    fun unsend(o: Outgoing) {
        _outbox.update { it - o }
        save()
    }

    /**
     * A transport received a message: returns the signed receipt to send back, or null when
     * it isn't a valid message for this device. Files need [wire], the connection they come
     * on; for those the answers go over it here and an empty receipt comes back.
     */
    fun receive(bytes: ByteArray, wire: Wire? = null): ByteArray? {
        val opened = Seal.open(keys.private, bytes) ?: return null
        val m = runCatching { JSONObject(String(opened.plain, Charsets.UTF_8)) }.getOrNull() ?: return null
        val from = verify(m) ?: return null
        // For this device, now, and only once.
        if (m.optString("to") != id || !fresh(m.optString("n"), m.optLong("ts"))) return null
        val peer = _peers.value[from.id]?.copy(name = from.name) ?: from
        return when (m.optString("t")) {
            "link" -> {
                val url = m.optString("url").takeIf { it.startsWith("http://") || it.startsWith("https://") } ?: return null
                val title = m.optString("title").ifBlank { null }
                if (trusted(from.id)) {
                    Log.i(TAG, "Link from ${from.name}")
                    onLink(url, title, peer)
                } else {
                    val r = Request(UUID.randomUUID().toString(), peer, url, title)
                    _requests.update { it + r }
                    onRequest(r)
                }
                receipt(from.id, m.optString("n"), ok = true)
            }
            "files" -> {
                if (wire == null) return receipt(from.id, m.optString("n"), ok = false)
                takeFiles(peer, m, opened.dataKey, wire)
                ByteArray(0)
            }
            "play" -> {
                val reply = runCatching { onPlay(peer, m) }.onFailure { Log.w(TAG, "Play command from ${from.name}", it) }.getOrNull()
                receipt(from.id, m.optString("n"), ok = reply != null, extra = reply)
            }
            else -> null
        }
    }

    /** The files of an offer: asks when the sender isn't trusted, then reads them into the inbox. */
    private fun takeFiles(from: Peer, m: JSONObject, dataKey: ByteArray, wire: Wire) {
        val nonce = m.optString("n")
        val offers = runCatching {
            val a = JSONArray(m.getString("files"))
            List(a.length()) { i -> a.getJSONObject(i).let { Offer(safeName(it.getString("name")), it.getLong("size")) } }
        }.getOrNull()?.takeIf { list -> list.isNotEmpty() && list.all { it.size >= 0 } }
        val dir = inbox
        val metadata = m.optLong("metadata") == 1L
        val total = runCatching { offers?.fold(0L) { sum, offer -> Math.addExact(sum, Math.addExact(offer.size, if (metadata) FileAttachment.MAX_COVER.toLong() else 0L)) } }.getOrNull()
        if (offers == null || total == null || dir == null || !dir.apply { mkdirs() }.isDirectory || total > (dir.usableSpace - 64L * 1024 * 1024).coerceAtLeast(0)) {
            wire.write(receipt(from.id, nonce, ok = false))
            return
        }
        val accepted = trusted(from.id) || run {
            val r = Request(UUID.randomUUID().toString(), from, "", null, offers)
            val decision = CompletableDeferred<Boolean>()
            synchronized(decisions) { decisions[r.id] = decision }
            _requests.update { it + r }
            onRequest(r)
            val answer = runBlocking { withTimeoutOrNull(ASK_FOR) { decision.await() } } ?: false
            synchronized(decisions) { decisions.remove(r.id) }
            _requests.update { list -> list.filterNot { it.id == r.id } }
            answer
        }
        wire.write(receipt(from.id, nonce, ok = accepted, extra = if (metadata) JSONObject().put("metadata", 1) else null))
        if (!accepted) return
        val frames = Frames(dataKey)
        val parts = mutableListOf<ReceivedFile>()
        try {
            wire.timeout = 30_000
            for (offer in offers) {
                val part = File(dir, "${UUID.randomUUID()}.part")
                parts += ReceivedFile(part, offer.name)
                var got = 0L
                part.outputStream().buffered(CHUNK).use { out ->
                    while (true) {
                        val (data, last) = frames.open(wire.read(CHUNK + 64))
                        check(data.size.toLong() <= offer.size - got) { "More than offered" }
                        got += data.size
                        out.write(data)
                        if (last) break
                    }
                }
                check(got == offer.size) { "Cut short" }
                if (metadata) {
                    val attachment = java.io.ByteArrayOutputStream()
                    while (true) {
                        val (data, last) = frames.open(wire.read(CHUNK + 64))
                        check(data.size <= FileAttachment.MAX_BYTES - attachment.size()) { "Metadata too large" }
                        attachment.write(data)
                        if (last) break
                    }
                    parts[parts.lastIndex] = FileAttachment.decode(attachment.toByteArray(), parts.last(), dir)
                }
            }
            wire.write(receipt(from.id, nonce, ok = true, done = true))
        } catch (e: Exception) {
            Log.w(TAG, "Files from ${from.name} didn't arrive", e)
            parts.forEach { it.delete() }
            return
        }
        onFiles(parts, from)
    }

    /** A sender's name for a file, without folders or characters a file system refuses. */
    internal fun safeName(name: String): String =
        name.substringAfterLast('/').substringAfterLast('\\').replace(Regex("""[\x00-\x1f:*?"<>|]"""), "_")
            .trim().trimStart('.').take(180).ifBlank { "file" }

    /** The user answered a request: [always] trusts the device from now on. */
    fun answer(r: Request, accept: Boolean, always: Boolean = false) {
        _requests.update { list -> list.filterNot { it.id == r.id } }
        if (accept && always) trust(r.from, true) else if (accept) remember(r.from)
        if (r.files.isNotEmpty() || r.play) {
            synchronized(decisions) { decisions[r.id] }?.complete(accept)
            return
        }
        if (accept) onLink(r.url, r.title, r.from)
    }

    fun trusted(id: String) = synchronized(devices) { devices[id]?.trusted == true }

    fun trust(p: Peer, on: Boolean) {
        synchronized(devices) { devices[p.id] = (devices[p.id] ?: p.copy(seen = 0, via = emptySet())).copy(trusted = on) }
        _peers.update { all -> all + (p.id to (all[p.id] ?: p).copy(trusted = on)) }
        save()
    }

    /** Forgets a device: it asks again next time and its waiting links are dropped. */
    fun forget(id: String) {
        synchronized(devices) { devices.remove(id) }
        _outbox.update { list -> list.filterNot { it.to == id } }
        _peers.update { all -> all[id]?.let { p -> if (p.online) all + (id to p.copy(trusted = false)) else all - id } ?: all }
        save()
    }

    private fun remember(p: Peer) {
        synchronized(devices) {
            if (p.id in devices) return
            devices[p.id] = p.copy(seen = 0, via = emptySet(), trusted = false)
        }
        _peers.update { all -> if (p.id in all) all else all + (p.id to p) }
        save()
    }

    /**
     * Who this device is, for beacons and Bluetooth: id, name, kind and public key, signed.
     * [port] is where links go on the network; [bye] says it is leaving.
     */
    fun hello(port: Int? = null, bye: Boolean = false): JSONObject = sign(JSONObject().put("hamstr", PROTOCOL).put("t", "hello").apply {
        // "play": it takes "Play on" from other Hamstr apps; older ones don't look at it.
        if (port != null) put("port", port).put("play", 1)
        if (bye) put("bye", true)
    })

    /** The device a beacon or Bluetooth hello is from, when its signature holds and it is recent. */
    fun checkHello(m: JSONObject): Peer? = if (m.optInt("hamstr") == PROTOCOL && m.optString("t") == "hello") verify(m) else null

    // --- Signing -------------------------------------------------------------------------

    private fun nonce() = b64(ByteArray(12).also(random::nextBytes))

    private fun sign(body: JSONObject, nonce: String = nonce()): JSONObject {
        body.put("v", PROTOCOL).put("id", id).put("name", name).put("kind", kind.name).put("key", publicKey)
            .put("ts", System.currentTimeMillis()).put("n", nonce)
        val sig = Signature.getInstance("SHA256withECDSA").run {
            initSign(keys.private)
            update(signed(body))
            sign()
        }
        return body.put("sig", b64(sig))
    }

    private fun JSONObject.bytes() = toString().toByteArray(Charsets.UTF_8)

    private fun Seal.seal(to: java.security.PublicKey, m: JSONObject) = seal(to, m.bytes())

    /** Every field but the signature, sorted, so both sides sign the same bytes whatever their JSON library's order. */
    internal fun signed(m: JSONObject) = m.keys().asSequence().filter { it != "sig" }.sorted()
        .joinToString("\n") { "$it=${m.get(it)}" }.toByteArray(Charsets.UTF_8)

    /** The sender of a signed message, when its key matches its id, the signature holds and it is recent. */
    private fun verify(m: JSONObject): Peer? {
        val key = unb64(m.optString("key")) ?: return null
        val id = m.optString("id")
        if (idOf(key) != id || id == this.id) return null
        if (kotlin.math.abs(System.currentTimeMillis() - m.optLong("ts")) > MAX_AGE) return null
        val ok = runCatching {
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(key)))
                update(signed(m))
                verify(unb64(m.optString("sig")) ?: return null)
            }
        }.getOrDefault(false)
        if (!ok) return null
        val kind = runCatching { DeviceKind.valueOf(m.optString("kind")) }.getOrDefault(DeviceKind.Phone)
        return Peer(id, m.optString("name").take(60).ifBlank { "Hamstr" }, kind, m.optString("key"), plays = m.optInt("play") >= 1)
    }

    /** True the first time a nonce shows up within [MAX_AGE]. */
    private fun fresh(nonce: String, ts: Long): Boolean {
        if (nonce.isEmpty()) return false
        val now = System.currentTimeMillis()
        synchronized(nonces) {
            val it = nonces.entries.iterator()
            while (it.hasNext()) { if (now - it.next().value > 2 * MAX_AGE) it.remove() else break }
            if (nonce in nonces) return false
            nonces[nonce] = now
            if (nonces.size > 5_000) nonces.remove(nonces.keys.first())
        }
        return kotlin.math.abs(now - ts) <= MAX_AGE
    }

    /** This device's signed answer to message [nonce] from [to]. */
    private fun receipt(to: String, nonce: String, ok: Boolean, done: Boolean = false, extra: JSONObject? = null): ByteArray =
        sign(JSONObject().apply { extra?.keys()?.forEach { k -> put("x_$k", extra.get(k)) } }
            .put("t", "ack").put("to", to).put("re", nonce).put("ok", ok).apply { if (done) put("done", true) }).bytes()

    /** The receipt in [reply], when [from] signed it for this device's message [nonce]. */
    private fun receipt(reply: ByteArray, from: String, nonce: String): JSONObject? {
        val m = runCatching { JSONObject(String(reply, Charsets.UTF_8)) }.getOrNull() ?: return null
        val signer = verify(m) ?: return null
        if (signer.id != from || m.optString("t") != "ack" || m.optString("to") != id || m.optString("re") != nonce) return null
        return m
    }

    private fun loadKeys(): KeyPair {
        val f = KeyFactory.getInstance("EC")
        val saved = runCatching {
            KeyPair(
                f.generatePublic(X509EncodedKeySpec(unb64(store.getString("nearby.public", null)!!)!!)),
                f.generatePrivate(PKCS8EncodedKeySpec(unb64(store.getString("nearby.private", null)!!)!!)),
            )
        }.getOrNull()
        if (saved != null) return saved
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        store.edit().putString("nearby.public", b64(pair.public.encoded)).putString("nearby.private", b64(pair.private.encoded)).apply()
        return pair
    }

    // --- nearby.json ---------------------------------------------------------------------

    private fun load() {
        val root = runCatching { JSONObject(file.read()) }.getOrNull() ?: return
        val list = root.optJSONArray("devices") ?: JSONArray()
        synchronized(devices) {
            for (i in 0 until list.length()) {
                val d = list.optJSONObject(i) ?: continue
                val kind = runCatching { DeviceKind.valueOf(d.optString("kind")) }.getOrDefault(DeviceKind.Phone)
                val p = runCatching { Peer(d.getString("id"), d.optString("name"), kind, d.optString("key"), d.optLong("seen"), trusted = d.optBoolean("trusted")) }.getOrNull() ?: continue
                devices[p.id] = p
            }
            _peers.value = devices.toMap()
            _known.value = devices.values.sortedBy { it.name.lowercase() }
        }
        val out = root.optJSONArray("outbox") ?: JSONArray()
        _outbox.value = (0 until out.length()).mapNotNull { i ->
            val o = out.optJSONObject(i) ?: return@mapNotNull null
            runCatching { Outgoing(o.getString("to"), o.getString("url"), o.optString("title").ifBlank { null }, o.optLong("at")) }.getOrNull()
        }
    }

    private fun save() {
        synchronized(devices) { _known.value = devices.values.sortedBy { it.name.lowercase() } }
        saves.trySend(Unit)
    }

    private fun writeNow() {
        val root = JSONObject()
        synchronized(devices) {
            val live = _peers.value
            root.put("devices", JSONArray(devices.values.map { k ->
                val seen = live[k.id]?.seen?.takeIf { it > 0 } ?: k.seen
                JSONObject().put("id", k.id).put("name", k.name).put("kind", k.kind.name).put("key", k.key).put("seen", seen).put("trusted", k.trusted)
            }))
        }
        root.put("outbox", JSONArray(_outbox.value.map { JSONObject().put("to", it.to).put("url", it.url).put("title", it.title ?: "").put("at", it.at) }))
        runCatching { file.write(root.toString()) }.onFailure { Log.w(TAG, "Couldn't save nearby.json", it) }
    }

    private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)
    private fun unb64(s: String) = runCatching { Base64.getDecoder().decode(s) }.getOrNull()

    /** 16 hex characters of the key's SHA-256. */
    fun idOf(key: ByteArray) = MessageDigest.getInstance("SHA-256").digest(key).take(8).joinToString("") { "%02x".format(it) }
}
