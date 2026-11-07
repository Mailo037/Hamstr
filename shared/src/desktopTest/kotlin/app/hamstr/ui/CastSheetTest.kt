package app.hamstr.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import app.hamstr.cast.CastDevice
import app.hamstr.cast.CastLink
import app.hamstr.cast.CastMedia
import app.hamstr.cast.CastProtocol
import app.hamstr.cast.CastSession
import app.hamstr.cast.CastStatus
import app.hamstr.cast.HamstrCast
import app.hamstr.nearby.DeviceKind
import app.hamstr.res.*
import app.hamstr.ui.components.Icon
import app.hamstr.ui.components.Txt
import app.hamstr.ui.theme.Hamstr
import app.hamstr.ui.theme.HamstrTheme
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

/** "Play on" in its states, as the phone and the computer show it, into shared/build/cast. */
@OptIn(ExperimentalTestApi::class)
class CastSheetTest {
    private val out = File("build/cast").apply { mkdirs() }

    private val tv = CastDevice("cast:1", "Living room TV", CastProtocol.Chromecast, "192.168.1.50", 8009, model = "Google TV Streamer")
    private val devices = listOf(
        tv,
        CastDevice(HamstrCast.PREFIX + "laptop", "Laptop", CastProtocol.Hamstr, "192.168.1.30", 0, hamstr = DeviceKind.Desktop),
        CastDevice("uuid:2", "[TV] Samsung Q80", CastProtocol.Dlna, "192.168.1.51", 9197, model = "Samsung QE55Q80"),
        CastDevice("uuid:3", "Kitchen", CastProtocol.Dlna, "192.168.1.52", 1400, model = "Sonos One", video = false),
        CastDevice("airplay:4", "Bedroom", CastProtocol.AirPlay, "192.168.1.53", 7000, model = "AppleTV3,2"),
        CastDevice("mpd:5", "Hi-fi", CastProtocol.Mpd, "192.168.1.54", 6600, model = "MPD", video = false),
    )

    private class Quiet : CastLink {
        override suspend fun connect() {}
        override suspend fun load(url: String, coverUrl: String?, media: CastMedia, at: Double, play: Boolean) {}
        override suspend fun play() {}
        override suspend fun pause() {}
        override suspend fun seek(seconds: Double) {}
        override suspend fun setVolume(level: Float) {}
        override suspend fun status() = CastStatus(CastStatus.State.Playing, 42.0, 200.0, 0.45f)
        override suspend fun stop() {}
        override fun close() {}
    }

    private fun playing(): CastSession = CastSession(tv, Quiet()).apply { setVolume(0.45f); play() }

    private fun phone(name: String, text: String, content: @Composable () -> Unit) {
        val scale = 2.625f
        runDesktopComposeUiTest(1080, 2400) {
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(scale)) {
                    HamstrTheme {
                        Box(Modifier.size((1080 / scale).dp, (2400 / scale).dp).background(Color.Black).testTag("shot"), contentAlignment = Alignment.BottomCenter) {
                            Box(Modifier.fillMaxSize().background(Hamstr.colors.canvas.copy(alpha = 0.6f)))
                            content()
                        }
                    }
                }
            }
            mainClock.advanceTimeBy(1_500)
            onNodeWithText(text, useUnmergedTree = true, substring = true).assertExists()
            ImageIO.write(onNodeWithTag("shot").captureToImage().toAwtImage(), "png", File(out, name))
        }
    }

    @Test fun drawsTheDevicesNearby() = phone("phone_cast_devices.png", "Living room TV") {
        CastSheetContent(devices, null, onOutputs = {}, onClose = {}, desktop = false, connectingId = "uuid:2")
    }

    @Test fun drawsWhatPlaysOnTheTv() = phone("phone_cast_playing.png", "Play on this phone") {
        CastSheetContent(devices, playing(), onOutputs = {}, onClose = {}, desktop = false)
    }

    @Test fun drawsNothingFound() = phone("phone_cast_empty.png", "Nothing yet") {
        CastSheetContent(emptyList(), null, onOutputs = {}, onClose = {}, desktop = false)
    }

    /** The video's place in the phone's player while it plays on the TV. */
    @Test fun drawsThePlayerAsTheRemote() = phone("phone_cast_player.png", "This is the remote") {
        val c = Hamstr.colors
        Column(Modifier.fillMaxSize().background(c.canvas).padding(top = 48.dp)) {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black), contentAlignment = Alignment.Center) {
                CastingCard(tv)
            }
            Column(Modifier.padding(20.dp)) {
                Txt("Big Buck Bunny", style = Hamstr.type.section, maxLines = 1)
                Txt("Blender Foundation", style = Hamstr.type.small, color = c.muted)
                Spacer(Modifier.height(18.dp))
                Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(c.line)) {
                    Box(Modifier.fillMaxWidth(0.21f).height(4.dp).background(c.accent))
                }
                Spacer(Modifier.height(24.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    Icon(Res.drawable.ic_skip_previous, tint = c.ink, size = 30.dp)
                    Box(Modifier.size(64.dp).clip(RoundedCornerShape(32.dp)).background(c.accent), contentAlignment = Alignment.Center) {
                        Icon(Res.drawable.ic_pause, tint = c.onAccent, size = 30.dp)
                    }
                    Icon(Res.drawable.ic_skip_next, tint = c.ink, size = 30.dp)
                }
                Spacer(Modifier.height(24.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Icon(Res.drawable.ic_lyrics, tint = c.muted, size = 22.dp)
                    Spacer(Modifier.width(22.dp))
                    Icon(Res.drawable.ic_equalizer, tint = c.muted, size = 22.dp)
                    Spacer(Modifier.width(22.dp))
                    Icon(Res.drawable.ic_cast, tint = c.accent, size = 22.dp)
                    Spacer(Modifier.width(22.dp))
                    Icon(Res.drawable.ic_queue_music, tint = c.muted, size = 22.dp)
                }
            }
        }
    }

    @Test fun drawsTheComputersSheet() {
        runDesktopComposeUiTest(1100, 760) {
            setContent {
                HamstrTheme {
                    Box(Modifier.size(1100.dp, 760.dp).background(Hamstr.colors.canvas).testTag("shot"), contentAlignment = Alignment.Center) {
                        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)))
                        Box(Modifier.widthIn(max = 600.dp)) { CastSheetContent(devices, null, onOutputs = null, onClose = {}, desktop = true) }
                    }
                }
            }
            mainClock.advanceTimeBy(1_500)
            onNodeWithText("Living room TV", useUnmergedTree = true).assertExists()
            ImageIO.write(onNodeWithTag("shot").captureToImage().toAwtImage(), "png", File(out, "desktop_cast_devices.png"))
        }
    }
}
