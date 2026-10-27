package app.hamstr.player

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.hamstr.MainActivity
import app.hamstr.data.Choice
import app.hamstr.data.DownloadFiles
import app.hamstr.data.DownloadStore
import app.hamstr.data.DownloadTask
import app.hamstr.data.Mode
import app.hamstr.data.Status
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class FileDeletionTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun task(file: File) = DownloadTask(
        url = "https://example.com/deletion-fixture", title = "Deletion fixture", thumbnail = null,
        source = null, durationSec = 30, filePath = file.absolutePath,
        choice = Choice(Mode.Audio, null, "mp4", "m4a", false), status = Status.Done,
    )

    private fun wav(file: File): File {
        val samples = 8000 * 30
        val size = samples * 2
        file.writeBytes(ByteBuffer.allocate(44 + size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + size); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(1); putInt(8000); putInt(16000); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(size)
            repeat(samples) { putShort((sin(it * 2 * Math.PI * 440 / 8000) * 1000).toInt().toShort()) }
        }.array())
        return file
    }

    @Test fun viewingAnImageClearsAudioBeforeAddingAnotherSong(): Unit = runBlocking {
        val root = File(context.cacheDir, "image-queue-${UUID.randomUUID()}").apply { mkdirs() }
        val first = task(wav(File(root, "first.wav")))
        val second = task(wav(File(root, "second.wav")))
        val image = task(File(root, "picture.png").apply {
            outputStream().use { out ->
                android.graphics.Bitmap.createBitmap(2, 2, android.graphics.Bitmap.Config.ARGB_8888)
                    .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            }
        })
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                assertTrue(Playback.open(context, first, queue = listOf(first, second), play = false))
                assertTrue(Playback.open(context, image))
                withContext(Dispatchers.Main) {
                    assertEquals(0, Playback.controller.value!!.mediaItemCount)
                    assertNull(DownloadFiles.hasVideo(File(image.filePath!!)))
                }
                assertTrue(Playback.addToQueue(context, second))
                withContext(Dispatchers.Main) {
                    assertEquals(listOf(second.id), Playback.queue.value.map { it.id })
                    assertEquals(second.id, Playback.current.value?.id)
                    assertEquals(1, Playback.controller.value!!.mediaItemCount)
                }
            }
        } finally {
            withContext(Dispatchers.Main) { Playback.close() }
            root.deleteRecursively()
        }
    }

    @Test fun deletionStopsTheMatchingPlayerAndKeepsOtherFilesAndFailedEntries(): Unit = runBlocking {
        val root = File(context.cacheDir, "delete-test-${UUID.randomUUID()}").apply { mkdirs() }
        val audio = task(wav(File(root, "playing.wav")))
        val alias = audio.copy(id = UUID.randomUUID().toString())
        val other = task(File(root, "other.txt").apply { writeText("another file") })
        val directory = File(root, "not-a-file").apply { mkdirs() }
        val child = File(directory, "keep.txt").apply { writeText("keep") }
        val invalid = task(directory)
        val tasks = listOf(audio, alias, other, invalid)
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                tasks.forEach(DownloadStore::add)
                Playback.open(context, audio)
                withTimeout(15_000) {
                    while (!withContext(Dispatchers.Main) { Playback.controller.value?.isPlaying == true }) delay(50)
                }
                assertTrue(DownloadFiles.delete(context, other).isSuccess)
                withContext(Dispatchers.Main) {
                    assertEquals(audio.id, Playback.current.value?.id)
                    assertTrue(Playback.controller.value!!.isPlaying)
                }
                assertTrue(DownloadFiles.delete(context, alias).isSuccess)
                assertFalse(File(audio.filePath!!).exists())
                assertNull(DownloadStore.get(audio.id))
                assertNull(DownloadStore.get(alias.id))
                withTimeout(5_000) {
                    while (!withContext(Dispatchers.Main) {
                        Playback.current.value == null && !Playback.expanded.value &&
                            Playback.controller.value?.isPlaying == false && Playback.controller.value?.mediaItemCount == 0
                    }) delay(30)
                }
                assertTrue(DownloadFiles.delete(context, invalid).isFailure)
                assertNotNull(DownloadStore.get(invalid.id))
                assertTrue(child.exists())
            }
        } finally {
            withContext(Dispatchers.Main) { Playback.close() }
            tasks.forEach { DownloadStore.remove(it.id) }
            root.deleteRecursively()
        }
    }

    @Test fun deletionWhileConnectingCannotRestartTheDeletedMedia(): Unit = runBlocking {
        val file = wav(File(context.cacheDir, "delete-pending-${UUID.randomUUID()}.wav"))
        val task = task(file)
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                DownloadStore.add(task)
                withTimeout(15_000) {
                    withContext(Dispatchers.Main) {
                        Playback.close()
                        Playback.controller.value?.release()
                        val opening = launch(start = CoroutineStart.UNDISPATCHED) { Playback.open(context, task) }
                        assertTrue(DownloadFiles.delete(context, task).isSuccess)
                        opening.join()
                        assertNull(Playback.current.value)
                        assertFalse(Playback.expanded.value)
                        assertFalse(Playback.controller.value?.isPlaying == true)
                        assertEquals(0, Playback.controller.value?.mediaItemCount ?: 0)
                    }
                }
                assertFalse(file.exists())
            }
        } finally {
            withContext(Dispatchers.Main) { Playback.close() }
            DownloadStore.remove(task.id)
            file.delete()
        }
    }
}
