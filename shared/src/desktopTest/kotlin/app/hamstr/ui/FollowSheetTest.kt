package app.hamstr.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import app.hamstr.data.Mode
import app.hamstr.data.Prefs
import app.hamstr.data.PropertiesFile
import app.hamstr.data.Subscription
import app.hamstr.data.Subscriptions
import app.hamstr.res.*
import app.hamstr.ui.components.MenuGroup
import app.hamstr.ui.components.MenuRow
import app.hamstr.ui.components.Sheet
import app.hamstr.ui.components.SheetHeader
import app.hamstr.ui.theme.Hamstr
import app.hamstr.ui.theme.HamstrTheme
import org.jetbrains.compose.resources.stringResource
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

/** The follow sheet, before and after following, as the phone shows it, into shared/build/settings. */
@OptIn(ExperimentalTestApi::class)
class FollowSheetTest {
    private val out = File("build/settings").apply { mkdirs() }
    private val link = "https://www.youtube.com/@agony"
    private val release = mapOf("follow_followed.png" to "phone_follow.png", "download_menu.png" to "phone_download_menu.png")

    private fun setUp(followed: Boolean) {
        val dir = File(System.getProperty("java.io.tmpdir"), "hamstr-follow-" + System.nanoTime()).apply { mkdirs() }
        Prefs.init(PropertiesFile(File(dir, "settings.properties")))
        Subscriptions.init(dir)
        Subscriptions.replace(
            if (followed) listOf(Subscription(url = Subscriptions.feedUrl(link), title = "AGONY", mode = Mode.Audio, checkedAt = System.currentTimeMillis() - 51 * 60_000L))
            else emptyList(),
        )
    }

    private fun shot(name: String, text: String, content: @Composable () -> Unit) {
        val scale = 2.625f
        runDesktopComposeUiTest(1080, 2400) {
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(scale)) {
                    HamstrTheme {
                        Box(
                            Modifier.size((1080 / scale).dp, (2400 / scale).dp).background(Color.Black).testTag("shot"),
                            contentAlignment = Alignment.BottomCenter,
                        ) {
                            Box(Modifier.fillMaxSize().background(Hamstr.colors.canvas.copy(alpha = 0.6f)))
                            content()
                        }
                    }
                }
            }
            mainClock.advanceTimeBy(1_500)
            onNodeWithText(text, useUnmergedTree = true).assertExists()
            val image = onNodeWithTag("shot").captureToImage().toAwtImage()
            ImageIO.write(image, "png", File(out, name))
            // Also whole phone screens for a release picture (tools/render_release.py).
            release[name]?.let { ImageIO.write(image, "png", File(File("build/release").apply { mkdirs() }, it)) }
        }
    }

    @Test fun drawsBeforeFollowing() {
        setUp(followed = false)
        shot("follow_new.png", "Follow") { FollowSheet("Lieless", link, onOpen = {}, onShare = {}, onClose = {}, onBrowse = {}) }
    }

    @Test fun drawsWhileFollowing() {
        setUp(followed = true)
        shot("follow_followed.png", "Stop following") { FollowSheet("AGONY", link, onOpen = {}, onShare = {}, onClose = {}, onBrowse = {}) }
    }

    /** The phone's menu for a queued download (MainMenus.kt), without its moving preview: the same pieces in the same order. */
    @Test fun drawsAQueuedDownloadsMenu() {
        setUp(followed = false)
        shot("download_menu.png", "Cancel") {
            Sheet {
                SheetHeader("Mine", subtitle = "https://music.youtube.com/watch?v=zJg87x2ezdg")
                Spacer(Modifier.height(14.dp))
                MenuGroup { MenuRow(Res.drawable.ic_share, stringResource(Res.string.menu_tile_share)) {} }
                Spacer(Modifier.height(10.dp))
                MenuGroup {
                    MenuRow(Res.drawable.ic_play_arrow, stringResource(Res.string.menu_download_now)) {}
                    MenuRow(Res.drawable.ic_timer, stringResource(Res.string.menu_tonight), subtitle = stringResource(Res.string.menu_tonight_hint, "01:00")) {}
                }
                Spacer(Modifier.height(10.dp))
                MenuGroup { MenuRow(Res.drawable.ic_close, stringResource(Res.string.menu_cancel), danger = true) {} }
            }
        }
    }
}
