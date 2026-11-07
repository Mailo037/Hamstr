package app.hamstr.desktop

import java.util.Date
import java.text.DateFormat
import app.hamstr.ui.InfoAction
import app.hamstr.ui.InfoSheet
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.hamstr.data.DownloadStore
import app.hamstr.data.DownloadTask
import app.hamstr.data.Status
import app.hamstr.engine.MediaLinks
import app.hamstr.res.*
import app.hamstr.nearby.Nearby
import app.hamstr.ui.AddSheet
import app.hamstr.ui.DeviceSheet
import app.hamstr.ui.RequestSheet
import app.hamstr.ui.ShareContent
import app.hamstr.ui.SharingSheet
import app.hamstr.ui.Overlay
import app.hamstr.ui.StashScreen
import app.hamstr.ui.ClipOffer
import app.hamstr.data.Prefs
import app.hamstr.data.Subscriptions
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalWindowInfo
import app.hamstr.ui.TourOverlay
import app.hamstr.ui.TourGate
import app.hamstr.ui.components.DropMenu
import app.hamstr.ui.components.Icon
import app.hamstr.ui.components.MenuDivider
import app.hamstr.ui.components.MenuItem
import app.hamstr.ui.components.RasterBackground
import app.hamstr.ui.components.Specs
import app.hamstr.ui.components.Txt
import app.hamstr.ui.components.animateSelection
import app.hamstr.ui.theme.Hamstr
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import java.io.File
import kotlin.math.roundToInt

private enum class Tab(val label: StringResource, val icon: DrawableResource) {
    Stash(Res.string.tab_stash, Res.drawable.ic_download),
    Library(Res.string.tab_library, Res.drawable.ic_grid_view),
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
    // After a new language, the window comes back where it was picked.
    val open = remember { Language.reopen.also { Language.reopen = null } ?: Page.entries.firstOrNull { it.name.equals(System.getenv("HAMSTR_OPEN"), ignoreCase = true) } }
    // HAMSTR_OPEN=Library starts on the library.
    val library = remember { System.getenv("HAMSTR_OPEN").equals("Library", ignoreCase = true) }
    var tab by remember { mutableStateOf(if (open != null) Tab.Settings else if (library) Tab.Library else Tab.Stash) }
    var page by remember { mutableStateOf(open ?: Page.Appearance) }
    var adding by remember { mutableStateOf(false) }
    // A remembered device's sheet, from Settings › Devices.
    var deviceFor by remember { mutableStateOf<String?>(null) }
    var clipLink by remember { mutableStateOf<String?>(null) }
    var menuFor by remember { mutableStateOf<DownloadTask?>(null) }
    // Where the pointer last was in the window: a download's menu opens there.
    var pointer by remember { mutableStateOf(Offset.Zero) }
    var menuAt by remember { mutableStateOf(IntOffset.Zero) }
    var sharing by remember { mutableStateOf<ShareContent?>(null) }
    val playing by DesktopPlayback.current.collectAsState()
    val playerExpanded by DesktopPlayback.expanded.collectAsState()
    // What another Hamstr plays here ("Play on") takes the player's place; playing something here ends it.
    val staged by DesktopStage.now.collectAsState()
    val stageExpanded by DesktopStage.expanded.collectAsState()
    LaunchedEffect(staged != null) { if (staged != null) DesktopPlayback.close() }
    DesktopPlaybackEffects()
    var infoFor by remember { mutableStateOf<DownloadTask?>(null) }
    val requests by Nearby.requests.collectAsState()
    // The tour on a first start; HAMSTR_TOUR=<page> opens it on that page, for screenshots.
    val tourAt = remember { System.getenv("HAMSTR_TOUR")?.toIntOrNull() }
    var touring by remember { mutableStateOf(tourAt != null || TourGate.due()) }
    val sheetOpen = touring || adding || menuFor != null || sharing != null || (playing != null && playerExpanded) || (staged != null && stageExpanded) || infoFor != null || requests.isNotEmpty()

    fun clipboardLink() = Files.clipboardText()?.let(MediaLinks::find)
    // Back in the window with a link copied elsewhere: the stash offers it, once per link.
    var clip by remember { mutableStateOf<ClipOffer?>(null) }
    // A followed channel's sheet, from Settings › Following.
    var followFor by remember { mutableStateOf<app.hamstr.data.Subscription?>(null) }
    val focused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(focused) {
        if (!focused || !Prefs.settings.value.offerClipboard) return@LaunchedEffect
        val link = clipboardLink() ?: return@LaunchedEffect
        val mark = link.hashCode().toLong()
        if (mark == Prefs.settings.value.clipSeen || DownloadStore.tasks.value.any { it.url == link }) return@LaunchedEffect
        Prefs.edit { it.copy(clipSeen = mark) }
        clip = ClipOffer(link)
    }
    fun follow(link: String) {
        Subscriptions.follow(link, Prefs.settings.value.mode) { Queue.feed(it) }
        page = Page.Following
        tab = Tab.Settings
    }
    // Videos and songs play in Hamstr; documents and the like open in the system's app.
    fun play(t: DownloadTask, from: List<DownloadTask> = visible) {
        if (playable(t)) DesktopPlayback.open(t, from) else t.filePath?.let { Files.open(File(it)) }
    }
    fun openMenu(t: DownloadTask) {
        menuAt = IntOffset(pointer.x.roundToInt(), pointer.y.roundToInt())
        menuFor = t
    }

