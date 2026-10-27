package app.hamstr

import androidx.compose.ui.platform.LocalWindowInfo
import kotlinx.coroutines.flow.MutableStateFlow
import app.hamstr.ui.PlaylistSheet
import app.hamstr.ui.AddToPlaylistSheet
import app.hamstr.ui.NameSheet
import app.hamstr.ui.rememberPlaylist
import app.hamstr.data.Playlists
import androidx.activity.compose.rememberLauncherForActivityResult
import app.hamstr.data.Importer
import app.hamstr.ui.AddSheet
import android.Manifest
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.hamstr.data.DownloadStore
import app.hamstr.data.DownloadFiles
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.platform.LocalConfiguration
import app.hamstr.data.Prefs
import app.hamstr.data.DownloadTask
import app.hamstr.data.Status
import app.hamstr.engine.DownloadService
import app.hamstr.engine.Engine
import app.hamstr.engine.Failures
import app.hamstr.engine.Fix
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import app.hamstr.ui.NotifyAsk
import app.hamstr.ui.NotifyPrompt
import app.hamstr.ui.Overlay
import app.hamstr.ui.components.DecodeText
import app.hamstr.ui.components.DragPillRow
import app.hamstr.ui.components.HamstrMark
import app.hamstr.ui.components.MarkState
import kotlinx.coroutines.delay
import app.hamstr.player.Kind
import app.hamstr.player.PipController
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.combine
import app.hamstr.player.Playback
import app.hamstr.player.PlayerHost
import app.hamstr.player.kindOf
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import app.hamstr.ui.LibraryScreen
import app.hamstr.ui.SettingsScreen
import app.hamstr.ui.ShareSheet
import app.hamstr.ui.QualitySheet
import app.hamstr.ui.RenameSheet
import app.hamstr.ui.InfoSheet
import app.hamstr.ui.InfoAction
import app.hamstr.ui.formatBytes
import app.hamstr.ui.formatBadge
import app.hamstr.ui.rememberBitmap
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.Image
import app.hamstr.ui.canHaveVersions
import app.hamstr.ui.canSaveAudio
import app.hamstr.engine.AudioOut
import app.hamstr.ui.StashScreen
import app.hamstr.ui.DownloadPreview
import app.hamstr.ui.UpdateBanner
import app.hamstr.ui.UpdateSheet
import app.hamstr.ui.release
import app.hamstr.update.UpdateState
import app.hamstr.update.Updater
import app.hamstr.ui.components.ButtonKind
import app.hamstr.ui.components.HButton
import app.hamstr.ui.components.Icon
import app.hamstr.ui.components.RasterBackground
import app.hamstr.ui.components.Txt
import app.hamstr.ui.theme.Hamstr
import app.hamstr.ui.theme.LocalFx
import app.hamstr.ui.theme.HamstrTheme
import app.hamstr.ui.theme.Radii
import java.io.File
import android.content.res.Configuration
import androidx.compose.foundation.layout.fillMaxHeight
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import app.hamstr.ui.components.LocalReduceMotion
import kotlin.math.abs
import kotlin.math.roundToInt
import app.hamstr.ui.components.Sheet
import app.hamstr.ui.components.pressable
import app.hamstr.ui.components.Specs

