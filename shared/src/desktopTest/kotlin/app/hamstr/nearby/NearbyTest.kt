package app.hamstr.nearby

import app.hamstr.data.DownloadStore
import app.hamstr.data.DownloadTask
import app.hamstr.data.Mode
import app.hamstr.data.Prefs
import app.hamstr.data.Status
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
import java.util.concurrent.FutureTask
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NearbyTest {
    @AfterTest
    fun clearDownloads() {
        DownloadStore.replace(emptyList())
        DownloadStore.close()
    }

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

        fun receipt(re: String, ok: Boolean = true, done: Boolean = false, signer: KeyPair = keys, metadata: Boolean = false) =
            sign(JSONObject().put("t", "ack").put("to", Nearby.id).put("re", re).put("ok", ok).apply {
                if (done) put("done", true)
                if (metadata) put("x_metadata", 1)
            }, signer = signer)
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
        DownloadStore.close()
        DownloadStore.init(dir)
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
        // A legacy receiver must still get only the original media, even when metadata exists.
        DownloadStore.add(media(source, File(dir, "cover.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }))

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
        var landed: List<ReceivedFile> = emptyList()
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
        assertEquals("song.opus", landed.single().name)
        assertContentEquals(content, landed.single().file.readBytes())
        assertNull(landed.single().metadata)
        assertNull(landed.single().cover)
    }

    private fun media(file: File, cover: File? = null) = DownloadTask(
        url = "https://example.com/original", title = "Original ${file.name}",
        thumbnail = cover?.toURI()?.toString(), source = "Youtube", uploader = "Original artist",
        durationSec = 183, videoHeight = 1080, mediaKey = "Youtube:original", channelUrl = "https://example.com/artist",
        choice = Prefs.settings.value.defaultChoice().copy(mode = Mode.Audio, open = true),
        status = Status.Done, filePath = file.absolutePath, hidden = true, anytime = true, group = "sender-group",
    )

    private fun readStream(frames: Frames, wire: Wire): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        while (true) {
            val (data, last) = frames.open(wire.read(512 * 1024))
            out.write(data)
            if (last) return out.toByteArray()
        }
    }

    /** Feed a signed offer and sealed streams without a socket or waiting on a broken sender. */
    private fun incoming(
        other: Other,
        content: List<ByteArray>,
        attachments: List<ByteArray>? = null,
        sizes: List<Long> = content.map { it.size.toLong() },
        metadataVersion: Long? = if (attachments == null) null else 1L,
        edit: (MutableList<ByteArray>) -> Unit = {},
    ): List<JSONObject> {
        val list = JSONArray(content.mapIndexed { i, _ -> JSONObject().put("name", "song$i.opus").put("size", sizes[i]) })
        val offer = other.sign(JSONObject().put("t", "files").put("to", Nearby.id).put("files", list.toString()).apply {
            metadataVersion?.let { put("metadata", it) }
        })
        val sealed = Seal.seal(Seal.publicKey(Nearby.publicKey)!!, offer.toString().toByteArray())
        val frames = Frames(sealed.dataKey)
        val input = mutableListOf<ByteArray>()
        content.forEachIndexed { i, bytes ->
            input += frames.seal(bytes, bytes.size, last = true)
            attachments?.get(i)?.let { attachment ->
                attachment.toList().chunked(50_000).forEachIndexed { at, chunk ->
                    input += frames.seal(chunk.toByteArray(), chunk.size, last = (at + 1) * 50_000 >= attachment.size)
                }
            }
        }
        edit(input)
        val replies = mutableListOf<JSONObject>()
        val wire = object : Wire {
            override var timeout = 10_000
            override fun read(max: Int) = input.removeAt(0).also { check(it.size <= max) }
            override fun write(bytes: ByteArray) { replies += JSONObject(String(bytes)) }
        }
        assertContentEquals(ByteArray(0), Nearby.receive(sealed.bytes, wire))
        return replies
    }

    @Test
    fun metadataAndCoversFollowTheirFilesOnlyAfterNegotiation() = runBlocking {
        val other = Other()
        val pipe = Pipe()
        val dir = setUp(listOf(Loop(pipe) { error("no links here") }))
        val files = listOf(
            File(dir, "one.opus").apply { writeBytes(ByteArray(70_000) { it.toByte() }) },
            File(dir, "two.opus").apply { writeBytes(byteArrayOf(4, 5, 6)) },
        )
        val cover = File(dir, "private-cover.jpg").apply { writeBytes(ByteArray(350_000) { (it % 251).toByte() }) }
        val tasks = files.map { media(it, cover) }
        // A second entry for the same physical file must not overwrite the newest details.
        DownloadStore.add(tasks.first().copy(id = "older-entry", title = "Old title", uploader = "Old artist", thumbnail = null))
        DownloadStore.addAll(tasks)
        val remote = FutureTask {
            val offer = other.open(pipe.b.read(64 * 1024))
            val m = JSONObject(String(offer.plain))
            assertEquals(1, m.getInt("metadata"))
            assertFalse(String(offer.plain).contains("private-cover"))
            pipe.b.write(other.receipt(m.getString("n"), metadata = true))
            val frames = Frames(offer.dataKey)
            val attachments = files.map { file ->
                assertContentEquals(file.readBytes(), readStream(frames, pipe.b))
                readStream(frames, pipe.b)
            }
            pipe.b.write(other.receipt(m.getString("n"), done = true))
            attachments
        }
        thread { remote.run() }
        assertEquals(SendResult.Sent, Nearby.sendFiles(other.peer, files))
        val attachments = remote.get(10, TimeUnit.SECONDS)

        Nearby.trust(other.peer, true)
        var landed = emptyList<ReceivedFile>()
        Nearby.onFiles = { received, _ -> landed = received }
        val replies = incoming(other, files.map { it.readBytes() }, attachments)
        assertEquals(1, replies.first().getInt("x_metadata"))
        assertTrue(replies.last().getBoolean("done"))
        assertEquals(2, landed.size)
        landed.forEachIndexed { i, received ->
            assertContentEquals(files[i].readBytes(), received.file.readBytes())
            assertContentEquals(cover.readBytes(), assertNotNull(received.cover).readBytes())
            val local = media(received.file).copy(id = "local-$i", title = "fallback", uploader = null, source = other.name,
                url = received.file.toURI().toString(), durationSec = 0, videoHeight = 0, mediaKey = null, channelUrl = null,
                hidden = false, anytime = false, group = "local-group", choice = Prefs.settings.value.defaultChoice())
            val adopted = assertNotNull(received.metadata).applyTo(local)
            assertEquals(tasks[i].title, adopted.title)
            assertEquals(tasks[i].uploader, adopted.uploader)
            assertEquals(tasks[i].url, adopted.url)
            assertEquals(tasks[i].source, adopted.source)
            assertEquals(tasks[i].durationSec, adopted.durationSec)
            assertEquals(tasks[i].videoHeight, adopted.videoHeight)
            assertEquals(tasks[i].mediaKey, adopted.mediaKey)
            assertEquals(tasks[i].channelUrl, adopted.channelUrl)
            assertEquals(Mode.Audio, adopted.choice.mode)
            assertEquals(local.id, adopted.id)
            assertEquals(local.filePath, adopted.filePath)
            assertEquals(local.group, adopted.group)
            assertFalse(adopted.hidden || adopted.anytime || adopted.choice.open)
            assertNull(adopted.thumbnail, "A sender-local cover path must not be adopted")
            received.delete()
        }
    }

    @Test
    fun incompleteOrTamperedAttachmentsNeverReachTheImporter() {
        val dir = setUp()
        val other = Other()
        Nearby.trust(other.peer, true)
        var delivered = false
        Nearby.onFiles = { _, _ -> delivered = true }
        val attachment = JSONObject().put("cover", Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))).toString().toByteArray()
        for (tamper in listOf(false, true)) {
            val replies = incoming(other, listOf(byteArrayOf(1), byteArrayOf(2)), listOf(attachment, attachment)) { frames ->
                if (tamper) frames[frames.lastIndex][20] = (frames.last()[20].toInt() xor 1).toByte()
                else frames.removeAt(frames.lastIndex)
            }
            assertTrue(replies.first().getBoolean("ok"))
            assertFalse(replies.any { it.optBoolean("done") })
            assertFalse(delivered)
            assertTrue(File(dir, "inbox").listFiles().orEmpty().isEmpty(), "All media and cover parts must be discarded")
        }
    }

    @Test
    fun malformedAttachmentsNeverReachTheImporter() {
        val dir = setUp()
        val other = Other()
        Nearby.trust(other.peer, true)
        var delivered = false
        Nearby.onFiles = { _, _ -> delivered = true }
        for (attachment in listOf("not json", "{\"cover\":\"not base64!\"}")) {
            val replies = incoming(other, listOf(byteArrayOf(1)), listOf(attachment.toByteArray()))
            assertFalse(replies.any { it.optBoolean("done") })
            assertFalse(delivered)
            assertTrue(File(dir, "inbox").listFiles().orEmpty().isEmpty())
        }
    }

    @Test
    fun emptyFilesWithEmptyAttachmentsStillArriveComplete() {
        setUp()
        val other = Other()
        Nearby.trust(other.peer, true)
        var landed = emptyList<ReceivedFile>()
        Nearby.onFiles = { files, _ -> landed = files }
        val replies = incoming(other, listOf(byteArrayOf()), listOf("{}".toByteArray()))
        try {
            assertTrue(replies.last().getBoolean("done"))
            assertTrue(landed.single().file.isFile)
            assertEquals(0L, landed.single().file.length())
            assertNull(landed.single().metadata)
            assertNull(landed.single().cover)
        } finally { landed.forEach { it.delete() } }
    }

    @Test
    fun unknownMetadataVersionsKeepTheLegacyFileOnlyStream() {
        setUp()
        val other = Other()
        Nearby.trust(other.peer, true)
        var landed = emptyList<ReceivedFile>()
        Nearby.onFiles = { files, _ -> landed = files }
        for (version in listOf(2L, 4_294_967_297L)) {
            val bytes = byteArrayOf(1, 2, 3)
            val replies = incoming(other, listOf(bytes), metadataVersion = version)
            try {
                assertFalse(replies.first().has("x_metadata"))
                assertTrue(replies.last().getBoolean("done"))
                assertContentEquals(bytes, landed.single().file.readBytes())
                assertNull(landed.single().metadata)
                assertNull(landed.single().cover)
            } finally { landed.forEach { it.delete() } }
        }
    }

    @Test
    fun fileSizesStillHaveToMatchAndCannotOverflow() {
        val dir = setUp()
        val other = Other()
        Nearby.trust(other.peer, true)
        var delivered = false
        Nearby.onFiles = { _, _ -> delivered = true }
        for (size in listOf(0L, 2L, Long.MAX_VALUE)) {
            val replies = incoming(other, listOf(byteArrayOf(1)), listOf("{}".toByteArray()), sizes = listOf(size))
            assertFalse(replies.any { it.optBoolean("done") })
            assertFalse(delivered)
            assertTrue(File(dir, "inbox").listFiles().orEmpty().isEmpty())
        }
    }

    @Test
    fun metadataCannotImportSenderPathsOrTaskState() {
        val dir = setUp()
        val file = File(dir, "local.opus").apply { writeBytes(byteArrayOf(1)) }
        val local = media(file).copy(status = Status.Done, hidden = false, anytime = false, group = "local-group")
        val data = JSONObject().put("title", "Real title").put("url", "file:///private/sender.opus")
            .put("thumbnail", "file:///private/cover.jpg").put("channelUrl", "content://private")
            .put("filePath", "/private/sender.opus").put("id", "sender-id").put("status", "Queued")
            .put("hidden", true).put("group", "sender-group").put("duration", -4)
        val adopted = FileMetadata.read(data).applyTo(local)
        assertEquals("Real title", adopted.title)
        assertEquals(local.url, adopted.url)
        assertEquals(local.filePath, adopted.filePath)
        assertEquals(local.id, adopted.id)
        assertEquals(local.status, adopted.status)
        assertEquals(local.group, adopted.group)
        assertEquals(local.durationSec, adopted.durationSec)
        assertEquals(local.channelUrl, adopted.channelUrl)
        assertNull(adopted.thumbnail)
        assertFalse(adopted.hidden)
    }

    @Test
    fun embeddedArtworkSurvivesWhenTheSenderHasOnlyARemoteThumbnail() {
        val dir = setUp()
        val local = media(File(dir, "local.opus")).copy(thumbnail = File(dir, "embedded.jpg").toURI().toString())
        val data = JSONObject().put("thumbnail", "https://example.com/cover.jpg")
            .put("duration", 4_294_967_297L).put("videoHeight", Long.MAX_VALUE).put("height", -1)
        val adopted = FileMetadata.read(data).applyTo(local)
        assertEquals(local.thumbnail, adopted.thumbnail)
        assertEquals(local.durationSec, adopted.durationSec)
        assertEquals(local.videoHeight, adopted.videoHeight)
        assertEquals(local.choice.height, adopted.choice.height)
        assertEquals("https://example.com/cover.jpg", FileMetadata.read(data).applyTo(local.copy(thumbnail = null)).thumbnail)
    }

    @Test
    fun changedFilesNeverSendACompleteStream() = runBlocking {
        // Mutate after the offer's length is signed but before the first file is read. No receiver
        // may see a final frame for either a shortened or a grown file, including an empty file.
        for ((before, after) in listOf(4 to 2, 4 to 6, 0 to 1)) {
            val other = Other()
            val offered = ByteArray(before) { it.toByte() }
            var source: File? = null
            var frames: Frames? = null
            var re: String? = null
            var finalFrame = false
            var reads = 0
            val wire = object : Wire {
                override var timeout = 10_000
                override fun write(bytes: ByteArray) {
                    if (frames == null) {
                        val offer = other.open(bytes)
                        val m = JSONObject(String(offer.plain))
                        assertEquals(before.toLong(), JSONArray(m.getString("files")).getJSONObject(0).getLong("size"))
                        frames = Frames(offer.dataKey)
                        re = m.getString("n")
                    } else {
                        finalFrame = finalFrame || assertNotNull(frames).open(bytes).second
                    }
                }
                override fun read(max: Int): ByteArray {
                    reads++
                    if (reads == 1) {
                        assertNotNull(source).writeBytes(ByteArray(after) { it.toByte() })
                        return other.receipt(assertNotNull(re))
                    }
                    return other.receipt(assertNotNull(re), done = true)
                }
            }
            val transport = object : StreamTransport {
                override val via = Via.Wifi
                override fun start(scope: kotlinx.coroutines.CoroutineScope) {}
                override fun stop() {}
                override fun reaches(id: String) = true
                override suspend fun send(id: String, message: ByteArray, accept: (ByteArray) -> Boolean) = error("no links here")
                override suspend fun exchange(id: String, block: (Wire) -> Boolean) = block(wire)
            }
            val dir = setUp(listOf(transport))
            val file = File(dir, "changing.opus").apply { writeBytes(offered) }
            source = file
            try {
                assertEquals(SendResult.Failed, Nearby.sendFiles(other.peer, listOf(file)))
                assertFalse(finalFrame, "A changed file must not be importable")
                assertEquals(1, reads, "A changed file must not wait for a completion receipt")
            } finally { file.delete() }
        }
    }

    @Test
    fun linksAndFilesCrossBluetooth() = runBlocking {
        val dir = setUp()
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
        var landed: List<ReceivedFile> = emptyList()
        Nearby.onFiles = { files, _ -> landed = files }
        val offer = other.sign(JSONObject().put("t", "files").put("to", Nearby.id).put("metadata", 1)
            .put("files", JSONArray().put(JSONObject().put("name", "clip.mp4").put("size", content.size)).toString()))
        val sealed = Seal.seal(Seal.publicKey(Nearby.publicKey)!!, offer.toString().toByteArray())
        val wire = Ble.wire(link)
        wire.write(sealed.bytes)
        val accepted = JSONObject(String(wire.read(64 * 1024)))
        assertTrue(accepted.getBoolean("ok"))
        assertEquals(1, accepted.getInt("x_metadata"))
        val frames = Frames(sealed.dataKey)
        val parts = content.toList().chunked(Ble.CHUNK)
        parts.forEachIndexed { i, part -> wire.write(frames.seal(part.toByteArray(), part.size, last = i == parts.lastIndex)) }
        val cover = File(dir, "bluetooth-cover.jpg").apply { writeBytes(ByteArray(60_000) { it.toByte() }) }
        val attachment = FileAttachment.encode(media(File(dir, "clip.mp4"), cover)).toList().chunked(Ble.CHUNK)
        attachment.forEachIndexed { i, part -> wire.write(frames.seal(part.toByteArray(), part.size, last = i == attachment.lastIndex)) }
        assertTrue(JSONObject(String(wire.read(64 * 1024))).getBoolean("done"))
        val deadline = System.currentTimeMillis() + 5_000
        while (landed.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals("clip.mp4", landed.single().name)
        assertContentEquals(content, landed.single().file.readBytes())
        assertNotNull(landed.single().metadata)
        assertContentEquals(cover.readBytes(), assertNotNull(landed.single().cover).readBytes())
    }
}
