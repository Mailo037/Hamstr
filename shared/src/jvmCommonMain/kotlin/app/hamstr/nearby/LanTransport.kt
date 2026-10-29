package app.hamstr.nearby

import app.hamstr.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
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
 * and to each network's broadcast address, and answers a newcomer at once. Links go over a
 * plain TCP connection to the port the beacon names, one length-prefixed message each way.
 *
 * [onStart] and [onStop] let the phone hold Wi-Fi's multicast lock while it listens.
 */
class LanTransport(private val onStart: () -> Unit = {}, private val onStop: () -> Unit = {}) : Transport {
    override val via = Via.Wifi

    private class Spot(val address: InetSocketAddress, val heard: Long)

    private val spots = ConcurrentHashMap<String, Spot>()
    private var server: ServerSocket? = null
    private var listen: MulticastSocket? = null
    private var out: MulticastSocket? = null
    private val jobs = mutableListOf<Job>()
    @Volatile private var eager = false

    override fun start(scope: CoroutineScope) {
        onStart()
        val server = ServerSocket(0).also { server = it }
        val listen = MulticastSocket(PORT).also { listen = it }
        out = MulticastSocket().apply { broadcast = true; timeToLive = 1 }
        interfaces().forEach { nif -> runCatching { listen.joinGroup(InetSocketAddress(GROUP, PORT), nif) } }

        jobs += scope.launch {
            while (isActive) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                launch { answer(socket) }
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
        jobs.forEach { it.cancel() }
        jobs.clear()
        runCatching { server?.close() }
        runCatching { listen?.close() }
        runCatching { out?.close() }
        server = null; listen = null; out = null
        spots.clear()
        onStop()
    }

    override fun discover(on: Boolean) {
        eager = on
        if (on) beacon(null)
    }

    override fun reaches(id: String) = spots[id]?.let { System.currentTimeMillis() - it.heard < 15_000 } == true

    override suspend fun send(id: String, message: ByteArray): Boolean {
        val spot = spots[id] ?: return false
        return Socket().use { s ->
            s.connect(spot.address, 3_000)
            s.soTimeout = 8_000
            val o = DataOutputStream(s.getOutputStream())
            o.writeInt(message.size)
            o.write(message)
            o.flush()
            val i = DataInputStream(s.getInputStream())
            val n = i.readInt()
            n in 1..16 && String(ByteArray(n).also(i::readFully)) == "ok"
        }
    }

    private fun answer(socket: Socket) = socket.use { s ->
        runCatching {
            s.soTimeout = 8_000
            val i = DataInputStream(s.getInputStream())
            val n = i.readInt()
            if (n !in 1..MAX_MESSAGE) return@runCatching
            val ok = Nearby.receive(ByteArray(n).also(i::readFully))
            val reply = (if (ok) "ok" else "no").toByteArray()
            DataOutputStream(s.getOutputStream()).apply { writeInt(reply.size); write(reply); flush() }
        }.onFailure { Log.d(TAG, "Incoming link failed", it) }
    }

    private fun heard(text: String, from: InetAddress) {
        val m = runCatching { JSONObject(text) }.getOrNull() ?: return
        if (m.optInt("hamstr") != 1) return
        val id = m.optString("id")
        val port = m.optInt("port")
        if (id == Nearby.id || port !in 1..65535) return
        val kind = runCatching { DeviceKind.valueOf(m.optString("kind")) }.getOrDefault(DeviceKind.Phone)
        val first = spots[id] == null
        spots[id] = Spot(InetSocketAddress(from, port), System.currentTimeMillis())
        Nearby.seen(id, m.optString("name").take(60), kind, m.optString("key"), via)
        // A newcomer hears back at once instead of waiting for the next beacon.
        if (first) beacon(from)
    }

    /** Says who this is: to [to] alone, or to the group and every network's broadcast address. */
    private fun beacon(to: InetAddress?) {
        val out = out ?: return
        val port = server?.localPort ?: return
        val bytes = Nearby.hello().put("port", port).toString().toByteArray(Charsets.UTF_8)
        fun send(address: InetAddress) = runCatching { out.send(DatagramPacket(bytes, bytes.size, address, PORT)) }
        if (to != null) { send(to); return }
        for (nif in interfaces()) {
            runCatching { out.networkInterface = nif }
            send(GROUP)
            nif.interfaceAddresses.mapNotNull { it.broadcast }.forEach { send(it) }
        }
    }

    private fun interfaces(): List<NetworkInterface> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().filter { n ->
            n.isUp && !n.isLoopback && !n.isPointToPoint && n.inetAddresses.toList().any { it is Inet4Address }
        }
    }.getOrDefault(emptyList())

    companion object {
        private const val TAG = "Nearby"
        const val PORT = 47613
        private val GROUP: InetAddress = InetAddress.getByName("239.255.47.61")
        private const val MAX_MESSAGE = 64 * 1024
    }
}
