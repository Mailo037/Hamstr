package app.hamstr.ui

import app.hamstr.ui.components.swipeActions
import androidx.compose.runtime.key
import app.hamstr.ui.components.SwipeAction
import app.hamstr.ui.components.SwipeRow
import org.jetbrains.compose.resources.DrawableResource
import app.hamstr.res.*
import app.hamstr.data.DownloadStore
import app.hamstr.data.Backup
import app.hamstr.data.Playlists
import app.hamstr.data.StorageUsage
import app.hamstr.data.IconStyle
import app.hamstr.data.MiniStyle
import app.hamstr.data.UiSize
import androidx.compose.foundation.Image
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.asImageBitmap
import app.hamstr.data.DownloadTask
import app.hamstr.data.Status
import app.hamstr.ui.components.AsciiImage
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.graphics.Color
import app.hamstr.data.DownloadEngine
import androidx.compose.runtime.CompositionLocalProvider
import app.hamstr.ui.components.searchHighlight
import app.hamstr.ui.components.LocalSettingHighlight
import app.hamstr.ui.components.SearchField
import androidx.compose.foundation.gestures.Orientation
import app.hamstr.ui.components.edgeFade
import android.Manifest
import android.content.Intent
import androidx.compose.runtime.LaunchedEffect
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.DisposableEffect
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import app.hamstr.ui.components.pressable
import app.hamstr.ui.theme.Radii
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import app.hamstr.BuildConfig
import app.hamstr.R
import app.hamstr.data.ACCENTS
import app.hamstr.data.Base
import app.hamstr.data.Mode
import app.hamstr.data.Prefs
import app.hamstr.data.Settings
import app.hamstr.data.heightLabel
import app.hamstr.engine.Engine
import app.hamstr.engine.Cheek
import app.hamstr.engine.CheekUpdates
import app.hamstr.engine.EngineState
import app.hamstr.engine.EngineVersionCheck
import app.hamstr.ui.components.AccentPicker
import app.hamstr.ui.components.ButtonKind
import app.hamstr.ui.components.DecodeText
import app.hamstr.ui.components.Divider
import app.hamstr.ui.components.Group
import app.hamstr.ui.components.HButton
import app.hamstr.ui.components.HSwitch
import app.hamstr.ui.components.Icon
import app.hamstr.ui.components.IconButton
import app.hamstr.ui.components.Segmented
import app.hamstr.ui.components.SettingRow
import app.hamstr.ui.components.Txt
import app.hamstr.ui.components.rememberVelocityStretch
import app.hamstr.ui.theme.Hamstr
import app.hamstr.update.UpdateState
import app.hamstr.update.Updater
import kotlinx.coroutines.launch
import kotlin.math.abs
import app.hamstr.ui.components.Chip
import app.hamstr.ui.components.ChipRow
import app.hamstr.ui.components.Chips
import app.hamstr.ui.components.Specs

/** Settings are split into categories, each on its own page (Glyph: never one long page). */
private enum class Page(val title: Int, val icon: DrawableResource) {
    Appearance(R.string.page_appearance, Res.drawable.ic_palette),
    Motion(R.string.page_motion, Res.drawable.ic_auto_awesome),
    Downloads(R.string.page_downloads, Res.drawable.ic_download),
    Following(R.string.page_following, Res.drawable.ic_person),
    Playback(R.string.page_playback, Res.drawable.ic_play_arrow),
    Queue(R.string.page_queue, Res.drawable.ic_folder_open),
    Engine(R.string.page_engine, Res.drawable.ic_code),
    Devices(R.string.page_devices, Res.drawable.ic_devices),
    Hidden(R.string.page_hidden, Res.drawable.ic_visibility_off),
    Data(R.string.page_data, Res.drawable.ic_pie_chart),
    About(R.string.page_about, Res.drawable.ic_info),
}

@Composable
fun SettingsScreen(
    contentPadding: PaddingValues,
    onUpdate: () -> Unit,
    onDelete: (List<DownloadTask>) -> Unit,
    onHint: (String) -> Unit,
    onTour: () -> Unit,
    onDevice: (String) -> Unit,
    onMenu: (DownloadTask) -> Unit,
    onFollowing: (app.hamstr.data.Subscription) -> Unit = {},
    onUnfollowed: (app.hamstr.data.Subscription, Int) -> Unit = { _, _ -> },
) {
    var page by rememberSaveable { mutableStateOf<Page?>(null) }
    var highlight by rememberSaveable { mutableStateOf<String?>(null) }
    BackHandler(enabled = page != null) { page = null; highlight = null }
    AnimatedContent(
        targetState = page,
        transitionSpec = {
            if (targetState != null) {
                (slideInHorizontally(Specs.enter()) { it / 3 } + fadeIn(tween(200))) togetherWith
                    (slideOutHorizontally(Specs.leave()) { -it / 8 } + fadeOut(tween(140)))
            } else {
                (slideInHorizontally(Specs.enter()) { -it / 8 } + fadeIn(tween(200))) togetherWith
                    (slideOutHorizontally(Specs.leave()) { it / 3 } + fadeOut(tween(140)))
            }
        },
        label = "settingsPage",
    ) { p ->
        if (p == null) Root(contentPadding) { target, setting -> page = target; highlight = setting }
        else CompositionLocalProvider(LocalSettingHighlight provides highlight) {
            SubPage(p, contentPadding, onUpdate, onDelete, onHint, onTour, onDevice, onMenu, onFollowing, onUnfollowed, onBack = { page = null; highlight = null })
        }
    }
}

