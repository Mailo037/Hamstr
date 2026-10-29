package app.hamstr.desktop

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import app.hamstr.data.DownloadStore
import app.hamstr.data.DownloadTask
import app.hamstr.data.Status
import app.hamstr.engine.MediaLinks
import app.hamstr.res.*
import app.hamstr.nearby.Nearby
import app.hamstr.ui.AddSheet
import app.hamstr.ui.RequestSheet
import app.hamstr.ui.SendSheet
import app.hamstr.ui.Overlay
import app.hamstr.ui.StashScreen
import app.hamstr.ui.components.Group
import app.hamstr.ui.components.Icon
import app.hamstr.ui.components.RasterBackground
import app.hamstr.ui.components.Sheet
import app.hamstr.ui.components.SheetHeader
import app.hamstr.ui.components.Specs
import app.hamstr.ui.components.Txt
import app.hamstr.ui.components.animateSelection
import app.hamstr.ui.statusLine
import app.hamstr.ui.theme.Hamstr
import app.hamstr.ui.theme.Radii
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import java.io.File

private enum class Tab(val label: StringResource, val icon: DrawableResource) {
    Stash(Res.string.tab_stash, Res.drawable.ic_download),
    Settings(Res.string.tab_settings, Res.drawable.ic_settings),
}

/**
 * The desktop window: the side bar the phone shows when held sideways, the shared screens
 * next to it, and the shared sheets over everything.
 */
@Composable
fun DesktopApp(frame: WindowFrame, onClose: () -> Unit, onQuit: () -> Unit) {
    val c = Hamstr.colors
    val tasks by DownloadStore.tasks.collectAsState()
    val engine by Queue.engine.collectAsState()
    val visible = remember(tasks) { tasks.filterNot { it.hidden } }
    // HAMSTR_OPEN=Engine (or any settings page) starts on that page, for screenshots.
    val open = remember { Page.entries.firstOrNull { it.name.equals(System.getenv("HAMSTR_OPEN"), ignoreCase = true) } }
    var tab by remember { mutableStateOf(if (open != null) Tab.Settings else Tab.Stash) }
    var page by remember { mutableStateOf(open ?: Page.Appearance) }
    var adding by remember { mutableStateOf(false) }
    var clipLink by remember { mutableStateOf<String?>(null) }
    var menuFor by remember { mutableStateOf<DownloadTask?>(null) }
    var sending by remember { mutableStateOf<Pair<String, String?>?>(null) }
    val requests by Nearby.requests.collectAsState()
    val sheetOpen = adding || menuFor != null || sending != null || requests.isNotEmpty()

    fun clipboardLink() = Files.clipboardText()?.let(MediaLinks::find)

    Box(
        Modifier
            .fillMaxSize()
            .then(if (Paths.windows || Paths.mac || frame.maximized) Modifier else Modifier.clip(RoundedCornerShape(10.dp)))
            .background(c.canvas)
            // Ctrl+V (⌘V) anywhere stashes the copied link, the desktop's share button.
            .onKeyEvent { e ->
                val paste = e.type == KeyEventType.KeyDown && e.key == Key.V && (e.isCtrlPressed || e.isMetaPressed)
                if (paste && !sheetOpen) clipboardLink()?.let { Queue.stash(it); tab = Tab.Stash; true } ?: false else false
            },
    ) {
        RasterBackground(Modifier.fillMaxSize())
        Column(Modifier.fillMaxSize()) {
        TitleBar(frame, onClose)
        Row(Modifier.fillMaxSize().padding(start = 10.dp, end = 12.dp, bottom = 12.dp)) {
            SideBar(tab, page, onTab = { tab = it }, onPage = { page = it; tab = Tab.Settings })
            Spacer(Modifier.width(12.dp))
            AnimatedContent(tab, Modifier.weight(1f).fillMaxHeight(), transitionSpec = { fadeIn(Specs.appear()) togetherWith fadeOut(Specs.disappear()) }, label = "tab") { t ->
                when (t) {
                    Tab.Stash -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                        Box(Modifier.widthIn(max = 880.dp)) {
                            StashScreen(
                                visible, engine, PaddingValues(top = 8.dp, bottom = 24.dp),
                                onAdd = { clipboardLink()?.let(Queue::stash) ?: run { clipLink = null; adding = true } },
                                onOpenAdd = { clipLink = clipboardLink(); adding = true },
                                onMenu = { menuFor = it },
                                onPlay = { t, _ -> t.filePath?.let { Files.open(File(it)) } },
                                onHide = { list -> DownloadStore.setHidden(list.map { it.id }.toSet(), true) },
                                onRetryEngine = Queue::retryStart,
                                onShowAll = {},
                            )
                        }
                    }
                    Tab.Settings -> DesktopSettings(page, engine, onQuit = onQuit)
                }
            }
        }
        }

        Overlay(adding, onDismiss = { adding = false }) {
            DesktopSheet {
                AddSheet(
                    clipLink,
                    onLink = { Queue.stash(it); adding = false; tab = Tab.Stash },
                    onFiles = { adding = false; Files.pickAndImport() },
                    onSend = { adding = false; sending = it to null },
                )
            }
        }
        Overlay(menuFor != null, onDismiss = { menuFor = null }) {
            menuFor?.let { t -> DesktopSheet { FileMenu(t, onSend = { menuFor = null; sending = t.url to t.title }) { menuFor = null } } }
        }
        Overlay(sending != null, onDismiss = { sending = null }) {
            sending?.let { (url, title) -> DesktopSheet { SendSheet(url, title) } }
        }
        val request = requests.firstOrNull()
        Overlay(request != null, onDismiss = { request?.let { Nearby.answer(it, accept = false) } }) {
            request?.let { r -> DesktopSheet { RequestSheet(r) { tab = Tab.Stash } } }
        }
        ResizeEdges(frame)
    }
}

