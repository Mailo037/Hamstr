package app.hamstr.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.testTag
import javax.imageio.ImageIO
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import app.hamstr.data.AppLanguages
import app.hamstr.data.Prefs
import app.hamstr.data.PropertiesFile
import app.hamstr.ui.theme.HamstrTheme
import java.io.File
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

/** Picking a language: every one is offered by its own name, and the words change at once. */
@OptIn(ExperimentalTestApi::class)
class LanguageTest {
    @Test fun findsOurTagForAnyLocale() {
        assertEquals("de", AppLanguages.of(Locale.forLanguageTag("de-AT")))
        assertEquals("pt-BR", AppLanguages.of(Locale.forLanguageTag("pt-PT")))
        assertEquals("zh-CN", AppLanguages.of(Locale.forLanguageTag("zh-Hans-CN")))
        assertEquals("", AppLanguages.of(Locale.forLanguageTag("sv")))
        assertEquals("", AppLanguages.of(null))
    }

    @Test fun pickingALanguageRedrawsInIt() {
        val file = File.createTempFile("hamstr-lang-", ".properties")
        val before = Locale.getDefault()
        try {
            Locale.setDefault(Locale.ENGLISH)
            Prefs.init(PropertiesFile(file))
            runComposeUiTest {
                // As the desktop app does it: the default locale changes, then all is drawn again.
                setContent {
                    val s by Prefs.settings.collectAsState()
                    key(s.language) {
                        HamstrTheme {
                            Box(Modifier.size(600.dp, 260.dp).testTag("language")) {
                                LanguageBlock(s.language) { tag ->
                                    Locale.setDefault(if (tag.isEmpty()) Locale.ENGLISH else AppLanguages.locale(tag))
                                    Prefs.edit { it.copy(language = tag) }
                                }
                            }
                        }
                    }
                }
                onNodeWithText("Language").assertExists()
                onNodeWithText("System default").assertExists()
                onNodeWithText("Deutsch").performClick()
                waitForIdle()
                onNodeWithText("Sprache").assertExists()
                // A picture of the picker in German, next to the tour's (build/tour).
                val out = File(System.getProperty("hamstr.tour") ?: "build/tour").apply { mkdirs() }
                ImageIO.write(onNodeWithTag("language").captureToImage().toAwtImage(), "png", File(out, "language_de.png"))
                onNodeWithText("Systemstandard").performClick()
                waitForIdle()
                onNodeWithText("Language").assertExists()
            }
            assertEquals("", Prefs.settings.value.language)
        } finally {
            Locale.setDefault(before)
            file.delete()
        }
    }
}
