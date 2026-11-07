package app.hamstr.desktop

import app.hamstr.Log
import app.hamstr.data.Prefs
import app.hamstr.nearby.Ble
import app.hamstr.nearby.BleServer
import app.hamstr.nearby.GattLink
import app.hamstr.nearby.Nearby
import app.hamstr.nearby.StreamTransport
import app.hamstr.nearby.Via
import app.hamstr.nearby.Wire
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.IOException
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Nearby over Bluetooth LE on the computer, through Hamstr's helper (desktop/ble): the JVM has
 * no Bluetooth, and the helper doesn't know what Hamstr's messages mean. It advertises the
 * service and hands every read and write of it here, where [BleServer] answers them like the
 * phone's GATT server does; and it reports phones it hears, which this connects to through it
 * to read who they are and send them links and files ([Ble]). A helper that stops is started
 * again, a little later each time.
 */
object DesktopBluetooth : StreamTransport {
    private const val TAG = "Nearby"
    override val via = Via.Bluetooth
    override val chunk = Ble.CHUNK

    enum class State { Off, Starting, On, Missing, NoAdapter }

    private val _state = MutableStateFlow(State.Off)
    /** For the Devices settings: whether Bluetooth runs, and why not. */
    val state: StateFlow<State> = _state.asStateFlow()

    private var job: Job? = null
    private var scope: CoroutineScope? = null
    @Volatile private var writer: BufferedWriter? = null
    @Volatile private var answers = BleServer()
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JSONObject>>()
    private val next = AtomicLong(1)
    // Where each device was heard lately: id → helper's device → when.
    private val spots = ConcurrentHashMap<String, ConcurrentHashMap<String, Long>>()
    // Ids learned from a hello, for devices that don't advertise theirs.
    private val ids = ConcurrentHashMap<String, String>()
    private val looked = ConcurrentHashMap<String, Long>()
    private val gattLock = Mutex()

    override fun start(scope: CoroutineScope) {
        this.scope = scope
        job?.cancel()
        job = scope.launch(Dispatchers.IO) {
            Prefs.settings.map { it.nearbyBluetooth }.distinctUntilChanged().collectLatest { on ->
                if (on) supervise() else _state.value = State.Off
            }
        }
    }

    override fun stop() {
        job?.cancel()
        job = null
        _state.value = State.Off
    }

    private suspend fun supervise() {
        // Queue prepares the same bundle in parallel. Wait for it before starting an
        // old helper, which Windows would then refuse to replace during an app update.
        try {
            Tools.install()
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't prepare the Bluetooth helper", e)
            _state.value = State.Missing
            return
        }
        var backoff = 2_000L
        var waited = 0
        while (true) {
            val bin = Tools.bluetooth
            if (!bin.isFile) {
                // The bundled tools are copied in on the first start of a version, maybe just now.
                if (waited++ < 24) {
                    delay(5_000)
                    continue
                }
                _state.value = State.Missing
                return
            }
            _state.value = State.Starting
            val started = System.currentTimeMillis()
            try {
                session(bin)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Bluetooth helper stopped", e)
            }
            if (_state.value == State.NoAdapter) return
            if (System.currentTimeMillis() - started > 60_000) backoff = 2_000L
            _state.value = State.Starting
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(120_000)
        }
    }