@Composable
private fun Root(contentPadding: PaddingValues, open: (Page, String?) -> Unit) {
    val s by Prefs.settings.collectAsState()
    val engine by Engine.state.collectAsState()
    val tasks by DownloadStore.tasks.collectAsState()
    val hidden = remember(tasks) { tasks.filter { it.hidden && it.status == Status.Done } }
    val scroll = rememberScrollState()
    var query by rememberSaveable { mutableStateOf("") }
    val context = LocalContext.current
    val results = remember(query) { searchSettings(context, query) }
    Column(
        Modifier
            .fillMaxSize()
            .edgeFade(scroll, Orientation.Vertical, insets = contentPadding)
            .verticalScroll(scroll)
            .padding(contentPadding)
            .padding(horizontal = 12.dp),
    ) {
        DecodeText(stringResource(R.string.tab_settings), style = Hamstr.type.title, modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp))
        Spacer(Modifier.height(10.dp))
        SearchField(query, { query = it }, stringResource(R.string.set_search))
        Spacer(Modifier.height(12.dp))
        if (query.isNotBlank()) {
            if (results.isEmpty()) {
                Txt(stringResource(R.string.set_no_match, query.trim()), color = Hamstr.colors.ghost, modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp))
            } else Group {
                results.forEach { r -> CategoryRow(r.page, stringResource(r.page.title), title = r.title) { open(r.page, r.title) } }
            }
        } else Group {
            Page.entries.forEach { p -> CategoryRow(p, summary(context, p, s, engine, hidden)) { open(p, null) } }
        }
    }
}

private fun summary(context: android.content.Context, p: Page, s: Settings, engine: EngineState, hidden: List<DownloadTask>): String = when (p) {
    Page.Playback -> if (s.eqOn) context.getString(R.string.set_playback_eq, app.hamstr.audio.Equalizer.Preset.of(s.eqPreset)?.let { context.getString(eqName(it)) } ?: context.getString(R.string.eq_custom))
        else context.getString(if (s.resumeAfterVideos) R.string.set_playback_resumes else R.string.set_playback_pauses)
    Page.Data -> context.getString(R.string.set_storage) + " · " + context.getString(R.string.set_backup)
    Page.Hidden -> if (hidden.isEmpty()) context.getString(R.string.hidden_summary_none)
        else context.resources.getQuantityString(R.plurals.hidden_summary, hidden.size, hidden.size, formatBytes(hidden.sumOf { it.sizeBytes }))
    Page.Appearance -> "${ACCENTS.firstOrNull { it.argb == s.accent }?.name ?: context.getString(R.string.set_custom)} · ${context.getString(baseName(s.base))}"
    Page.Motion -> {
        val on = listOf(s.intro, s.raster, s.asciiThumbs, s.rain, s.decode, s.moods, s.stretch, s.haptics).count { it }
        context.getString(R.string.set_effects_on, on, 8)
    }
    Page.Downloads -> if (s.mode == Mode.Audio) "${context.getString(R.string.mode_audio)} · ${s.audioFormat}"
        else "${context.getString(R.string.mode_video)} · ${heightLabel(s.height)} · ${s.container}"
    Page.Following -> app.hamstr.data.Subscriptions.all.value.size.let { n ->
        if (n == 0) context.getString(R.string.follow_summary_none) else context.resources.getQuantityString(R.plurals.follow_summary, n, n)
    }
    Page.Queue -> context.getString(R.string.set_queue_summary, s.parallel) + (if (s.wifiOnly) " · " + context.getString(R.string.set_wifi_short) else "") +
        if (s.nightOnly) " · " + context.getString(R.string.set_night) else ""
    Page.Engine -> when (engine) {
        is EngineState.Ready -> (if (s.engine == DownloadEngine.Cheek) "Cheek ${Cheek.active.value} · yt-dlp " else "yt-dlp ") + engine.version
        EngineState.Starting -> context.getString(R.string.set_starting)
        is EngineState.Broken -> context.getString(R.string.set_error)
    }
    Page.Devices -> if (s.nearby) context.getString(R.string.set_devices_summary, s.deviceName.ifBlank { app.hamstr.nearby.Nearby.defaultName })
        else context.getString(R.string.set_devices_summary_off)
    Page.About -> "Hamstr ${BuildConfig.VERSION_NAME}" + if (s.appUpdates) " · " + context.getString(R.string.set_checks_updates) else ""
}

@Composable
private fun CategoryRow(p: Page, summary: String, title: String = stringResource(p.title), onClick: () -> Unit) {
    val c = Hamstr.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radii.row))
            .pressable(onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(c.surface), contentAlignment = Alignment.Center) {
            Icon(p.icon, tint = c.accent, size = 20.dp)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Txt(title, style = Hamstr.type.bodyMedium)
            Spacer(Modifier.height(2.dp))
            Txt(summary, style = Hamstr.type.small, color = c.ghost, maxLines = 1)
        }
        Icon(Res.drawable.ic_chevron_right, tint = c.ghost, size = 20.dp)
    }
}

/**
 * A category page. Besides the back button and system back, it can be dragged to the
 * right: it follows the finger, squashes a little with its speed and goes back past 30 %.
 */
