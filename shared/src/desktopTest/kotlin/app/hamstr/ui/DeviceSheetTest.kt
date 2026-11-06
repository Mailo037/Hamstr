package app.hamstr.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
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
import app.hamstr.data.Prefs
import app.hamstr.data.PropertiesFile
import app.hamstr.nearby.DeviceKind
import app.hamstr.nearby.Nearby
import app.hamstr.ui.theme.Hamstr
import app.hamstr.ui.theme.HamstrTheme
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

/** A remembered device's sheet as the phone shows it, into shared/build/settings; look at it after changing the sheet. */
@OptIn(ExperimentalTestApi::class)
class DeviceSheetTest {
    private val out = File("build/settings").apply { mkdirs() }

    @Test fun drawsAnAwayDevice() {
        val dir = File(System.getProperty("java.io.tmpdir"), "hamstr-device-" + System.nanoTime()).apply { mkdirs() }
        Prefs.init(PropertiesFile(File(dir, "settings.properties")))
        Prefs.edit { it.copy(nearby = false) }
        val device = JSONObject().put("id", "pc037").put("name", "PC037").put("kind", DeviceKind.Desktop.name)
            .put("key", "").put("seen", System.currentTimeMillis() - 5 * 86_400_000L).put("trusted", true)
        File(dir, "nearby.json").writeText(JSONObject().put("devices", JSONArray().put(device)).toString())
        Nearby.inbox = File(dir, "inbox")
        Nearby.init(PropertiesFile(File(dir, "nearby.properties")), dir, DeviceKind.Phone, "Test", emptyList())
        val scale = 2.625f
        runDesktopComposeUiTest(1080, 2000) {
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(scale)) {
                    HamstrTheme {
                        Box(
                            Modifier.size((1080 / scale).dp, (2000 / scale).dp).background(Color.Black).testTag("shot"),
                            contentAlignment = Alignment.BottomCenter,
                        ) {
                            Box(Modifier.fillMaxSize().background(Hamstr.colors.canvas.copy(alpha = 0.6f)))
                            DeviceSheet("pc037", null, onPickFiles = { emptyList() }, onClose = {})
                        }
                    }
                }
            }
            mainClock.advanceTimeBy(1_500)
            onNodeWithText("Forget this device", useUnmergedTree = true).assertExists()
            ImageIO.write(onNodeWithTag("shot").captureToImage().toAwtImage(), "png", File(out, "device_phone.png"))
        }
    }
}
