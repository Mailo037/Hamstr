package app.hamstr.nearby

import app.hamstr.data.Prefs
import app.hamstr.data.PropertiesFile
import org.json.JSONObject
import java.io.File
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NearbyTest {
    private val keys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private val key = Base64.getEncoder().encodeToString(keys.public.encoded)
    private val id = Nearby.idOf(keys.public.encoded)

    /** A link as another Hamstr signs it. */
    private fun message(url: String, nonce: String = "n" + System.nanoTime(), from: String = id, edit: (JSONObject) -> Unit = {}): ByteArray {
        val m = JSONObject().put("v", 1).put("t", "link").put("id", from).put("name", "Other").put("kind", "Phone").put("key", key)
            .put("ts", System.currentTimeMillis()).put("n", nonce).put("url", url).put("title", "")
        val signed = listOf("v", "t", "id", "name", "kind", "key", "ts", "n", "url", "title").joinToString("\n") { m.opt(it)?.toString().orEmpty() }
        val sig = Signature.getInstance("SHA256withECDSA").run { initSign(keys.private); update(signed.toByteArray()); sign() }
        m.put("sig", Base64.getEncoder().encodeToString(sig))
        edit(m)
        return m.toString().toByteArray()
    }

    @Test
    fun checksEveryLinkAndAsksBeforeTrusting() {
        val dir = File(System.getProperty("java.io.tmpdir"), "hamstr-nearby-" + System.nanoTime()).apply { mkdirs() }
        Prefs.init(PropertiesFile(File(dir, "settings.properties")))
        Prefs.edit { it.copy(nearby = false) }
        Nearby.init(PropertiesFile(File(dir, "nearby.properties")), dir, DeviceKind.Desktop, "Test", emptyList())
        val stashed = mutableListOf<String>()
        Nearby.onLink = { url, _, _ -> stashed += url }

        // A stranger's first link waits for an answer.
        assertTrue(Nearby.receive(message("https://example.com/a")))
        assertEquals(listOf("https://example.com/a"), Nearby.requests.value.map { it.url })
        assertTrue(stashed.isEmpty())

        // Changed after signing, sent twice, or under another id: dropped.
        assertFalse(Nearby.receive(message("https://example.com/b") { it.put("url", "https://evil.example/") }))
        val same = message("https://example.com/c", nonce = "once")
        assertTrue(Nearby.receive(same))
        assertFalse(Nearby.receive(same))
        assertFalse(Nearby.receive(message("https://example.com/d", from = "0123456789abcdef")))
        assertFalse(Nearby.receive(message("ftp://example.com/e")))

        // "Always allow": this link and the next go straight in, and that is remembered.
        Nearby.answer(Nearby.requests.value.first(), accept = true, always = true)
        assertTrue(Nearby.receive(message("https://example.com/f")))
        assertEquals(listOf("https://example.com/a", "https://example.com/f"), stashed)
        assertTrue(Nearby.known.value.single { it.id == id }.trusted)
        assertTrue(JSONObject(File(dir, "nearby.json").readText()).getJSONArray("devices").getJSONObject(0).getBoolean("trusted"))
    }
}