    Box(
        Modifier
            .fillMaxSize()
            .then(if (Paths.windows || Paths.mac || frame.maximized) Modifier else Modifier.clip(RoundedCornerShape(10.dp)))
            .background(c.canvas)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull()?.let { pointer = it.position }
                }
            }
            // Ctrl+V (⌘V) anywhere stashes the copied link, the desktop's share button.
            .onKeyEvent { e ->
                val paste = e.type == KeyEventType.KeyDown && e.key == Key.V && (e.isCtrlPressed || e.isMetaPressed)
                if (paste && !sheetOpen) clipboardLink()?.let { Queue.stash(it); tab = Tab.Stash; true } ?: false else false
            },
    ) {
        RasterBackground(Modifier.fillMaxSize())
        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.height(TITLE_BAR))
            Box(Modifier.weight(1f).fillMaxWidth()) {
                Row(Modifier.fillMaxSize().padding(start = 10.dp, end = 12.dp, bottom = 12.dp)) {
                    Column(Modifier.width(216.dp).fillMaxHeight()) {
                        SideBar(tab, page, onTab = { tab = it }, onPage = { page = it; tab = Tab.Settings })
                        Spacer(Modifier.weight(1f))
                        DesktopMiniPlayer()
                    }
                    Spacer(Modifier.width(12.dp))
                    AnimatedContent(tab, Modifier.weight(1f).fillMaxHeight(), transitionSpec = { fadeIn(Specs.appear()) togetherWith fadeOut(Specs.disappear()) }, label = "tab") { t ->
                        when (t) {
                            Tab.Stash -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                                Box(Modifier.widthIn(max = 880.dp)) {
                                    StashScreen(
                                        visible, engine, PaddingValues(top = 8.dp, bottom = 24.dp),
                                        onAdd = { clipboardLink()?.let(Queue::stash) ?: run { clipLink = null; adding = true } },
                                        onOpenAdd = { clipLink = clipboardLink(); adding = true },
                                        onMenu = ::openMenu,
                                        onPlay = ::play,
                                        onHide = { list -> DownloadStore.setHidden(list.map { it.id }.toSet(), true) },
                                        onRetryEngine = Queue::retryStart,
                                        onShowAll = {},
                                        clip = clip,
                                        onClip = { take -> clip?.link?.takeIf { take }?.let(Queue::stash); clip = null },
                                    )
                                }
                            }
                            Tab.Library -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                                Box(Modifier.widthIn(max = 880.dp)) { DesktopLibrary(visible, onPlay = ::play, onMenu = ::openMenu) }
                            }
                            Tab.Settings -> DesktopSettings(page, engine, onQuit = onQuit, onTour = { touring = true }, onDevice = { clipLink = clipboardLink(); deviceFor = it }, onFollowing = { followFor = it })
                        }
                    }
                }
            }
        }

        // Modals and the tour dim the whole window, the title bar's strip included, so nothing up
        // there keeps a colour of its own; the bar itself is drawn last, on top and usable, so the
        // window still moves, minimizes and closes with one open.
        Overlay(adding, onDismiss = { adding = false }) {
            DesktopSheet {
                AddSheet(
                    clipLink,
                    onLink = { Queue.stash(it); adding = false; tab = Tab.Stash },
                    onFiles = { adding = false; Files.pickAndImport() },
                    onSend = { adding = false; sharing = ShareContent(it) },
                    onStash = { link, choice -> Queue.stash(link, choice); adding = false; tab = Tab.Stash },
                    onFollow = { adding = false; follow(it) },
                )
            }
        }
        Overlay(followFor != null, onDismiss = { followFor = null }) {
            followFor?.let { f ->
                DesktopSheet {
                    app.hamstr.ui.FollowSheet(
                        f.title, f.url,
                        onOpen = { Files.browse(it) },
                        onShare = { url -> followFor = null; sharing = ShareContent(url) },
                        onClose = { followFor = null },
                    )
                }
            }
        }
        Overlay(deviceFor != null, onDismiss = { deviceFor = null }) {
            deviceFor?.let { id -> DesktopSheet { DeviceSheet(id, clipLink, onPickFiles = { Files.pick() }, onClose = { deviceFor = null }) } }
        }
        Overlay(sharing != null, onDismiss = { sharing = null }) {
            sharing?.let { content ->
                DesktopSheet {
                    SharingSheet(
                        content,
                        onCopyLink = { Files.copy(it); sharing = null },
                        onCopyFiles = { Files.copyFiles(content.files.mapNotNull { it.filePath?.let(::File) }); sharing = null },
                        onShareFiles = { content.files.firstOrNull()?.filePath?.let { Files.reveal(File(it)) }; sharing = null },
                        fileActionLabel = Res.string.desktop_show_in_folder,
                        fileActionIcon = Res.drawable.ic_folder_open,
                    )
                }
            }
        }
        Overlay(infoFor != null, onDismiss = { infoFor = null }) {
            infoFor?.let { t ->
                val file = t.filePath?.let(::File)?.takeIf { it.isFile }
                DesktopSheet {
                    InfoSheet(
                        t,
                        onCopy = Files::copy,
                        formatDate = { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)) },
                        actions = buildMap {
                            if (t.url.startsWith("http")) put(Res.string.info_link, InfoAction(Res.drawable.ic_open_in_new, Res.string.info_open_link) { Files.browse(t.url) })
                            if (file != null) put(Res.string.info_file, InfoAction(Res.drawable.ic_folder_open, Res.string.desktop_show_in_folder) { Files.reveal(file) })
                        },
                    )
                }
            }
        }
        var castSheet by remember { mutableStateOf(false) }
        Overlay(playing != null && playerExpanded, onDismiss = DesktopPlayback::minimize) {
            playing?.let { t -> DesktopPlayer(t, onCast = { castSheet = true }, onMinimize = DesktopPlayback::minimize, onClose = DesktopPlayback::close) }
        }
        Overlay(staged != null && stageExpanded, onDismiss = DesktopStage::minimize) {
            staged?.let { s -> DesktopPlayer(s.task, stream = s.player, onMinimize = DesktopStage::minimize, onClose = DesktopStage::close) }
        }
        Overlay(castSheet, onDismiss = { castSheet = false }) {
            DesktopSheet { app.hamstr.ui.CastSheet { castSheet = false } }
        }
        val request = requests.firstOrNull()
        Overlay(request != null, onDismiss = { request?.let { Nearby.answer(it, accept = false) } }) {
            request?.let { r -> DesktopSheet { RequestSheet(r) { tab = Tab.Stash } } }
        }
        // A download's menu, where it was right-clicked (or clicked, for a running one).
        FileMenu(
            menuFor, menuAt, onShare = { t -> menuFor = null; sharing = ShareContent.from(listOf(t)) }, onPlay = { t -> menuFor = null; play(t) },
            onInfo = { t -> menuFor = null; infoFor = t },
        ) { menuFor = null }
        TourOverlay(touring, onDone = { TourGate.done(); touring = false; tab = Tab.Stash }, startAt = (tourAt ?: 1) - 1, top = TITLE_BAR)
        TitleBar(frame, onClose)
        ResizeEdges(frame)
    }
}

