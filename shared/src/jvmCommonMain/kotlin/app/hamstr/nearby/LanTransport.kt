package app.hamstr.nearby

import app.hamstr.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * Hamstr on the same network. Every app says who it is every few seconds, to a multicast group
 * and to each network's broadcast address, and answers a newcomer at once. The beacons are
 * signed ([Nearby.hello]), so nobody can pass for a device or move it to another address; a
 * beacon played again from somewhere else only adds a place that won't sign for anything.
 * Messages go over a plain TCP connection to the port the beacon names, length-prefixed,
 * as many each way as the exchange needs (one for a link, a stream of frames for files).
 *
 * [onStart] and [onStop] let the phone hold Wi-Fi's multicast lock while it listens.
 */
class LanTransport(private val onStart: () -> Unit = {}, private val onStop: () -> Unit = {}) : StreamTransport {
    override val via = Via.Wifi

    // Every place a device was heard at lately, with when.
    private val spots = ConcurrentHashMap<String, ConcurrentHashMap<InetSocketAddress, Long>>()
    private var server: ServerSocket? = null
    private var listen: MulticastSocket? = null
    private var out: MulticastSocket? = null
    private val jobs = mutableListOf<Job>()
    // A handful of incoming connections at a time; more only wait.
    private val incoming = Semaphore(8)
    @Volatile private var eager = false
    private var hooked = false

    override fun start(scope: CoroutineScope) {
        onStart()
        // Quitting the app ends the process without stop(); it says goodbye on the way out.
        if (!hooked) {
            hooked = true
            Runtime.getRuntime().addShutdownHook(Thread { runCatching { bye() } })
        }
        val server = ServerSocket(0).also { server = it }
        val listen = MulticastSocket(PORT).also { listen = it }
        out = MulticastSocket().apply { broadcast = true; timeToLive = 1 }
        interfaces().forEach { nif -> runCatching { listen.joinGroup(InetSocketAddress(GROUP, PORT), nif) } }

        jobs += scope.launch {
            while (isActive) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                launch { incoming.withPermit { answer(socket) } }
            }
        }
        jobs += scope.launch {
            val buf = ByteArray(4096)
            while (isActive) {
                val packet = DatagramPacket(buf, buf.size)
                runCatching { listen.receive(packet) }.getOrNull() ?: break
                heard(String(packet.data, 0, packet.length, Charsets.UTF_8), packet.address)
            }
        }
        jobs += scope.launch {
            while (isActive) {
                beacon(null)
                delay(if (eager) 1_000 else 3_000)
            }
        }
    }

    override fun stop() {
        bye()
        jobs.forEach { it.cancel() }
        jobs.clear()
        runCatching { server?.close() }
        runCatching { listen?.close() }
        runCatching { out?.close() }
        server = null; listen = null; out = null
        spots.clear()
        eager = false
        onStop()
    }

    override fun discover(on: Boolean) {
        eager = on
        if (on) beacon(null)
    }

    /** The places [id] was heard at in the last 15 seconds, the latest first. */
    private fun places(id: String): List<InetSocketAddress> {
        val now = System.currentTimeMillis()
        return spots[id]?.entries?.filter { now - it.value < 15_000 }?.sortedByDescending { it.value }?.map { it.key }.orEmpty()
    }

    override fun reaches(id: String) = places(id).isNotEmpty()

    override suspend fun send(id: String, message: ByteArray, accept: (ByteArray) -> Boolean): Boolean =
        exchange(id) { wire -> wire.write(message); accept(wire.read(MAX_REPLY)) }

    override suspend fun exchange(id: String, block: (Wire) -> Boolean): Boolean {
        for (address in places(id)) {
            val ok = runCatching {
                Socket().use { s ->
                    s.connect(address, 3_000)
                    block(SocketWire(s).apply { timeout = 8_000 })
                }
            }.onFailure { Log.d(TAG, "No answer at $address", it) }.getOrDefault(false)
            if (ok) return true
        }
        return false
    }

    private fun answer(socket: Socket) = socket.use { s ->
        runCatching {
            val wire = SocketWire(s).apply { timeout = 8_000 }
            val reply = Nearby.receive(wire.read(MAX_MESSAGE), wire)
            // An empty receipt means the exchange already answered over the wire itself.
            when {
                reply == null -> wire.write("no".toByteArray())
                reply.isNotEmpty() -> wire.write(reply)
            }
        }.onFailure { Log.d(TAG, "Incoming message failed", it) }
    }

    private fun heard(text: String, from: InetAddress) {
        val m = runCatching { JSONObject(text) }.getOrNull() ?: return
        val port = m.optInt("port")
        if (port !in 1..65535) return
        val peer = Nearby.checkHello(m) ?: return
        val address = InetSocketAddress(from, port)
        if (m.optBoolean("bye")) {
            val left = spots[peer.id]?.apply { remove(address) }
            if (left.isNullOrEmpty()) {
                spots.remove(peer.id)
                Nearby.gone(peer.id, via)
            }
            return
        }
        val places = spots.getOrPut(peer.id) { ConcurrentHashMap() }
        val first = places.isEmpty()
        places[address] = System.currentTimeMillis()
        // Places not heard from in a minute go; a moved device leaves its old one behind.
        places.entries.removeIf { System.currentTimeMillis() - it.value > 60_000 }
        Nearby.seen(peer.id, peer.name, peer.kind, peer.key, via)
        // A newcomer hears back at once instead of waiting for the next beacon.
        if (first) beacon(from)
    }

    /** Tells the others this Hamstr is leaving, so they show it as away right away. */
    private fun bye() = beacon(null, bye = true)

    /** Says who this is: to [to] alone, or to the group and every network's broadcast address. */
    private fun beacon(to: InetAddress?, bye: Boolean = false) {
        val out = out ?: return
        val port = server?.localPort ?: return
        val bytes = Nearby.hello(port, bye).toString().toByteArray(Charsets.UTF_8)
        fun send(address: InetAddress) = runCatching { out.send(DatagramPacket(bytes, bytes.size, address, PORT)) }
        if (to != null) { send(to); return }
        // One sender for every network in turn; beacons from two threads would pick each other's.
        synchronized(out) {
            for (nif in interfaces()) {
                runCatching { out.networkInterface = nif }
                send(GROUP)
                nif.interfaceAddresses.mapNotNull { it.broadcast }.forEach { send(it) }
            }
        }
    }

    private fun interfaces(): List<NetworkInterface> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().filter { n ->
            n.isUp && !n.isLoopback && !n.isPointToPoint && n.inetAddresses.toList().any { it is Inet4Address }
        }
    }.getOrDefault(emptyList())

    /** Length-prefixed messages over a socket. */
    private class SocketWire(private val socket: Socket) : Wire {
        private val input = DataInputStream(socket.getInputStream().buffered())
        private val output = DataOutputStream(socket.getOutputStream().buffered())

        override var timeout: Int
            get() = socket.soTimeout
            set(value) { socket.soTimeout = value }

        override fun read(max: Int): ByteArray {
            val n = input.readInt()
            if (n !in 0..max) throw EOFException("A message of $n bytes")
            return ByteArray(n).also(input::readFully)
        }

        override fun write(bytes: ByteArray) {
            output.writeInt(bytes.size)
            output.write(bytes)
            output.flush()
        }
    }

    companion object {
        private const val TAG = "Nearby"
        const val PORT = 47613
        private val GROUP: InetAddress = InetAddress.getByName("239.255.47.61")
        private const val MAX_MESSAGE = 64 * 1024
        private const val MAX_REPLY = 16 * 1024
    }
}
