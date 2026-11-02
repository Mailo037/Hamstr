package app.hamstr.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.hamstr.data.Prefs
import app.hamstr.data.PropertiesFile
import app.hamstr.ui.theme.HamstrTheme
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.math.roundToInt

/** The first-start tour: its pages on each device, the buttons, and how it ends. */
@OptIn(ExperimentalTestApi::class)
class TourTest {
    @Test fun phoneHasEveryPageAndTheDesktopSkipsThePlayer() {
        assertEquals(7, TourStep.on(desktop = false).size)
        assertEquals(TourStep.entries.filterNot { it.phoneOnly }, TourStep.on(desktop = true))
        assertEquals(6, TourStep.on(desktop = true).size)
    }

    @Test fun showsOnceAndRemembersThatItWasSeen() {
        val file = File.createTempFile("hamstr-tour-", ".properties")
        try {
            Prefs.init(PropertiesFile(file))
            assertTrue(TourGate.due(), "a first start")
            TourGate.done()
            assertFalse(TourGate.due())
            // Started again: it stays seen.
            Prefs.init(PropertiesFile(file))
            assertFalse(TourGate.due())
        } finally {
            file.delete()
        }
    }

    @Test fun nextAndBackWalkThePagesAndTheLastOneFinishes() = runComposeUiTest {
        var done = 0
        setContent { HamstrTheme { Box(Modifier.size(412.dp, 900.dp)) { Tour(onDone = { done++ }, desktop = false) } } }
        mainClock.advanceTimeBy(1_000)
        title("Share a link, keep the file").assertExists()
        onNodeWithText("Back").assertDoesNotExist()

        onNodeWithText("Next").performClick()
        mainClock.advanceTimeBy(1_500)
        title("Share from any app").assertExists()
        onNodeWithText("Back").performClick()
        mainClock.advanceTimeBy(1_500)
        title("Share a link, keep the file").assertExists()

        repeat(6) {
            onNodeWithText("Next").performClick()
            mainClock.advanceTimeBy(1_500)
        }
        title("Make it yours").assertExists()
        onNodeWithText("Skip").assertDoesNotExist()
        assertEquals(0, done)
        onNodeWithText("Start stashing").performClick()
        assertEquals(1, done)
    }

    @Test fun skipEndsTheTourRightAway() = runComposeUiTest {
        var done = 0
        setContent { HamstrTheme { Box(Modifier.size(412.dp, 900.dp)) { Tour(onDone = { done++ }, desktop = false, startAt = 2) } } }
        mainClock.advanceTimeBy(1_000)
        title("Video or just the sound").assertExists()
        onNodeWithText("Skip").performClick()
        assertEquals(1, done)
    }

    @Test fun theComputerTalksAboutPastingInstead() = runComposeUiTest {
        setContent { HamstrTheme { Box(Modifier.size(1100.dp, 700.dp)) { Tour(onDone = {}, desktop = true, startAt = 1) } } }
        mainClock.advanceTimeBy(1_000)
        title("Copy a link, paste it here").assertExists()
        title("Share from any app").assertDoesNotExist()
    }

    /**
     * Every page at a moment that shows what it is about, as a phone and as the desktop window
     * draws it, into build/tour (or -Dhamstr.tour=<folder>). For the README and reviews.
     */
    @Test fun rendersEveryPage() {
        val out = File(System.getProperty("hamstr.tour") ?: "build/tour").apply { mkdirs() }
        // The moment (ms after the page settles) each picture is caught at.
        val phone = listOf(3_000L, 2_720L, 5_330L, 2_300L, 2_500L, 3_800L, 1_500L)
        phone.forEachIndexed { i, at -> shot(File(out, "phone_${i + 1}_${TourStep.on(false)[i].name.lowercase()}.png"), false, i, at, 412, 892, 2.625f) }
        val desktop = listOf(3_000L, 4_200L, 1_200L, 2_300L, 3_800L, 1_500L)
        desktop.forEachIndexed { i, at -> shot(File(out, "desktop_${i + 1}_${TourStep.on(true)[i].name.lowercase()}.png"), true, i, at, 1100, 690, 1.5f) }
        // The tightest places: a phone held sideways and a small phone, on the tallest page.
        shot(File(out, "sideways_7_look.png"), false, 6, 1_500L, 892, 412, 2.625f)
        shot(File(out, "small_7_look.png"), false, 6, 1_500L, 360, 640, 2f)
        shot(File(out, "small_3_choose.png"), false, 2, 5_330L, 360, 640, 2f)
    }

    // A window as big as the picture, so nothing is cut off at the default 1024 × 768.
    private fun shot(file: File, desktop: Boolean, page: Int, at: Long, width: Int, height: Int, scale: Float) =
        runDesktopComposeUiTest((width * scale).roundToInt(), (height * scale).roundToInt()) {
            mainClock.autoAdvance = false
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(scale)) {
                    HamstrTheme {
                        Box(Modifier.size(width.dp, height.dp).testTag("tour")) { Tour(onDone = {}, desktop = desktop, startAt = page) }
                    }
                }
            }
            mainClock.advanceTimeBy(at)
            save(file)
        }

    /** Titles decode in from noise; screen readers, and so the test, get the real words. */
    private fun ComposeUiTest.title(text: String) = onNode(hasText(text) or hasContentDescription(text))

    private fun ComposeUiTest.save(file: File) {
        ImageIO.write(onNodeWithTag("tour").captureToImage().toAwtImage(), "png", file)
    }
}