/** Sheets keep a readable width on a wide window and sit in its middle. */
@Composable
private fun DesktopSheet(content: @Composable BoxScope.() -> Unit) =
    Box(Modifier.widthIn(max = 600.dp), content = content)

/**
 * The tabs in a column, with the selection pill gliding between them (panel 16, padding 4,
 * pill 12). Settings opens its category pages right under it, so they are one click away.
 */
@Composable
private fun SideBar(selected: Tab, page: Page, onTab: (Tab) -> Unit, onPage: (Page) -> Unit) {
    val c = Hamstr.colors
    val haptics = LocalHapticFeedback.current
    val update by Updater.state.collectAsState()
    val pillY by animateDpAsState(ITEM * selected.ordinal, Specs.arrive(), label = "pill")
    Column(
        Modifier
            .width(216.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(c.overlay)
            .padding(4.dp)
            .animateContentSize(Specs.blend()),
    ) {
        Box {
            Box(Modifier.offset(y = pillY).fillMaxWidth().height(ITEM).clip(RoundedCornerShape(12.dp)).background(c.hover))
            Column {
                Tab.entries.forEach { t ->
                    val on = t == selected
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(ITEM)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(remember { MutableInteractionSource() }, null) {
                                if (!on) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                onTab(t)
                            }
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(t.icon, tint = if (on) c.ink else c.muted, size = 18.dp)
                        Spacer(Modifier.width(12.dp))
                        Txt(stringResource(t.label), style = Hamstr.type.bodyMedium, color = if (on) c.ink else c.muted)
                    }
                }
            }
        }
        AnimatedVisibility(selected == Tab.Settings, enter = expandVertically(Specs.blend()) + fadeIn(Specs.appear()), exit = shrinkVertically(Specs.blend()) + fadeOut(Specs.disappear())) {
            Column(Modifier.padding(top = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Page.entries.forEach { p ->
                    // A new release found on launch shows as a dot on About.
                    val dot = p == Page.About && update is UpdateState.Available
                    PageItem(p, p == page, dot) { if (p != page) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); onPage(p) }
                }
            }
        }
    }
}

/** A settings page under the Settings tab; the open one lifts a surface step. */
@Composable
private fun PageItem(p: Page, on: Boolean, dot: Boolean, onClick: () -> Unit) {
    val c = Hamstr.colors
    val lift = animateSelection(on)
    Row(
        Modifier
            .fillMaxWidth()
            .height(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(c.surface.copy(alpha = lift))
            .clickable(onClick = onClick)
            .padding(start = 20.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(p.icon, tint = if (on) c.accent else c.ghost, size = 18.dp)
        Spacer(Modifier.width(12.dp))
        Txt(stringResource(p.title), style = Hamstr.type.body, color = if (on) c.ink else c.muted, maxLines = 1, modifier = Modifier.weight(1f))
        if (dot) Box(Modifier.size(7.dp).clip(RoundedCornerShape(50)).background(c.accent))
    }
}

// A pointer needs less room than a finger: the phone's tab bar items are 54 dp.
private val ITEM = 40.dp

/** A download's menu: open it, find it, copy its link or take it off the list. */
@Composable
private fun FileMenu(t: DownloadTask, onSend: () -> Unit, onClose: () -> Unit) {
    val file = t.filePath?.let(::File)?.takeIf { it.exists() }
    Sheet {
        SheetHeader(t.title, subtitle = statusLine(t))
        Spacer(Modifier.height(16.dp))
        Group {
            if (file != null && t.status == Status.Done) {
                MenuRow(Res.drawable.ic_open_in_new, stringResource(Res.string.desktop_open)) { Files.open(file); onClose() }
                MenuRow(Res.drawable.ic_folder_open, stringResource(Res.string.desktop_show_in_folder)) { Files.reveal(file); onClose() }
            }
            if (t.url.startsWith("http")) {
                MenuRow(Res.drawable.ic_link, stringResource(Res.string.menu_copy_link)) { Files.copy(t.url); onClose() }
                MenuRow(Res.drawable.ic_send, stringResource(Res.string.nearby_send)) { onSend() }
            }
            MenuRow(Res.drawable.ic_visibility_off, stringResource(Res.string.menu_hide)) {
                DownloadStore.setHidden(setOf(t.id), true)
                onClose()
            }
        }
    }
}

@Composable
private fun MenuRow(icon: DrawableResource, label: String, onClick: () -> Unit) {
    val c = Hamstr.colors
    Row(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(Radii.row))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(12.dp)).background(c.surface), contentAlignment = Alignment.Center) {
            Icon(icon, tint = c.accent, size = 18.dp)
        }
        Spacer(Modifier.width(12.dp))
        Txt(label, style = Hamstr.type.body)
    }
}
