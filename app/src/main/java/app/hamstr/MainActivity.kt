package app.hamstr

import androidx.compose.ui.platform.LocalWindowInfo
import kotlinx.coroutines.flow.MutableStateFlow
import app.hamstr.ui.GroupSheet
import androidx.activity.compose.rememberLauncherForActivityResult
import app.hamstr.data.Importer
import app.hamstr.ui.AddSheet
import android.Manifest
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.hamstr.data.DownloadStore
import app.hamstr.data.Prefs
import app.hamstr.data.DownloadTask
import app.hamstr.data.Status
import app.hamstr.engine.DownloadService
import app.hamstr.engine.Engine
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import app.hamstr.ui.Overlay
import app.hamstr.ui.components.DecodeText
import app.hamstr.ui.components.DragPillRow
import app.hamstr.ui.components.HamstrMark
import app.hamstr.ui.components.LocalReduceMotion
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
import app.hamstr.ui.StashScreen
import app.hamstr.ui.DownloadPreview
import app.hamstr.ui.UpdateBanner
import app.hamstr.ui.UpdateSheet
import app.hamstr.ui.release
import app.hamstr.update.UpdateState
import app.hamstr.update.Updater
import app.hamstr.ui.components.Icon
import app.hamstr.ui.components.RasterBackground
import app.hamstr.ui.components.Txt
import app.hamstr.ui.theme.Hamstr
import app.hamstr.ui.theme.LocalFx
import app.hamstr.ui.theme.HamstrTheme
import app.hamstr.ui.theme.TravelEasing
import java.io.File