/** Modals keep a readable width on a wide window. */
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

/**
 * A download's menu at the pointer: play or open it, find it, share it, see its info or take it off the list.
 * A pointer gets there with one right-click, so it is a small menu rather than a sheet.
 */
@Composable
private fun FileMenu(t: DownloadTask?, at: IntOffset, onShare: (DownloadTask) -> Unit, onPlay: (DownloadTask) -> Unit, onInfo: (DownloadTask) -> Unit, onClose: () -> Unit) {
    // The last download stays in the menu while it fades out.
    var last by remember { mutableStateOf(t) }
    if (t != null) last = t
    val shown = last ?: return
    val file = shown.filePath?.let(::File)?.takeIf { it.exists() }
    Box(Modifier.fillMaxSize()) {
        DropMenu(t != null, onClose, at = at) {
            if (file != null && shown.status == Status.Done) {
                if (playable(shown)) MenuItem(stringResource(Res.string.player_play), { onPlay(shown) }, Res.drawable.ic_play_arrow)
                MenuItem(stringResource(Res.string.desktop_open), { Files.open(file); onClose() }, Res.drawable.ic_open_in_new)
                MenuItem(stringResource(Res.string.desktop_show_in_folder), { Files.reveal(file); onClose() }, Res.drawable.ic_folder_open)
            }
            if (shown.url.startsWith("http") || (file != null && shown.status == Status.Done))
                MenuItem(stringResource(Res.string.menu_tile_share), { onShare(shown) }, Res.drawable.ic_share)
            if (shown.status == Status.Done) MenuItem(stringResource(Res.string.menu_info), { onInfo(shown) }, Res.drawable.ic_info)
            MenuDivider()
            MenuItem(stringResource(Res.string.menu_hide), { DownloadStore.setHidden(setOf(shown.id), true); onClose() }, Res.drawable.ic_visibility_off)
        }
    }
}
