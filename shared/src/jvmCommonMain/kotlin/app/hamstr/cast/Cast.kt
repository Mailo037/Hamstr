package app.hamstr.cast

import app.hamstr.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Playing on a TV or speaker on the same network ("Play on"), on the phone and the computer.
 * While a sheet asks ([discover]) Hamstr looks for Chromecast, DLNA (Sonos too), MPD and AirPlay devices; [connect]
 * starts a [CastSession], which the players see and hand what plays over to.
 */
object Cast {
    private const val TAG = "Cast"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _devices = MutableStateFlow<List<CastDevice>>(emptyList())
    /** What answered lately, by name. */
    val devices: StateFlow<List<CastDevice>> = _devices.asStateFlow()
    private val seen = ConcurrentHashMap<String, Pair<CastDevice, Long>>()

    private val _session = MutableStateFlow<CastSession?>(null)
    /** The device something plays on now, or null while it plays here. */
    val session: StateFlow<CastSession?> = _session.asStateFlow()

    /** The phone holds Wi-Fi's multicast lock while it looks; the computer needs nothing. */
    var multicast: (Boolean) -> Unit = {}

    /** Starts the bundled FFmpeg with these arguments, for files a device doesn't play ([Convert]). */
    var ffmpeg: ((List<String>) -> ProcessBuilder)? = null

    /** Where the copies made for devices go. */
    var cache: File? = null

    private var watchers = 0
    private var looking: Job? = null

    /** Looks for devices while at least one caller has it on. */
    @Synchronized
    fun discover(on: Boolean) {
        watchers = (watchers + if (on) 1 else -1).coerceAtLeast(0)
        if (watchers > 0 && looking == null) {
            looking = scope.launch {
                runCatching { multicast(true) }
                try {
                    while (isActive) {
                        val dlna = launch { runInterruptible { runCatching { Dlna.search(5_000, ::found) }.onFailure { Log.d(TAG, "ssdp: ${it.message}") } } }
                        val cast = launch {
                            runInterruptible {
                                runCatching {
                                    Mdns.search(listOf(Chromecast.SERVICE, Mpd.SERVICE, AirPlay.SERVICE), 5_000) { i ->
                                        when (i.service) {
                                            Chromecast.SERVICE -> Chromecast.device(i)
                                            Mpd.SERVICE -> Mpd.device(i)
                                            AirPlay.SERVICE -> AirPlay.device(i)
                                            else -> null
                                        }?.let(::found)
                                    }
                                }.onFailure { Log.d(TAG, "mdns: ${it.message}") }
                            }
                        }
                        dlna.join(); cast.join()
                        // Gone a minute: off, or left the network.
                        val now = System.currentTimeMillis()
                        seen.entries.removeIf { now - it.value.second > 60_000 && it.key != _session.value?.device?.id }
                        publish()
                        delay(6_000)
                    }
                } finally {
                    runCatching { multicast(false) }
                }
            }
        } else if (watchers == 0) {
            looking?.cancel()
            looking = null
        }
    }

    private fun found(d: CastDevice) {
        // A TV that speaks several ways shows once, by the best one: Cast, then DLNA, then AirPlay, then MPD.
        val twin = seen.values.firstOrNull { (o, _) -> o.host == d.host && o.protocol != d.protocol }?.first
        if (twin != null && RANK.indexOf(twin.protocol) < RANK.indexOf(d.protocol)) {
            seen[twin.id] = twin to System.currentTimeMillis()
            return
        }
        if (twin != null) seen.remove(twin.id)
        seen[d.id] = d to System.currentTimeMillis()
        publish()
    }

    private val RANK = listOf(CastProtocol.Chromecast, CastProtocol.Dlna, CastProtocol.AirPlay, CastProtocol.Mpd)

    private fun publish() {
        _devices.value = seen.values.map { it.first }.sortedWith(compareBy({ it.name.lowercase() }, { it.id }))
    }

    /** Connects to [device] and makes it the session; what plays here moves over once a player loads it. */
    suspend fun connect(device: CastDevice): Result<CastSession> = withContext(Dispatchers.IO) {
        if (_session.value?.device?.id == device.id) return@withContext Result.success(_session.value!!)
        disconnect()
        val link: CastLink = when (device.protocol) {
            CastProtocol.Chromecast -> Chromecast.Link(device)
            CastProtocol.Dlna -> Dlna.Link(device)
            CastProtocol.Mpd -> Mpd.Link(device)
            CastProtocol.AirPlay -> AirPlay.Link(device)
        }
        runCatching { link.connect() }
            .onFailure { link.close(); Log.w(TAG, "connect to ${device.name}", it) }
            .map { CastSession(device, link).also { _session.value = it } }
    }

    /** Stops playing there; the players take over here, paused where it was. */
    fun disconnect() {
        val s = _session.value ?: return
        _session.value = null
        s.end()
    }

    /** The session broke (the TV went off or away). */
    internal fun lost(s: CastSession) {
        if (_session.value === s) disconnect()
    }
}

