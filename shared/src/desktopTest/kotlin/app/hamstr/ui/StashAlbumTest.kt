package app.hamstr.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import app.hamstr.data.Choice
import app.hamstr.data.DownloadTask
import app.hamstr.data.Mode
import app.hamstr.data.Status
import app.hamstr.engine.EngineState
import app.hamstr.ui.theme.HamstrTheme
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

/** An album that came in together stays one row in Stashed, which opens to its songs. */
@OptIn(ExperimentalTestApi::class)
class StashAlbumTest {
    private val song = Choice(Mode.Audio, null, "mp4", "mp3", false)
    private val now = System.currentTimeMillis()

    private fun track(i: Int, title: String) = DownloadTask(
        url = "https://example.com/$i", title = title, thumbnail = null, source = "youtube", durationSec = 110, choice = song,
        status = Status.Done, sizeBytes = 3L shl 20, createdAt = now - 60_000 + i, finishedAt = now - 1_000 * i,
        group = "album", groupTitle = "I don't play 'bout you", uploader = "whyslow",
    )

    private val tasks = listOf(
        track(4, "I don't play 'bout you (Sped Up)"),
        track(3, "I don't play 'bout you (Super Slowed)"),
        track(2, "I don't play 'bout you (Slowed)"),
        track(1, "I don't play 'bout you #JerseyClub"),
        DownloadTask(url = "https://example.com/a", title = "Nothing Left", thumbnail = null, source = "youtube", durationSec = 200, choice = song,
            status = Status.Done, sizeBytes = 11L shl 20, createdAt = now - 600_000, finishedAt = now - 600_000, uploader = "Ross Tregenza - Topic"),
    )

    @Test fun albumIsOneRowThatOpens() = runComposeUiTest {
        setContent {
            HamstrTheme {
                Box(Modifier.size(420.dp, 900.dp)) {
                    StashScreen(tasks, EngineState.Ready("test"), PaddingValues(0.dp), {}, {}, {}, { _, _ -> }, {}, {}, {})
                }
            }
        }
        waitForIdle()
        onNodeWithText("I don't play 'bout you").assertExists()
        onNodeWithText("4 songs", substring = true, ignoreCase = true).assertExists()
        assertEquals(0, onAllNodesWithText("I don't play 'bout you (Slowed)").fetchSemanticsNodes().size, "closed")
        onNodeWithText("Nothing Left").assertExists()
        save("stash_album_closed")

        onNodeWithText("I don't play 'bout you").performClick()
        waitForIdle()
        mainClock.advanceTimeBy(1_000)
        onNodeWithText("I don't play 'bout you (Slowed)").assertExists()
        onNodeWithText("I don't play 'bout you #JerseyClub").assertExists()
        save("stash_album_open")
    }

    private fun androidx.compose.ui.test.ComposeUiTest.save(name: String) {
        val dir = File(System.getProperty("java.io.tmpdir"), "hamstr-tests").apply { mkdirs() }
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(dir, "$name.png"))
    }
}
