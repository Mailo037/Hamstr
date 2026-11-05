package app.hamstr.data

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileChecksTest {
    private fun hex(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun until(what: () -> Boolean) = runBlocking { withTimeout(10_000) { while (!what()) delay(20) } }

    @Test
    fun aSavedFileIsHashedAndAChangeShows() {
        val dir = File(System.getProperty("java.io.tmpdir"), "hamstr-checks-" + System.nanoTime()).apply { mkdirs() }
        val file = File(dir, "clip.mp4").apply { writeBytes(ByteArray(3000) { it.toByte() }) }
        val original = file.readBytes()
        val task = DownloadTask(
            id = "f", url = "https://example.com/f", title = "Clip", thumbnail = null, source = null, durationSec = 0,
            choice = Choice(Mode.Video, null, "mp4", "opus", false), status = Status.Done, filePath = file.path,
        )
        try {
            DownloadStore.replace(listOf(task))
            FileChecks.refresh()
            until { DownloadStore.get("f")?.sha256 != null }
            assertEquals(hex(original), DownloadStore.get("f")?.sha256)
            assertEquals(original.size.toLong(), DownloadStore.get("f")?.hashedSize)

            // Only touched: the same bytes with a new time are not a change.
            file.setLastModified(file.lastModified() - 60_000)
            FileChecks.refresh()
            until { DownloadStore.get("f")?.hashedModified == file.lastModified() }
            assertTrue("f" !in FileChecks.changed.value)

            // Edited in place, same size: the hash tells.
            file.writeBytes(original.copyOf().also { it[10] = 99 })
            file.setLastModified(file.lastModified() + 120_000)
            FileChecks.refresh()
            until { "f" in FileChecks.changed.value }

            // Put back as it was: no longer marked.
            file.writeBytes(original)
            FileChecks.refresh()
            until { "f" !in FileChecks.changed.value }

            // The hash is kept with the list, and a new download of the entry drops it.
            val again = DownloadStore.decode(DownloadStore.encode()).single()
            assertEquals(hex(original), again.sha256)
            DownloadStore.update("f") { it.copy(status = Status.Queued) }
            assertNull(DownloadStore.get("f")?.sha256)
        } finally {
            DownloadStore.replace(emptyList())
            dir.deleteRecursively()
        }
    }
    @Test
    fun hashesMatchForFilesBiggerThanTheBufferOneAfterAnother() {
        val dir = File(System.getProperty("java.io.tmpdir"), "hamstr-checks-" + System.nanoTime()).apply { mkdirs() }
        try {
            // A check reads every file through one buffer: a smaller file after a bigger one must not pick up its tail.
            val big = ByteArray((3 shl 20) + 17) { (it * 31).toByte() }
            val small = ByteArray(1000) { (it * 7).toByte() }
            val a = File(dir, "a.bin").apply { writeBytes(big) }
            val b = File(dir, "b.bin").apply { writeBytes(small) }
            val buffer = ByteArray(1 shl 20)
            assertEquals(hex(big), FileChecks.sha256(a, buffer))
            assertEquals(hex(small), FileChecks.sha256(b, buffer))
            assertNull(FileChecks.sha256(File(dir, "gone.bin"), buffer))
            assertEquals(hex(small), FileChecks.sha256(b))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun downloadsSharingAFileBothGetItsHash() {
        val dir = File(System.getProperty("java.io.tmpdir"), "hamstr-checks-" + System.nanoTime()).apply { mkdirs() }
        val file = File(dir, "song.m4a").apply { writeBytes(ByteArray(5000) { it.toByte() }) }
        fun task(id: String) = DownloadTask(
            id = id, url = "https://example.com/$id", title = id, thumbnail = null, source = null, durationSec = 0,
            choice = Choice(Mode.Audio, null, "m4a", "opus", false), status = Status.Done, filePath = file.path,
        )
        try {
            DownloadStore.replace(listOf(task("x"), task("y")))
            FileChecks.refresh()
            until { DownloadStore.get("x")?.sha256 != null && DownloadStore.get("y")?.sha256 != null }
            assertEquals(hex(file.readBytes()), DownloadStore.get("x")?.sha256)
            assertEquals(DownloadStore.get("x")?.sha256, DownloadStore.get("y")?.sha256)
        } finally {
            DownloadStore.replace(emptyList())
            dir.deleteRecursively()
        }
    }
}
