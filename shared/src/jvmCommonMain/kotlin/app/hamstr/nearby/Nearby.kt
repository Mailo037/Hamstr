package app.hamstr.nearby

import app.hamstr.Log
import app.hamstr.data.AtomicJson
import app.hamstr.data.KeyValues
import app.hamstr.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
) {
    val online get() = via.isNotEmpty()
}

/** A link another device wants to send, waiting for "Accept" because the sender isn't trusted yet. */
data class Request(val id: String, val from: Peer, val url: String, val title: String?)

/** What happened to a link sent with [Nearby.send]. */
enum class SendResult { Sent, Queued, Failed }

/** Something that finds devices and carries messages: Wi-Fi on both apps, Bluetooth on the phone. */
interface Transport {
    val via: Via
    fun start(scope: CoroutineScope)
    fun stop()
    /** Look harder for others, e.g. while the send sheet is open. */
    fun discover(on: Boolean) {}
    fun reaches(id: String): Boolean
    suspend fun send(id: String, message: ByteArray): Boolean
}

/**
 * Sending links between Hamstr apps nearby, like AirDrop: devices on the same Wi-Fi or in
 * Bluetooth range show up by themselves. The first link from a device asks; "Always allow"
 * remembers it. A link for a device that is away waits in the outbox until it shows up.
 */
object Nearby {
    private const val TAG = "Nearby"
    private const val GONE_AFTER = 15_000L
    private const val MAX_AGE = 24 * 60 * 60_000L

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

    private var transports: List<Transport> = emptyList()
    private var running = false
    private var sweeper: Job? = null

    /** Called with a link to stash, from a trusted device or after "Accept". */
    var onLink: (url: String, title: String?, from: Peer) -> Unit = { _, _, _ -> }
    /** Called when an untrusted device asks; the app tells the user (a notification, the window). */
    var onRequest: (Request) -> Unit = {}

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

    private val nonces = LinkedHashSet<String>()

    fun init(store: KeyValues, dir: File, kind: DeviceKind, defaultName: String, transports: List<Transport>) {
        this.store = store
        this.kind = kind
        this.defaultName = defaultName
        this.transports = transports
        file = AtomicJson(File(dir, "nearby.json"))
        keys = loadKeys()
        id = idOf(keys.public.encoded)
        load()
        scope.launch {
            Prefs.settings.map { it.nearby }.distinctUntilChanged().collect { if (it) start() else stop() }
        }
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

    /** A transport heard a device. Its waiting links go out now. */
    fun seen(id: String, name: String, kind: DeviceKind, key: String, via: Via) {
        if (id == this.id || idOf(unb64(key) ?: return) != id) return
        val now = System.currentTimeMillis()
        var fresh = false
        _peers.update { all ->
            val old = all[id]
            fresh = old == null || via !in old.via
            all + (id to Peer(id, name, kind, key, now, (old?.via ?: emptySet()) + via, trusted = trusted(id)))
        }
        synchronized(devices) {
            devices[id]?.let { k -> if (k.name != name) { devices[id] = k.copy(name = name); save() } }
        }
        if (fresh && _outbox.value.any { it.to == id }) scope.launch { flush(id) }
    }

    /** Sends a link; if the device is away or doesn't answer, it waits in the outbox. */
    // Runs in Nearby's own scope, so closing the sheet (or the share window) doesn't cut it off.
    suspend fun send(peer: Peer, url: String, title: String?): SendResult = scope.async {
        remember(peer)
        if (deliver(peer.id, url, title)) return@async SendResult.Sent
        _outbox.update { it + Outgoing(peer.id, url, title, System.currentTimeMillis()) }
        save()
        SendResult.Queued
    }.await()

    private suspend fun deliver(to: String, url: String, title: String?): Boolean {
        val message = sign(JSONObject().put("t", "link").put("url", url).put("title", title ?: ""))
        for (t in transports.sortedBy { it.via.ordinal }) {
            if (!t.reaches(to)) continue
            if (runCatching { t.send(to, message) }.onFailure { Log.w(TAG, "${t.via} send failed", it) }.getOrDefault(false)) return true
        }
        return false
    }

    private suspend fun flush(to: String) {
        for (o in _outbox.value.filter { it.to == to }) {
            if (!deliver(o.to, o.url, o.title)) return
            _outbox.update { it - o }
            save()
        }
    }

    /** Takes a link back out of the outbox. */
    fun unsend(o: Outgoing) {
        _outbox.update { it - o }
        save()
    }

    /** A transport received a message. Returns false when it isn't a valid one. */
    fun receive(bytes: ByteArray): Boolean {
        val m = runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }.getOrNull() ?: return false
        val from = verify(m) ?: return false
        if (m.optString("t") != "link") return false
        val url = m.optString("url").takeIf { it.startsWith("http") } ?: return false
        val title = m.optString("title").ifBlank { null }
        val peer = _peers.value[from.id]?.copy(name = from.name) ?: from
        if (trusted(from.id)) {
            Log.i(TAG, "Link from ${from.name}")
            onLink(url, title, peer)
        } else {
            val r = Request(UUID.randomUUID().toString(), peer, url, title)
            _requests.update { it + r }
            onRequest(r)
        }
        return true
    }

