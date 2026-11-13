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
import app.hamstr.data.Subscriptions
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
import androidx.compose.runtime.key
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.collectAsState
import app.hamstr.ui.components.LocalAmbient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import app.hamstr.data.DownloadTask
import app.hamstr.data.Status
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.pluralStringResource
import java.awt.Dimension
import java.awt.SystemTray
import java.awt.Taskbar
import java.io.File
import kotlin.math.roundToInt
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    Paths.data.mkdirs()
    // Already running: that one comes to the front (a start at sign-in leaves it be).
    if (!Instance.claim(quiet = Autostart.TRAY_FLAG in args)) return
    // The history is the biggest file: read beside the settings, the splash and Compose loading.
    DownloadStore.preload(Paths.data)
    Prefs.init(PropertiesFile(File(Paths.data, "settings.properties")))
    // Not every Linux desktop has a tray; there closing the window quits as before.
    val tray = SystemTray.isSupported()
    val startHidden = Autostart.TRAY_FLAG in args && tray
    // Something on screen right away while Compose loads; a start into the tray stays quiet.
    if (!startHidden) Splash.show()
    Language.start()
    Positions.init(PropertiesFile(File(Paths.data, "positions.properties")))
    DownloadStore.init(Paths.data)
    Subscriptions.init(Paths.data)
    Downloads.control = Queue
    Queue.start()
    FileChecks.refresh()
    // Started again by an update: did the new version arrive?
    args.firstOrNull { it.startsWith(Updater.UPDATED_FLAG) }?.let { Updater.restarted(it.removePrefix(Updater.UPDATED_FLAG)) }
    Updater.watch()
    Nearby.inbox = Paths.inbox
    // Files a TV doesn't play go there as a copy made by the bundled FFmpeg.
    app.hamstr.cast.Cast.ffmpeg = { args -> ProcessBuilder(listOf(Tools.ffmpeg.path) + args) }
    app.hamstr.cast.Cast.cache = java.io.File(Paths.data, "cache/cast")
    Nearby.init(
        PropertiesFile(File(Paths.data, "nearby.properties")), Paths.data, DeviceKind.Desktop,
        defaultName = System.getenv("COMPUTERNAME") ?: runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrNull() ?: "Computer",
        transports = listOf(LanTransport(), DesktopBluetooth),
    )
    // Other Hamstr apps can play here ("Play on"), into the window's player.
    app.hamstr.cast.HamstrCast.install(DesktopStage)
    val icon = Thread.currentThread().contextClassLoader.getResourceAsStream("hamstr_window.png")?.use { BitmapPainter(loadImageBitmap(it)) }
    CoroutineScope(Dispatchers.IO).launch {
        Prefs.settings.map { it.startWithSystem }.distinctUntilChanged().collect(Autostart::set)
    }

    application {
        var visible by remember { mutableStateOf(!startHidden) }
        val trayState = rememberTrayState()
        val show = { visible = true }
        val close = { if (tray && Prefs.settings.value.trayOnClose) visible = false else exitApplication() }
        // A new language draws the tray menu and the window again, in it.
        val language by Prefs.settings.map { it.language }.distinctUntilChanged().collectAsState(Prefs.settings.value.language)
        val received = key(language) { stringResource(Res.string.nearby_received, "%s") }
        LaunchedEffect(received) {
            Nearby.onLink = { url, title, from ->
                Queue.stash(url)
                if (tray) trayState.sendNotification(Notification(received.replace("%s", from.name), title ?: url))
            }
            Nearby.onFiles = { files, from ->
                val kept = Files.adopt(files, from.name)
                if (tray && kept.isNotEmpty()) trayState.sendNotification(Notification(received.replace("%s", from.name), kept.joinToString(", ") { it.name }))
            }
        }
        // How the downloads are doing, outside the window too: the tray's tooltip, the taskbar
        // button's progress, a notification when they are through while the window is away.
        val tasks by DownloadStore.tasks.collectAsState()
        val live by DownloadStore.live.collectAsState()
        val active = remember(tasks, live) { tasks.filter { it.status.active && !it.hidden }.map { DownloadStore.current(it, live) } }
        val moving = active.count { it.status == Status.Running && it.stage?.waiting != true }
        val percent = if (active.isEmpty()) 0 else (active.sumOf { it.progress.toDouble() } / active.size * 100).roundToInt().coerceIn(0, 100)
        val tooltip = key(language) {
            when {
                moving > 0 -> pluralStringResource(Res.plurals.tray_downloading, moving, moving) + " · $percent %"
                active.isNotEmpty() -> pluralStringResource(Res.plurals.tray_waiting, active.size, active.size)
                else -> "Hamstr"
            }
        }
        var windowFocused by remember { mutableStateOf(false) }
        TrayNews(tasks, away = !visible || !windowFocused) { title, text -> if (tray) trayState.sendNotification(Notification(title, text)) }
        if (tray) key(language) {
            Tray(
                icon = icon ?: painterResource(Res.drawable.ic_download),
                state = trayState,
                tooltip = tooltip,
                onAction = show,
                menu = {
                    Item(stringResource(Res.string.tray_open), onClick = show)
                    Item(stringResource(Res.string.tray_paste), onClick = { Files.clipboardText()?.let(MediaLinks::find)?.let(Queue::stash) })
                    Separator()
                    Item(stringResource(Res.string.tray_quit), onClick = ::exitApplication)
                },
            )
        }
        // What plays names the window, in the taskbar and the window switcher.
        val playing by DesktopPlayback.current.collectAsState()
        val staged by DesktopStage.now.collectAsState()
        val place = remember { WindowPlace.load() }
        Window(
            onCloseRequest = close,
            visible = visible,
            state = rememberWindowState(size = place.size, position = place.position),
            title = (staged?.task ?: playing)?.title?.let { "$it — Hamstr" } ?: "Hamstr",
            icon = icon,
            // Keys nothing in the window took: Space plays, Ctrl+N adds, and so on (DesktopApp).
            onKeyEvent = { Shortcuts.handle(it) },
            // Hamstr draws its own title bar; macOS keeps its traffic lights over it. Linux has no
            // rounded frameless windows, so there the window is see-through and the app clips itself.
            undecorated = !Paths.mac,
            transparent = !Paths.mac && !Paths.windows,
        ) {
            val frame = remember { WindowFrame(window) }
            // The first frame is drawn: the start window has done its job.
            LaunchedEffect(Unit) { withFrameNanos {}; Splash.close() }
            // A device asking to send a link brings the window up, where the question waits.
            LaunchedEffect(Unit) { Nearby.requests.collect { if (it.isNotEmpty()) { visible = true; window.isMinimized = false; window.toFront() } } }
            // Opened again from the tray: in front, not behind whatever was there.
            LaunchedEffect(visible) { if (visible) { window.isMinimized = false; window.toFront() } }
            // Started a second time: this window comes up instead.
            LaunchedEffect(Unit) { Instance.show.collect { visible = true; window.isMinimized = false; window.toFront() } }
            LaunchedEffect(Unit) {
                window.minimumSize = Dimension(760, 520)
                // Files dropped on the window come in like picked ones; a dropped link is stashed (Drop).
                window.contentPane.dropTarget = Drop.target()
                frame.round()
                if (place.maximized && !Paths.mac) frame.maximize()
                WindowPlace.watch(frame)
                if (Paths.mac) window.rootPane.apply {
                    putClientProperty("apple.awt.fullWindowContent", true)
                    putClientProperty("apple.awt.transparentTitleBar", true)
                    putClientProperty("apple.awt.windowTitleVisible", false)
                }
            }
            // Back in front: files changed or deleted meanwhile show, as on the phone.
            val focused = LocalWindowInfo.current.isWindowFocused
            LaunchedEffect(focused) { windowFocused = focused; if (focused) FileChecks.refresh() }
            LaunchedEffect(moving, active.size, percent) { TaskbarProgress.show(window, moving, active.size, percent) }
            // Where it was, for the next start; also when Quit ends it without a move first.
            DisposableEffect(Unit) { onDispose { WindowPlace.save(frame) } }
            // Ambient motion only while the window is in front: in the background Hamstr draws nothing.
            CompositionLocalProvider(LocalAmbient provides (visible && LocalWindowInfo.current.isWindowFocused)) {
                key(language) { HamstrTheme { DesktopApp(frame, onClose = close, onQuit = ::exitApplication) } }
            }
        }
    }
    // Quit means gone: a thread left running would keep Hamstr's files in use for an update.
    exitProcess(0)
}

