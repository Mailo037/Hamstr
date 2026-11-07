package app.hamstr.cast

import app.hamstr.data.Prefs
import app.hamstr.data.PropertiesFile
import app.hamstr.nearby.DeviceKind
import app.hamstr.nearby.Nearby
import app.hamstr.nearby.StreamTransport
import app.hamstr.nearby.Via
import app.hamstr.nearby.Wire
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** "Play on" between two Hamstr apps: the receiver's checks and the sender's link, against a device the test plays. */
class HamstrCastTest {
    /** Another Hamstr: its own keys, sealing and signing the way the app does. */
    private class Other(val name: String = "Phone") {
        val keys: KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val key: String = Base64.getEncoder().encodeToString(keys.public.encoded)
        val id = Nearby.idOf(keys.public.encoded)

        fun sign(body: JSONObject): JSONObject {
            body.put("v", 2).put("id", id).put("name", name).put("kind", "Phone").put("key", key).put("ts", System.currentTimeMillis()).put("n", "n" + System.nanoTime())
            val sig = Signature.getInstance("SHA256withECDSA").run { initSign(keys.private); update(Nearby.signed(body)); sign() }
            return body.put("sig", Base64.getEncoder().encodeToString(sig))
        }

        /** A play command sealed for this computer's Hamstr. */
        fun command(cmd: String, session: String = "session-1", fill: JSONObject.() -> Unit = {}): ByteArray =
            seal(Nearby.publicKey, sign(JSONObject().put("t", "play").put("to", Nearby.id).put("cmd", cmd).put("session", session).apply(fill)).toString().toByteArray())

