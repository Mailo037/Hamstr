package app.hamstr.cast

import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.SocketTimeoutException

/**
 * Multicast DNS, just enough to find devices by service type: Chromecast
 * (`_googlecast._tcp`), MPD (`_mpd._tcp`) and AirPlay (`_airplay._tcp`).
 */
object Mdns {
    private val GROUP: InetAddress = InetAddress.getByName("224.0.0.251")
    private const val PORT = 5353

    /** One service instance: its name, where it is and what its TXT record says. */
    data class Instance(val service: String, val name: String, val host: String, val port: Int, val txt: Map<String, String>) {
        /** The instance's own name, without the service. */
        val label get() = name.removeSuffix(".$service").replace("\\032", " ")
    }

    /** Asks for [services] for [millis]; each instance that becomes complete goes to [found] once. */
    fun search(services: List<String>, millis: Long, found: (Instance) -> Unit) {
        val socket = runCatching {
            MulticastSocket(null).apply { reuseAddress = true; bind(InetSocketAddress(PORT)); joinGroup(GROUP) }
        }.getOrElse { MulticastSocket(0) }
        socket.use { s ->
            s.timeToLive = 255
            s.soTimeout = 400
            val query = query(services)
            val seen = HashSet<String>()
            val records = Records(services)
            val end = System.currentTimeMillis() + millis
            var asked = 0L
            val buf = ByteArray(9000)
            while (System.currentTimeMillis() < end) {
                if (System.currentTimeMillis() - asked > 1500) {
                    runCatching { s.send(DatagramPacket(query, query.size, InetSocketAddress(GROUP, PORT))) }
                    asked = System.currentTimeMillis()
                }
                val packet = DatagramPacket(buf, buf.size)
                try { s.receive(packet) } catch (_: SocketTimeoutException) { continue }
                runCatching { records.read(packet.data.copyOf(packet.length)) }
                for (i in records.instances(packet.address.hostAddress)) if (seen.add(i.service + "/" + i.name)) found(i)
            }
        }
    }

    /** PTR questions for [services], asking for unicast answers as well. */
    internal fun query(services: List<String>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0, 0, 0, 0, 0, services.size.toByte(), 0, 0, 0, 0, 0, 0))
        for (service in services) {
            service.split('.').forEach { label -> out.write(label.length); out.write(label.toByteArray()) }
            out.write(0)
            out.write(byteArrayOf(0, 12, 0x80.toByte(), 1))
        }
        return out.toByteArray()
    }

    /** What the answers said so far: which instances, where, and what they call themselves. */
    internal class Records(private val services: List<String>) {
        val ptr = LinkedHashMap<String, String>() // instance -> service
        val srv = HashMap<String, Pair<String, Int>>()
        val txt = HashMap<String, Map<String, String>>()
        val a = HashMap<String, String>()

        fun read(msg: ByteArray) {
            var at = 12
            val qd = u16(msg, 4); val an = u16(msg, 6); val ns = u16(msg, 8); val ar = u16(msg, 10)
            repeat(qd) { at = name(msg, at).second + 4 }
            repeat(an + ns + ar) {
                val (owner, after) = name(msg, at)
                val type = u16(msg, after)
                val len = u16(msg, after + 8)
                val data = after + 10
                when (type) {
                    12 -> services.firstOrNull { it.equals(owner, true) }?.let { ptr[name(msg, data).first] = it }
                    33 -> srv[owner.lowercase()] = name(msg, data + 6).first to u16(msg, data + 4)
                    16 -> {
                        val map = HashMap<String, String>()
                        var i = data
                        while (i < data + len) {
                            val n = msg[i].toInt() and 0xFF
                            if (n > 0) {
                                val entry = String(msg, i + 1, n, Charsets.UTF_8)
                                map[entry.substringBefore('=').lowercase()] = entry.substringAfter('=', "")
                            }
                            i += n + 1
                        }
                        txt[owner.lowercase()] = map
                    }
                    1 -> if (len == 4) a[owner.lowercase()] = (0 until 4).joinToString(".") { (msg[data + it].toInt() and 0xFF).toString() }
                }
                at = data + len
            }
        }

        /** The instances whose place is known; [from] stands in for a missing address. */
        fun instances(from: String?): List<Instance> = ptr.mapNotNull { (inst, service) ->
            val key = inst.lowercase()
            val (target, port) = srv[key] ?: return@mapNotNull null
            val host = a[target.lowercase()] ?: from ?: return@mapNotNull null
            Instance(service, inst, host, port, txt[key].orEmpty())
        }

        private fun u16(b: ByteArray, i: Int) = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)

        /** A name at [start], following compression pointers; with where the record goes on. */
        private fun name(b: ByteArray, start: Int): Pair<String, Int> {
            val labels = mutableListOf<String>()
            var i = start
            var end = -1
            var jumps = 0
            while (true) {
                val len = b[i].toInt() and 0xFF
                when {
                    len == 0 -> { if (end < 0) end = i + 1; break }
                    len and 0xC0 == 0xC0 -> {
                        if (end < 0) end = i + 2
                        i = ((len and 0x3F) shl 8) or (b[i + 1].toInt() and 0xFF)
                        if (++jumps > 20) break
                    }
                    else -> { labels += String(b, i + 1, len, Charsets.UTF_8); i += len + 1 }
                }
            }
            return labels.joinToString(".") to end
        }
    }
}