class MainActivity : ComponentActivity() {
    private val pip = PipController { params -> runCatching { setPictureInPictureParams(params) } }
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    private val storagePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        askPermissions(this, notifPermission::launch, storagePermission::launch)
        if (DownloadStore.tasks.value.any { it.status == Status.Queued }) DownloadService.send(this, DownloadService.ACTION_START)
        Updater.checkOnLaunch(this)
        addOnPictureInPictureModeChangedListener { inPip.value = it.isInPictureInPictureMode }
        inPip.value = isInPictureInPictureMode
        pip.bind(null, null)
        lifecycleScope.launch {
            combine(Playback.current, Playback.controller) { task, player -> task to player }
                .collect { (task, player) -> pip.bind(task, player) }
        }
        if (savedInstanceState == null) pendingAction.value = intent?.action?.takeIf { it in ACTIONS }
        setContent { HamstrTheme { App(inPip.value) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.action?.takeIf { it in ACTIONS }?.let { pendingAction.value = it }
    }

    companion object {
        // Launcher shortcuts and the widget's buttons.
        const val ACTION_PASTE = "app.hamstr.action.PASTE"
        const val ACTION_ADD = "app.hamstr.action.ADD"
        const val ACTION_LIBRARY = "app.hamstr.action.LIBRARY"
        private val ACTIONS = setOf(ACTION_PASTE, ACTION_ADD, ACTION_LIBRARY)
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

fun askPermissions(activity: ComponentActivity, notif: (String) -> Unit, storage: (String) -> Unit) {
    if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
        notif(Manifest.permission.POST_NOTIFICATIONS)
    }
    if (Build.VERSION.SDK_INT <= 28 && ContextCompat.checkSelfPermission(activity, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
        storage(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }
}

private enum class Tab(val label: String, val icon: Int) {
    Stash("Stash", R.drawable.ic_download),
    Library("Library", R.drawable.ic_grid_view),
    Settings("Settings", R.drawable.ic_settings),
}

private val URL = Regex("""https?://\S+""")

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
    var hint by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    var addSheet by rememberSaveable { mutableStateOf(false) }
    var groupFor by rememberSaveable { mutableStateOf<String?>(null) }
    var clipLink by remember { mutableStateOf<String?>(null) }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            hint = if (uris.size == 1) "Bringing it in …" else "Bringing ${uris.size} files in …"
            val n = Importer.import(context, uris)
            hint = when {
                n == uris.size -> if (n == 1) "Added to the stash" else "$n files added"
                n == 0 -> "Couldn't read that file"
                else -> "$n of ${uris.size} files added"
            }
        }
    }
    fun clipboardLink(): String? {
        val cm = context.getSystemService(ClipboardManager::class.java)
        val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
        return URL.find(text)?.value
    }
    val playing by Playback.current.collectAsState()
    val play: (DownloadTask) -> Unit = { t -> scope.launch { Playback.open(context, t) } }
    val settings by Prefs.settings.collectAsState()
    val update by Updater.state.collectAsState()
    var updateSheet by rememberSaveable { mutableStateOf(false) }
    // The banner shows once per release; closing it leaves the update in Settings > About.
    val offered = (update as? UpdateState.Available)?.release?.takeIf { it.version != settings.seenRelease }

    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // Room for the tab bar, plus the mini player while something is loaded.
    val padding = PaddingValues(top = top + 8.dp, bottom = bottom + 84.dp + if (playing != null) 72.dp else 0.dp)

    LaunchedEffect(hint) { if (hint != null) { kotlinx.coroutines.delay(2600); hint = null } }

    // The clipboard is only readable once the window has focus.
    val action by MainActivity.pendingAction.collectAsState()
    val focused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(action, focused) {
        when (action) {
            MainActivity.ACTION_LIBRARY -> tab = Tab.Library
            MainActivity.ACTION_ADD -> if (focused) { tab = Tab.Stash; clipLink = clipboardLink(); addSheet = true } else return@LaunchedEffect
            MainActivity.ACTION_PASTE -> if (focused) {
                tab = Tab.Stash
                val url = clipboardLink()
                if (url != null) sheetUrl = url else { hint = "No link in the clipboard"; clipLink = null; addSheet = true }
            } else return@LaunchedEffect
            else -> return@LaunchedEffect
        }
        MainActivity.pendingAction.value = null
    }

    Box(Modifier.fillMaxSize().background(c.canvas)) {
        RasterBackground(Modifier.fillMaxWidth().height(top + 280.dp))
        AnimatedContent(
            targetState = tab,
            transitionSpec = {
                val dir = if (targetState.ordinal > initialState.ordinal) 1 else -1
                (slideInHorizontally(spring(dampingRatio = 0.85f, stiffness = 420f)) { dir * it / 5 } + fadeIn(tween(220))) togetherWith
                    (slideOutHorizontally(tween(180)) { -dir * it / 6 } + fadeOut(tween(150)))
            },
            label = "tab",
        ) { t ->
            when (t) {
                Tab.Stash -> StashScreen(
                    tasks, engine, padding,
                    onAdd = {
                        val url = clipboardLink()
                        if (url != null) sheetUrl = url else hint = "No link in the clipboard"
                    },
                    onOpenAdd = { clipLink = clipboardLink(); addSheet = true },
                    onMenu = { menuFor = it },
                    onPlay = play,
                )
                Tab.Library -> LibraryScreen(tasks, padding, onMenu = { menuFor = it }, onPlay = play, onGroup = { groupFor = it })
                Tab.Settings -> SettingsScreen(padding, onUpdate = { updateSheet = true })
            }
        }
        TabBar(tab, { tab = it }, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(start = 12.dp, end = 12.dp, bottom = 12.dp))
        if (!inPip) PlayerHost(miniBottom = bottom + 78.dp, inPip = false)

        AnimatedVisibility(hint != null, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 96.dp)) {
            Box(Modifier.clip(RoundedCornerShape(14.dp)).background(c.raised).padding(horizontal = 16.dp, vertical = 10.dp)) {
                Txt(hint.orEmpty(), style = Hamstr.type.small)
            }
        }

        var lastOffered by remember { mutableStateOf(offered) }
        if (offered != null) lastOffered = offered
        AnimatedVisibility(
            offered != null && introDone && !updateSheet && !inPip,
            enter = slideInVertically(spring(dampingRatio = 0.7f, stiffness = 380f)) { -it * 2 } + fadeIn(),
            exit = slideOutVertically(tween(200)) { -it * 2 } + fadeOut(tween(160)),
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            lastOffered?.let { r -> UpdateBanner(r, onOpen = { updateSheet = true }, onDismiss = { Updater.dismiss(r) }) }
        }

        Overlay(visible = groupFor != null, onDismiss = { groupFor = null }) {
            groupFor?.let { g -> GroupSheet(g, onPlay = { groupFor = null; play(it) }, onMenu = { menuFor = it }) }
        }
        Overlay(visible = addSheet, onDismiss = { addSheet = false }) {
            AddSheet(
                clipboardLink = clipLink,
                onLink = { url -> addSheet = false; sheetUrl = url },
                onFiles = { addSheet = false; pickFiles.launch(arrayOf("*/*")) },
            )
        }
        Overlay(visible = sheetUrl != null, onDismiss = { sheetUrl = null }) {
            sheetUrl?.let { url ->
                ShareSheet(
                    url,
                    onStash = { info, choice ->
                        DownloadService.stash(context, url, info, choice)
                        sheetUrl = null
                        tab = Tab.Stash
                    },
                    onDismiss = { sheetUrl = null },
                )
            }
        }
        Overlay(visible = menuFor != null, onDismiss = { menuFor = null }) {
            menuFor?.let { t -> TaskMenu(t, onPlay = play, onClose = { menuFor = null }) }
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
        if (inPip) PlayerHost(miniBottom = 0.dp, inPip = true)
    }
}

@Composable
private fun TaskMenu(task: DownloadTask, onPlay: (DownloadTask) -> Unit, onClose: () -> Unit) {
    val c = Hamstr.colors
    val context = LocalContext.current
    // The menu stays open while the download moves on, so it follows the live task.
    val tasks by DownloadStore.tasks.collectAsState()
    val t = tasks.firstOrNull { it.id == task.id } ?: task
    val file = t.filePath?.let(::File)?.takeIf { it.exists() }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
            .background(c.overlay)
            .clickable(enabled = false) {}
            .navigationBarsPadding()
            .padding(12.dp),
    ) {
        Box(Modifier.align(Alignment.CenterHorizontally).width(36.dp).height(4.dp).clip(RoundedCornerShape(4.dp)).background(c.line))
        Spacer(Modifier.height(12.dp))
        if (t.status != Status.Done && t.status != Status.Canceled) {
            DownloadPreview(t, Modifier.padding(horizontal = 4.dp))
            Spacer(Modifier.height(12.dp))
        }
        Txt(t.title, style = Hamstr.type.section, maxLines = 2, modifier = Modifier.padding(horizontal = 12.dp))
        Spacer(Modifier.height(2.dp))
        Txt(t.url, style = Hamstr.type.mono, color = c.ghost, maxLines = 1, modifier = Modifier.padding(horizontal = 12.dp))
        Spacer(Modifier.height(10.dp))
        if (file != null) {
            val kind = kindOf(file)
            MenuRow(
                if (kind == Kind.Video || kind == Kind.Audio) R.drawable.ic_play_arrow else R.drawable.ic_open_in_new,
                when (kind) { Kind.Video, Kind.Audio -> "Play in Hamstr"; Kind.Image -> "View in Hamstr"; Kind.Other -> "Details" },
            ) { onPlay(t); onClose() }
            MenuRow(R.drawable.ic_open_in_new, "Open with…") { Playback.openWith(context, t); onClose() }
            MenuRow(R.drawable.ic_share, "Share file") { Playback.shareFile(context, t); onClose() }
        }
        MenuRow(R.drawable.ic_link, "Copy link") {
            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("Link", t.url))
            onClose()
        }
        if (t.status == Status.Failed || t.status == Status.Paused) {
            MenuRow(R.drawable.ic_refresh, "Try again") { DownloadService.send(context, DownloadService.ACTION_RESUME, t.id); onClose() }
        }
        if (t.status.active) {
            MenuRow(R.drawable.ic_close, "Cancel") { DownloadService.send(context, DownloadService.ACTION_CANCEL, t.id); onClose() }
        } else {
            MenuRow(R.drawable.ic_close, "Remove from list") { DownloadStore.remove(t.id); onClose() }
        }
        if (file != null) {
            MenuRow(R.drawable.ic_delete, "Delete file", danger = true) {
                file.delete()
                android.media.MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), null, null)
                DownloadStore.remove(t.id)
                onClose()
            }
        }
    }
}

@Composable
private fun MenuRow(icon: Int, label: String, danger: Boolean = false, onClick: () -> Unit) {
    val c = Hamstr.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, tint = if (danger) c.red else c.muted)
        Txt(label, color = if (danger) c.red else c.ink)
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
            Txt(t.label, style = Hamstr.type.small, color = lerp(c.ghost, c.ink, near), maxLines = 1)
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
