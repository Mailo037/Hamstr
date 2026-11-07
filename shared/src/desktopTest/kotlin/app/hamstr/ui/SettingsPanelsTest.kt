package app.hamstr.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.hamstr.data.Prefs
import app.hamstr.data.PropertiesFile
import app.hamstr.res.*
import app.hamstr.ui.components.Divider
import app.hamstr.ui.components.Group
import app.hamstr.ui.components.HSlider
import app.hamstr.ui.components.IconButton
import app.hamstr.ui.components.Txt
import app.hamstr.ui.theme.Hamstr
import app.hamstr.ui.theme.HamstrTheme
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.roundToInt
import kotlin.test.Test

/**
 * The equalizer and Data & backup as the phone and the computer draw them, into
 * shared/build/settings; look at the pictures after changing either.
 */
@OptIn(ExperimentalTestApi::class)
class SettingsPanelsTest {
    private val out = File("build/settings").apply { mkdirs() }

    private val use = StorageUse(
        listOf(
            StorageUse.Part(StorageUse.Kind.Downloads, 7_400_000_000),
            StorageUse.Part(StorageUse.Kind.App, 96_000_000),
            StorageUse.Part(StorageUse.Kind.Engine, 182_000_000),
            StorageUse.Part(StorageUse.Kind.Data, 21_000_000),
            StorageUse.Part(StorageUse.Kind.Cache, 64_000_000),
        ),
        free = 41_000_000_000, total = 128_000_000_000,
    )

    @Test fun drawsTheEqualizerWithAPreset() {
        val file = File.createTempFile("hamstr-eq-", ".properties")
        try {
            Prefs.init(PropertiesFile(file))
            Prefs.edit { it.copy(eqOn = true, eqPreset = "rock") }
            shot("eq_phone.png", 412, 2.625f, check = "Rock") { Group { EqualizerBlock() } }
            Prefs.edit { it.copy(eqPreset = "custom", eqGains = listOf(-4f, 0f, 3f, 6f, 2f, -2f, -6f, 0f, 4f, 9f)) }
            shot("eq_custom_desktop.png", 640, 1f, check = "Custom") { Group { EqualizerBlock() } }
        } finally {
            // What is still being written would bring the file back after the delete.
            PropertiesFile.flushAll()
            Prefs.init(PropertiesFile(file.apply { delete() }))
        }
    }

    @Test fun drawsTheStorageRingNarrowAndWide() {
        shot("storage_phone.png", 412, 2.625f, check = "Downloads") { Group { StorageChart(use) } }
        shot("storage_desktop.png", 640, 1f, check = "Cache") { Group { StorageChart(use) } }
        shot("storage_measuring.png", 412, 2.625f) { Group { StorageChart(null) } }
    }

    @Test fun drawsTheWholeDataPage() {
        shot("data_phone.png", 412, 2.625f, check = "Backup") {
            Column {
                DataSettings(measure = { use }, clearCache = { 0 }, export = { BackupResult.Saved }, import = { null })
            }
        }
    }

    /**
     * Both pages as a phone shows them, a whole screen each (1080 × 2400), for a release picture
     * made without an emulator (tools/render_release.py).
     */
    @Test fun drawsPhoneScreensForTheReleasePicture() {
        val file = File.createTempFile("hamstr-eq-", ".properties")
        try {
            Prefs.init(PropertiesFile(file))
            Prefs.edit { it.copy(eqOn = true, eqPreset = "custom", eqGains = listOf(5f, 4f, 2f, 0f, -1f, 0f, 1f, 3f, 4f, 5f), crossfade = 6, normalize = true) }
            screen("phone_eq.png", Res.string.page_playback) {
                Group {
                    val s = Prefs.settings.value
                    FxRow(stringResource(Res.string.set_resume_videos), stringResource(Res.string.set_resume_videos_hint), s.resumeAfterVideos) {}
                    Divider()
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Txt(stringResource(Res.string.set_crossfade), modifier = Modifier.weight(1f))
                            Txt(stringResource(Res.string.set_crossfade_secs, s.crossfade), style = Hamstr.type.mono, color = Hamstr.colors.accent)
                        }
                        HSlider(s.crossfade, 0..12, "") {}
                        Txt(stringResource(Res.string.set_crossfade_hint), style = Hamstr.type.small, color = Hamstr.colors.ghost)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Group { EqualizerBlock() }
            }
            screen("phone_data.png", Res.string.page_data) {
                DataSettings(measure = { use }, clearCache = { 0 }, export = { null }, import = { null })
            }
        } finally {
            // What is still being written would bring the file back after the delete.
            PropertiesFile.flushAll()
            Prefs.init(PropertiesFile(file.apply { delete() }))
        }
    }

    private fun screen(name: String, title: StringResource, content: @Composable ColumnScope.() -> Unit) =
        runDesktopComposeUiTest(1080, 2400) {
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(2.625f)) {
                    HamstrTheme {
                        Column(Modifier.size((1080 / 2.625f).dp, (2400 / 2.625f).dp).background(Hamstr.colors.canvas).padding(horizontal = 12.dp).testTag("shot")) {
                            Spacer(Modifier.height(36.dp))
                            Row(Modifier.padding(horizontal = 2.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                IconButton(Res.drawable.ic_arrow_back, "", {}, tint = Hamstr.colors.ink)
                                Spacer(Modifier.width(12.dp))
                                Txt(stringResource(title), style = Hamstr.type.title)
                            }
                            Spacer(Modifier.height(14.dp))
                            content()
                        }
                    }
                }
            }
            mainClock.advanceTimeBy(1_500)
            ImageIO.write(onNodeWithTag("shot").captureToImage().toAwtImage(), "png", File(out, name))
        }

    private fun shot(name: String, width: Int, scale: Float, check: String? = null, content: @Composable () -> Unit) =
        runDesktopComposeUiTest((width * scale).roundToInt(), (1400 * scale).roundToInt()) {
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(scale)) {
                    HamstrTheme {
                        Column(Modifier.width(width.dp).background(Hamstr.colors.canvas).padding(12.dp).testTag("shot")) { content() }
                    }
                }
            }
            mainClock.advanceTimeBy(1_500)
            check?.let { onNodeWithText(it, substring = true, useUnmergedTree = true).assertExists() }
            ImageIO.write(onNodeWithTag("shot").captureToImage().toAwtImage(), "png", File(out, name))
        }
}
