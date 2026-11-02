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
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.hamstr.data.Prefs
import app.hamstr.data.PropertiesFile
import app.hamstr.data.UiSize
import app.hamstr.ui.theme.HamstrTheme
import java.io.File
import java.util.Locale
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

    @Test fun swipingTheBackgroundsStaysOnTheLastPage() = runComposeUiTest {
        setContent { HamstrTheme { Box(Modifier.size(412.dp, 900.dp)) { Tour(onDone = {}, desktop = false, startAt = 6) } } }
        mainClock.advanceTimeBy(1_000)
        title("Make it yours").assertExists()
        // The row is at its start: the whole swipe is left over, and must not turn the page back.
        onNodeWithText("Graphite").performTouchInput { swipeRight(startX = centerX, endX = centerX + 300f) }
        mainClock.advanceTimeBy(2_000)
        title("Make it yours").assertExists()
        title("Pass links between devices").assertDoesNotExist()
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
        val phone = listOf(3_000L, 2_720L, 5_330L, 2_300L, 2_500L, 2_650L, 1_500L)
        phone.forEachIndexed { i, at -> shot(File(out, "phone_${i + 1}_${TourStep.on(false)[i].name.lowercase()}.png"), false, i, at, 412, 892, 2.625f) }
        val desktop = listOf(3_000L, 4_200L, 1_200L, 2_300L, 2_650L, 1_500L)
        desktop.forEachIndexed { i, at -> shot(File(out, "desktop_${i + 1}_${TourStep.on(true)[i].name.lowercase()}.png"), true, i, at, 1100, 690, 1.5f) }
        // Sending, after the link flew: the phone asks, then fetches it.
        shot(File(out, "phone_6_send_ask.png"), false, 5, 4_600L, 412, 892, 2.625f)
        shot(File(out, "phone_6_send_fetch.png"), false, 5, 7_400L, 412, 892, 2.625f)
        shot(File(out, "phone_6_send_seek.png"), false, 5, 1_000L, 412, 892, 2.625f)
        // The tightest places: a phone held sideways and a small phone, on the tallest page.
        shot(File(out, "sideways_7_look.png"), false, 6, 1_500L, 892, 412, 2.625f)
        shot(File(out, "small_7_look.png"), false, 6, 1_500L, 360, 640, 2f)
        shot(File(out, "small_3_choose.png"), false, 2, 5_330L, 360, 640, 2f)
    }

    /** The last page at every UI size: the picture shrinks and grows with the rest, never against it. */
    @Test fun rendersTheLastPageAtEverySize() {
        val out = File(System.getProperty("hamstr.tour") ?: "build/tour").apply { mkdirs() }
        val file = File.createTempFile("hamstr-size-", ".properties")
        try {
            Prefs.init(PropertiesFile(file))
            for (size in UiSize.entries) {
                Prefs.edit { it.copy(size = size) }
                shot(File(out, "size_${size.name.lowercase()}_7_look.png"), false, 6, 1_500L, 412, 892, 2.625f)
            }
        } finally {
            Prefs.init(PropertiesFile(file.apply { delete() }))
        }
    }

    /**
     * A few pages in other languages: longer words (German, Russian) and other scripts (Chinese,
     * Japanese) must fit the same layout. Written next to the English pictures.
     */
    @Test fun rendersOtherLanguages() {
        val out = File(System.getProperty("hamstr.tour") ?: "build/tour").apply { mkdirs() }
        val before = Locale.getDefault()
        try {
            for (tag in listOf("de", "es", "fr", "ru", "ja", "zh")) {
                Locale.setDefault(Locale.forLanguageTag(tag))
                for ((page, at) in listOf(1 to 2_720L, 3 to 2_300L, 6 to 1_500L)) {
                    shot(File(out, "lang_${tag}_${page + 1}.png"), false, page, at, 412, 892, 2.625f)
                }
            }
        } finally {
            Locale.setDefault(before)
        }
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