class MainActivity : ComponentActivity() {
    private val pip = PipController { params -> runCatching { setPictureInPictureParams(params) } }
    private val storagePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        askStorage(this, storagePermission::launch)
        Engine.warmUp()
        Updater.checkOnLaunch(this)
        addOnPictureInPictureModeChangedListener { inPip.value = it.isInPictureInPictureMode }
        inPip.value = isInPictureInPictureMode
        pip.bind(null, null)
        lifecycleScope.launch {
            combine(Playback.current, Playback.controller) { task, player -> task to player }
                .collect { (task, player) -> pip.bind(task, player) }
        }
        if (savedInstanceState == null) take(intent)
        setContent { HamstrTheme { App(inPip.value) } }
    }

    override fun onStart() {
        super.onStart()
        // Back in the foreground: queued downloads (also ones Android paused) go on, gone files show up.
        if (DownloadStore.tasks.value.any { it.status == Status.Queued }) DownloadService.send(this, DownloadService.ACTION_START)
        DownloadFiles.refreshMissing()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        take(intent)
    }

    private fun take(intent: Intent?) {
        val action = intent?.action?.takeIf { it in ACTIONS } ?: return
        pendingAction.value = if (action == ACTION_OPEN) "$ACTION_OPEN:${intent.getStringExtra(DownloadService.EXTRA_ID)}" else action
    }

    companion object {
        // Launcher shortcuts, the widget's buttons and "Play" in notifications.
        const val ACTION_PASTE = "app.hamstr.action.PASTE"
        const val ACTION_ADD = "app.hamstr.action.ADD"
        const val ACTION_LIBRARY = "app.hamstr.action.LIBRARY"
        const val ACTION_OPEN = "app.hamstr.action.OPEN"
        private val ACTIONS = setOf(ACTION_PASTE, ACTION_ADD, ACTION_LIBRARY, ACTION_OPEN)
        internal val pendingAction = MutableStateFlow<String?>(null)
    }

    private val inPip = androidx.compose.runtime.mutableStateOf(false)

    /** Before Android 12 there is no auto-enter: going home with a playing video enters PiP here. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < 31 && pip.eligible) {
            runCatching { enterPictureInPictureMode(pip.params()) }
        }
    }

    override fun onDestroy() {
        pip.close()
        super.onDestroy()
    }
}

/** Android 8 and 9 need permission to write to Download/Hamstr; notifications are asked for after the first stash. */
fun askStorage(activity: ComponentActivity, storage: (String) -> Unit) {
    if (Build.VERSION.SDK_INT <= 28 && ContextCompat.checkSelfPermission(activity, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
        storage(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }
}

private enum class Tab(val label: Int, val icon: Int) {
    Stash(R.string.tab_stash, R.drawable.ic_download),
    Library(R.string.tab_library, R.drawable.ic_grid_view),
    Settings(R.string.tab_settings, R.drawable.ic_settings),
}

/** A short message at the bottom, optionally with one action (Undo, Open, Retry). */
private data class Snack(val text: String, val action: String? = null, val id: Long = System.nanoTime(), val run: (() -> Unit)? = null)

@Composable
private fun App(inPip: Boolean) {
    val c = Hamstr.colors
    val context = LocalContext.current
    val tasks by DownloadStore.tasks.collectAsState()
    val engine by Engine.state.collectAsState()
    var tab by rememberSaveable { mutableStateOf(Tab.Stash) }
    val fx = LocalFx.current
    // Shortcuts and the widget are for getting something done fast, so they skip the intro.
    var introDone by rememberSaveable { mutableStateOf(!fx.intro || MainActivity.pendingAction.value != null) }
    var sheetUrl by rememberSaveable { mutableStateOf<String?>(null) }
    var menuFor by remember { mutableStateOf<DownloadTask?>(null) }
    var qualityFor by remember { mutableStateOf<DownloadTask?>(null) }
    var renameFor by remember { mutableStateOf<DownloadTask?>(null) }
    var infoFor by remember { mutableStateOf<DownloadTask?>(null) }
    var snack by remember { mutableStateOf<Snack?>(null) }
    val scope = rememberCoroutineScope()
    var addSheet by rememberSaveable { mutableStateOf(false) }
    var playlistFor by rememberSaveable { mutableStateOf<String?>(null) }
    var addToList by remember { mutableStateOf<List<DownloadTask>?>(null) }
    // A new list (for these files, maybe none) or a rename of the list with this id.
    var newList by remember { mutableStateOf<List<DownloadTask>?>(null) }
    var renameList by rememberSaveable { mutableStateOf<String?>(null) }
    var clipLink by remember { mutableStateOf<String?>(null) }
    var notifyAsk by rememberSaveable { mutableStateOf(false) }
    fun hint(text: String) { snack = Snack(text) }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            hint(if (uris.size == 1) context.getString(R.string.import_one) else context.getString(R.string.import_many, uris.size))
            val n = Importer.import(context, uris)
            hint(
                when {
                    n == uris.size -> if (n == 1) context.getString(R.string.import_done_one) else context.getString(R.string.import_done_many, n)
                    n == 0 -> context.getString(R.string.import_failed)
                    else -> context.getString(R.string.import_done_some, n, uris.size)
                },
            )
        }
    }
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { notifyAsk = false }
    fun clipboardLink(): String? {
        val cm = context.getSystemService(ClipboardManager::class.java)
        val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
        return app.hamstr.engine.MediaLinks.find(text)
    }
    val playing by Playback.current.collectAsState()
    val missing by DownloadFiles.missing.collectAsState()
    fun again(t: DownloadTask) {
        DownloadService.again(context, t)
        DownloadStore.remove(t.id)
        tab = Tab.Stash
    }
    // Lists hand in what plays next: other songs after a song, the rest of a playlist after one of its files.
    val play: (DownloadTask, List<DownloadTask>) -> Unit = { t, from ->
        scope.launch {
            if (!Playback.open(context, t, Playback.queueFor(t, from))) {
                DownloadFiles.refreshMissing()
                snack = Snack(context.getString(R.string.file_gone), context.getString(R.string.download_again)) { again(t) }
            }
        }
    }
    // A playlist or the songs list plays exactly as shown, in that order.
    val playList: (DownloadTask, List<DownloadTask>) -> Unit = { t, list ->
        scope.launch {
            if (!Playback.open(context, t, list)) {
                DownloadFiles.refreshMissing()
                snack = Snack(context.getString(R.string.file_gone), context.getString(R.string.download_again)) { again(t) }
            }
        }
    }
    val saveAudio: (DownloadTask) -> Unit = { v ->
        scope.launch {
            hint(context.getString(R.string.audio_saving))
            hint(AudioOut.save(context, v).fold({ context.getString(R.string.audio_saved) }, { it.message ?: context.getString(R.string.audio_failed) }))
        }
    }
    // Hiding keeps the files; deleting waits a moment for Undo before the files go.
    val hide: (List<DownloadTask>) -> Unit = { list ->
        val removed = DownloadStore.removeAll(list.map { it.id }.toSet())
        val files = list.count { it.status == Status.Done }
        snack = Snack(
            when {
                files == 0 -> context.getString(R.string.removed_from_list)
                list.size == 1 -> context.getString(R.string.hidden_one)
                else -> context.getString(R.string.hidden_many, list.size)
            },
            context.getString(R.string.undo),
        ) { DownloadStore.restore(removed) }
    }
    val delete: (List<DownloadTask>) -> Unit = { list ->
        val pending = DownloadFiles.deleteLater(context, list)
        snack = Snack(
            if (pending.count <= 1) context.getString(R.string.deleted_one) else context.getString(R.string.deleted_many, pending.count),
            context.getString(R.string.undo),
        ) { DownloadFiles.undo(context, pending) }
    }
    val share: (List<DownloadTask>) -> Unit = { list -> Playback.shareFiles(context, list) }
    val retryEngine: () -> Unit = {
        scope.launch {
            hint(context.getString(R.string.engine_restarting))
            Engine.retryStart().fold(
                { hint(context.getString(R.string.engine_restarted)) },
                { snack = Snack(context.getString(R.string.engine_still_broken), context.getString(R.string.err_copy)) { copy(context, it.message.orEmpty()) } },
            )
        }
    }
    val settings by Prefs.settings.collectAsState()
    val update by Updater.state.collectAsState()
    var updateSheet by rememberSaveable { mutableStateOf(false) }
    // The banner shows once per release; closing it leaves the update in Settings > About.
    val offered = (update as? UpdateState.Available)?.release?.takeIf { it.version != settings.seenRelease }

    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // Held sideways the tabs and the mini player move into a side bar on the left, so the
    // screens keep the full height; upright they float at the bottom.
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val safe = WindowInsets.safeDrawing.asPaddingValues()
    val sideStart = safe.calculateLeftPadding(LayoutDirection.Ltr) + 12.dp
    val contentStart = if (landscape) sideStart + SIDEBAR + 4.dp else 0.dp
    val contentEnd = if (landscape) safe.calculateRightPadding(LayoutDirection.Ltr) else 0.dp
    // Room for the tab bar, plus the mini player while something is loaded.
    val padding = if (landscape) PaddingValues(top = top + 8.dp, bottom = bottom + 16.dp)
    else PaddingValues(top = top + 8.dp, bottom = bottom + 84.dp + if (playing != null) 72.dp else 0.dp)

    LaunchedEffect(snack?.id) {
        val s = snack ?: return@LaunchedEffect
        delay(if (s.action != null) DownloadFiles.UNDO_MS else 2600)
        if (snack?.id == s.id) snack = null
    }

    // The clipboard is only readable once the window has focus.
    val action by MainActivity.pendingAction.collectAsState()
    val focused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(action, focused) {
        val a = action ?: return@LaunchedEffect
        when {
            a == MainActivity.ACTION_LIBRARY -> tab = Tab.Library
            a == MainActivity.ACTION_ADD -> if (focused) { tab = Tab.Stash; clipLink = clipboardLink(); addSheet = true } else return@LaunchedEffect
            a == MainActivity.ACTION_PASTE -> if (focused) {
                tab = Tab.Stash
                val url = clipboardLink()
                if (url != null) sheetUrl = url else { hint(context.getString(R.string.no_clipboard_link)); clipLink = null; addSheet = true }
            } else return@LaunchedEffect
            a.startsWith(MainActivity.ACTION_OPEN) -> DownloadStore.get(a.substringAfter(':'))?.let { t ->
                play(t, DownloadStore.tasks.value.filter { it.status == Status.Done })
            }
            else -> return@LaunchedEffect
        }
        MainActivity.pendingAction.value = null
    }

    Box(Modifier.fillMaxSize().background(c.canvas)) {
        RasterBackground(Modifier.fillMaxWidth().height(top + 280.dp))
        // Swipe sideways anywhere to change tabs; the tab bar and the pager follow each other.
        val pager = rememberPagerState(initialPage = tab.ordinal) { Tab.entries.size }
        LaunchedEffect(tab) { if (pager.targetPage != tab.ordinal) pager.animateScrollToPage(tab.ordinal, animationSpec = Specs.enter()) }
        // The first value is the page the pager started on; a shortcut may already have moved the tab on.
        LaunchedEffect(pager) { snapshotFlow { pager.settledPage }.drop(1).collect { tab = Tab.entries[it] } }
        // A turn (or the side bar's insets arriving) resizes the pages mid-animation; land on the tab's page again.
        val currentTab by rememberUpdatedState(tab)
        LaunchedEffect(pager) {
            snapshotFlow { pager.layoutInfo.viewportSize }.distinctUntilChanged().drop(1).collect { pager.scrollToPage(currentTab.ordinal) }
        }
        HorizontalPager(pager, Modifier.fillMaxSize().padding(start = contentStart, end = contentEnd), key = { Tab.entries[it] }) { page ->
            when (Tab.entries[page]) {
                Tab.Stash -> StashScreen(
                    tasks, engine, padding,
                    onAdd = {
                        val url = clipboardLink()
                        if (url != null) sheetUrl = url else hint(context.getString(R.string.no_clipboard_link))
                    },
                    onOpenAdd = { clipLink = clipboardLink(); addSheet = true },
                    onMenu = { menuFor = it },
                    onPlay = play,
                    onHide = hide,
                    onRetryEngine = retryEngine,
                    onShowAll = { tab = Tab.Library },
                )
                Tab.Library -> LibraryScreen(
                    tasks, padding,
                    onMenu = { menuFor = it }, onPlay = play, onPlayList = playList,
                    onPlaylist = { playlistFor = it }, onNewPlaylist = { newList = emptyList() }, onAddToPlaylist = { addToList = it },
                    onHide = hide, onDelete = delete, onShare = share,
                )
                Tab.Settings -> SettingsScreen(padding, onUpdate = { updateSheet = true })
            }
        }
        if (landscape) {
            // One panel from top to bottom: the tabs up top, the mini player at its foot.
            SideTabs(
                tab, { tab = it },
                Modifier.padding(start = sideStart, top = top + 8.dp, bottom = bottom + 12.dp).width(SIDEBAR).fillMaxHeight()
                    .clip(RoundedCornerShape(22.dp)).background(c.overlay).padding(5.dp),
            )
            if (!inPip) PlayerHost(
                miniBottom = bottom + 9.dp, inPip = false, onMenu = { menuFor = it }, onQuality = { qualityFor = it },
                miniStart = sideStart + 5.dp, miniWidth = SIDEBAR - 10.dp,
            )
        } else {
            TabBar(tab, { tab = it }, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(start = 12.dp, end = 12.dp, bottom = 12.dp))
            if (!inPip) PlayerHost(miniBottom = bottom + 78.dp, inPip = false, onMenu = { menuFor = it }, onQuality = { qualityFor = it })
        }

        AnimatedVisibility(
            snack != null,
            enter = slideInVertically { it / 2 } + fadeIn(),
            exit = slideOutVertically { it / 2 } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(
                start = 16.dp + contentStart, end = 16.dp + contentEnd,
                bottom = if (landscape) 16.dp else 96.dp + if (playing != null) 72.dp else 0.dp,
            ),
        ) {
            var shown by remember { mutableStateOf(snack) }
            snack?.let { shown = it }
            shown?.let { s ->
                Row(
                    Modifier.clip(RoundedCornerShape(14.dp)).background(c.raised).padding(start = 16.dp, end = 6.dp, top = 4.dp, bottom = 4.dp).heightIn(min = 40.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Txt(s.text, style = Hamstr.type.small, modifier = Modifier.weight(1f, fill = false).padding(end = 10.dp, top = 6.dp, bottom = 6.dp))
                    if (s.action != null) {
                        Box(
                            Modifier.clip(RoundedCornerShape(10.dp)).clickable { s.run?.invoke(); snack = null }
                                .heightIn(min = 40.dp).padding(horizontal = 12.dp),
                            contentAlignment = Alignment.Center,
                        ) { Txt(s.action, style = Hamstr.type.button, color = c.accent) }
                    } else Spacer(Modifier.width(10.dp))
                }
            }
        }

        var lastOffered by remember { mutableStateOf(offered) }
        if (offered != null) lastOffered = offered
        AnimatedVisibility(
            offered != null && introDone && !updateSheet && !inPip,
            enter = slideInVertically(spring(dampingRatio = 0.7f, stiffness = 380f)) { -it * 2 } + fadeIn(),
            exit = slideOutVertically(Specs.leave()) { -it * 2 } + fadeOut(Specs.listOut()),
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            lastOffered?.let { r -> UpdateBanner(r, onOpen = { updateSheet = true }, onDismiss = { Updater.dismiss(r) }) }
        }

        Overlay(visible = playlistFor != null && renameList == null, onDismiss = { playlistFor = null }) {
            playlistFor?.let { id ->
                PlaylistSheet(
                    id,
                    onPlay = { t, items -> playlistFor = null; playList(t, items) },
                    onMenu = { menuFor = it },
                    onRename = { renameList = id },
                    onDeleteFiles = delete,
                    onClose = { playlistFor = null },
                    onHint = { hint(it) },
                )
            }
        }
        Overlay(visible = addToList != null && newList == null, onDismiss = { addToList = null }) {
            addToList?.let { files ->
                AddToPlaylistSheet(files, onNew = { newList = files }, onDone = { msg -> addToList = null; hint(msg) })
            }
        }
        Overlay(visible = newList != null, onDismiss = { newList = null }) {
            newList?.let { files ->
                NameSheet(stringResource(R.string.pl_new), "", stringResource(R.string.pl_create)) { name ->
                    val id = Playlists.create(name, files)
                    newList = null
                    addToList = null
                    if (files.isEmpty()) playlistFor = id
                    else hint(if (files.size == 1) context.getString(R.string.pl_added_one, name) else context.getString(R.string.pl_added_many, files.size, name))
                }
            }
        }
        Overlay(visible = renameList != null, onDismiss = { renameList = null }) {
            renameList?.let { id ->
                val name = rememberPlaylist(id)?.name.orEmpty()
                NameSheet(stringResource(R.string.pl_rename), name, stringResource(R.string.pl_save)) { n ->
                    Playlists.rename(id, n)
                    renameList = null
                }
            }
        }
        Overlay(visible = addSheet, onDismiss = { addSheet = false }) {
            AddSheet(
                clipboardLink = clipLink,
                onLink = { url -> addSheet = false; sheetUrl = url },
                onFiles = { addSheet = false; pickFiles.launch(arrayOf("*/*")) },
            )
        }
        val stashed = {
            sheetUrl = null
            tab = Tab.Stash
            if (NotifyAsk.due(context)) notifyAsk = true
        }
        Overlay(visible = sheetUrl != null, onDismiss = { sheetUrl = null }) {
            sheetUrl?.let { url ->
                ShareSheet(
                    url,
                    onStash = { link, info, choice -> DownloadService.stash(context, link, info, choice); stashed() },
                    onBatchQueued = { stashed() },
                    onDismiss = { sheetUrl = null },
                    onOpen = { t -> sheetUrl = null; play(t, DownloadStore.tasks.value.filter { it.status == Status.Done }) },
                    onStashVersion = { of, choice -> DownloadService.stashVersion(context, of, choice, replace = false); stashed() },
                )
            }
        }
        Overlay(visible = notifyAsk, onDismiss = { NotifyAsk.asked(); notifyAsk = false }) {
            NotifyPrompt(
                onAllow = { NotifyAsk.asked(); askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS) },
                onLater = { NotifyAsk.asked(); notifyAsk = false },
            )
        }
        Overlay(visible = menuFor != null, onDismiss = { menuFor = null }) {
            menuFor?.let { t ->
                TaskMenu(
                    t, gone = t.id in missing,
                    onPlay = { play(it, DownloadStore.tasks.value.filter { d -> d.status == Status.Done }) },
                    onClose = { menuFor = null },
                    onQuality = { menuFor = null; qualityFor = it },
                    onSaveAudio = { menuFor = null; saveAudio(it) },
                    onRename = { menuFor = null; renameFor = it },
                    onInfo = { menuFor = null; infoFor = it },
                    onAddToPlaylist = { menuFor = null; addToList = listOf(it) },
                    onHide = { menuFor = null; hide(it) },
                    onDelete = { menuFor = null; delete(listOf(it)) },
                    onAgain = { menuFor = null; again(it) },
                    onCopied = { hint(it) },
                )
            }
        }
        Overlay(visible = renameFor != null, onDismiss = { renameFor = null }) {
            renameFor?.let { t ->
                RenameSheet(t, onRename = { name ->
                    renameFor = null
                    scope.launch {
                        hint(DownloadFiles.rename(context, t, name).fold({ context.getString(R.string.rename_done) }, { it.message ?: context.getString(R.string.rename_failed) }))
                    }
                })
            }
        }
        Overlay(visible = infoFor != null, onDismiss = { infoFor = null }) {
            infoFor?.let { t ->
                val present = t.filePath?.let(::File)?.isFile == true
                InfoSheet(
                    t,
                    onCopy = { copy(context, it); hint(context.getString(R.string.info_copied)) },
                    // The same changes the file menu offers, right where the value is shown.
                    actions = buildMap {
                        if (present) put(R.string.info_name, InfoAction(R.drawable.ic_edit, R.string.menu_rename) { infoFor = null; renameFor = t })
                        if (present && canHaveVersions(t)) put(R.string.info_quality, InfoAction(R.drawable.ic_movie, R.string.menu_quality) { infoFor = null; qualityFor = t })
                        if (t.url.startsWith("http")) put(R.string.info_link, InfoAction(R.drawable.ic_open_in_new, R.string.info_open_link) {
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(t.url))) }
                        })
                        if (present) put(R.string.info_file, InfoAction(R.drawable.ic_open_in_new, R.string.menu_open_with) { Playback.openWith(context, t) })
                    },
                )
            }
        }
        Overlay(visible = qualityFor != null, onDismiss = { qualityFor = null }) {
            qualityFor?.let { t ->
                QualitySheet(
                    t,
                    onSwitch = { v -> qualityFor = null; scope.launch { Playback.switchTo(context, v) } },
                    onSaveAudio = { v -> qualityFor = null; saveAudio(v) },
                    onDelete = { v -> delete(listOf(v)) },
                    onDone = { msg -> qualityFor = null; hint(msg) },
                )
            }
        }
        Overlay(visible = updateSheet, onDismiss = { updateSheet = false }) {
            UpdateSheet(
                onLater = { update.release?.let(Updater::dismiss); updateSheet = false },
                onClose = { updateSheet = false },
            )
        }
        if (!introDone) Intro(onDone = { introDone = true })
        // Always cover the entire app in PiP, even if playback stops during the transition.
        // Keep the normal UI composed underneath so tabs and scroll positions survive.
        if (inPip) PlayerHost(miniBottom = 0.dp, inPip = true, onMenu = { menuFor = it }, onQuality = { qualityFor = it })
    }
}

