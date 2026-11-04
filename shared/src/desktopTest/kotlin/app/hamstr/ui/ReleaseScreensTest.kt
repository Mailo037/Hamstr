package app.hamstr.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import app.hamstr.data.DownloadTask
import app.hamstr.data.Mode
import app.hamstr.data.Prefs
import app.hamstr.data.PropertiesFile
import app.hamstr.data.Settings
import app.hamstr.data.Stage
import app.hamstr.data.Status
import app.hamstr.data.Subscription
import app.hamstr.data.Subscriptions
import app.hamstr.engine.EngineState
import app.hamstr.res.*
import app.hamstr.ui.components.IconButton
import app.hamstr.ui.components.Txt
import app.hamstr.ui.theme.Hamstr
import app.hamstr.ui.theme.HamstrTheme
import org.jetbrains.compose.resources.stringResource
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

/**
 * The stash with what waits and what failed, and Settings › Following, a whole phone screen each
 * (1080 × 2400) into shared/build/release, for a release picture made without an emulator
 * (tools/render_release.py).
 */
@OptIn(ExperimentalTestApi::class)
class ReleaseScreensTest {
    private val out = File("build/release").apply { mkdirs() }
    private val now = System.currentTimeMillis()
    private val video = Settings().defaultChoice()
    private val song = video.copy(mode = Mode.Audio)

    private fun task(i: Int, title: String, by: String, status: Status, stage: Stage? = null, progress: Float = 0f, error: String? = null, audio: Boolean = false) =
        DownloadTask(
            id = "t$i", url = "https://example.com/$i", title = title, thumbnail = null, source = "youtube", durationSec = 240,
            choice = if (audio) song else video, status = status, stage = stage, progress = progress, error = error, uploader = by,
            speed = if (status == Status.Running) "6.4 MB/s" else "", etaSec = if (status == Status.Running) 48 else -1,
            sizeBytes = if (status == Status.Done) (40L + i * 7) shl 20 else 0, videoHeight = if (status == Status.Done && !audio) 1080 else 0,
            createdAt = now - i * 60_000, finishedAt = if (status == Status.Done) now - i * 3_600_000 else 0,
        )

    private val tasks = listOf(
        task(1, "Rainy Tokyo walk at night", "Night Drive Sessions", Status.Queued, Stage.WaitingForNight),
        task(2, "Lofi beats to study to", "Lofi Garden", Status.Queued, Stage.WaitingForNight, audio = true),
        task(3, "Ten-minute ramen", "Tiny Kitchen", Status.Queued, Stage.WaitingForNight),
        task(4, "Mountain timelapse 4K", "Skyline", Status.Running, progress = 0.42f),
        task(5, "Live set from the harbour", "Harbour FM", Status.Failed, error = "ERROR: [youtube] abc: nsig extraction failed"),
        task(6, "Bike repair basics", "Workshop", Status.Failed, error = "ERROR: Unable to extract player response"),
        task(7, "Late night jazz", "Lofi Garden", Status.Done, audio = true),
        task(8, "Street food in Bangkok", "Tiny Kitchen", Status.Done),
        task(9, "Coastal drive, no talking", "Night Drive Sessions", Status.Done),
    )

    @Test fun drawsTheStashAndFollowing() {
        val file = File.createTempFile("hamstr-release-", ".properties")
        try {
            Prefs.init(PropertiesFile(file))
            // Titles that decode in from noise would be caught halfway.
            Prefs.edit { it.copy(nightOnly = true, nightFrom = 60, nightTo = 420, decode = false) }
            screen("phone_stash.png", check = "Start now") {
                StashScreen(
                    tasks, EngineState.Ready("test"), PaddingValues(top = 36.dp, bottom = 24.dp), {}, {}, {}, { _, _ -> }, {}, {}, {},
                    clip = ClipOffer("https://www.youtube.com/watch?v=dQw4w9WgXcQ"),
                )
            }
            Subscriptions.replace(
                listOf(
                    Subscription(id = "a", url = "https://www.youtube.com/@lofigarden/videos", title = "Lofi Garden", mode = Mode.Audio, checkedAt = now - 35 * 60_000, stashed = 12),
                    Subscription(id = "b", url = "https://www.youtube.com/@nightdrive/videos", title = "Night Drive Sessions", checkedAt = now - 95 * 60_000, stashed = 4),
                    Subscription(id = "c", url = "https://www.youtube.com/@tinykitchen/videos", title = "Tiny Kitchen", checkedAt = now - 160 * 60_000, stashed = 7),
                ),
            )
            screen("phone_following.png", check = "Tiny Kitchen") {
                Column(Modifier.padding(horizontal = 12.dp)) {
                    Spacer(Modifier.height(36.dp))
                    Row(Modifier.padding(horizontal = 2.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(Res.drawable.ic_arrow_back, "", {}, tint = Hamstr.colors.ink)
                        Spacer(Modifier.width(12.dp))
                        Txt(stringResource(Res.string.page_following), style = Hamstr.type.title)
                    }
                    Spacer(Modifier.height(14.dp))
                    FollowingSettings()
                }
            }
        } finally {
            Subscriptions.replace(emptyList())
            Prefs.init(PropertiesFile(file.apply { delete() }))
        }
    }

    private fun screen(name: String, check: String, content: @Composable () -> Unit) =
        runDesktopComposeUiTest(1080, 2400) {
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(2.625f)) {
                    HamstrTheme {
                        Box(Modifier.size((1080 / 2.625f).dp, (2400 / 2.625f).dp).background(Hamstr.colors.canvas).testTag("shot")) { content() }
                    }
                }
            }
            waitForIdle()
            onNodeWithText(check, substring = true).assertExists()
            ImageIO.write(onNodeWithTag("shot").captureToImage().toAwtImage(), "png", File(out, name))
        }
}
