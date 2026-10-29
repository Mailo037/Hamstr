package app.hamstr.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.hamstr.data.DownloadStore
import app.hamstr.data.Downloads
import app.hamstr.data.FileChecks
import app.hamstr.data.Positions
import app.hamstr.data.Prefs
import app.hamstr.data.PropertiesFile
import app.hamstr.nearby.DeviceKind
import app.hamstr.nearby.LanTransport
import app.hamstr.nearby.Nearby
import app.hamstr.ui.theme.HamstrTheme
import java.awt.Dimension
import java.io.File

fun main() {
    Paths.data.mkdirs()
    Prefs.init(PropertiesFile(File(Paths.data, "settings.properties")))
    Positions.init(PropertiesFile(File(Paths.data, "positions.properties")))
    DownloadStore.init(Paths.data)
    Downloads.control = Queue
    Queue.start()
    FileChecks.refresh()
    Updater.checkOnLaunch()
    Nearby.init(
        PropertiesFile(File(Paths.data, "nearby.properties")), Paths.data, DeviceKind.Desktop,
        defaultName = System.getenv("COMPUTERNAME") ?: runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrNull() ?: "Computer",
        transports = listOf(LanTransport()),
    )
    Nearby.onLink = { url, _, _ -> Queue.stash(url) }
    val icon = Thread.currentThread().contextClassLoader.getResourceAsStream("hamstr_window.png")?.use { BitmapPainter(loadImageBitmap(it)) }

    application {
        Window(
            onCloseRequest = ::exitApplication,
            state = rememberWindowState(size = DpSize(1120.dp, 760.dp), position = WindowPosition(Alignment.Center)),
            title = "Hamstr",
            icon = icon,
            // Hamstr draws its own title bar; macOS keeps its traffic lights over it. Linux has no
            // rounded frameless windows, so there the window is see-through and the app clips itself.
            undecorated = !Paths.mac,
            transparent = !Paths.mac && !Paths.windows,
        ) {
            val frame = remember { WindowFrame(window) }
            // A device asking to send a link brings the window up, where the question waits.
            LaunchedEffect(Unit) { Nearby.requests.collect { if (it.isNotEmpty()) { window.isMinimized = false; window.toFront() } } }
            LaunchedEffect(Unit) {
                window.minimumSize = Dimension(760, 520)
                frame.round()
                if (Paths.mac) window.rootPane.apply {
                    putClientProperty("apple.awt.fullWindowContent", true)
                    putClientProperty("apple.awt.transparentTitleBar", true)
                    putClientProperty("apple.awt.windowTitleVisible", false)
                }
            }
            HamstrTheme { DesktopApp(frame, onClose = ::exitApplication) }
        }
    }
}
