package app.hamstr.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import app.hamstr.data.Choice
import app.hamstr.data.DownloadStore
import app.hamstr.data.DownloadTask
import app.hamstr.data.Mode
import app.hamstr.data.Status
import app.hamstr.ui.theme.HamstrTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/** A tick redraws the row of its own download, not the others on screen. */
@OptIn(ExperimentalTestApi::class)
class ProgressSubscriptionTest {
    private fun running(id: String) = DownloadTask(
        id = id, url = "https://example.com/$id", title = id, thumbnail = null, source = null, durationSec = 0,
        choice = Choice(Mode.Video, null, "mp4", "opus", false), status = Status.Running,
    )

    // Like TaskRow: a composable of its own, so it is redrawn on its own.
    @Composable
    private fun Row(t: DownloadTask, drawn: MutableMap<String, Int>, shown: MutableMap<String, Float>) {
        val now = rememberCurrent(t)
        SideEffect {
            drawn[t.id] = drawn.getValue(t.id) + 1
            shown[t.id] = now.progress
        }
    }

    @Test
    fun aTickRecomposesOnlyTheRowOfItsDownload() = runComposeUiTest {
        val a = running("a")
        val b = running("b")
        DownloadStore.replace(listOf(a, b))
        val drawn = mutableMapOf("a" to 0, "b" to 0)
        val shown = mutableMapOf<String, Float>()
        try {
            setContent {
                Column {
                    Row(a, drawn, shown)
                    Row(b, drawn, shown)
                }
            }
            waitForIdle()
            val before = drawn.toMap()

            DownloadStore.tick("b") { it.copy(progress = 0.4f) }
            waitForIdle()
            assertEquals(0.4f, shown["b"])
            assertEquals(before.getValue("a"), drawn.getValue("a"), "a's row stays as it is")

            val afterB = drawn.toMap()
            DownloadStore.tick("a") { it.copy(progress = 0.7f) }
            waitForIdle()
            assertEquals(0.7f, shown["a"])
            assertEquals(afterB.getValue("b"), drawn.getValue("b"), "b's row stays as it is")
        } finally {
            DownloadStore.replace(emptyList())
        }
    }
    @Test
    fun aTaskRowShowsItsDownloadsTicks() = runComposeUiTest {
        val a = running("a")
        DownloadStore.replace(listOf(a))
        try {
            // A running row animates without end (its rain, its title): the clock moves by hand, never "idle".
            mainClock.autoAdvance = false
            setContent { HamstrTheme { TaskRow(a, onClick = {}, onLongClick = {}) } }
            mainClock.advanceTimeBy(100)
            DownloadStore.tick("a") { it.copy(progress = 0.42f, stage = null) }
            mainClock.advanceTimeBy(100)
            // The row was handed the entry once; the tick reaches it by itself.
            onNodeWithText("42%").assertExists()
        } finally {
            DownloadStore.replace(emptyList())
        }
    }
}
