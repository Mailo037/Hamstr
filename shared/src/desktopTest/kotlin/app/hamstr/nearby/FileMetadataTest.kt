package app.hamstr.nearby

import app.hamstr.data.Choice
import app.hamstr.data.DownloadTask
import app.hamstr.data.Mode
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileMetadataTest {
    private fun task(thumbnail: String?) = DownloadTask(
        url = "https://example.com/song", title = "Original title", thumbnail = thumbnail,
        source = "Youtube", uploader = "Original artist", durationSec = 120,
        choice = Choice(Mode.Audio, null, "mp4", "opus", false),
    )

    @Test
    fun unavailableOrOversizedCoverStillCarriesTheOriginalDetails() {
        val dir = kotlin.io.path.createTempDirectory("nearby-art-").toFile()
        try {
            val oversized = File(dir, "large.jpg").apply {
                RandomAccessFile(this, "rw").use { it.setLength(FileAttachment.MAX_COVER.toLong() + 1) }
            }
            for (cover in listOf(File(dir, "missing.jpg"), oversized)) {
                val original = task(cover.toURI().toString())
                val encoded = FileAttachment.encode(original)
                val json = JSONObject(String(encoded, Charsets.UTF_8))
                assertTrue(encoded.size <= FileAttachment.MAX_BYTES)
                assertTrue(!json.has("cover"))
                assertTrue(!String(encoded, Charsets.UTF_8).contains(cover.name), "Sender-local paths stay private")
                val received = FileAttachment.decode(encoded, ReceivedFile(File(dir, "media.part"), "song.opus"), dir)
                val adopted = assertNotNull(received.metadata).applyTo(task(null).copy(title = "fallback", uploader = null))
                assertEquals(original.title, adopted.title)
                assertEquals(original.uploader, adopted.uploader)
                assertNull(adopted.thumbnail)
                assertNull(received.cover)
            }
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun coverBytesAreBoundedAndStoredUnderReceiverChosenNames() {
        val dir = kotlin.io.path.createTempDirectory("nearby-art-").toFile()
        try {
            val art = byteArrayOf(1, 2, 3, 4)
            val attachment = JSONObject().put("cover", Base64.getEncoder().encodeToString(art))
                .put("name", "../../outside.jpg").put("coverPath", "../../outside.jpg")
                .toString().toByteArray(Charsets.UTF_8)
            val received = FileAttachment.decode(attachment, ReceivedFile(File(dir, "media.part"), "song.opus"), dir)
            val cover = assertNotNull(received.cover)
            assertEquals(dir.canonicalFile, cover.parentFile.canonicalFile)
            assertTrue(cover.name.endsWith(".cover.part"))
            assertContentEquals(art, cover.readBytes())
            received.delete()

            assertFailsWith<IllegalArgumentException> {
                FileAttachment.decode(ByteArray(FileAttachment.MAX_BYTES + 1), received, dir)
            }
            val oversized = JSONObject().put("cover", Base64.getEncoder().encodeToString(ByteArray(FileAttachment.MAX_COVER + 1)))
                .toString().toByteArray(Charsets.UTF_8)
            assertFailsWith<IllegalArgumentException> { FileAttachment.decode(oversized, received, dir) }
            assertTrue(dir.listFiles().orEmpty().isEmpty(), "Rejected artwork must not leave a cover part")
        } finally { dir.deleteRecursively() }
    }
}
