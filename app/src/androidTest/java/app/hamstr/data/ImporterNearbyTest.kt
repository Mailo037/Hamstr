package app.hamstr.data

import android.graphics.Bitmap
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.hamstr.engine.Engine
import app.hamstr.nearby.FileMetadata
import app.hamstr.nearby.ReceivedFile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.URI
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ImporterNearbyTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun wav(): ByteArray {
        val size = 16_000 // One second, mono 16-bit PCM at 8 kHz.
        return ByteBuffer.allocate(44 + size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + size); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(1); putInt(8000); putInt(16000); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(size)
            repeat(size / 2) { putShort(0) }
        }.array()
    }

    private fun cleanUp(targets: List<File>) {
        val paths = targets.map { it.absolutePath }.toSet()
        DownloadStore.tasks.value.filter { it.filePath in paths }.forEach { task ->
            task.thumbnail?.takeIf { it.startsWith("file:") }?.let {
                runCatching { File(URI(it)).delete() }
            }
            task.contentUri?.let { runCatching { context.contentResolver.delete(Uri.parse(it), null, null) } }
            DownloadStore.remove(task.id)
        }
        targets.forEach { it.delete() }
    }

    @Test
    fun receivedTrackKeepsItsArtistDetailsAndCoverInPhoneOwnedStorage(): Unit = runBlocking {
        val dir = File(context.cacheDir, "nearby-import-${UUID.randomUUID()}").apply { mkdirs() }
        val name = "nearby-import-${UUID.randomUUID()}.wav"
        val bytes = wav()
        val part = File(dir, "media.part").apply { writeBytes(bytes) }
        val cover = File(dir, "cover.part")
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        try {
            cover.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
        val artwork = cover.readBytes()
        val original = DownloadTask(
            id = "sender-task", url = "https://example.com/song", title = "Original title, not the filename",
            thumbnail = "file:///sender/private/cover.jpg", source = "Youtube", uploader = "Original artist", durationSec = 147,
            choice = Choice(Mode.Audio, null, "wav", "wav", false, open = true),
            status = Status.Running, filePath = "/sender/song.wav", contentUri = "content://sender/song",
            mediaKey = "Youtube:song", channelUrl = "https://example.com/artist", hidden = true, anytime = true,
            group = "sender-group", sha256 = "sender-hash",
        )
        val target = File(Engine.outputDir, name)
        try {
            val kept = Importer.adopt(context, listOf(ReceivedFile(part, name, FileMetadata.from(original), cover)), "Sender desktop")
            assertEquals(listOf(target.absolutePath), kept.map { it.absolutePath })
            val adopted = DownloadStore.tasks.value.single { it.filePath == target.absolutePath }
            assertEquals(original.title, adopted.title)
            assertEquals(original.uploader, adopted.uploader)
            assertEquals(original.source, adopted.source)
            assertEquals(original.url, adopted.url)
            assertEquals(original.durationSec, adopted.durationSec)
            assertEquals(original.mediaKey, adopted.mediaKey)
            assertEquals(original.channelUrl, adopted.channelUrl)
            assertEquals(Mode.Audio, adopted.choice.mode)
            assertEquals("wav", adopted.choice.container)
            assertEquals(Status.Done, adopted.status)
            assertEquals(1f, adopted.progress, 0f)
            assertEquals(bytes.size.toLong(), adopted.sizeBytes)
            assertNotEquals(original.id, adopted.id)
            assertNotEquals(original.contentUri, adopted.contentUri)
            assertFalse(adopted.hidden || adopted.anytime)
            assertNull(adopted.group)
            assertArrayEquals(bytes, target.readBytes())
            val poster = File(URI(requireNotNull(adopted.thumbnail)))
            assertEquals(File(context.filesDir, "posters").canonicalFile, poster.parentFile.canonicalFile)
            assertArrayEquals(artwork, poster.readBytes())
            assertFalse(part.exists() || cover.exists())
        } finally {
            cleanUp(listOf(target))
            dir.deleteRecursively()
        }
    }

    @Test
    fun legacyFilesStillUseTheSenderAsBatchProvenance(): Unit = runBlocking {
        val dir = File(context.cacheDir, "nearby-legacy-${UUID.randomUUID()}").apply { mkdirs() }
        val files = List(2) { i ->
            ReceivedFile(File(dir, "$i.part").apply { writeText("file $i") }, "nearby-legacy-${UUID.randomUUID()}.txt")
        }
        val targets = files.map { File(Engine.outputDir, it.name) }
        try {
            val kept = Importer.adopt(context, files, "Old desktop")
            assertEquals(2, kept.size)
            val adopted = DownloadStore.tasks.value.filter { it.filePath in targets.map { target -> target.absolutePath } }
            assertEquals(2, adopted.size)
            assertTrue(adopted.all { it.source == "Old desktop" && it.uploader == null && it.thumbnail == null && it.status == Status.Done })
            assertEquals(1, adopted.map { it.group }.distinct().size)
            assertNotNull(adopted.first().group)
            assertTrue(adopted.all { it.groupTitle == "Old desktop" })
            files.forEachIndexed { i, received ->
                assertEquals("file $i", targets[i].readText())
                assertFalse(received.file.exists())
            }
        } finally {
            cleanUp(targets)
            dir.deleteRecursively()
        }
    }
}
