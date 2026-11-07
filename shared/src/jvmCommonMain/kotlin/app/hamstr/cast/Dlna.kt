package app.hamstr.cast

import app.hamstr.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.net.DatagramPacket
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.SocketTimeoutException
import java.net.URI
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.roundToInt

/**
 * DLNA/UPnP media renderers: most smart TVs (Samsung, LG, Sony, Philips, Panasonic), AV
 * receivers, Sonos and Kodi. SSDP finds them; their description names the AVTransport service
 * that takes SetAVTransportURI, Play, Pause, Seek and Stop as SOAP calls.
 */
object Dlna {
    private const val TAG = "Cast"
    private const val GROUP = "239.255.255.250"
    private const val PORT = 1900
    private const val AV = "urn:schemas-upnp-org:service:AVTransport:1"
    private const val RC = "urn:schemas-upnp-org:service:RenderingControl:1"

    /** Asks the network for renderers for [millis]; each one that answers goes to [found] once. */
    fun search(millis: Long, found: (CastDevice) -> Unit) {
        val seen = HashSet<String>()
        MulticastSocket(0).use { socket ->
            socket.timeToLive = 2
            socket.soTimeout = 400
            val ask = { target: String ->
                val msg = "M-SEARCH * HTTP/1.1\r\nHOST: $GROUP:$PORT\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: $target\r\nUSER-AGENT: Hamstr UPnP/1.0\r\n\r\n".toByteArray()
                runCatching { socket.send(DatagramPacket(msg, msg.size, InetSocketAddress(GROUP, PORT))) }
            }
            val end = System.currentTimeMillis() + millis
            var asked = 0L
            val buf = ByteArray(4096)
            while (System.currentTimeMillis() < end) {
                // Asked a few times: one datagram on Wi-Fi gets lost easily.
                if (System.currentTimeMillis() - asked > 1500) {
                    ask(AV); ask("urn:schemas-upnp-org:device:MediaRenderer:1")
                    asked = System.currentTimeMillis()
                }
                val packet = DatagramPacket(buf, buf.size)
                try { socket.receive(packet) } catch (_: SocketTimeoutException) { continue }
                val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                val location = header(text, "location") ?: continue
                if (!seen.add(location)) continue
                runCatching { describe(location) }
                    .onFailure { Log.d(TAG, "description of $location: ${it.message}") }
                    .getOrNull()?.let(found)
            }
        }
    }

    internal fun header(response: String, name: String): String? =
        response.lineSequence().firstOrNull { it.substringBefore(':').trim().equals(name, ignoreCase = true) }?.substringAfter(':')?.trim()

    /** The renderer behind a description URL, or null when it has no AVTransport. */
    fun describe(location: String): CastDevice? {
        val r = LanHttp.request(location, timeout = 3000)
        if (r.code !in 200..299) return null
        return parseDescription(location, r.body)
    }

