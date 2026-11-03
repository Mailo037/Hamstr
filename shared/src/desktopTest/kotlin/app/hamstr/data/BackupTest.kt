package app.hamstr.data

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BackupTest {
    private fun task(id: String, status: Status) = DownloadTask(
        id = id, url = "https://example.com/$id", title = "Song $id", thumbnail = null, source = "example", durationSec = 200,
        choice = Choice(Mode.Audio, null, "mp4", "opus", false), status = status, filePath = "/nowhere/$id.opus",
    )

    @Test
    fun aBackupBringsSettingsAndDownloadsBackButNotTheDevicesOwn() {
        val dir = File(System.getProperty("java.io.tmpdir"), "hamstr-backup-" + System.nanoTime()).apply { mkdirs() }
        Prefs.init(PropertiesFile(File(dir, "settings.properties")))
        Positions.init(PropertiesFile(File(dir, "positions.properties")))
        // The list stays in memory: initialising it here would take the folder DownloadStoreTest sets up.
        try {
            Prefs.edit { it.copy(accent = 0xFF5AB4FF.toInt(), eqOn = true, eqPreset = "rock", crossfade = 6, deviceName = "Laptop") }
            DownloadStore.replace(listOf(task("a", Status.Done), task("b", Status.Running)))
            Positions.save("a", 90_000, 200_000)
            val text = Backup.export("1.0.0")

            Prefs.edit { it.copy(accent = 0xFFFF6B6B.toInt(), eqOn = false, eqPreset = "flat", crossfade = 0, deviceName = "Other") }
            DownloadStore.replace(listOf(task("a", Status.Done)))
            Positions.clear("a")
            val restored = Backup.restore(text)

            val s = Prefs.settings.value
            assertEquals(0xFF5AB4FF.toInt(), s.accent)
            assertEquals("rock", s.eqPreset)
            assertEquals(true, s.eqOn)
            assertEquals(6, s.crossfade)
            // Each device keeps its own name.
            assertEquals("Other", s.deviceName)
            assertEquals(1, restored.downloads)
            assertEquals(listOf("a", "b"), DownloadStore.tasks.value.map { it.id })
            // What was downloading there waits here instead of starting by itself.
            assertEquals(Status.Paused, DownloadStore.get("b")?.status)
            assertEquals(90_000, Positions.resumeAt("a"))
        } finally {
            DownloadStore.replace(emptyList())
            Positions.clear("a")
            Prefs.edit { Settings() }
        }
    }

    @Test
    fun anythingElseIsRefused() {
        assertFailsWith<IllegalArgumentException> { Backup.restore("{}") }
        assertFailsWith<IllegalArgumentException> { Backup.restore("not json") }
        assertFailsWith<IllegalArgumentException> { Backup.restore("""{"kind":"hamstr-backup","format":99}""") }
    }

    @Test
    fun settingsSurviveTheTripThroughTheirKeys() {
        val s = Settings(eqGains = List(10) { it - 5f }, crossfade = 3, height = null, subLangs = listOf("de", "en"))
        assertEquals(s, Prefs.decode(Prefs.encode(s)))
    }
}