@Composable
private fun SubPage(
    p: Page,
    contentPadding: PaddingValues,
    onUpdate: () -> Unit,
    onDelete: (List<DownloadTask>) -> Unit,
    onHint: (String) -> Unit,
    onTour: () -> Unit,
    onDevice: (String) -> Unit,
    onMenu: (DownloadTask) -> Unit,
    onFollowing: (app.hamstr.data.Subscription) -> Unit,
    onUnfollowed: (app.hamstr.data.Subscription, Int) -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val offset = remember { Animatable(0f) }
    var width by remember { mutableIntStateOf(1) }
    var raw by remember { mutableFloatStateOf(0f) }
    val stretch by rememberVelocityStretch({ offset.value }, sensitivity = 0.00004f, max = 0.05f)
    val scroll = rememberScrollState()
    Column(
        Modifier
            .fillMaxSize()
            .onSizeChanged { width = it.width.coerceAtLeast(1) }
            .graphicsLayer {
                translationX = offset.value
                alpha = 1f - (offset.value / width).coerceIn(0f, 1f) * 0.5f
                scaleY = 1f - abs(stretch) * 0.6f
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { raw = 0f },
                    onHorizontalDrag = { change, dx ->
                        raw += dx
                        if (raw > 0f) {
                            change.consume()
                            scope.launch { offset.snapTo(raw) }
                        }
                    },
                    onDragEnd = {
                        if (offset.value > width * 0.3f) {
                            haptics.performHapticFeedback(HapticFeedbackType.GestureEnd)
                            onBack()
                        } else scope.launch { offset.animateTo(0f, Specs.release()) }
                    },
                    onDragCancel = { scope.launch { offset.animateTo(0f, Specs.release()) } },
                )
            }
            .edgeFade(scroll, Orientation.Vertical, insets = contentPadding)
            .verticalScroll(scroll)
            .padding(contentPadding)
            .padding(horizontal = 12.dp),
    ) {
        Row(Modifier.padding(horizontal = 2.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(Res.drawable.ic_arrow_back, stringResource(R.string.set_back), onBack, tint = Hamstr.colors.ink)
            Spacer(Modifier.width(12.dp))
            DecodeText(stringResource(p.title), style = Hamstr.type.title)
        }
        Spacer(Modifier.height(14.dp))
        when (p) {
            Page.Appearance -> AppearancePage()
            Page.Motion -> MotionPage()
            Page.Downloads -> DownloadsPage()
            Page.Following -> app.hamstr.ui.FollowingSettings(onFollowing, onUnfollowed)
            Page.Queue -> QueuePage()
            Page.Playback -> PlaybackPage()
            Page.Engine -> EnginePage()
            Page.Devices -> DevicesPage(onDevice)
            Page.Hidden -> HiddenSettings(onDelete, onHint, onMenu)
            Page.Data -> DataPage()
            Page.About -> AboutPage(onUpdate, onTour)
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** Nearby: the shared settings, plus Bluetooth, which needs Android's permission and the adapter on. */
@Composable
private fun DevicesPage(onDevice: (String) -> Unit) {
    val s by Prefs.settings.collectAsState()
    val context = LocalContext.current
    val ble = app.hamstr.nearby.BleTransport
    var allowed by remember { mutableStateOf(ble.granted(context)) }
    var on by remember { mutableStateOf(ble.adapterOn(context)) }
    // Bluetooth can be switched in the quick settings while this page is open.
    LaunchedEffect(Unit) {
        while (true) {
            allowed = ble.granted(context)
            on = ble.adapterOn(context)
            kotlinx.coroutines.delay(1500)
        }
    }
    val ask = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()) {
        allowed = ble.granted(context)
        ble.instance?.refresh()
    }
    val turnOn = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) {
        on = ble.adapterOn(context)
    }
    NearbySettings(bluetooth = {
        val hint = when {
            !s.nearbyBluetooth -> R.string.set_nearby_bluetooth_hint
            !allowed -> R.string.set_nearby_bluetooth_off
            !on -> R.string.set_nearby_bluetooth_disabled
            else -> R.string.set_nearby_bluetooth_hint
        }
        FxRow(stringResource(R.string.set_nearby_bluetooth), stringResource(hint), s.nearbyBluetooth) { v ->
            when {
                // On but not ready: the tap fixes what's missing instead of switching it off.
                s.nearbyBluetooth && !allowed -> ask.launch(ble.permissions)
                s.nearbyBluetooth && !on -> turnOn.launch(Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE))
                else -> {
                    Prefs.edit { it.copy(nearbyBluetooth = v) }
                    if (v && !allowed) ask.launch(ble.permissions)
                }
            }
        }
    }, onDevice = onDevice)
}

@Composable
private fun AppearancePage() {
    val s by Prefs.settings.collectAsState()
    val activity = LocalContext.current as android.app.Activity
    Group {
        LookBlocks()
        LanguageBlock(remember { app.hamstr.AppLanguage.current(activity) }) { app.hamstr.AppLanguage.set(activity, it) }
        SettingBlock(stringResource(R.string.set_mini)) {
            val labels = mapOf(MiniStyle.Floating to stringResource(R.string.mini_floating), MiniStyle.Docked to stringResource(R.string.mini_docked), MiniStyle.Compact to stringResource(R.string.mini_compact))
            Segmented(MiniStyle.entries.toList(), s.miniStyle, { labels.getValue(it) }, { v -> Prefs.edit { it.copy(miniStyle = v) } })
        }
    }
    Spacer(Modifier.height(12.dp))
    Group {
        SettingBlock(stringResource(R.string.set_icon_style)) {
            IconStylePicker(s.iconStyle, s.accent, s.iconFollowsAccent) { v -> Prefs.edit { it.copy(iconStyle = v) } }
        }
        Divider()
        FxRow(stringResource(R.string.set_icon_accent), stringResource(R.string.set_icon_accent_hint), s.iconFollowsAccent) { v -> Prefs.edit { it.copy(iconFollowsAccent = v) } }
    }
    Spacer(Modifier.height(12.dp))
    Txt(stringResource(R.string.set_appearance_note), style = Hamstr.type.small, color = Hamstr.colors.ghost, modifier = Modifier.padding(horizontal = 6.dp))
}

/** The launcher icon's styles as small tiles, drawn with the cheeks the launcher will show. */
@Composable
private fun IconStylePicker(style: IconStyle, accent: Int, follows: Boolean, onPick: (IconStyle) -> Unit) {
    val c = Hamstr.colors
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val cheeks = ACCENTS[if (follows) AppIcon.nearest(accent) else 0].argb
    val labels = mapOf(IconStyle.Dark to R.string.icon_dark, IconStyle.Light to R.string.icon_light, IconStyle.Accent to R.string.icon_accent)
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        IconStyle.entries.forEach { st ->
            val on = st == style
            val (tile, ink, cheek) = when (st) {
                IconStyle.Dark -> Triple(0xFF0A0A09.toInt(), 0xFFEEEEEE.toInt(), cheeks)
                IconStyle.Light -> Triple(0xFFF2EEE8.toInt(), 0xFF1A1714.toInt(), cheeks)
                IconStyle.Accent -> Triple(cheeks, 0xFF14100A.toInt(), 0xFFFFFFFF.toInt())
            }
            val mark = remember(st, cheek) { AppIcon.mark(context, 160, cheek, ink).asImageBitmap() }
            val scale by animateFloatAsState(if (on) 1.08f else 1f, Specs.pop(), label = "iconStyle")
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .size(64.dp)
                        .graphicsLayer { scaleX = scale; scaleY = scale }
                        .clip(RoundedCornerShape(20.dp))
                        .border(if (on) 2.dp else 0.dp, if (on) c.ink else Color.Transparent, RoundedCornerShape(20.dp))
                        .padding(if (on) 4.dp else 0.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(tile))
                        .clickable {
                            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            onPick(st)
                        }
                        .padding(12.dp),
                ) { Image(mark, null, Modifier.fillMaxSize()) }
                Spacer(Modifier.height(6.dp))
                Txt(stringResource(labels.getValue(st)), style = Hamstr.type.small, color = if (on) c.ink else c.ghost)
            }
        }
    }
}

