package app.hamstr.data

import android.os.StrictMode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class DownloadStorePerformanceTest {
    @Test
    @SdkSuppress(minSdkVersion = 28)
    fun statusBurstsSaveLatestStateWithoutMainThreadWrites(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = File(instrumentation.targetContext.filesDir, "downloads.json")
        val id = "store-test-${UUID.randomUUID()}"
        val violations = AtomicInteger()
        try {
            instrumentation.runOnMainSync {
                val previous = StrictMode.getThreadPolicy()
                StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder()
                    .detectDiskWrites()
                    .penaltyListener({ it.run() }) { violations.incrementAndGet() }
                    .build())
                try {
                    DownloadStore.add(DownloadTask(
                        id = id, url = "https://example.com/test", title = "start", thumbnail = null,
                        source = null, durationSec = 0,
                        choice = Choice(Mode.Video, null, "mp4", "opus", false), status = Status.Paused,
                    ))
                    repeat(100) { n -> DownloadStore.update(id) { it.copy(title = "version-$n") } }
                    DownloadStore.update(id) { it.copy(status = Status.Done, title = "final") }
                } finally {
                    StrictMode.setThreadPolicy(previous)
                }
            }
            assertEquals("Disk writes on the main thread", 0, violations.get())
            withTimeout(10_000) {
                while (true) {
                    val saved = runCatching { JSONArray(file.readText()) }.getOrNull()
                    val task = saved?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }.firstOrNull { it.optString("id") == id } }
                    if (task?.optString("title") == "final" && task.optString("status") == "Done") break
                    delay(20)
                }
            }
        } finally {
            DownloadStore.remove(id)
        }
        withTimeout(10_000) {
            while (runCatching { JSONArray(file.readText()).let { a -> (0 until a.length()).any { a.getJSONObject(it).optString("id") == id } } }.getOrDefault(true)) delay(20)
        }
    }
}