private fun copy(context: android.content.Context, text: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("Hamstr", text))
}

@Composable
private fun TaskMenu(
    task: DownloadTask,
    gone: Boolean,
    onPlay: (DownloadTask) -> Unit,
    onClose: () -> Unit,
    onQuality: (DownloadTask) -> Unit,
    onSaveAudio: (DownloadTask) -> Unit,
    onRename: (DownloadTask) -> Unit,
    onInfo: (DownloadTask) -> Unit,
    onAddToPlaylist: (DownloadTask) -> Unit,
    onHide: (List<DownloadTask>) -> Unit,
    onDelete: (DownloadTask) -> Unit,
    onAgain: (DownloadTask) -> Unit,
    onCopied: (String) -> Unit,
) {
    val c = Hamstr.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // The menu stays open while the download moves on, so it follows the live task.
    val tasks by DownloadStore.tasks.collectAsState()
    val t = tasks.firstOrNull { it.id == task.id } ?: task
    val file = t.filePath?.let(::File)?.takeIf { it.exists() }
    var updating by remember { mutableStateOf(false) }
    Sheet {
        if (t.status != Status.Done && t.status != Status.Canceled) {
            DownloadPreview(t)
            Spacer(Modifier.height(12.dp))
        }
        MenuHeader(t, showPicture = t.status == Status.Done)
        Spacer(Modifier.height(14.dp))
        if (t.status == Status.Failed) {
            // What happened, in plain words, with the engine's own message underneath.
            val why = Failures.explain(t.error)
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(Radii.row)).background(c.surface).padding(12.dp)) {
                Txt(stringResource(why.title), style = Hamstr.type.bodyMedium, color = c.red)
                Spacer(Modifier.height(2.dp))
                Txt(stringResource(why.hint), style = Hamstr.type.small, color = c.muted)
                t.error?.let {
                    Spacer(Modifier.height(8.dp))
                    Txt(it, style = Hamstr.type.mono, color = c.ghost)
                }
                if (why.fix == Fix.UpdateYtDlp) {
                    Spacer(Modifier.height(10.dp))
                    HButton(
                        stringResource(if (updating) R.string.err_updating else R.string.err_update_retry),
                        {
                            if (!updating) scope.launch {
                                updating = true
                                Engine.update(context)
                                updating = false
                                DownloadService.send(context, DownloadService.ACTION_RESUME, t.id)
                                onClose()
                            }
                        },
                        Modifier.fillMaxWidth(), kind = ButtonKind.Primary, icon = R.drawable.ic_refresh, enabled = !updating,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            t.error?.let { err ->
                MenuRow(R.drawable.ic_content_paste, stringResource(R.string.err_copy)) { copy(context, err); onCopied(context.getString(R.string.err_copied)) }
            }
        }
        if (gone && t.status == Status.Done) {
            Txt(stringResource(R.string.file_gone_long), style = Hamstr.type.small, color = c.muted, modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp))
            MenuRow(R.drawable.ic_refresh, stringResource(R.string.download_again)) { onAgain(t) }
        }
        val copyLink = { copy(context, t.url); onCopied(context.getString(R.string.info_copied)); onClose() }
        if (file != null) {
            val kind = kindOf(file)
            val playable = kind == Kind.Video || kind == Kind.Audio
            // The everyday actions as tiles, then what changes the file, then what removes it.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionTile(
                    if (playable) R.drawable.ic_play_arrow else R.drawable.ic_open_in_new,
                    stringResource(if (playable) R.string.menu_tile_play else R.string.menu_tile_view),
                    accent = true,
                ) { onPlay(t); onClose() }
                ActionTile(R.drawable.ic_share, stringResource(R.string.menu_tile_share)) { Playback.shareFile(context, t); onClose() }
                ActionTile(R.drawable.ic_open_in_new, stringResource(R.string.menu_tile_open)) { Playback.openWith(context, t); onClose() }
                ActionTile(R.drawable.ic_link, stringResource(R.string.menu_copy_link)) { copyLink() }
            }
            Spacer(Modifier.height(10.dp))
            MenuGroup {
                if (canHaveVersions(t)) MenuRow(R.drawable.ic_movie, stringResource(R.string.menu_quality)) { onQuality(t) }
                if (canSaveAudio(t, tasks.filter { it.family == t.family })) MenuRow(R.drawable.ic_music_note, stringResource(R.string.menu_save_audio)) { onSaveAudio(t) }
                if (playable) MenuRow(R.drawable.ic_playlist_add, stringResource(R.string.pl_add)) { onAddToPlaylist(t) }
                MenuRow(R.drawable.ic_edit, stringResource(R.string.menu_rename)) { onRename(t) }
                MenuRow(R.drawable.ic_info, stringResource(R.string.menu_info)) { onInfo(t) }
            }
            Spacer(Modifier.height(10.dp))
        } else {
            MenuRow(R.drawable.ic_link, stringResource(R.string.menu_copy_link)) { copyLink() }
        }
        if (t.status == Status.Failed || t.status == Status.Paused) {
            MenuRow(R.drawable.ic_refresh, stringResource(R.string.err_try_again)) { DownloadService.send(context, DownloadService.ACTION_RESUME, t.id); onClose() }
        }
        when {
            t.status.active -> MenuRow(R.drawable.ic_close, stringResource(R.string.menu_cancel)) { DownloadService.send(context, DownloadService.ACTION_CANCEL, t.id); onClose() }
            file != null -> MenuGroup {
                MenuRow(R.drawable.ic_visibility_off, stringResource(R.string.menu_hide), subtitle = stringResource(R.string.menu_hide_hint)) { onHide(listOf(t)) }
                MenuRow(R.drawable.ic_delete, stringResource(R.string.menu_delete), danger = true) { onDelete(t) }
            }
            else -> MenuRow(R.drawable.ic_close, stringResource(R.string.menu_remove)) { onHide(listOf(t)) }
        }
    }
}

