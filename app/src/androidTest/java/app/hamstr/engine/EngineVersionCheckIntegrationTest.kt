package app.hamstr.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.hamstr.data.Prefs
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class EngineVersionCheckIntegrationTest {
    @Test fun checkingVersionsNeverInstallsOrChangesTheRuntime(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val original = Prefs.settings.value
        Prefs.edit { it.copy(autoUpdate = false) }
        try {
            withTimeout(60_000) { Engine.awaitReady() }
            val archive = File(context.noBackupFilesDir, "youtubedl-android/yt-dlp/yt-dlp")
            fun hash() = MessageDigest.getInstance("SHA-256").digest(archive.readBytes()).toList()
            val before = hash()
            val modified = archive.lastModified()
            val installed = YoutubeDL.getInstance().execute(YoutubeDLRequest(emptyList()).apply { addOption("--version") }).out.trim()

            Engine.checkVersion { installed }
            assertEquals(EngineVersionCheck.Current, Engine.versionCheck.value)
            assertEquals(EngineState.Ready(installed), Engine.state.value)

            Engine.checkVersion { "2099.01.01" }
            assertEquals(EngineVersionCheck.Available("2099.01.01"), Engine.versionCheck.value)

            Engine.checkVersion { "1900.01.01" }
            assertEquals(EngineVersionCheck.Current, Engine.versionCheck.value)

            Engine.checkVersion { error("Offline fixture") }
            assertTrue(Engine.versionCheck.value is EngineVersionCheck.Failed)
            assertEquals(before, hash())
            assertEquals(modified, archive.lastModified())
            assertEquals(original.lastUpdateCheck, Prefs.settings.value.lastUpdateCheck)
        } finally {
            Prefs.edit { original }
        }
    }
}