@Composable
private fun PlaybackPage() {
    val s by Prefs.settings.collectAsState()
    Group {
        FxRow(stringResource(R.string.set_resume_videos), stringResource(R.string.set_resume_videos_hint), s.resumeAfterVideos) { v -> Prefs.edit { it.copy(resumeAfterVideos = v) } }
        Divider()
        CrossfadeBlock()
    }
    Spacer(Modifier.height(12.dp))
    Group { EqualizerBlock() }
}

/** How long one song fades into the next; 0 keeps the cut. Only the phone plays a queue. */
@Composable
internal fun CrossfadeBlock() {
    val s by Prefs.settings.collectAsState()
    var secs by remember(s.crossfade) { mutableIntStateOf(s.crossfade) }
    val resources = LocalResources.current
    val off = stringResource(R.string.set_crossfade_off)
    val label = stringResource(R.string.set_crossfade)
    Column(Modifier.searchHighlight(label).padding(horizontal = 12.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Txt(label, modifier = Modifier.weight(1f))
            Txt(if (secs == 0) off else stringResource(R.string.set_crossfade_secs, secs), style = Hamstr.type.mono, color = if (secs == 0) Hamstr.colors.ghost else Hamstr.colors.accent)
        }
        app.hamstr.ui.components.HSlider(
            secs, 0..12, label,
            describe = { if (it == 0) off else resources.getString(R.string.set_crossfade_secs, it) },
            onDone = { v -> Prefs.edit { it.copy(crossfade = v) } },
        ) { secs = it }
        Txt(stringResource(R.string.set_crossfade_hint), style = Hamstr.type.small, color = Hamstr.colors.ghost)
    }
}

/** Settings › Data & backup: the shared page, with Android's file pickers and folders. */
@Composable
private fun DataPage() {
    val context = LocalContext.current
    val app = context.applicationContext
    var waiting by remember { mutableStateOf<kotlinx.coroutines.CompletableDeferred<Uri?>?>(null) }
    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { waiting?.complete(it); waiting = null }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { waiting?.complete(it); waiting = null }
    suspend fun pick(launch: () -> Unit): Uri? = kotlinx.coroutines.CompletableDeferred<Uri?>().also { waiting = it; launch() }.await()
    DataSettings(
        measure = { StorageUsage.measure(app) },
        clearCache = { StorageUsage.clearCache(app) },
        export = export@{
            val uri = pick { create.launch(Backup.fileName()) } ?: return@export null
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    val text = Backup.export(BuildConfig.VERSION_NAME, mapOf("playlists" to org.json.JSONArray(Playlists.encode())))
                    app.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(text.toByteArray()) }
                    BackupResult.Saved
                }.getOrElse { BackupResult.Failed(it.message ?: it.javaClass.simpleName, reading = false) }
            }
        },
        import = import@{
            val uri = pick { open.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) } ?: return@import null
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    val text = app.contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
                    val restored = Backup.restore(text)
                    restored.extras.optJSONArray("playlists")?.let { Playlists.merge(it.toString()) }
                    BackupResult.Restored(restored.downloads)
                }.getOrElse { BackupResult.Failed(it.message ?: it.javaClass.simpleName, reading = true) }
            }
        },
    )
}

@Composable
private fun MotionPage() {
    val s by Prefs.settings.collectAsState()
    Group {
        FxRow(stringResource(R.string.set_intro), stringResource(R.string.set_intro_hint), s.intro) { v -> Prefs.edit { it.copy(intro = v) } }
        Divider()
        FxRow(stringResource(R.string.set_moods), stringResource(R.string.set_moods_hint), s.moods) { v -> Prefs.edit { it.copy(moods = v) } }
        Divider()
        FxRow(stringResource(R.string.set_stretch), stringResource(R.string.set_stretch_hint), s.stretch) { v -> Prefs.edit { it.copy(stretch = v) } }
        Divider()
        FxRow(stringResource(R.string.set_haptics), stringResource(R.string.set_haptics_hint), s.haptics) { v -> Prefs.edit { it.copy(haptics = v) } }
    }
    Spacer(Modifier.height(16.dp))
    AsciiEffects()
}

@Composable
private fun DownloadsPage() {
    val s by Prefs.settings.collectAsState()
    FormatDefaults()
    Spacer(Modifier.height(12.dp))
    Group {
        FxRow(stringResource(R.string.set_subtitles), stringResource(R.string.set_subtitles_hint, s.subtitleLanguages().joinToString(", ") { languageName(it) }), s.subtitles) { v -> Prefs.edit { it.copy(subtitles = v) } }
        AnimatedVisibility(s.subtitles, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            SettingBlock(stringResource(R.string.set_sub_langs)) { SubtitleLanguages(s) }
        }
        Divider()
        FxRow(stringResource(R.string.set_clean_names), stringResource(R.string.set_clean_names_hint), s.cleanNames) { v -> Prefs.edit { it.copy(cleanNames = v) } }
        Divider()
        FxRow(stringResource(R.string.set_instant), stringResource(R.string.set_instant_hint), s.quickStash) { v -> Prefs.edit { it.copy(quickStash = v) } }
        Divider()
        FxRow(stringResource(R.string.set_playlists), stringResource(R.string.set_playlists_hint), s.playlists) { v -> Prefs.edit { it.copy(playlists = v) } }
        Divider()
        OpenWhenDoneRow(s.openWhenDone, hint = true) { v -> Prefs.edit { it.copy(openWhenDone = v) } }
        Divider()
        app.hamstr.ui.ClipboardRow()
    }
}