/** Picture, title and one line of facts (channel, quality, size) at the top of the menu. */
@Composable
private fun MenuHeader(t: DownloadTask, showPicture: Boolean) {
    val c = Hamstr.colors
    Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (showPicture) {
            val bitmap = rememberBitmap(t.thumbnail, 240)
            Box(Modifier.width(88.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)).background(c.black)) {
                bitmap?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            }
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Txt(t.title, style = Hamstr.type.bodyMedium, maxLines = 2)
            Spacer(Modifier.height(3.dp))
            val facts = listOfNotNull(
                t.uploader ?: t.source,
                formatBadge(t).takeIf { t.status == Status.Done }?.uppercase(),
                t.sizeBytes.takeIf { it > 0 }?.let(::formatBytes),
            )
            Txt(facts.joinToString(" · ").ifEmpty { t.url }, style = Hamstr.type.mono, color = c.ghost, maxLines = 1)
        }
    }
}

/** A button in the menu's top row: icon over a short label. */
@Composable
private fun RowScope.ActionTile(icon: Int, label: String, accent: Boolean = false, onClick: () -> Unit) {
    val c = Hamstr.colors
    Column(
        Modifier
            .weight(1f)
            .clip(RoundedCornerShape(16.dp))
            .background(if (accent) c.accent else c.surface)
            .pressable(onClick)
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, tint = if (accent) c.onAccent else c.ink, size = 22.dp)
        Spacer(Modifier.height(6.dp))
        Txt(label, style = Hamstr.type.small, color = if (accent) c.onAccent else c.muted, maxLines = 1)
    }
}