        /** Opens a message sealed to this device (the same scheme as Nearby's Seal). */
        fun open(bytes: ByteArray): JSONObject {
            val m = JSONObject(String(bytes))
            val eph = Base64.getDecoder().decode(m.getString("e"))
            val secret = KeyAgreement.getInstance("ECDH").run { init(keys.private); doPhase(KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(eph)), true); generateSecret() }
            val k = hkdf(secret, eph)
            val plain = Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(k, 0, 32, "AES"), GCMParameterSpec(128, Base64.getDecoder().decode(m.getString("iv"))))
                updateAAD(eph)
                doFinal(Base64.getDecoder().decode(m.getString("ct")))
            }
            return JSONObject(String(plain))
        }

        private fun seal(to: String, plain: ByteArray): ByteArray {
            val eph = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
            val pub = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(to)))
            val secret = KeyAgreement.getInstance("ECDH").run { init(eph.private); doPhase(pub, true); generateSecret() }
            val k = hkdf(secret, eph.public.encoded)
            val iv = ByteArray(12).also { java.security.SecureRandom().nextBytes(it) }
            val ct = Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(k, 0, 32, "AES"), GCMParameterSpec(128, iv))
                updateAAD(eph.public.encoded)
                doFinal(plain)
            }
            val b = Base64.getEncoder()
            return JSONObject().put("v", 2).put("e", b.encodeToString(eph.public.encoded)).put("iv", b.encodeToString(iv)).put("ct", b.encodeToString(ct)).toString().toByteArray()
        }

        private fun hkdf(secret: ByteArray, salt: ByteArray): ByteArray {
            val prk = Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(salt, "HmacSHA256")); doFinal(secret) }
            val out = java.io.ByteArrayOutputStream()
            var block = ByteArray(0)
            var i = 1
            while (out.size() < 64) {
                block = Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(prk, "HmacSHA256")); update(block); update("hamstr nearby 2".toByteArray()); update(i++.toByte()); doFinal() }
                out.write(block)
            }
            return out.toByteArray().copyOf(64)
        }
    }

    private class FakeStage : HamstrCast.Stage {
        val calls = mutableListOf<String>()
        var item: HamstrCast.Incoming? = null
        var state = CastStatus.State.Playing
        override fun load(item: HamstrCast.Incoming) { this.item = item; calls += "load ${item.name} at ${item.at}" }
        override fun play() { calls += "play" }
        override fun pause() { calls += "pause" }
        override fun seek(seconds: Double) { calls += "seek $seconds" }
        override fun setVolume(level: Float) { calls += "volume $level" }
        override fun volume() = 0.4f
        override fun status(item: HamstrCast.Incoming) = if (item == this.item) CastStatus(state, 12.0, 200.0, 0.4f) else null
        override fun stop(item: HamstrCast.Incoming) { calls += "stop"; this.item = null }
    }

    private fun setUp(transports: List<app.hamstr.nearby.Transport> = emptyList()) {
        val dir = File(System.getProperty("java.io.tmpdir"), "hamstr-play-" + System.nanoTime()).apply { mkdirs() }
        Prefs.init(PropertiesFile(File(dir, "settings.properties")))
        Prefs.edit { it.copy(nearby = false) }
        Nearby.init(PropertiesFile(File(dir, "nearby.properties")), dir, DeviceKind.Desktop, "Test", transports)
    }

    private fun reply(bytes: ByteArray?) = JSONObject(String(assertNotNull(bytes)))

    @Test
    fun asksFirstThenTakesCommandsOnlyForItsSession() {
        setUp()
        val stage = FakeStage()
        HamstrCast.install(stage)
        val phone = Other()

        // Before anyone allowed it, a load is refused.
        assertFalse(reply(Nearby.receive(phone.command("load") { put("url", "http://192.168.1.20:4000/t/song.m4a") })).getBoolean("ok"))

        // Connecting asks; the answer lets it in until the app quits.
        var hello: JSONObject? = null
        val asking = thread { hello = reply(Nearby.receive(phone.command("hello"))) }
        val deadline = System.currentTimeMillis() + 5_000
        // Other tests may leave requests of their own; this one is the phone's.
        fun asked() = Nearby.requests.value.firstOrNull { it.play && it.from.id == phone.id }
        while (asked() == null && System.currentTimeMillis() < deadline) Thread.sleep(10)
        val r = assertNotNull(asked())
        Nearby.answer(r, accept = true)
        asking.join(5_000)
        assertTrue(hello!!.getBoolean("ok"))
        assertEquals(0.4, hello!!.getDouble("x_volume"), 0.001)

        // Only a stream from a private address plays; the receipt is signed by this device.
        assertFalse(reply(Nearby.receive(phone.command("load") { put("url", "http://example.com/song.m4a") })).getBoolean("ok"))
        val loaded = reply(Nearby.receive(phone.command("load") { put("url", "http://192.168.1.20:4000/t/My%20song.m4a").put("title", "My song").put("at", 30.5).put("media", "d1") }))
        assertTrue(loaded.getBoolean("ok"))
        assertEquals(Nearby.id, loaded.getString("id"))
        assertEquals("load My song.m4a at 30.5", stage.calls.last())
        assertEquals("remote:${phone.id}:d1", HamstrCast.incoming.value?.key)

        val status = reply(Nearby.receive(phone.command("status")))
        assertEquals("Playing", status.getString("x_state"))
        // It plays on while the answer is made, so the position may be a little past 12 s.
        assertEquals(12.0, status.getDouble("x_position"), 0.5)
        assertEquals(200.0, status.getDouble("x_duration"), 0.001)

        // Another session of the same device (an older connect) only hears that it ended.
        assertTrue(reply(Nearby.receive(phone.command("status", session = "session-0"))).getBoolean("x_ended"))
        Nearby.receive(phone.command("pause", session = "session-0"))
        assertFalse("pause" in stage.calls)
        Nearby.receive(phone.command("pause"))
        Nearby.receive(phone.command("seek") { put("at", 42.0) })
        assertEquals(listOf("pause", "seek 42.0"), stage.calls.takeLast(2))

        // The user here played something else: the sender hears it ended, and it is off the stage.
        HamstrCast.release()
        assertTrue(reply(Nearby.receive(phone.command("status"))).getBoolean("x_ended"))
        assertNull(HamstrCast.incoming.value)

        // A stranger that wasn't let in can't do anything.
        assertFalse(reply(Nearby.receive(Other("Stranger").command("play"))).getBoolean("ok"))
    }

    @Test
    fun onlyLinksIntoThisNetwork() {
        assertTrue(HamstrCast.onThisNetwork("http://192.168.0.5:8000/a/b.mp4"))
        assertTrue(HamstrCast.onThisNetwork("http://10.0.0.2/x"))
        assertTrue(HamstrCast.onThisNetwork("http://[fd12::1]:80/x"))
        assertFalse(HamstrCast.onThisNetwork("http://8.8.8.8/x"))
        assertFalse(HamstrCast.onThisNetwork("https://192.168.0.5/x"))
        assertFalse(HamstrCast.onThisNetwork("http://router.local/x"))
        assertFalse(HamstrCast.onThisNetwork("http://user@192.168.0.5/x"))
        assertFalse(HamstrCast.onThisNetwork("file:///etc/passwd"))
    }

    /** The other side of a connection: opens what this device sends and answers with a signed receipt. */
    private class Answering(val other: Other, val answer: (JSONObject) -> JSONObject) : StreamTransport {
        val seen = mutableListOf<JSONObject>()
        override val via = Via.Wifi
        override fun start(scope: kotlinx.coroutines.CoroutineScope) {}
        override fun stop() {}
        override fun reaches(id: String) = id == other.id
        override suspend fun send(id: String, message: ByteArray, accept: (ByteArray) -> Boolean) = false
        override suspend fun exchange(id: String, block: (Wire) -> Boolean): Boolean {
            var out: ByteArray? = null
            val wire = object : Wire {
                override var timeout = 1_000
                override fun write(bytes: ByteArray) {
                    val m = other.open(bytes).also { seen += it }
                    val extra = answer(m)
                    out = other.sign(JSONObject().apply { extra.keys().forEach { k -> put("x_$k", extra.get(k)) } }
                        .put("t", "ack").put("to", Nearby.id).put("re", m.getString("n")).put("ok", true)).toString().toByteArray()
                }
                override fun read(max: Int) = out ?: error("nothing sent")
            }
            return block(wire)
        }
    }

    @Test
    fun theSendersLinkSpeaksTheProtocol(): Unit = runBlocking {
        val laptop = Other("Laptop")
        var ended = false
        val fake = Answering(laptop) { m ->
            when (m.getString("cmd")) {
                "status" -> if (ended) JSONObject().put("ended", true) else JSONObject().put("state", "Paused").put("position", 61.5).put("duration", 180.0).put("volume", 0.25)
                else -> JSONObject()
            }
        }
        setUp(listOf(fake))
        Nearby.seen(laptop.id, "Laptop", DeviceKind.Desktop, laptop.key, Via.Wifi, plays = true)
        assertTrue(Nearby.peers.value.getValue(laptop.id).plays)
        val link = HamstrCast.Link(CastDevice(HamstrCast.PREFIX + laptop.id, "Laptop", CastProtocol.Hamstr, "192.168.1.30", 0, hamstr = DeviceKind.Desktop))
        link.connect()
        link.load("http://192.168.1.10:5000/t/a.opus", null, CastMedia("d1", File("a.opus"), "A", "B", "audio/ogg", false), 3.0, true)
        val s = link.status()
        assertEquals(CastStatus.State.Paused, s.state)
        assertEquals(61.5, s.position, 0.001)
        assertEquals(0.25f, s.volume)
        val load = fake.seen[1]
        assertEquals(listOf("hello", "load"), fake.seen.take(2).map { it.getString("cmd") })
        assertEquals(listOf(laptop.id, "http://192.168.1.10:5000/t/a.opus", "A", "d1"), listOf(load.getString("to"), load.getString("url"), load.getString("title"), load.getString("media")))
        // Every command of a session carries its id.
        assertEquals(1, fake.seen.map { it.getString("session") }.toSet().size)
        ended = true
        assertFailsWith<CastLink.Released> { link.status() }
    }
}
