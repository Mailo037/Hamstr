package app.hamstr.data

import java.io.File
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PropertiesFileTest {
    private fun tempDir() = File(System.getProperty("java.io.tmpdir"), "hamstr-props-" + System.nanoTime()).apply { mkdirs() }

    /** What is on disk now, read past any copy in memory. */
    private fun onDisk(file: File) = Properties().apply { if (file.isFile) file.inputStream().use(::load) }

    /** Waits up to [ms] for [done]; the writer retries on its own schedule. */
    private fun eventually(ms: Long = 10_000, done: () -> Boolean): Boolean {
        val until = System.nanoTime() + ms * 1_000_000
        while (System.nanoTime() < until) {
            if (done()) return true
            Thread.sleep(20)
        }
        return done()
    }

    @Test
    fun aChangeIsReadableAtOnceAndOnDiskOncePendingWritesAreDone() {
        val dir = tempDir()
        try {
            val file = File(dir, "settings.properties")
            val props = PropertiesFile(file)
            repeat(200) { props.edit().putInt("n", it).apply() }
            props.edit().putString("s", "x").apply()
            assertEquals(199, props.getInt("n", -1))
            assertTrue(props.flush())
            assertEquals("199", onDisk(file).getProperty("n"))
            assertEquals("x", onDisk(file).getProperty("s"))
            // Opened again (a restart): what was written is there.
            assertEquals(199, PropertiesFile(file).getInt("n", -1))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun anEditThatChangesNothingLeavesTheFileAlone() {
        val dir = tempDir()
        try {
            val file = File(dir, "settings.properties")
            val props = PropertiesFile(file)
            props.edit().putInt("a", 1).putString("b", "x").apply()
            assertTrue(props.flush())
            val stale = file.lastModified() - 60_000
            file.setLastModified(stale)
            props.edit().putInt("a", 1).putString("b", "x").apply()
            assertTrue(props.flush())
            assertEquals(stale, file.lastModified(), "not written again")
            props.edit().putInt("a", 2).apply()
            assertTrue(props.flush())
            assertEquals("2", onDisk(file).getProperty("a"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun aFailedWriteIsTriedAgainByItself() {
        val dir = tempDir()
        try {
            val file = File(dir, "settings.properties")
            val props = PropertiesFile(file)
            // The temporary file can't be made: the write fails after the value is in memory.
            val blocker = File(file.path + ".new").apply { mkdirs() }
            props.edit().putBoolean("on", true).apply()
            assertFalse(props.flush(), "a flush says the file is behind")
            assertFalse(file.exists())
            assertEquals(true, props.getBoolean("on", false))
            // The disk is back: the retry writes it without anything else being changed.
            blocker.delete()
            assertTrue(eventually { props.saved })
            assertEquals("true", onDisk(file).getProperty("on"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun aSettingChangedWhileTheDiskFailedIsThereAfterARestart() {
        val dir = tempDir()
        try {
            val file = File(dir, "settings.properties")
            val store = PropertiesFile(file)
            Prefs.init(store)
            val blocker = File(file.path + ".new").apply { mkdirs() }
            Prefs.edit { it.copy(accent = 0x123456) }
            // The same pick again while the disk still fails: memory has it, nothing more to do there.
            Prefs.edit { it.copy(accent = 0x123456) }
            assertFalse(store.flush(timeoutMs = 2_000))
            blocker.delete()
            assertTrue(eventually { store.saved })
            // Started again: the setting survived.
            Prefs.init(PropertiesFile(file))
            assertEquals(0x123456, Prefs.settings.value.accent)
        } finally {
            Prefs.init(PropertiesFile(File(dir, "reset.properties")))
            dir.deleteRecursively()
        }
    }

    @Test
    fun aFlushWritesAtOnceInsteadOfWaitingForTheRetry() {
        val dir = tempDir()
        try {
            val file = File(dir, "settings.properties")
            val props = PropertiesFile(file)
            val blocker = File(file.path + ".new").apply { mkdirs() }
            props.edit().putInt("a", 1).apply()
            // Failing a few times pushes the next retry seconds away.
            repeat(4) { assertFalse(props.flush()) }
            blocker.delete()
            assertTrue(props.flush(), "written now, not at the retry's time")
            assertEquals("1", onDisk(file).getProperty("a"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun openedAgainWhileItCantBeWrittenTheChangesCarryOver() {
        val dir = tempDir()
        try {
            val file = File(dir, "settings.properties")
            val first = PropertiesFile(file)
            first.edit().putInt("a", 1).apply()
            assertTrue(first.flush())
            val blocker = File(file.path + ".new").apply { mkdirs() }
            first.edit().putInt("a", 2).putString("b", "x").apply()
            // The disk still fails: the new copy starts from what the old one had, not from the stale file.
            val second = PropertiesFile(file)
            assertEquals(2, second.getInt("a", 0))
            assertEquals("x", second.getString("b", null))
            assertFalse(second.saved)
            blocker.delete()
            assertTrue(second.flush())
            assertEquals("2", onDisk(file).getProperty("a"))
            assertEquals("x", onDisk(file).getProperty("b"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun anOlderHandleKeepsWritingAfterTheFileIsOpenedAgain() {
        val dir = tempDir()
        try {
            val file = File(dir, "settings.properties")
            val first = PropertiesFile(file)
            first.edit().putInt("a", 1).apply()
            val second = PropertiesFile(file)
            first.edit().putInt("a", 2).apply()
            assertEquals(2, second.getInt("a", 0), "one copy in memory")
            assertTrue(PropertiesFile.flushAll())
            assertEquals("2", onDisk(file).getProperty("a"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun openedAgainItReadsWhatTheDiskHasNow() {
        val dir = tempDir()
        try {
            val file = File(dir, "settings.properties")
            PropertiesFile(file).edit().putInt("a", 1).apply()
            assertTrue(PropertiesFile.flushAll())
            file.delete()
            assertEquals(0, PropertiesFile(file).getInt("a", 0), "a deleted file starts empty")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun openedAndEditedFromManyThreadsAtOnceNothingIsLost() {
        val dir = tempDir()
        try {
            val file = File(dir, "settings.properties")
            file.writeText("old=1\n")
            val gate = java.util.concurrent.CountDownLatch(1)
            val threads = (0 until 8).map { n ->
                Thread {
                    gate.await()
                    val props = PropertiesFile(file)
                    repeat(25) { props.edit().putInt("k$n-$it", it).apply() }
                }.apply { start() }
            }
            gate.countDown()
            threads.forEach { it.join() }
            assertTrue(PropertiesFile.flushAll())
            val disk = onDisk(file)
            assertEquals("1", disk.getProperty("old"))
            for (n in 0 until 8) for (k in 0 until 25) assertEquals("$k", disk.getProperty("k$n-$k"), "k$n-$k")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun aFileThatCantBeReadAgainKeepsWhatIsInMemory() {
        val dir = tempDir()
        try {
            val file = File(dir, "settings.properties")
            val first = PropertiesFile(file)
            first.edit().putString("name", "kept").apply()
            assertTrue(first.flush())
            // A broken escape: reading it again fails, and the settings stay as they were.
            file.writeText("name=\\uZZZZ\n")
            val second = PropertiesFile(file)
            assertEquals("kept", second.getString("name", null))
            assertEquals("kept", first.getString("name", null))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun failedFlushesLeaveOneRetryWaitingNotMany() {
        val dir = tempDir()
        try {
            val file = File(dir, "settings.properties")
            val props = PropertiesFile(file)
            val blocker = File(file.path + ".new").apply { mkdirs() }
            props.edit().putInt("a", 1).apply()
            // Each failed flush waits longer before the next try: 0.5, 1, 2, 4 s … now 8 s away.
            repeat(4) { assertFalse(props.flush()) }
            val before = props.attempts
            Thread.sleep(3_000)
            assertEquals(before, props.attempts, "no earlier retry still went off")
            blocker.delete()
            assertTrue(props.flush())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun aBurstOfChangesIsWrittenOnce() {
        val dir = tempDir()
        try {
            val file = File(dir, "settings.properties")
            val props = PropertiesFile(file)
            // A slider dragged: many values in a moment.
            repeat(100) { props.edit().putInt("volume", it).apply() }
            assertTrue(eventually { props.saved })
            assertEquals(1, props.attempts)
            assertEquals("99", onDisk(file).getProperty("volume"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun aChangeMadeWhileTheFileIsReadAgainIsNotUndone() {
        val dir = tempDir()
        var held: () -> Unit = {}
        try {
            val file = File(dir, "settings.properties")
            val first = PropertiesFile(file)
            first.edit().putInt("a", 1).apply()
            assertTrue(first.flush())
            // Opened again: right after its flush another thread changes a setting, which reaches
            // the disk only after the old file was read.
            held = PropertiesFile.pauseWriter()
            PropertiesFile.afterFlush = { first.edit().putInt("a", 2).apply() }
            PropertiesFile.afterRead = { held(); assertTrue(first.flush()) }
            val second = PropertiesFile(file)
            held()
            assertEquals(2, second.getInt("a", 0), "the newer change stays")
            assertTrue(second.flush())
            assertEquals("2", onDisk(file).getProperty("a"))
        } finally {
            PropertiesFile.afterFlush = null
            PropertiesFile.afterRead = null
            held()
            dir.deleteRecursively()
        }
    }

    @Test
    fun openingAFileAgainWaitsOnlyForThatFile() {
        val dir = tempDir()
        try {
            val stuck = PropertiesFile(File(dir, "stuck.properties"))
            File(dir, "stuck.properties.new").mkdirs()
            stuck.edit().putInt("a", 1).apply()
            val other = File(dir, "other.properties")
            PropertiesFile(other).edit().putInt("b", 2).apply()
            // Another file still failing doesn't hold this one up.
            val start = System.nanoTime()
            assertEquals(2, PropertiesFile(other).getInt("b", 0))
            assertTrue((System.nanoTime() - start) / 1_000_000 < 5_000)
            File(dir, "stuck.properties.new").delete()
            assertTrue(eventually { stuck.saved })
        } finally {
            dir.deleteRecursively()
        }
    }
}