/**
 * Android only lets an app come forward on its own with "Display over other apps", so
 * turning this on asks for that; without it the finished notification stays the way in.
 * Here as the default, and in the download sheet for one download ([hint] off: no line under it
 * unless the permission is missing). With [sendsAway] off (the share sheet) it only switches,
 * and the missing permission is a hint pointing to Settings instead of a jump out of the share.
 */
@Composable
internal fun OpenWhenDoneRow(on: Boolean, hint: Boolean, sendsAway: Boolean = true, set: (Boolean) -> Unit) {
    val context = LocalContext.current
    fun allowed() = android.provider.Settings.canDrawOverlays(context)
    var system by remember { mutableStateOf(allowed()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) system = allowed() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val askSystem = {
        runCatching {
            context.startActivity(
                Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:" + context.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
    val subtitle = when {
        on && !system -> stringResource(if (sendsAway) R.string.set_open_done_needs else R.string.share_open_done_needs)
        hint -> stringResource(R.string.set_open_done_hint)
        else -> null
    }
    SettingRow(
        stringResource(R.string.set_open_done), subtitle = subtitle,
        onClick = { if (sendsAway && on && !system) askSystem() else set(!on) },
        checked = on,
    ) {
        HSwitch(on, { v ->
            set(v)
            if (sendsAway && v && !system) askSystem()
        })
    }
}

@Composable
private fun QueuePage() {
    val s by Prefs.settings.collectAsState()
    Group {
        SettingBlock(stringResource(R.string.set_parallel)) {
            Segmented(listOf(1, 2, 3), s.parallel, { it.toString() }, { n -> Prefs.edit { it.copy(parallel = n) } })
        }
        val context = LocalContext.current
        val where = Engine.outputDir.absolutePath.replace("/storage/emulated/0", stringResource(R.string.set_internal_storage))
        SettingRow(
            stringResource(R.string.set_save_location), subtitle = where + "\n" + stringResource(R.string.set_open_folder),
            onClick = { openFolder(context) },
        ) { Icon(Res.drawable.ic_folder_open, tint = Hamstr.colors.muted, size = 20.dp) }
        Divider()
        // A folder inside Download: Android lets any kind of file live there, the other shared folders not.
        var folder by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(s.downloadFolder)) }
        SettingBlock(stringResource(R.string.set_folder_phone)) {
            app.hamstr.ui.components.SheetField(
                folder,
                { v -> folder = v; Prefs.edit { it.copy(downloadFolder = v.text.take(120)) } },
                "Hamstr", Res.drawable.ic_folder_open, mono = true,
            )
            Spacer(Modifier.height(6.dp))
            app.hamstr.ui.components.Txt(stringResource(R.string.set_folder_phone_hint), style = Hamstr.type.small, color = Hamstr.colors.ghost)
        }
        Divider()
        FxRow(stringResource(R.string.set_wifi_only), stringResource(R.string.set_wifi_only_hint), s.wifiOnly) { v -> Prefs.edit { it.copy(wifiOnly = v) } }
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            Divider()
            FxRow(stringResource(R.string.set_transfer_jobs), stringResource(R.string.set_transfer_jobs_hint), s.transferJobs) { v -> Prefs.edit { it.copy(transferJobs = v) } }
        }
    }
    Spacer(Modifier.height(12.dp))
    Group { app.hamstr.ui.ScheduleRows(charger = true) }
    Spacer(Modifier.height(12.dp))
    Group { NotificationsRow() }
}

/**
 * Hamstr's own notification switch. Off just keeps Hamstr quiet, without a trip to Android's
 * settings. On asks for Android's permission when it is missing, and only goes to the system
 * settings once Android no longer lets Hamstr ask.
 */
@Composable
private fun NotificationsRow() {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val s by Prefs.settings.collectAsState()
    fun allowed() = NotificationManagerCompat.from(context).areNotificationsEnabled()
    var system by remember { mutableStateOf(allowed()) }
    // Follows changes made in the system settings while Hamstr was away.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) system = allowed() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val openSettings = {
        context.startActivity(
            Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { system = allowed() }
    val on = s.notifications && system
    val toggle = {
        if (on) {
            Prefs.edit { it.copy(notifications = false) }
        } else {
            Prefs.edit { it.copy(notifications = true) }
            if (!system) {
                // Android asks itself until the user said no twice; after that only its settings can.
                val canAsk = Build.VERSION.SDK_INT >= 33 && activity != null &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
                    (!s.askedNotifications || activity.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS))
                if (canAsk) {
                    Prefs.edit { it.copy(askedNotifications = true) }
                    ask.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else openSettings()
            }
        }
    }
    SettingRow(
        stringResource(R.string.set_notifications),
        subtitle = stringResource(
            when {
                on -> R.string.set_notifications_on
                !s.notifications -> R.string.set_notifications_off
                else -> R.string.set_notifications_blocked
            },
        ),
        onClick = toggle,
        checked = on,
    ) { HSwitch(on, { toggle() }) }
}

/** Which languages subtitles come in; nothing picked means the phone's language and English. */
@Composable
private fun SubtitleLanguages(s: Settings) {
    val picked = s.subtitleLanguages()
    val system = androidx.compose.ui.text.intl.Locale.current.language
    val options = (listOf(system) + SUBTITLE_LANGUAGES).distinct()
    ChipRow {
        options.forEach { code ->
            val on = code in picked
            Chip(languageName(code), on, {
                val next = if (on) picked - code else picked + code
                // At least one stays picked.
                if (next.isNotEmpty()) Prefs.edit { it.copy(subLangs = next) }
            })
        }
    }
}

private val SUBTITLE_LANGUAGES = listOf("en", "de", "es", "fr", "it", "pt", "nl", "pl", "tr", "ru", "uk", "ar", "hi", "ja", "ko", "zh")

/** "de" as "Deutsch", in its own language. */
internal fun languageName(code: String): String =
    java.util.Locale.forLanguageTag(code).let { it.getDisplayLanguage(it) }.replaceFirstChar { it.titlecase() }.ifEmpty { code }

@Composable
private fun EnginePage() {
    val c = Hamstr.colors
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val s by Prefs.settings.collectAsState()
    val engine by Engine.state.collectAsState()
    val updating by Engine.updating.collectAsState()
    val versionCheck by Engine.versionCheck.collectAsState()
    var updateMsg by remember { mutableStateOf<String?>(null) }
    val cheekVersion by Cheek.active.collectAsState()
    val cheekCheck by CheekUpdates.check.collectAsState()
    val cheekUpdating by CheekUpdates.updating.collectAsState()
    var cheekMsg by remember { mutableStateOf<String?>(null) }
    Group { EngineChoice() }
    if (s.engine == DownloadEngine.Cheek) {
        Spacer(Modifier.height(12.dp))
        SettingLabel("Cheek", Modifier.padding(horizontal = 6.dp))
        Spacer(Modifier.height(8.dp))
        Group {
            SettingRow(stringResource(R.string.set_cheek_version), subtitle = cheekVersion + " · " + if (Cheek.updated) stringResource(R.string.set_cheek_updated) else stringResource(R.string.set_cheek_bundled, BuildConfig.VERSION_NAME))
            Divider()
            FxRow(stringResource(R.string.set_cheek_auto), stringResource(R.string.set_cheek_auto_hint), s.cheekAutoUpdate) { v -> Prefs.edit { it.copy(cheekAutoUpdate = v) } }
            Column(Modifier.padding(8.dp)) {
                val available = cheekCheck as? EngineVersionCheck.Available
                val checking = cheekCheck == EngineVersionCheck.Checking
                HButton(
                    when {
                        cheekUpdating -> stringResource(R.string.set_updating)
                        checking -> stringResource(R.string.set_checking_version)
                        available != null -> stringResource(R.string.set_update_now)
                        else -> stringResource(R.string.set_check_version)
                    },
                    onClick = {
                        if (!cheekUpdating && !checking) scope.launch {
                            cheekMsg = null
                            if (available == null) CheekUpdates.checkVersion()
                            else cheekMsg = CheekUpdates.update().fold({ resources.getString(R.string.set_cheek_installed, it) }, { resources.getString(R.string.set_update_failed_why, it.message) })
                        }
                    },
                    enabled = !cheekUpdating && !checking && Cheek.available,
                )
                val message = cheekMsg ?: when (val check = cheekCheck) {
                    is EngineVersionCheck.Available -> stringResource(R.string.set_version_available, check.version)
                    EngineVersionCheck.Current -> stringResource(R.string.set_cheek_current, cheekVersion)
                    is EngineVersionCheck.Failed -> check.message
                    else -> null
                }
                message?.let {
                    Spacer(Modifier.height(8.dp))
                    Txt(it, style = Hamstr.type.small, color = c.muted, modifier = Modifier.padding(horizontal = 4.dp))
                }
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    SettingLabel(if (s.engine == DownloadEngine.Cheek) stringResource(R.string.set_ytdlp_resolver) else "yt-dlp", Modifier.padding(horizontal = 6.dp))
    Spacer(Modifier.height(8.dp))
    Group {
        SettingRow(
            stringResource(R.string.set_version),
            subtitle = when (val e = engine) {
                is EngineState.Ready -> e.version
                EngineState.Starting -> stringResource(R.string.set_starting)
                is EngineState.Broken -> e.message
            },
        )
        Divider()
        FxRow(stringResource(R.string.set_ytdlp_auto), stringResource(R.string.set_ytdlp_auto_hint), s.autoUpdate) { v -> Prefs.edit { it.copy(autoUpdate = v) } }
        Column(Modifier.padding(8.dp)) {
            val available = versionCheck as? EngineVersionCheck.Available
            val checking = versionCheck == EngineVersionCheck.Checking
            HButton(
                when {
                    updating -> stringResource(R.string.set_updating)
                    checking -> stringResource(R.string.set_checking_version)
                    available != null -> stringResource(R.string.set_update_now)
                    else -> stringResource(R.string.set_check_version)
                },
                onClick = {
                    if (!updating && !checking) scope.launch {
                        updateMsg = null
                        if (available == null) Engine.checkVersion()
                        else updateMsg = Engine.update(context).exceptionOrNull()?.let { resources.getString(R.string.set_update_failed_why, it.message) }
                    }
                },
                enabled = !updating && !checking && engine is EngineState.Ready,
            )
            val message = updateMsg ?: when (val check = versionCheck) {
                is EngineVersionCheck.Available -> stringResource(R.string.set_version_available, check.version)
                EngineVersionCheck.Current -> stringResource(R.string.set_up_to_date)
                is EngineVersionCheck.Failed -> check.message
                else -> null
            }
            message?.let {
                Spacer(Modifier.height(8.dp))
                Txt(it, style = Hamstr.type.small, color = c.muted, modifier = Modifier.padding(horizontal = 4.dp))
            }
        }
    }
}

@Composable
private fun AboutPage(onUpdate: () -> Unit, onTour: () -> Unit) {
    val c = Hamstr.colors
    val context = LocalContext.current
    val s by Prefs.settings.collectAsState()
    val update by Updater.state.collectAsState()
    Group {
        SettingRow("Hamstr ${BuildConfig.VERSION_NAME}", subtitle = stringResource(R.string.set_about_local))
        Divider()
        SettingRow(stringResource(R.string.set_tour), subtitle = stringResource(R.string.set_tour_hint), onClick = onTour) {
            Icon(Res.drawable.ic_chevron_right, tint = c.ghost)
        }
        Divider()
        SettingRow(stringResource(R.string.set_source), subtitle = "github.com/${Updater.REPO}", onClick = {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/${Updater.REPO}")))
        }) { Icon(Res.drawable.ic_open_in_new, tint = c.ghost) }
    }
    Spacer(Modifier.height(16.dp))
    SettingLabel(stringResource(R.string.set_updates), Modifier.padding(horizontal = 6.dp))
    Spacer(Modifier.height(8.dp))
    Group {
        FxRow(stringResource(R.string.set_app_updates), stringResource(R.string.set_app_updates_hint), s.appUpdates) { v -> Prefs.edit { it.copy(appUpdates = v) } }
        Divider()
        SettingRow(
            when (val u = update) {
                UpdateState.Checking -> stringResource(R.string.set_checking)
                is UpdateState.Available -> stringResource(R.string.set_update_out, u.release.version)
                // Through Compose resources like the desktop: the string has a plain %, which Android would format.
                is UpdateState.Downloading -> if (u.progress < 0) stringResource(R.string.upd_downloading_dots) else org.jetbrains.compose.resources.stringResource(Res.string.set_update_downloading, u.release.version, (u.progress * 100).toInt())
                is UpdateState.Ready -> stringResource(R.string.upd_ready, u.release.version)
                is UpdateState.Installing -> stringResource(R.string.set_update_installing, u.release.version)
                is UpdateState.Failed -> stringResource(R.string.set_update_failed)
                UpdateState.UpToDate -> stringResource(R.string.set_up_to_date)
                UpdateState.Idle -> stringResource(R.string.set_updates)
            },
            subtitle = when (val u = update) {
                is UpdateState.Failed -> u.message
                else -> if (s.lastAppCheck == 0L) stringResource(R.string.set_not_checked)
                else stringResource(R.string.set_last_checked) + " " + DateUtils.getRelativeTimeSpanString(s.lastAppCheck, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
            },
        )
        Column(Modifier.padding(8.dp)) {
            val release = update.release
            if (release != null) {
                HButton(stringResource(R.string.set_update_to, release.version), onUpdate, Modifier.fillMaxWidth(), kind = ButtonKind.Primary, icon = Res.drawable.ic_download)
            } else {
                HButton(
                    if (update == UpdateState.Checking) stringResource(R.string.set_checking) else stringResource(R.string.set_check_now),
                    onClick = { Updater.check(context) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = update != UpdateState.Checking,
                )
            }
        }
    }
    Spacer(Modifier.height(16.dp))
    Group {
        SettingRow(stringResource(R.string.set_licenses), subtitle = "Hamstr (GPL-3.0), Cheek (Unlicense), yt-dlp (Unlicense), youtubedl-android (GPL-3.0), FFmpeg (LGPL/GPL), Inter & JetBrains Mono (OFL), Phosphor Icons (MIT)")
    }
}

private class SettingEntry(val title: Int, val page: Page, val words: String)

/** A search hit: the setting's title as shown on its page, so it can be highlighted there. */
private class Found(val title: String, val page: Page)

private val SETTINGS_INDEX = listOf(
    SettingEntry(R.string.set_nearby, Page.Devices, "nearby airdrop quick share send devices phone computer pc geräte senden teilen"),
    SettingEntry(R.string.set_nearby_bluetooth, Page.Devices, "bluetooth ble nearby"),
    SettingEntry(R.string.set_device_name, Page.Devices, "name device gerät"),
    SettingEntry(R.string.set_accent, Page.Appearance, "color colour farbe accent hue theme pink orange"),
    SettingEntry(R.string.set_background, Page.Appearance, "theme dark oled black cocoa graphite hintergrund"),
    SettingEntry(R.string.set_icon_accent, Page.Appearance, "icon launcher app icon cheeks backen symbol"),
    SettingEntry(R.string.set_icon_style, Page.Appearance, "icon style light dark accent launcher symbol"),
    SettingEntry(R.string.set_size, Page.Appearance, "size scale big small large text größe groß klein schrift"),
    SettingEntry(R.string.set_language, Page.Appearance, "language sprache idioma langue lingua taal język dil язык 言語 언어 语言 english deutsch translate übersetzen"),
    SettingEntry(R.string.set_mini, Page.Appearance, "mini player docked floating slim compact miniplayer"),
    SettingEntry(R.string.set_resume_videos, Page.Playback, "youtube tiktok pause resume audio focus music fortsetzen wiedergabe"),
    SettingEntry(R.string.eq_title, Page.Playback, "equalizer eq bass treble presets sound klang höhen tiefen rock pop"),
    SettingEntry(R.string.set_crossfade, Page.Playback, "crossfade fade blend transition gapless überblenden übergang"),
    SettingEntry(R.string.set_storage, Page.Data, "storage space memory size cache clear speicher platz belegt leeren"),
    SettingEntry(R.string.backup_export, Page.Data, "backup export save file settings sichern exportieren datei einstellungen"),
    SettingEntry(R.string.backup_import, Page.Data, "backup import restore move phone wiederherstellen importieren übertragen"),
    SettingEntry(R.string.page_hidden, Page.Hidden, "hidden unlisted restore delete versteckt wiederherstellen löschen"),
    SettingEntry(R.string.set_intro, Page.Motion, "intro splash start animation"),
    SettingEntry(R.string.set_moods, Page.Motion, "blink wink munch hamster mark"),
    SettingEntry(R.string.set_stretch, Page.Motion, "stretch deform drag speed animation"),
    SettingEntry(R.string.set_haptics, Page.Motion, "vibration haptic vibrate"),
    SettingEntry(R.string.set_raster, Page.Motion, "raster characters top"),
    SettingEntry(R.string.set_ascii_thumbs, Page.Motion, "characters thumbnails images"),
    SettingEntry(R.string.set_rain, Page.Motion, "rain falling loading effect"),
    SettingEntry(R.string.set_decode, Page.Motion, "noise titles text effect"),
    SettingEntry(R.string.set_default_mode, Page.Downloads, "mode video audio default share"),
    SettingEntry(R.string.set_video_quality, Page.Downloads, "resolution 1080p 4k 720p qualitat qualität"),
    SettingEntry(R.string.set_video_format, Page.Downloads, "container mp4 mkv webm"),
    SettingEntry(R.string.share_audio_format, Page.Downloads, "opus m4a mp3 music musik"),
    SettingEntry(R.string.set_subtitles, Page.Downloads, "subtitles captions untertitel"),
    SettingEntry(R.string.set_sub_langs, Page.Downloads, "subtitles languages sprache untertitel captions"),
    SettingEntry(R.string.set_clean_names, Page.Downloads, "file name names id brackets dateiname"),
    SettingEntry(R.string.set_instant, Page.Downloads, "quick instant share sheet direct"),
    SettingEntry(R.string.set_playlists, Page.Downloads, "playlist album"),
    SettingEntry(R.string.set_open_done, Page.Downloads, "open play automatic finished done öffnen automatisch fertig"),
    SettingEntry(R.string.set_parallel, Page.Queue, "parallel concurrent queue at once"),
    SettingEntry(R.string.set_save_location, Page.Queue, "folder storage path speicher ordner"),
    SettingEntry(R.string.set_wifi_only, Page.Queue, "wifi wi-fi wlan mobile data metered cellular"),
    SettingEntry(R.string.set_night, Page.Queue, "night schedule later time hours nachts nacht zeitplan später uhrzeit"),
    SettingEntry(R.string.set_charging, Page.Queue, "charging charger battery power akku laden ladegerät strom"),
    SettingEntry(R.string.set_offer_clip, Page.Downloads, "clipboard copy copied paste link zwischenablage kopiert einfügen"),
    SettingEntry(R.string.page_following, Page.Following, "follow subscribe subscription channel playlist new videos automatic abo abonnieren kanal folgen neu automatisch"),
    SettingEntry(R.string.set_notifications, Page.Queue, "notifications notification benachrichtigungen permission progress alert"),
    SettingEntry(R.string.set_engine, Page.Engine, "cheek rust engine yt-dlp speed fast parallel connections"),
    SettingEntry(R.string.set_version, Page.Engine, "yt-dlp engine version"),
    SettingEntry(R.string.set_cheek_version, Page.Engine, "cheek engine installed bundled check version"),
    SettingEntry(R.string.set_ytdlp_auto, Page.Engine, "yt-dlp update auto"),
    SettingEntry(R.string.set_cheek_auto, Page.Engine, "cheek update auto release github"),
    SettingEntry(R.string.set_tour, Page.About, "tour onboarding intro help guide how start hilfe einführung anleitung"),
    SettingEntry(R.string.set_source, Page.About, "github code source"),
    SettingEntry(R.string.set_app_updates, Page.About, "app update release github"),
    SettingEntry(R.string.set_licenses, Page.About, "license open source gpl"),
)

private fun searchSettings(context: android.content.Context, query: String): List<Found> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return emptyList()
    return SETTINGS_INDEX
        .map { e -> e to context.getString(e.title) }
        .filter { (e, title) -> q.split(' ').all { w -> title.lowercase().contains(w) || e.words.contains(w) || context.getString(e.page.title).lowercase().contains(w) } }
        .sortedByDescending { (_, title) -> title.lowercase().startsWith(q) }
        .map { (e, title) -> Found(title, e.page) }
}

/**
 * Download/Hamstr in the system's Files app. Android has no single "show this folder" intent:
 * the Files app opens a document folder, and otherwise the Downloads list is the closest.
 */
private fun openFolder(context: android.content.Context) {
    Engine.outputDir.mkdirs()
    val relative = Engine.outputDir.absolutePath.removePrefix(android.os.Environment.getExternalStorageDirectory().absolutePath).trimStart('/')
    val folder = android.provider.DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:$relative")
    val tries = listOf(
        Intent(Intent.ACTION_VIEW).setDataAndType(folder, android.provider.DocumentsContract.Document.MIME_TYPE_DIR)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
        Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS),
    )
    for (intent in tries) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (_: android.content.ActivityNotFoundException) {
        } catch (_: SecurityException) {
        }
    }
    android.widget.Toast.makeText(context, R.string.set_open_folder_failed, android.widget.Toast.LENGTH_SHORT).show()
}

/** A preset's name as an Android string, for the summaries built outside composition. */
private fun eqName(p: app.hamstr.audio.Equalizer.Preset) = when (p) {
    app.hamstr.audio.Equalizer.Preset.Flat -> R.string.eq_flat
    app.hamstr.audio.Equalizer.Preset.Bass -> R.string.eq_bass
    app.hamstr.audio.Equalizer.Preset.Treble -> R.string.eq_treble
    app.hamstr.audio.Equalizer.Preset.Vocals -> R.string.eq_vocals
    app.hamstr.audio.Equalizer.Preset.Rock -> R.string.eq_rock
    app.hamstr.audio.Equalizer.Preset.Pop -> R.string.eq_pop
    app.hamstr.audio.Equalizer.Preset.Electronic -> R.string.eq_electronic
    app.hamstr.audio.Equalizer.Preset.HipHop -> R.string.eq_hiphop
    app.hamstr.audio.Equalizer.Preset.Jazz -> R.string.eq_jazz
    app.hamstr.audio.Equalizer.Preset.Classical -> R.string.eq_classical
    app.hamstr.audio.Equalizer.Preset.Acoustic -> R.string.eq_acoustic
    app.hamstr.audio.Equalizer.Preset.Speech -> R.string.eq_speech
    app.hamstr.audio.Equalizer.Preset.Night -> R.string.eq_night
}

/** A background's name as an Android string, for the summaries built outside composition. */
private fun baseName(base: app.hamstr.data.Base) = when (base) {
    app.hamstr.data.Base.Graphite -> R.string.base_graphite
    app.hamstr.data.Base.Oled -> R.string.base_oled
    app.hamstr.data.Base.Cocoa -> R.string.base_cocoa
    app.hamstr.data.Base.Midnight -> R.string.base_midnight
    app.hamstr.data.Base.Moss -> R.string.base_moss
    app.hamstr.data.Base.Plum -> R.string.base_plum
}