/** Rows that belong together, on one panel. */
@Composable
private fun MenuGroup(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Hamstr.colors.surface).padding(4.dp),
        content = content,
    )
}

@Composable
private fun MenuRow(icon: Int, label: String, danger: Boolean = false, subtitle: String? = null, onClick: () -> Unit) {
    val c = Hamstr.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, tint = if (danger) c.red else c.muted)
        Column {
            Txt(label, color = if (danger) c.red else c.ink)
            if (subtitle != null) Txt(subtitle, style = Hamstr.type.small, color = c.ghost)
        }
    }
}

/** Bottom tab bar: tap a tab or drag the pill along; it stretches with its speed. */
@Composable
private fun TabBar(selected: Tab, onSelect: (Tab) -> Unit, modifier: Modifier = Modifier) {
    val c = Hamstr.colors
    DragPillRow(
        count = Tab.entries.size,
        selected = selected.ordinal,
        onSelect = { onSelect(Tab.entries[it]) },
        itemHeight = 54.dp,
        pillRadius = 17.dp,
        pillColor = c.hover,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(c.overlay)
            .padding(5.dp),
    ) { i, near ->
        val t = Tab.entries[i]
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.graphicsLayer {
                val s = 1f + near * 0.06f
                scaleX = s; scaleY = s
                translationY = -near * 1.5f
            },
        ) {
            Icon(t.icon, tint = lerp(c.ghost, c.ink, near), size = 22.dp)
            Spacer(Modifier.height(3.dp))
            Txt(stringResource(t.label), style = Hamstr.type.small, color = lerp(c.ghost, c.ink, near), maxLines = 1)
        }
    }
}

