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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.rememberTrayState
import app.hamstr.engine.MediaLinks
import app.hamstr.res.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalWindowInfo
import app.hamstr.ui.components.LocalAmbient
import java.awt.Dimension
import java.awt.SystemTray
import java.io.File
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    Paths.data.mkdirs()
    // Already running: that one comes to the front (a start at sign-in leaves it be).
    if (!Instance.claim(quiet = Autostart.TRAY_FLAG in args)) return
    Prefs.init(PropertiesFile(File(Paths.data, "settings.properties")))
    Positions.init(PropertiesFile(File(Paths.data, "positions.properties")))
    DownloadStore.init(Paths.data)
    Downloads.control = Queue
    Queue.start()
    FileChecks.refresh()
    // Started again by an update: did the new version arrive?
    args.firstOrNull { it.startsWith(Updater.UPDATED_FLAG) }?.let { Updater.restarted(it.removePrefix(Updater.UPDATED_FLAG)) }
    Updater.watch()
    Nearby.init(
        PropertiesFile(File(Paths.data, "nearby.properties")), Paths.data, DeviceKind.Desktop,
        defaultName = System.getenv("COMPUTERNAME") ?: runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrNull() ?: "Computer",
        transports = listOf(LanTransport()),
    )
    val icon = Thread.currentThread().contextClassLoader.getResourceAsStream("hamstr_window.png")?.use { BitmapPainter(loadImageBitmap(it)) }
    // Not every Linux desktop has a tray; there closing the window quits as before.
    val tray = SystemTray.isSupported()
    val startHidden = Autostart.TRAY_FLAG in args && tray
    CoroutineScope(Dispatchers.IO).launch {
        Prefs.settings.map { it.startWithSystem }.distinctUntilChanged().collect(Autostart::set)
    }

    application {
        var visible by remember { mutableStateOf(!startHidden) }
        val trayState = rememberTrayState()
        val show = { visible = true }
        val close = { if (tray && Prefs.settings.value.trayOnClose) visible = false else exitApplication() }
        val received = stringResource(Res.string.nearby_received, "%s")
        LaunchedEffect(Unit) {
            Nearby.onLink = { url, title, from ->
                Queue.stash(url)
                if (tray) trayState.sendNotification(Notification(received.replace("%s", from.name), title ?: url))
            }
        }
        if (tray) {
            Tray(
                icon = icon ?: painterResource(Res.drawable.ic_download),
                state = trayState,
                tooltip = "Hamstr",
                onAction = show,
                menu = {
                    Item(stringResource(Res.string.tray_open), onClick = show)
                    Item(stringResource(Res.string.tray_paste), onClick = { Files.clipboardText()?.let(MediaLinks::find)?.let(Queue::stash) })
                    Separator()
                    Item(stringResource(Res.string.tray_quit), onClick = ::exitApplication)
                },
            )
        }
        Window(
            onCloseRequest = close,
            visible = visible,
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
            LaunchedEffect(Unit) { Nearby.requests.collect { if (it.isNotEmpty()) { visible = true; window.isMinimized = false; window.toFront() } } }
            // Opened again from the tray: in front, not behind whatever was there.
            LaunchedEffect(visible) { if (visible) { window.isMinimized = false; window.toFront() } }
            // Started a second time: this window comes up instead.
            LaunchedEffect(Unit) { Instance.show.collect { visible = true; window.isMinimized = false; window.toFront() } }
            LaunchedEffect(Unit) {
                window.minimumSize = Dimension(760, 520)
                frame.round()
                if (Paths.mac) window.rootPane.apply {
                    putClientProperty("apple.awt.fullWindowContent", true)
                    putClientProperty("apple.awt.transparentTitleBar", true)
                    putClientProperty("apple.awt.windowTitleVisible", false)
                }
            }
            // Ambient motion only while the window is in front: in the background Hamstr draws nothing.
            CompositionLocalProvider(LocalAmbient provides (visible && LocalWindowInfo.current.isWindowFocused)) {
                HamstrTheme { DesktopApp(frame, onClose = close, onQuit = ::exitApplication) }
            }
        }
    }
    // Quit means gone: a thread left running would keep Hamstr's files in use for an update.
    exitProcess(0)
}
