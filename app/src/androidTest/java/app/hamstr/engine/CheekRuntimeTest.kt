package app.hamstr.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.hamstr.BuildConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** A Cheek release downloaded after the APK was built runs from the app's own files. */
@RunWith(AndroidJUnit4::class)
class CheekRuntimeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun onlySemverCompatibleNewerReleasesAreTaken() {
        val (major, minor, patch) = BuildConfig.CHEEK_VERSION.split('.').map { it.toInt() }
        assertTrue(Cheek.compatible("$major.$minor.${patch + 1}"))
        assertTrue(Cheek.accepts("$major.$minor.${patch + 1}"))
        assertFalse(Cheek.accepts(BuildConfig.CHEEK_VERSION))
        assertFalse(Cheek.accepts("$major.$minor.${maxOf(patch - 1, 0)}"))
        assertFalse(Cheek.compatible("${major + 1}.0.0"))
        if (major == 0) assertFalse(Cheek.compatible("0.${minor + 1}.0"))
        assertFalse(Cheek.compatible("nightly"))
    }

    @Test fun installedUpdateRunsThroughTheLoader(): Unit = runBlocking {
        val updates = File(context.filesDir, "cheek")
        val (major, minor, patch) = BuildConfig.CHEEK_VERSION.split('.').map { it.toInt() }
        val version = "$major.$minor.${patch + 50}"
        try {
            val bytes = File(context.applicationInfo.nativeLibraryDir, "libcheek.so").readBytes()
            Cheek.install(version, bytes)
            assertEquals(version, Cheek.active.value)
            assertTrue(Cheek.updated)
            // The test reuses the bundled executable, so it reports the bundled number.
            assertTrue(Cheek.version().isNotBlank())
            assertEquals(version, File(updates, "current").readText())
        } finally {
            updates.deleteRecursively()
        }
    }
}
