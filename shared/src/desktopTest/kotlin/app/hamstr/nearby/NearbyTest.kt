package app.hamstr.nearby

import app.hamstr.data.Prefs
import app.hamstr.data.PropertiesFile
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NearbyTest {
    /** Another Hamstr, played by the test: its own keys, signing the way the app signs. */
    private class Other(val name: String = "Other") {
        val keys: KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val key: String = Base64.getEncoder().encodeToString(keys.public.encoded)
        val id = Nearby.idOf(keys.public.encoded)
        val peer get() = Peer(id, name, DeviceKind.Phone, key)

        fun sign(body: JSONObject, ts: Long = System.currentTimeMillis(), nonce: String = "n" + System.nanoTime(), signer: KeyPair = keys): JSONObject {
            body.put("v", 2).put("id", id).put("name", name).put("kind", "Phone").put("key", key).put("ts", ts).put("n", nonce)
            val sig = Signature.getInstance("SHA256withECDSA").run { initSign(signer.private); update(Nearby.signed(body)); sign() }
            return body.put("sig", Base64.getEncoder().encodeToString(sig))
        }

        /** A link sealed for this computer's Hamstr. */
        fun link(url: String, to: String = Nearby.id, nonce: String = "n" + System.nanoTime(), ts: Long = System.currentTimeMillis(), edit: (JSONObject) -> Unit = {}): ByteArray {
            val m = sign(JSONObject().put("t", "link").put("to", to).put("url", url).put("title", ""), ts, nonce)
            edit(m)
            return Seal.seal(Seal.publicKey(Nearby.publicKey)!!, m.toString().toByteArray()).bytes
        }

        fun receipt(re: String, ok: Boolean = true, done: Boolean = false, signer: KeyPair = keys) =
            sign(JSONObject().put("t", "ack").put("to", Nearby.id).put("re", re).put("ok", ok).apply { if (done) put("done", true) }, signer = signer)
                .toString().toByteArray()

        fun open(bytes: ByteArray) = Seal.open(keys.private, bytes)!!
    }

    /** Both ends of a connection in memory. */
    private class Pipe {
        private val ab = LinkedBlockingQueue<ByteArray>()
        private val ba = LinkedBlockingQueue<ByteArray>()
        val a = end(ba, ab)
        val b = end(ab, ba)
        private fun end(inbox: LinkedBlockingQueue<ByteArray>, outbox: LinkedBlockingQueue<ByteArray>) = object : Wire {
            override var timeout = 10_000
            override fun read(max: Int) = inbox.poll(timeout.toLong(), TimeUnit.MILLISECONDS)?.also { check(it.size <= max) } ?: error("timed out")
            override fun write(bytes: ByteArray) { outbox.put(bytes) }
        }
    }

    private class Loop(val stream: Pipe? = null, val reply: (ByteArray) -> ByteArray) : StreamTransport {
        override val via = Via.Wifi
        override fun start(scope: kotlinx.coroutines.CoroutineScope) {}
        override fun stop() {}
        override fun reaches(id: String) = true
        override suspend fun send(id: String, message: ByteArray, accept: (ByteArray) -> Boolean) = accept(reply(message))
        override suspend fun exchange(id: String, block: (Wire) -> Boolean) = block(stream!!.a)
    }

    /** A GATT connection to [server] in memory, with a small MTU: long reads and many pieces, like the radio. */
    private class FakeLink(val server: BleServer, val client: String = "aa:bb:cc:dd:ee:ff", mtu: Int = 64) : GattLink {
        private val most = mtu - 1
        override val writeSize = mtu - 3
        override val fastWrites = true
        override suspend fun read(characteristic: String): ByteArray? {
            val out = java.io.ByteArrayOutputStream()
            while (true) {
                val rest = server.read(client, characteristic, out.size()) ?: return null
                val part = rest.copyOf(minOf(rest.size, most))
                out.write(part)
                if (part.size < most) return out.toByteArray()
            }
        }
        override suspend fun write(characteristic: String, bytes: ByteArray, fast: Boolean): Boolean {
            check(bytes.size <= writeSize) { "${bytes.size} bytes don't fit in one write" }
            return server.write(client, characteristic, bytes)
        }
    }

    private fun setUp(transports: List<Transport> = emptyList()): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "hamstr-nearby-" + System.nanoTime()).apply { mkdirs() }
        Prefs.init(PropertiesFile(File(dir, "settings.properties")))
        Prefs.edit { it.copy(nearby = false) }
        Nearby.inbox = File(dir, "inbox")
        Nearby.init(PropertiesFile(File(dir, "nearby.properties")), dir, DeviceKind.Desktop, "Test", transports)
        return dir
    }

    @Test
    fun checksEveryLinkAndAsksBeforeTrusting() {
        val dir = setUp()
        val other = Other()
        val stashed = mutableListOf<String>()
        Nearby.onLink = { url, _, _ -> stashed += url }

        // A stranger's first link waits for an answer, and the receipt is signed for that link.
        val nonce = "first"
        val receipt = assertNotNull(Nearby.receive(other.link("https://example.com/a", nonce = nonce)))
        val r = JSONObject(String(receipt))
        assertEquals(listOf("ack", other.id, nonce, Nearby.id), listOf(r.getString("t"), r.getString("to"), r.getString("re"), r.getString("id")))
        assertEquals(listOf("https://example.com/a"), Nearby.requests.value.map { it.url })
        assertTrue(stashed.isEmpty())

        // Changed after signing, sent twice, for another device, too old, not sealed or not a web link: dropped.
        assertNull(Nearby.receive(other.link("https://example.com/b") { it.put("url", "https://evil.example/") }))
        val same = other.link("https://example.com/c", nonce = "once")
        assertNotNull(Nearby.receive(same))
        assertNull(Nearby.receive(same))
        assertNull(Nearby.receive(other.link("https://example.com/d", to = "0123456789abcdef")))
        assertNull(Nearby.receive(other.link("https://example.com/e", ts = System.currentTimeMillis() - Nearby.MAX_AGE - 60_000)))
        assertNull(Nearby.receive(other.sign(JSONObject().put("t", "link").put("to", Nearby.id).put("url", "https://example.com/f")).toString().toByteArray()))
        assertNull(Nearby.receive(other.link("ftp://example.com/g")))
        val sealedElsewhere = Seal.seal(Other().keys.public, other.sign(JSONObject().put("t", "link").put("to", Nearby.id).put("url", "https://example.com/h")).toString().toByteArray()).bytes
        assertNull(Nearby.receive(sealedElsewhere))

        // "Always allow": this link and the next go straight in, and that is remembered.
        Nearby.answer(Nearby.requests.value.first(), accept = true, always = true)
        assertNotNull(Nearby.receive(other.link("https://example.com/i")))
        assertEquals(listOf("https://example.com/a", "https://example.com/i"), stashed)
        assertTrue(Nearby.known.value.single { it.id == other.id }.trusted)
        val saved = File(dir, "nearby.json")
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline && !(saved.exists() && saved.readText().contains("\"trusted\":true"))) Thread.sleep(20)
        assertTrue(JSONObject(saved.readText()).getJSONArray("devices").getJSONObject(0).getBoolean("trusted"))

        // A device that says goodbye is away at once: a stranger leaves the list, a known one stays as away.
        val laptop = Other("Laptop")
        Nearby.seen(laptop.id, "Laptop", DeviceKind.Desktop, laptop.key, Via.Wifi)
        assertTrue(Nearby.peers.value.getValue(laptop.id).online)
        Nearby.gone(laptop.id, Via.Wifi)
        assertFalse(laptop.id in Nearby.peers.value)
        Nearby.seen(other.id, "Other", DeviceKind.Phone, other.key, Via.Wifi)
        Nearby.gone(other.id, Via.Wifi)
        assertFalse(Nearby.peers.value.getValue(other.id).online)
    }

    @Test
    fun beaconsOnlyCountWhenSigned() {
        setUp()
        val other = Other()
        val hello = other.sign(JSONObject().put("hamstr", 2).put("t", "hello").put("port", 4000))
        assertEquals(other.id, Nearby.checkHello(JSONObject(hello.toString()))?.id)
        // Moved to another port, renamed, too old or the old unsigned kind: not this device.
        assertNull(Nearby.checkHello(JSONObject(hello.toString()).put("port", 4001)))
        assertNull(Nearby.checkHello(JSONObject(hello.toString()).put("name", "Mallory")))
        assertNull(Nearby.checkHello(other.sign(JSONObject().put("hamstr", 2).put("t", "hello"), ts = System.currentTimeMillis() - 2 * Nearby.MAX_AGE)))
        assertNull(Nearby.checkHello(JSONObject().put("hamstr", 1).put("id", other.id).put("key", other.key).put("name", "Other")))
        // This app's own hello checks out the same way for others.
        assertTrue(Nearby.hello(1234).getInt("port") == 1234 && Nearby.hello().getString("id") == Nearby.id)
    }

    @Test
    fun aLinkIsSentOnlyWhenTheRightDeviceSignsForIt() = runBlocking {
        val other = Other()
        val forger = Other()
        var forge = false
        setUp(listOf(Loop { message ->
            val inner = JSONObject(String(other.open(message).plain))
            assertEquals(other.id, inner.getString("to"))
            // Somebody else answering in its place, or the right device for another message.
            if (forge) other.receipt(inner.getString("n"), signer = forger.keys) else other.receipt(inner.getString("n"))
        }))
        assertEquals(SendResult.Sent, Nearby.send(other.peer, "https://example.com/a", null))
        forge = true
        assertEquals(SendResult.Queued, Nearby.send(other.peer, "https://example.com/b", null))
        assertEquals(listOf("https://example.com/b"), Nearby.outbox.value.map { it.url })
    }

    @Test
    fun filesGoBothWaysSealed() = runBlocking {
        val other = Other()
        val pipe = Pipe()
        val dir = setUp(listOf(Loop(pipe) { error("no links here") }))
        val content = ByteArray(700_000) { (it % 251).toByte() }
        val source = File(dir, "clip.mp4").apply { writeBytes(content) }

        // Out: the other device reads the offer, accepts and gets the bytes in sealed frames.
        val received = java.io.ByteArrayOutputStream()
        val remote = thread {
            val offer = other.open(pipe.b.read(64 * 1024))
            val m = JSONObject(String(offer.plain))
            assertEquals("clip.mp4", JSONArray(m.getString("files")).getJSONObject(0).getString("name"))
            pipe.b.write(other.receipt(m.getString("n")))
            val frames = Frames(offer.dataKey)
            while (true) {
                val (data, last) = frames.open(pipe.b.read(512 * 1024))
                received.write(data)
                if (last) break
            }
            pipe.b.write(other.receipt(m.getString("n"), done = true))
        }
        var progress = 0f
        assertEquals(SendResult.Sent, Nearby.sendFiles(other.peer, listOf(source)) { progress = it })
        remote.join()
        assertContentEquals(content, received.toByteArray())
        assertEquals(1f, progress)

        // In: from a trusted device the files land in the inbox under the sender's (cleaned) name.
        Nearby.trust(other.peer, true)
        var landed: List<Pair<File, String>> = emptyList()
        Nearby.onFiles = { files, _ -> landed = files }
        val offer = other.sign(JSONObject().put("t", "files").put("to", Nearby.id)
            .put("files", JSONArray().put(JSONObject().put("name", "../../evil/song.opus").put("size", content.size)).toString()))
        val sealed = Seal.seal(Seal.publicKey(Nearby.publicKey)!!, offer.toString().toByteArray())
        val sender = thread {
            val answer = JSONObject(String(pipe.b.read(64 * 1024)))
            assertTrue(answer.getBoolean("ok"))
            val frames = Frames(sealed.dataKey)
            content.toList().chunked(200_000).forEachIndexed { i, part, ->
                pipe.b.write(frames.seal(part.toByteArray(), part.size, last = i == (content.size - 1) / 200_000))
            }
            assertTrue(JSONObject(String(pipe.b.read(64 * 1024))).getBoolean("done"))
        }
        assertContentEquals(ByteArray(0), Nearby.receive(sealed.bytes, pipe.a))
        sender.join()
        assertEquals("song.opus", landed.single().second)
        assertContentEquals(content, landed.single().first.readBytes())
    }

    @Test
    fun linksAndFilesCrossBluetooth() = runBlocking {
        setUp()
        val other = Other()
        val link = FakeLink(BleServer())
        // Who this device is, in a long read.
        assertEquals(Nearby.id, JSONObject(String(assertNotNull(link.read(Ble.INFO)))).getString("id"))

        // A link written in pieces, delivered once its receipt names it.
        Nearby.trust(other.peer, true)
        val stashed = mutableListOf<String>()
        Nearby.onLink = { url, _, _ -> stashed += url }
        assertTrue(Ble.send(link, other.link("https://example.com/ble", nonce = "ble-1")) { JSONObject(String(it)).getString("re") == "ble-1" })
        assertEquals(listOf("https://example.com/ble"), stashed)
        // Played again, it gets no receipt.
        assertFalse(Ble.send(link, other.link("https://example.com/ble", nonce = "ble-1")) { JSONObject(String(it)).optString("re") == "ble-1" })

        // A file on the stream: offer, answer, frames and the receipt for all of it.
        val content = ByteArray(100_000) { (it % 241).toByte() }
        var landed: List<Pair<File, String>> = emptyList()
        Nearby.onFiles = { files, _ -> landed = files }
        val offer = other.sign(JSONObject().put("t", "files").put("to", Nearby.id)
            .put("files", JSONArray().put(JSONObject().put("name", "clip.mp4").put("size", content.size)).toString()))
        val sealed = Seal.seal(Seal.publicKey(Nearby.publicKey)!!, offer.toString().toByteArray())
        val wire = Ble.wire(link)
        wire.write(sealed.bytes)
        assertTrue(JSONObject(String(wire.read(64 * 1024))).getBoolean("ok"))
        val frames = Frames(sealed.dataKey)
        val parts = content.toList().chunked(Ble.CHUNK)
        parts.forEachIndexed { i, part -> wire.write(frames.seal(part.toByteArray(), part.size, last = i == parts.lastIndex)) }
        assertTrue(JSONObject(String(wire.read(64 * 1024))).getBoolean("done"))
        val deadline = System.currentTimeMillis() + 5_000
        while (landed.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals("clip.mp4", landed.single().second)
        assertContentEquals(content, landed.single().first.readBytes())
    }
}
