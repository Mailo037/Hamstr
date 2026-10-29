package app.hamstr.ui

import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.unit.dp
import app.hamstr.res.Res
import app.hamstr.res.ic_close
import app.hamstr.res.ic_restore
import app.hamstr.ui.components.Group
import app.hamstr.ui.components.SettingRow
import app.hamstr.ui.components.SwipeAction
import app.hamstr.ui.components.SwipeRow
import app.hamstr.ui.theme.Hamstr
import app.hamstr.ui.theme.HamstrTheme
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

/** A swipe row: swiped on the phone, icons at its end on the desktop. */
@OptIn(ExperimentalTestApi::class)
class SwipeRowTest {
    private class Counts {
        var forgot = 0
        var kept = 0
    }

    private fun row(icons: Boolean, n: Counts) = @androidx.compose.runtime.Composable {
        HamstrTheme {
            Group(Modifier.width(420.dp)) {
                SwipeRow(
                    start = SwipeAction(Res.drawable.ic_restore, "Keep", Hamstr.colors.accent) { n.kept++ },
                    end = SwipeAction(Res.drawable.ic_close, "Forget", Hamstr.colors.red, removes = true) { n.forgot++ },
                    background = Hamstr.colors.overlay,
                    icons = icons,
                ) { SettingRow("PC0372", subtitle = "Trusted · links go straight into the stash") }
            }
        }
    }

    /** A finger moving sideways in small steps, as a swipe on the glass arrives. */
    private fun TouchInjectionScope.drag(step: Float) {
        down(center)
        repeat(30) { moveBy(Offset(step, 0f), delayMillis = 16) }
        up()
    }

    @Test fun desktopShowsIcons() = runComposeUiTest {
        val n = Counts()
        setContent(row(icons = true, n))
        onNodeWithContentDescription("Forget").performClick()
        onNodeWithContentDescription("Keep").performClick()
        waitForIdle()
        assertEquals(1, n.forgot)
        assertEquals(1, n.kept)
        val dir = File(System.getProperty("java.io.tmpdir"), "hamstr-tests").apply { mkdirs() }
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(dir, "swipe_row_desktop.png"))
    }

    @Test fun phoneSwipes() = runComposeUiTest {
        val n = Counts()
        setContent(row(icons = false, n))
        assertEquals(0, onAllNodesWithContentDescription("Forget").fetchSemanticsNodes().size, "no icons on the phone")
        onNodeWithText("PC0372").performTouchInput { drag(-12f) }
        mainClock.advanceTimeBy(1_000)
        waitForIdle()
        assertEquals(1, n.forgot, "swiped left")
        onNodeWithText("PC0372").performTouchInput { drag(12f) }
        mainClock.advanceTimeBy(1_000)
        waitForIdle()
        assertEquals(1, n.kept, "swiped right")
    }
}