/**
 * The tray's notifications about downloads, only while the window is away (hidden in the tray,
 * or behind other windows): one when the queue is through, one when a download fails.
 */
@Composable
private fun TrayNews(tasks: List<DownloadTask>, away: Boolean, notify: (String, String) -> Unit) {
    val seen = remember { HashMap<String, Status>() }
    val finished = remember { ArrayList<String>() }
    val awayNow by rememberUpdatedState(away)
    val notifyNow by rememberUpdatedState(notify)
    LaunchedEffect(tasks) {
        val first = seen.isEmpty()
        for (t in tasks) {
            val before = seen.put(t.id, t.status)
            if (first || before == null || before == t.status || !before.active) continue
            if (t.status == Status.Done) finished += t.title
            if (t.status == Status.Failed && awayNow) notifyNow(getString(Res.string.tray_failed), t.title)
        }
        if (tasks.none { it.status.active } && finished.isNotEmpty()) {
            val titles = finished.toList()
            finished.clear()
            if (!awayNow) return@LaunchedEffect
            val done = getPluralString(Res.plurals.tray_done, titles.size, titles.size)
            if (titles.size == 1) notifyNow(titles[0], done) else notifyNow(done, titles.joinToString(", "))
        }
    }
}

/** The taskbar button fills while downloads run (Windows), or the dock icon (macOS). */
private object TaskbarProgress {
    private val bar = runCatching { if (Taskbar.isTaskbarSupported()) Taskbar.getTaskbar() else null }.getOrNull()

    fun show(window: java.awt.Window, moving: Int, active: Int, percent: Int) {
        val b = bar ?: return
        runCatching {
            if (b.isSupported(Taskbar.Feature.PROGRESS_STATE_WINDOW) && b.isSupported(Taskbar.Feature.PROGRESS_VALUE_WINDOW)) {
                when {
                    moving > 0 -> { b.setWindowProgressState(window, Taskbar.State.NORMAL); b.setWindowProgressValue(window, percent) }
                    // Waiting for Wi-Fi, the night or a retry: the bar stays, paused.
                    active > 0 -> { b.setWindowProgressValue(window, percent); b.setWindowProgressState(window, Taskbar.State.PAUSED) }
                    else -> b.setWindowProgressState(window, Taskbar.State.OFF)
                }
            } else if (b.isSupported(Taskbar.Feature.PROGRESS_VALUE)) {
                b.setProgressValue(if (moving > 0) percent else -1)
            }
        }.onFailure { app.hamstr.Log.d("HamstrTaskbar", "No taskbar progress", it) }
    }
}
