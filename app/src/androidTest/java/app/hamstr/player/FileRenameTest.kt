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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
class FileRenameTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun task(file: File) = DownloadTask(
        url = "https://example.com/rename-fixture", title = "Rename fixture", thumbnail = null,
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

    @Test fun renameMovesEntriesAndThePlayerAndRefusesBadNames(): Unit = runBlocking {
        val root = File(context.cacheDir, "rename-test-${UUID.randomUUID()}").apply { mkdirs() }
        val audio = task(wav(File(root, "playing.wav")))
        val alias = audio.copy(id = UUID.randomUUID().toString())
        val other = task(File(root, "taken.txt").apply { writeText("another file") })
        val tasks = listOf(audio, alias, other)
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                tasks.forEach(DownloadStore::add)
                Playback.open(context, audio)
                withTimeout(15_000) {
                    while (!withContext(Dispatchers.Main) { Playback.controller.value?.isPlaying == true }) delay(50)
                }

                val renamed = DownloadFiles.rename(context, audio, "  My: song? ").getOrThrow()
                val target = File(root, "My song.wav")
                assertEquals(target.absolutePath, renamed.filePath)
                assertEquals("My song", renamed.title)
                assertTrue(target.isFile)
                assertFalse(File(root, "playing.wav").exists())
                assertEquals(target.absolutePath, DownloadStore.get(alias.id)?.filePath)
                withContext(Dispatchers.Main) {
                    assertEquals(target.absolutePath, Playback.current.value?.filePath)
                    assertTrue(Playback.controller.value!!.isPlaying)
                }

                // taken.txt is another extension, so taken.wav doesn't clash with it.
                assertTrue(DownloadFiles.rename(context, renamed, "taken").isSuccess)
                val taken = DownloadStore.get(audio.id)!!
                File(root, "clash.wav").writeText("x")
                assertTrue(DownloadFiles.rename(context, taken, "clash").isFailure)
                assertTrue(DownloadFiles.rename(context, taken, " ./ ").isFailure)
                assertEquals(File(root, "taken.wav").absolutePath, DownloadStore.get(audio.id)?.filePath)

                val upper = DownloadFiles.rename(context, taken, "TAKEN").getOrThrow()
                assertEquals("TAKEN.wav", File(upper.filePath!!).name)
                assertTrue(root.list()!!.contains("TAKEN.wav"))
            }
        } finally {
            withContext(Dispatchers.Main) { Playback.close() }
            tasks.forEach { DownloadStore.remove(it.id) }
            root.deleteRecursively()
        }
    }
}
