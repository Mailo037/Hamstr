package app.hamstr.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import app.hamstr.nearby.Nearby
import app.hamstr.nearby.Via
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import app.hamstr.data.Choice
import app.hamstr.data.DownloadTask
import app.hamstr.data.Mode
import app.hamstr.data.Status
import app.hamstr.ui.theme.HamstrTheme
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class SharingSheetTest {
    private fun task(file: File, url: String = "https://example.com/watch", status: Status = Status.Done) = DownloadTask(
        url = url, title = "A walk through the forest", thumbnail = null, source = null, durationSec = 120,
        choice = Choice(Mode.Video, 720, "mp4", "", false), status = status, filePath = file.path,
    )

    @Test fun switchesBetweenFileAndLinkWithoutSharingTheWrongContent() {
        val file = File.createTempFile("hamstr-share-", ".mp4")
        try {
            // Devices other tests left in Nearby would push the buttons out of the window.
            Nearby.peers.value.keys.forEach { id -> Nearby.forget(id); Via.entries.forEach { Nearby.gone(id, it) } }
            val content = ShareContent.from(listOf(task(file)))
            var filesShared = 0
            var copied: String? = null
            var shared: String? = null
            runComposeUiTest {
                setContent {
                    HamstrTheme {
                        Box(Modifier.size(420.dp, 820.dp)) {
                            SharingSheet(content, onCopyLink = { copied = it }, onShareLink = { shared = it }, onShareFiles = { filesShared++ })
                        }
                    }
                }
                onNodeWithText("Copy link").assertDoesNotExist()
                onNodeWithText("Share through other apps").performClick()
                assertEquals(1, filesShared)
                save("share_file")
                onNodeWithText("Link").performClick()
                mainClock.advanceTimeBy(1_000)
                onNodeWithText("Send to a device").assertExists()
                onNodeWithText("Copy link").performClick()
                assertEquals(content.url, copied)
                onNodeWithContentDescription("Share through other apps").performClick()
                assertEquals(content.url, shared)
                assertEquals(1, filesShared)
                save("share_link")
            }
        } finally { file.delete() }
    }

    @Test fun onlyOffersCompletedFilesAndNeverSharesAnImportedPathAsALink() {
        val file = File.createTempFile("hamstr-share-", ".mp4")
        try {
            val imported = ShareContent.from(listOf(task(file, file.toURI().toString())))
            assertNull(imported.url)
            assertEquals(1, imported.files.size)
            assertTrue(ShareContent.from(listOf(task(file, status = Status.Running))).files.isEmpty())
            val batch = ShareContent.from(listOf(task(file), task(file, "https://example.com/other")))
            assertNull(batch.url)
            assertEquals(2, batch.files.size)
            file.delete()
            val missing = ShareContent.from(listOf(task(file)))
            assertTrue(missing.files.isEmpty())
            assertEquals("https://example.com/watch", missing.url)
        } finally { file.delete() }
    }

    private fun androidx.compose.ui.test.ComposeUiTest.save(name: String) {
        val dir = File(System.getProperty("java.io.tmpdir"), "hamstr-tests").apply { mkdirs() }
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(dir, "$name.png"))
    }
}