    /** The user answered a request: [always] trusts the device from now on. */
    fun answer(r: Request, accept: Boolean, always: Boolean = false) {
        _requests.update { list -> list.filterNot { it.id == r.id } }
        if (!accept) return
        if (always) trust(r.from, true) else remember(r.from)
        onLink(r.url, r.title, r.from)
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

    /** Who this device is, for beacons and Bluetooth: id, name, kind and public key. */
    fun hello(): JSONObject = JSONObject().put("hamstr", 1).put("id", id).put("name", name).put("kind", kind.name).put("key", publicKey)

    // --- Signing -------------------------------------------------------------------------

    private fun sign(body: JSONObject): ByteArray {
        body.put("v", 1).put("id", id).put("name", name).put("kind", kind.name).put("key", publicKey)
            .put("ts", System.currentTimeMillis()).put("n", b64(ByteArray(12).also { SecureRandom().nextBytes(it) }))
        val sig = Signature.getInstance("SHA256withECDSA").run {
            initSign(keys.private)
            update(signed(body))
            sign()
        }
        return body.put("sig", b64(sig)).toString().toByteArray(Charsets.UTF_8)
    }

    /** The fields a signature covers, in a fixed order. */
    private fun signed(m: JSONObject) = listOf("v", "t", "id", "name", "kind", "key", "ts", "n", "url", "title")
        .joinToString("\n") { m.opt(it)?.toString().orEmpty() }.toByteArray(Charsets.UTF_8)

    private fun verify(m: JSONObject): Peer? {
        val key = unb64(m.optString("key")) ?: return null
        val id = m.optString("id")
        if (idOf(key) != id || id == this.id) return null
        if (kotlin.math.abs(System.currentTimeMillis() - m.optLong("ts")) > MAX_AGE) return null
        val nonce = m.optString("n")
        synchronized(nonces) {
            if (!nonces.add(nonce)) return null
            if (nonces.size > 500) nonces.remove(nonces.first())
        }
        val ok = runCatching {
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(key)))
                update(signed(m))
                verify(unb64(m.optString("sig")) ?: return null)
            }
        }.getOrDefault(false)
        if (!ok) return null
        val kind = runCatching { DeviceKind.valueOf(m.optString("kind")) }.getOrDefault(DeviceKind.Phone)
        return Peer(id, m.optString("name").take(60).ifBlank { "Hamstr" }, kind, m.optString("key"))
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
                val d = list.getJSONObject(i)
                val kind = runCatching { DeviceKind.valueOf(d.optString("kind")) }.getOrDefault(DeviceKind.Phone)
                val p = Peer(d.getString("id"), d.optString("name"), kind, d.optString("key"), d.optLong("seen"), trusted = d.optBoolean("trusted"))
                devices[p.id] = p
            }
            _peers.value = devices.toMap()
            _known.value = devices.values.toList()
        }
        val out = root.optJSONArray("outbox") ?: JSONArray()
        _outbox.value = (0 until out.length()).map { i ->
            val o = out.getJSONObject(i)
            Outgoing(o.getString("to"), o.getString("url"), o.optString("title").ifBlank { null }, o.optLong("at"))
        }
    }

    private fun save() {
        val root = JSONObject()
        synchronized(devices) {
            val live = _peers.value
            root.put("devices", JSONArray(devices.values.map { k ->
                val seen = live[k.id]?.seen?.takeIf { it > 0 } ?: k.seen
                JSONObject().put("id", k.id).put("name", k.name).put("kind", k.kind.name).put("key", k.key).put("seen", seen).put("trusted", k.trusted)
            }))
        }
        synchronized(devices) { _known.value = devices.values.sortedBy { it.name.lowercase() } }
        root.put("outbox", JSONArray(_outbox.value.map { JSONObject().put("to", it.to).put("url", it.url).put("title", it.title ?: "").put("at", it.at) }))
        runCatching { file.write(root.toString()) }.onFailure { Log.w(TAG, "Couldn't save nearby.json", it) }
    }

    private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)
    private fun unb64(s: String) = runCatching { Base64.getDecoder().decode(s) }.getOrNull()

    /** 16 hex characters of the key's SHA-256. */
    fun idOf(key: ByteArray) = MessageDigest.getInstance("SHA-256").digest(key).take(8).joinToString("") { "%02x".format(it) }
}
