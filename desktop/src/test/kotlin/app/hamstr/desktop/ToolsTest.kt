package app.hamstr.desktop

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ToolsTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val exe = if (Paths.windows) ".exe" else ""

    private interface Kernel32 : Library {
        fun CreateFileW(
            name: WString,
            access: Int,
            share: Int,
            security: Pointer?,
            creation: Int,
            attributes: Int,
            template: Pointer?,
        ): Pointer

        fun CloseHandle(handle: Pointer): Int
    }

    private fun withBundle(test: (File, File) -> Unit) {
        test(temporary.newFolder("bundled"), temporary.newFolder("installed"))
    }

    private fun tool(folder: File, name: String, contents: String): File =
        File(folder, "$name$exe").apply { writeText(contents) }

    private fun pinModified(file: File): FileTime {
        // Far from the copy time, without sleeps or filesystem timestamp precision assumptions.
        Files.setLastModifiedTime(file.toPath(), FileTime.fromMillis(946_684_800_000L))
        return Files.getLastModifiedTime(file.toPath())
    }

    @Test
    fun appVersionChangeLeavesIdenticalToolsUntouched() = withBundle { bundled, destination ->
        val sources = listOf(
            tool(bundled, "ffmpeg", "ffmpeg bytes"),
            tool(bundled, "hamstr-ble", "bluetooth bytes"),
            tool(bundled, "yt-dlp", "identical downloader bytes"),
        )
        Tools.install(bundled, destination, "1.0.0")
        val stamp = File(destination, "bundle.txt")
        val oldStamp = stamp.readText()
        val modified = sources.associate { source ->
            source.name to pinModified(File(destination, source.name))
        }

        Tools.install(bundled, destination, "2.0.0")

        for (source in sources) {
            val installed = File(destination, source.name)
            assertContentEquals(source.readBytes(), installed.readBytes(), source.name)
            assertEquals(modified.getValue(source.name), Files.getLastModifiedTime(installed.toPath()), source.name)
        }
        assertNotEquals(oldStamp, stamp.readText())
        assertTrue(stamp.readText().startsWith("2.0.0 "))
    }

    @Test
    fun appVersionChangePreservesIdenticalHelperLockedAgainstReplacementOnWindows() {
        assumeTrue("Windows file sharing is required", Paths.windows)
        withBundle { bundled, destination ->
            val source = tool(bundled, "hamstr-ble", "identical helper bytes")
            Tools.install(bundled, destination, "1.0.0")
            val target = File(destination, source.name)
            val bytes = target.readBytes()
            val modified = pinModified(target)
            val stamp = File(destination, "bundle.txt")
            val oldStamp = stamp.readText()
            val kernel32 = Native.load("kernel32", Kernel32::class.java)
            val handle = kernel32.CreateFileW(
                name = WString(target.absolutePath),
                access = 0x80000000.toInt(), // GENERIC_READ
                share = 0x00000001, // FILE_SHARE_READ only: no writes or deletion.
                security = null,
                creation = 3, // OPEN_EXISTING
                attributes = 0x00000080, // FILE_ATTRIBUTE_NORMAL
                template = null,
            )
            assertNotEquals(-1L, Pointer.nativeValue(handle), "CreateFileW failed: ${Native.getLastError()}")
            try {
                assertFailsWith<IOException> { source.copyTo(target, overwrite = true) }
                assertContentEquals(bytes, target.readBytes())
                assertEquals(modified, Files.getLastModifiedTime(target.toPath()))
                assertEquals(-1L, Files.mismatch(source.toPath(), target.toPath()))

                Tools.install(bundled, destination, "2.0.0")

                assertContentEquals(bytes, target.readBytes())
                assertEquals(modified, Files.getLastModifiedTime(target.toPath()))
                assertNotEquals(oldStamp, stamp.readText())
                assertTrue(stamp.readText().startsWith("2.0.0 "))
            } finally {
                assertTrue(kernel32.CloseHandle(handle) != 0, "CloseHandle failed: ${Native.getLastError()}")
            }
        }
    }

    @Test
    fun mixedBundleUpgradeReplacesOnlyChangedTools() = withBundle { bundled, destination ->
        val ffmpeg = tool(bundled, "ffmpeg", "same ffmpeg")
        val bluetooth = tool(bundled, "hamstr-ble", "same bluetooth")
        val cheek = tool(bundled, "cheek", "old cheek")
        val ffprobe = tool(bundled, "ffprobe", "old ffprobe")
        Tools.install(bundled, destination, "1.0.0")
        val unchanged = listOf(ffmpeg, bluetooth).associate { source ->
            source.name to pinModified(File(destination, source.name))
        }
        cheek.writeText("new cheek with more bytes")
        ffprobe.writeText("new ffprobe with more bytes")

        Tools.install(bundled, destination, "2.0.0")

        for (source in listOf(ffmpeg, bluetooth, cheek, ffprobe)) {
            assertContentEquals(source.readBytes(), File(destination, source.name).readBytes(), source.name)
        }
        for ((name, modified) in unchanged) {
            assertEquals(modified, Files.getLastModifiedTime(File(destination, name).toPath()), name)
        }
        assertTrue(File(destination, "bundle.txt").readText().startsWith("2.0.0 "))
        assertEquals(
            setOf(ffmpeg.name, bluetooth.name, cheek.name, ffprobe.name, "bundle.txt"),
            destination.listFiles().orEmpty().map { it.name }.toSet(),
        )
    }

    @Test
    fun missingToolIsRestoredEvenWhenBundleStampMatches() = withBundle { bundled, destination ->
        val ffmpeg = tool(bundled, "ffmpeg", "unchanged ffmpeg")
        val bluetooth = tool(bundled, "hamstr-ble", "missing bluetooth")
        Tools.install(bundled, destination, "1.0.0")
        val stamp = File(destination, "bundle.txt").readText()
        val installedFfmpeg = File(destination, ffmpeg.name)
        val modified = pinModified(installedFfmpeg)
        val missing = File(destination, bluetooth.name)
        assertTrue(missing.delete())
        assertFalse(missing.exists())

        Tools.install(bundled, destination, "1.0.0")

        assertContentEquals(bluetooth.readBytes(), missing.readBytes())
        assertContentEquals(ffmpeg.readBytes(), installedFfmpeg.readBytes())
        assertEquals(modified, Files.getLastModifiedTime(installedFfmpeg.toPath()))
        assertEquals(stamp, File(destination, "bundle.txt").readText())
    }

    @Test
    fun appVersionChangeReplacesChangedBytesEvenWhenSizeMatches() = withBundle { bundled, destination ->
        val source = tool(bundled, "ffmpeg", "old-binary")
        Tools.install(bundled, destination, "1.0.0")
        val installed = File(destination, source.name)
        val oldBytes = installed.readBytes()
        val stamp = File(destination, "bundle.txt")
        val oldStamp = stamp.readText()
        source.writeText("new-binary")
        assertEquals(installed.length(), source.length())
        assertFalse(oldBytes.contentEquals(source.readBytes()))

        Tools.install(bundled, destination, "2.0.0")

        assertContentEquals(source.readBytes(), installed.readBytes())
        assertNotEquals(oldStamp, stamp.readText())
        assertTrue(stamp.readText().startsWith("2.0.0 "))
    }

    @Test
    fun failedReplacementPreservesTargetAndStampAndCleansTemporaryFile() = withBundle { bundled, destination ->
        val source = tool(bundled, "ffmpeg", "old ffmpeg")
        Tools.install(bundled, destination, "1.0.0")
        val stamp = File(destination, "bundle.txt")
        val oldStamp = stamp.readBytes()
        val stampModified = pinModified(stamp)
        val target = File(destination, source.name)
        assertTrue(target.delete())
        // Replacing a nonempty directory fails on Windows and Unix, without permissions or real executables.
        assertTrue(target.mkdir())
        val sentinel = File(target, "keep.txt").apply { writeText("keep the old target intact") }
        val oldContents = sentinel.readBytes()
        val sentinelModified = pinModified(sentinel)
        val entries = destination.listFiles().orEmpty().map { it.name }.toSet()
        source.writeText("new ffmpeg")

        assertFailsWith<IOException> { Tools.install(bundled, destination, "2.0.0") }

        assertTrue(target.isDirectory)
        assertEquals(listOf(sentinel.name), target.listFiles().orEmpty().map { it.name })
        assertContentEquals(oldContents, sentinel.readBytes())
        assertEquals(sentinelModified, Files.getLastModifiedTime(sentinel.toPath()))
        assertContentEquals(oldStamp, stamp.readBytes())
        assertEquals(stampModified, Files.getLastModifiedTime(stamp.toPath()))
        assertEquals(entries, destination.listFiles().orEmpty().map { it.name }.toSet(), "staged files must be removed")
    }
}
