package app.hamstr.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class TaskProcessesTest {
    @Test fun stoppingOneTaskClosesChildPipesAndLeavesOtherTasksRunning(): Unit = runBlocking {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val work = File(cache, "parts/test-${UUID.randomUUID()}")
        val other = File(cache, "parts/test-${UUID.randomUUID()}")
        fun start(directory: File) = ProcessBuilder(
            "/system/bin/sh", "-c", "sleep 8 & wait", "hamstr-test", directory.absolutePath,
        ).start()
        val process = start(work)
        val unrelated = start(other)
        try {
            val pipe = async(Dispatchers.IO) { process.inputStream.readBytes() }
            delay(300) // The child inherits the stdout pipe, just as FFmpeg does.
            TaskProcesses.stop(work)
            assertTrue("Parent process did not stop", process.waitFor(3, TimeUnit.SECONDS))
            withTimeout(3_000) { pipe.await() }
            assertTrue("Another task was stopped", unrelated.isAlive)
        } finally {
            TaskProcesses.stop(work)
            TaskProcesses.stop(other)
            process.destroy()
            unrelated.destroy()
        }
    }
}