    /** One run of the helper, until it exits or this is cancelled. */
    private suspend fun session(bin: java.io.File) = coroutineScope {
        val p = ProcessBuilder(bin.path).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        answers = BleServer()
        writer = p.outputStream.bufferedWriter()
        val reading = launch(Dispatchers.IO) {
            runCatching { p.inputStream.bufferedReader().forEachLine(::event) }
        }
        try {
            reading.join()
        } finally {
            // Closing its input ends the helper; a stuck one goes anyway.
            runCatching { writer?.close() }
            writer = null
            if (!p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly()
            pending.values.forEach { it.completeExceptionally(IOException("The Bluetooth helper stopped")) }
            pending.clear()
            spots.clear()
        }
    }

    private fun event(line: String) {
        val e = runCatching { JSONObject(line) }.getOrNull() ?: return
        when (e.optString("ev")) {
            "ready" -> {
                if (!e.optBoolean("central")) {
                    _state.value = State.NoAdapter
                    runCatching { writer?.close() }
                    return
                }
                scope?.launch(Dispatchers.IO) {
                    // Serving can fail on an adapter without the peripheral role; finding and sending still work.
                    if (call(JSONObject().put("op", "serve")) == null) Log.w(TAG, "This computer can't be found over Bluetooth")
                    if (call(JSONObject().put("op", "scan").put("on", true)) != null) _state.value = State.On
                }
            }
            "done" -> pending.remove(e.optLong("req"))?.complete(e)
            "read" -> {
                val value = answers.read(e.optString("client"), e.optString("char"), e.optInt("offset"))
                respond(e.optLong("sreq"), value != null, value)
            }
            "write" -> {
                val data = runCatching { Base64.getDecoder().decode(e.optString("data")) }.getOrNull()
                val ok = data != null && e.optInt("offset") == 0 && answers.write(e.optString("client"), e.optString("char"), data)
                respond(e.optLong("sreq"), ok, null)
            }
            "seen" -> seen(e.optString("dev"), if (e.isNull("hamstr")) null else e.optString("hamstr").takeIf { it.length == 16 })
        }
    }

    private fun respond(sreq: Long, ok: Boolean, data: ByteArray?) {
        send(JSONObject().put("op", "respond").put("sreq", sreq).put("ok", ok).apply {
            if (data != null) put("data", Base64.getEncoder().encodeToString(data))
        })
    }

    @Synchronized
    private fun send(cmd: JSONObject): Boolean = runCatching {
        val w = writer ?: return false
        w.write(cmd.toString())
        w.newLine()
        w.flush()
    }.isSuccess

    /** Runs a command; its answer, or null when it failed or the helper is gone. */
    private suspend fun call(cmd: JSONObject, timeout: Long = 30_000): JSONObject? {
        val req = next.getAndIncrement()
        val answer = CompletableDeferred<JSONObject>()
        pending[req] = answer
        try {
            if (!send(cmd.put("req", req))) return null
            return withTimeoutOrNull(timeout) { answer.await() }?.takeIf { it.optBoolean("ok") }
        } catch (e: IOException) {
            return null
        } finally {
            pending.remove(req)
        }
    }

    private fun seen(dev: String, advertised: String?) {
        val id = advertised ?: ids[dev]
        if (id == Nearby.id) return
        val now = System.currentTimeMillis()
        if (id != null) {
            spots.getOrPut(id) { ConcurrentHashMap() }.apply {
                put(dev, now)
                entries.removeIf { now - it.value > 60_000 }
            }
            val known = Nearby.peers.value[id]
            if (known != null && known.key.isNotEmpty()) {
                Nearby.seen(id, known.name, known.kind, known.key, via)
                return
            }
        }
        if (now - (looked[dev] ?: 0) < 60_000) return
        looked[dev] = now
        scope?.launch(Dispatchers.IO) { lookUp(dev) }
    }

    private suspend fun lookUp(dev: String) = gattLock.withLock {
        val peer = link(dev) { Ble.hello(it) } ?: return@withLock
        ids[dev] = peer.id
        spots.getOrPut(peer.id) { ConcurrentHashMap() }[dev] = System.currentTimeMillis()
        Nearby.seen(peer.id, peer.name, peer.kind, peer.key, via)
    }

    private fun places(id: String): List<String> {
        val now = System.currentTimeMillis()
        return spots[id]?.entries?.filter { now - it.value < 15_000 }?.sortedByDescending { it.value }?.map { it.key }.orEmpty()
    }

    override fun reaches(id: String) = _state.value == State.On && places(id).isNotEmpty()

    override suspend fun send(id: String, message: ByteArray, accept: (ByteArray) -> Boolean): Boolean {
        for (dev in places(id)) {
            if (gattLock.withLock { link(dev) { Ble.send(it, message, accept) } } == true) return true
        }
        return false
    }

    override suspend fun exchange(id: String, block: (Wire) -> Boolean): Boolean {
        for (dev in places(id)) {
            if (gattLock.withLock { link(dev, within = null) { block(Ble.wire(it)) } } == true) return true
        }
        return false
    }

    /** Connects to [dev] through the helper and runs [block]; [within] bounds it, null leaves it to [block]. */
    private suspend fun <T> link(dev: String, within: Long? = 20_000, block: suspend (GattLink) -> T): T? {
        val r = call(JSONObject().put("op", "connect").put("dev", dev), timeout = 40_000) ?: return null
        val chars = r.optJSONArray("chars")
        val fast = chars != null && (0 until chars.length()).any { i ->
            chars.optJSONObject(i)?.let { it.optString("uuid").equals(Ble.STREAM, ignoreCase = true) && it.optBoolean("fast") } == true
        }
        val link = HelperLink(dev, r.optInt("mtu", 23), fast)
        return try {
            if (within == null) block(link) else withTimeoutOrNull(within) { block(link) }
        } finally {
            call(JSONObject().put("op", "disconnect").put("dev", dev), timeout = 5_000)
        }
    }

    private class HelperLink(private val dev: String, mtu: Int, override val fastWrites: Boolean) : GattLink {
        override val writeSize = (mtu - 3).coerceIn(20, 512)

        override suspend fun read(characteristic: String): ByteArray? =
            call(JSONObject().put("op", "read").put("dev", dev).put("char", characteristic))
                ?.let { runCatching { Base64.getDecoder().decode(it.optString("data")) }.getOrNull() }

        override suspend fun write(characteristic: String, bytes: ByteArray, fast: Boolean): Boolean =
            call(JSONObject().put("op", "write").put("dev", dev).put("char", characteristic).put("fast", fast)
                .put("data", Base64.getEncoder().encodeToString(bytes))) != null
    }
}