    internal fun parseDescription(location: String, xml: ByteArray): CastDevice? {
        val doc = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            isExpandEntityReferences = false
        }.newDocumentBuilder().parse(ByteArrayInputStream(xml))
        val root = doc.documentElement
        val base = text(root, "URLBase")?.takeIf { it.isNotBlank() } ?: location
        // The renderer may be a device inside the root device (receivers often are).
        val devices = root.getElementsByTagName("device")
        for (i in 0 until devices.length) {
            val dev = devices.item(i) as Element
            val services = dev.getElementsByTagName("service")
            var transport: String? = null
            var rendering: String? = null
            for (j in 0 until services.length) {
                val s = services.item(j) as Element
                if (s.parentNode?.parentNode != dev) continue
                val type = text(s, "serviceType") ?: continue
                val control = text(s, "controlURL") ?: continue
                if (type.startsWith("urn:schemas-upnp-org:service:AVTransport:")) transport = resolve(base, control)
                if (type.startsWith("urn:schemas-upnp-org:service:RenderingControl:")) rendering = resolve(base, control)
            }
            if (transport == null) continue
            val url = URI(location)
            val name = text(dev, "friendlyName")?.trim().orEmpty().ifEmpty { url.host }
            val maker = text(dev, "manufacturer")?.trim()
            val model = text(dev, "modelName")?.trim()
            val audioOnly = listOf(name, model, maker).any { s -> s != null && AUDIO.any { s.contains(it, ignoreCase = true) } }
            return CastDevice(
                id = text(dev, "UDN")?.trim()?.ifEmpty { null } ?: location,
                name = name,
                protocol = CastProtocol.Dlna,
                host = url.host,
                port = if (url.port > 0) url.port else 80,
                model = listOfNotNull(maker, model).joinToString(" ").ifEmpty { null }?.take(60),
                video = !audioOnly,
                transport = transport,
                rendering = rendering,
            )
        }
        return null
    }

    // Names that say a renderer is a speaker, so it gets a speaker's icon.
    private val AUDIO = listOf("sonos", "speaker", "receiver", "denon", "marantz", "yamaha", "bose", "heos", "soundbar", "audio", "amp")

    private fun text(e: Element, tag: String): String? {
        val list = e.getElementsByTagName(tag)
        return if (list.length > 0) list.item(0).textContent else null
    }

    private fun resolve(base: String, path: String): String = URI(base).resolve(path.trim()).toString()

    /** One renderer's AVTransport and RenderingControl. */
    class Link(private val device: CastDevice) : CastLink {
        private var media: CastMedia? = null
        private var stopped = false
        // STOPPED only means the song ended once it has played; before that the TV is still loading.
        private var played = false

        override suspend fun connect() {
            // A plain GetTransportInfo: the TV answers, or it isn't there.
            soap(device.transport!!, AV, "GetTransportInfo", "<InstanceID>0</InstanceID>")
        }

        override suspend fun load(url: String, coverUrl: String?, media: CastMedia, at: Double, play: Boolean) {
            this.media = media
            stopped = false
            played = false
            // Some TVs refuse a new link while one plays.
            runCatching { soap(device.transport!!, AV, "Stop", "<InstanceID>0</InstanceID>") }
            soap(
                device.transport!!, AV, "SetAVTransportURI",
                "<InstanceID>0</InstanceID><CurrentURI>${esc(url)}</CurrentURI><CurrentURIMetaData>${esc(didl(url, coverUrl, media))}</CurrentURIMetaData>",
            )
            if (play) play()
            if (at > 1) {
                // A TV only seeks once the file plays; it tries again a few times.
                repeat(10) {
                    kotlinx.coroutines.delay(500)
                    val state = runCatching { transportState() }.getOrNull()
                    if (state == "PLAYING" || state == "PAUSED_PLAYBACK") {
                        runCatching { seek(at) }
                        return
                    }
                }
            }
        }

        override suspend fun play() { stopped = false; soap(device.transport!!, AV, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>") }
        override suspend fun pause() { soap(device.transport!!, AV, "Pause", "<InstanceID>0</InstanceID>") }
        override suspend fun seek(seconds: Double) {
            soap(device.transport!!, AV, "Seek", "<InstanceID>0</InstanceID><Unit>REL_TIME</Unit><Target>${clock(seconds)}</Target>")
        }

        override suspend fun setVolume(level: Float) {
            val rc = device.rendering ?: return
            volume = level
            soap(rc, RC, "SetVolume", "<InstanceID>0</InstanceID><Channel>Master</Channel><DesiredVolume>${(level * 100).roundToInt().coerceIn(0, 100)}</DesiredVolume>")
        }

        private var polls = 0
        private var volume: Float? = null

        override suspend fun status(): CastStatus = kotlinx.coroutines.coroutineScope {
            // Both questions at once; the volume only every few polls, it rarely changes.
            val stateAsk = async(Dispatchers.IO) { transportState() }
            val infoAsk = async(Dispatchers.IO) { soap(device.transport!!, AV, "GetPositionInfo", "<InstanceID>0</InstanceID>") }
            val rc = device.rendering
            if (rc != null && polls++ % 4 == 0) {
                volume = runCatching { tag(soap(rc, RC, "GetVolume", "<InstanceID>0</InstanceID><Channel>Master</Channel>"), "CurrentVolume")?.toFloatOrNull()?.div(100f) }.getOrNull() ?: volume
            }
            val state = stateAsk.await()
            val info = infoAsk.await()
            val position = seconds(tag(info, "RelTime"))
            val duration = seconds(tag(info, "TrackDuration"))
            if (state == "PLAYING") played = true
            CastStatus(
                state = when (state) {
                    "PLAYING" -> CastStatus.State.Playing
                    "PAUSED_PLAYBACK", "PAUSED_RECORDING" -> CastStatus.State.Paused
                    "TRANSITIONING" -> CastStatus.State.Loading
                    "STOPPED", "NO_MEDIA_PRESENT" -> when {
                        stopped -> CastStatus.State.Idle
                        played -> CastStatus.State.Finished
                        else -> CastStatus.State.Loading
                    }
                    else -> CastStatus.State.Idle
                },
                position = position, duration = duration, volume = volume,
            )
        }

        private suspend fun transportState() = tag(soap(device.transport!!, AV, "GetTransportInfo", "<InstanceID>0</InstanceID>"), "CurrentTransportState")

        override suspend fun stop() {
            stopped = true
            runCatching { soap(device.transport!!, AV, "Stop", "<InstanceID>0</InstanceID>") }
        }

        override fun close() {}

        private fun didl(url: String, cover: String?, m: CastMedia): String {
            val cls = if (m.video) "object.item.videoItem" else "object.item.audioItem.musicTrack"
            return """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">""" +
                """<item id="0" parentID="-1" restricted="1"><dc:title>${esc(m.title)}</dc:title>""" +
                (m.artist?.let { "<upnp:artist>${esc(it)}</upnp:artist><dc:creator>${esc(it)}</dc:creator>" } ?: "") +
                (cover?.let { "<upnp:albumArtURI>${esc(it)}</upnp:albumArtURI>" } ?: "") +
                "<upnp:class>$cls</upnp:class>" +
                """<res protocolInfo="http-get:*:${m.mime}:DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000" size="${m.file.length()}">${esc(url)}</res>""" +
                "</item></DIDL-Lite>"
        }
    }

    private suspend fun soap(control: String, service: String, action: String, args: String): String = withContext(Dispatchers.IO) {
        val body = """<?xml version="1.0" encoding="utf-8"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><u:$action xmlns:u="$service">$args</u:$action></s:Body></s:Envelope>"""
        val r = LanHttp.request(
            control, "POST",
            mapOf("Content-Type" to "text/xml; charset=\"utf-8\"", "SOAPACTION" to "\"$service#$action\""),
            body.toByteArray(Charsets.UTF_8), timeout = 6000,
        )
        if (r.code !in 200..299) throw java.io.IOException("$action: HTTP ${r.code} ${tag(r.text, "errorDescription").orEmpty()}")
        r.text
    }

    internal fun tag(xml: String, name: String): String? =
        Regex("<(?:\\w+:)?$name(?:\\s[^>]*)?>([^<]*)</(?:\\w+:)?$name>").find(xml)?.groupValues?.get(1)?.trim()

    internal fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")

    internal fun clock(seconds: Double): String {
        val s = seconds.toLong().coerceAtLeast(0)
        return "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60)
    }

    /** "0:03:25", "00:03:25.120" or "NOT_IMPLEMENTED" in seconds. */
    internal fun seconds(clock: String?): Double {
        val parts = clock?.split(':') ?: return 0.0
        if (parts.size != 3) return 0.0
        val h = parts[0].toDoubleOrNull() ?: return 0.0
        val m = parts[1].toDoubleOrNull() ?: return 0.0
        val s = parts[2].toDoubleOrNull() ?: return 0.0
        return h * 3600 + m * 60 + s
    }
}
