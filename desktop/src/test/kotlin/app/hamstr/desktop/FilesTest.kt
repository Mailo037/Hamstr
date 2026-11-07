package app.hamstr.desktop

import app.hamstr.data.DownloadStore
import app.hamstr.data.DownloadTask
import app.hamstr.data.Mode
import app.hamstr.data.Prefs
import app.hamstr.data.PropertiesFile
import app.hamstr.data.Status
import app.hamstr.nearby.FileMetadata
import app.hamstr.nearby.ReceivedFile
import java.io.File
import java.net.URI
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FilesTest {
    @AfterTest
    fun clearDownloads() { DownloadStore.replace(emptyList()) }

    private fun setUp(): File {
        val dir = kotlin.io.path.createTempDirectory("hamstr-files-").toFile()
        Prefs.init(PropertiesFile(File(dir, "settings.properties")))
        Prefs.edit { it.copy(downloadFolder = File(dir, "downloads").path) }
        DownloadStore.replace(emptyList())
        return dir
    }

    @Test
    fun adoptsOriginalDetailsAndKeepsCoverOutsideTheInbox() {
        val dir = setUp()
        val part = File(dir, "media.part").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val art = File(dir, "cover.part").apply { writeBytes(byteArrayOf(5, 6, 7)) }
        val original = DownloadTask(
            id = "sender-id", url = "https://example.com/song", title = "Original title, not the filename",
            thumbnail = "file:///sender/cover.jpg", source = "Youtube", uploader = "Original artist", durationSec = 147,
            mediaKey = "Youtube:song", channelUrl = "https://example.com/artist",
            choice = Prefs.settings.value.defaultChoice().copy(mode = Mode.Audio, open = true),
            filePath = "/sender/song.opus", contentUri = "content://sender", status = Status.Running,
            hidden = true, group = "sender-group", sha256 = "sender-hash",
        )
        val collision = File(Paths.downloads.apply { mkdirs() }, "song.opus").apply { writeBytes(byteArrayOf(9)) }
        val kept = Files.adopt(listOf(ReceivedFile(part, "song.opus", FileMetadata.from(original), art)), "Sender phone")
        val task = DownloadStore.tasks.value.single()
        val poster = File(URI(assertNotNull(task.thumbnail)))
        try {
            assertEquals(1, kept.size)
            assertContentEquals(byteArrayOf(1, 2, 3, 4), kept.single().readBytes())
            assertContentEquals(byteArrayOf(9), collision.readBytes())
            assertEquals(original.title, task.title)
            assertEquals(original.uploader, task.uploader)
            assertEquals(original.source, task.source)
            assertEquals(original.url, task.url)
            assertEquals(original.durationSec, task.durationSec)
            assertEquals(original.mediaKey, task.mediaKey)
            assertEquals(original.channelUrl, task.channelUrl)
            assertEquals(Mode.Audio, task.choice.mode)
            assertEquals(kept.single().path, task.filePath)
            assertEquals(4L, task.sizeBytes)
            assertEquals(Status.Done, task.status)
            assertNotEquals(original.id, task.id)
            assertNull(task.contentUri)
            assertNull(task.group)
            assertFalse(task.hidden || task.choice.open)
            assertFalse(part.exists() || art.exists())
            assertContentEquals(byteArrayOf(5, 6, 7), poster.readBytes())
            assertEquals(File(Paths.data, "posters").canonicalFile, poster.parentFile.canonicalFile)
        } finally {
            poster.delete()
            dir.deleteRecursively()
        }
    }

    @Test
    fun artistOnlyMetadataDoesNotBecomeTheSenderAndKeepsAudioModeInMp4() {
        val dir = setUp()
        val part = File(dir, "audio.part").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val original = DownloadTask(
            url = "https://example.com/song", title = "Artist's song", thumbnail = "https://example.com/art.jpg",
            source = null, uploader = "Original artist", durationSec = 90,
            choice = Prefs.settings.value.defaultChoice().copy(mode = Mode.Audio),
        )
        try {
            val kept = Files.adopt(listOf(ReceivedFile(part, "song.mp4", FileMetadata.from(original))), "Sender laptop")
            val task = DownloadStore.tasks.value.single()
            assertEquals(1, kept.size)
            assertEquals("Original artist", task.uploader)
            assertNull(task.source, "The sender is provenance, not the media's source or artist")
            assertEquals(original.thumbnail, task.thumbnail, "An unavailable attachment keeps the remote artwork fallback")
            assertEquals(Mode.Audio, task.choice.mode)
            assertEquals("mp4", task.choice.container)
            assertEquals(Status.Done, task.status)
            assertContentEquals(byteArrayOf(1, 2, 3), kept.single().readBytes())
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun legacyFileBatchesStillImportWithoutAttachments() {
        val dir = setUp()
        val parts = listOf("one.opus", "two.opus").map { name ->
            ReceivedFile(File(dir, "$name.part").apply { writeBytes(byteArrayOf(1, 2)) }, name)
        }
        try {
            val kept = Files.adopt(parts, "Old phone")
            assertEquals(2, kept.size)
            val tasks = DownloadStore.tasks.value
            assertEquals(setOf("one", "two"), tasks.map { it.title }.toSet())
            assertTrue(tasks.all { it.source == "Old phone" && it.thumbnail == null && it.status == Status.Done })
            assertEquals(1, tasks.map { it.group }.distinct().size)
            assertNotNull(tasks.first().group)
            assertTrue(tasks.all { it.groupTitle == "Old phone" })
            assertTrue(parts.none { it.file.exists() })
        } finally { dir.deleteRecursively() }
    }
}