/** Width of the side bar that holds the tabs and the mini player in landscape. */
private val SIDEBAR = 216.dp

/** The tabs as a column for the side bar; the pill springs to the chosen one. */
@Composable
private fun SideTabs(selected: Tab, onSelect: (Tab) -> Unit, modifier: Modifier = Modifier) {
    val c = Hamstr.colors
    val haptics = LocalHapticFeedback.current
    val itemHeight = 48.dp
    val y by animateFloatAsState(
        selected.ordinal.toFloat(),
        if (LocalReduceMotion.current) snap() else Specs.arrive(),
        label = "sideTab",
    )
    Box(modifier) {
        Box(
            Modifier
                .offset { IntOffset(0, (y * itemHeight.toPx()).roundToInt()) }
                .fillMaxWidth()
                .height(itemHeight)
                .clip(RoundedCornerShape(17.dp))
                .background(c.hover),
        )
        Column {
            Tab.entries.forEach { t ->
                val near = (1f - abs(y - t.ordinal)).coerceIn(0f, 1f)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(itemHeight)
                        .clip(RoundedCornerShape(17.dp))
                        .clickable(remember { MutableInteractionSource() }, null, role = Role.Tab) {
                            if (t != selected) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            onSelect(t)
                        }
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(t.icon, tint = lerp(c.ghost, c.ink, near), size = 22.dp)
                    Spacer(Modifier.width(14.dp))
                    Txt(stringResource(t.label), style = Hamstr.type.bodyMedium, color = lerp(c.ghost, c.ink, near), maxLines = 1)
                }
            }
        }
    }
}

/**
 * Cold-start intro: the hamster assembles out of ASCII noise, hops and winks, then the
 * screen fades away into the app. Tap to skip.
 */
@Composable
private fun Intro(onDone: () -> Unit) {
    val c = Hamstr.colors
    val mark = remember { MarkState(intro = 0f) }
    val fade = remember { Animatable(1f) }
    val done by rememberUpdatedState(onDone)
    var skip by remember { mutableStateOf(false) }
    LaunchedEffect(skip) {
        if (!skip) {
            mark.assemble(700)
            delay(260)
        }
        fade.animateTo(0f, tween(320))
        done()
    }
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = fade.value }
            .background(c.canvas)
            .clickable(remember { MutableInteractionSource() }, null) { skip = true },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.graphicsLayer {
                val s = 0.9f + 0.1f * fade.value
                scaleX = s; scaleY = s
            },
        ) {
            HamstrMark(150.dp, state = mark, background = false, interactive = false)
            Spacer(Modifier.height(18.dp))
            DecodeText("Hamstr", style = Hamstr.type.title, delayMs = 380)
        }
    }
}