/**
 * Something playing on one device: what it plays and how it is doing.
 *
 * Built for quick taps: a command changes [status] at once (the UI never waits for the
 * network), goes out on its own queue so it never waits behind a status poll, and of several
 * of a kind only the latest is sent (dragging the bar seeks once, skipping through five songs
 * loads one, play-pause-play sends play). The poll holds back while commands are on their way.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CastSession internal constructor(val device: CastDevice, private val link: CastLink) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val commands = Dispatchers.IO.limitedParallelism(1)
    private val server = MediaServer()

    private val _status = MutableStateFlow(CastStatus(CastStatus.State.Idle))
    val status: StateFlow<CastStatus> = _status.asStateFlow()
    private val _media = MutableStateFlow<CastMedia?>(null)
    /** What was last sent there. */
    val media: StateFlow<CastMedia?> = _media.asStateFlow()
    private val _error = MutableStateFlow<Throwable?>(null)
    /** The last command the device refused; cleared by the next that works. */
    val error: StateFlow<Throwable?> = _error.asStateFlow()
    private val _preparing = MutableStateFlow<Float?>(null)
    /** 0..1 while a copy the device plays is being made, null otherwise. */
    val preparing: StateFlow<Float?> = _preparing.asStateFlow()

    // Commands hold the poll back for a moment, so an old status doesn't undo a fresh tap.
    @Volatile private var quietUntil = 0L
    private val inFlight = java.util.concurrent.atomic.AtomicInteger()
    // The newest command of each kind; an older one that hasn't gone out yet is dropped.
    private val newest = ConcurrentHashMap<Kind, Long>()
    private val counter = java.util.concurrent.atomic.AtomicLong()

    private enum class Kind { Load, Play, Seek, Volume }

    init {
        scope.launch {
            var failures = 0
            while (isActive) {
                delay(if (_status.value.state == CastStatus.State.Playing || _status.value.state == CastStatus.State.Loading) 1_000 else 2_000)
                if (_media.value == null || inFlight.get() > 0 || System.currentTimeMillis() < quietUntil) continue
                val asked = System.currentTimeMillis()
                runCatching { link.status() }
                    .onSuccess { s ->
                        failures = 0
                        // A command that went out meanwhile knows better.
                        if (inFlight.get() == 0 && quietUntil < asked) _status.value = s.copy(duration = s.duration.takeIf { it > 0 } ?: _status.value.duration)
                    }
                    .onFailure { failures++ }
                if (failures >= 6) {
                    Log.w("Cast", "lost ${device.name}")
                    Cast.lost(this@CastSession)
                }
            }
        }
    }

    /** Sends [m] there, from [at] seconds. */
    fun load(m: CastMedia, at: Double = 0.0, play: Boolean = true) {
        _media.value = m
        command(Kind.Load, CastStatus.State.Loading, at) { id ->
            // A file the device doesn't play goes as a copy that it does.
            val (file, mime) = try {
                Convert.prepare(m, device, progress = { _preparing.value = it }, wanted = { newest[Kind.Load] == id })
            } finally {
                _preparing.value = null
            }
            if (newest[Kind.Load] != id) return@command
            val url = server.share(file, mime, device.host) ?: throw java.io.IOException("No network to reach ${device.name}")
            val cover = m.cover?.let { c ->
                when {
                    c.startsWith("http://") || c.startsWith("https://") -> c
                    File(c).isFile -> server.share(File(c), if (c.endsWith(".png", true)) "image/png" else "image/jpeg", device.host)
                    else -> null
                }
            }
            link.load(url, cover, m.copy(mime = mime, file = file), at, play)
        }
    }

    fun play() = command(Kind.Play, CastStatus.State.Playing) { link.play() }
    fun pause() = command(Kind.Play, CastStatus.State.Paused) { link.pause() }
    fun toggle() = if (_status.value.state == CastStatus.State.Playing) pause() else play()
    fun seek(seconds: Double) = seconds.coerceAtLeast(0.0).let { to -> command(Kind.Seek, null, to) { link.seek(to) } }
    fun setVolume(level: Float) {
        _status.value = _status.value.copy(volume = level)
        command(Kind.Volume, null) { link.setVolume(level) }
    }

    private fun command(kind: Kind, state: CastStatus.State?, at: Double? = null, block: suspend (Long) -> Unit) {
        val now = _status.value
        _status.value = now.copy(
            state = state ?: now.state,
            position = at ?: now.positionNow(),
            at = System.currentTimeMillis(),
        )
        val id = counter.incrementAndGet()
        newest[kind] = id
        // A new song makes waiting seeks and play-pauses meaningless.
        if (kind == Kind.Load) { newest[Kind.Seek] = id; newest[Kind.Play] = id }
        inFlight.incrementAndGet()
        quietUntil = System.currentTimeMillis() + 1_500
        scope.launch(commands) {
            try {
                if (newest[kind] != id) return@launch
                runCatching { block(id) }
                    .onSuccess { _error.value = null }
                    .onFailure { e -> Log.w("Cast", "command on ${device.name}", e); _error.value = e }
            } finally {
                inFlight.decrementAndGet()
                quietUntil = System.currentTimeMillis() + 800
            }
        }
    }

    internal fun end() {
        scope.launch(commands) {
            runCatching { link.stop() }
            link.close()
            server.stop()
            scope.cancel()
        }
    }
}
