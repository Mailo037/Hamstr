package app.hamstr.ui

import org.jetbrains.compose.resources.DrawableResource
import app.hamstr.res.*
import app.hamstr.data.DownloadStore
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
    Playback(R.string.page_playback, Res.drawable.ic_play_arrow),
    Queue(R.string.page_queue, Res.drawable.ic_folder_open),
    Engine(R.string.page_engine, Res.drawable.ic_code),
    Devices(R.string.page_devices, Res.drawable.ic_devices),
    Hidden(R.string.page_hidden, Res.drawable.ic_visibility_off),
    About(R.string.page_about, Res.drawable.ic_info),
}

@Composable
fun SettingsScreen(contentPadding: PaddingValues, onUpdate: () -> Unit, onDelete: (List<DownloadTask>) -> Unit, onHint: (String) -> Unit) {
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
            SubPage(p, contentPadding, onUpdate, onDelete, onHint, onBack = { page = null; highlight = null })
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
    Page.Playback -> context.getString(if (s.resumeAfterVideos) R.string.set_playback_resumes else R.string.set_playback_pauses)
    Page.Hidden -> if (hidden.isEmpty()) context.getString(R.string.hidden_summary_none)
        else context.resources.getQuantityString(R.plurals.hidden_summary, hidden.size, hidden.size, formatBytes(hidden.sumOf { it.sizeBytes }))
    Page.Appearance -> "${ACCENTS.firstOrNull { it.argb == s.accent }?.name ?: context.getString(R.string.set_custom)} · ${s.base.label}"
    Page.Motion -> {
        val on = listOf(s.intro, s.raster, s.asciiThumbs, s.rain, s.decode, s.moods, s.stretch, s.haptics).count { it }
        context.getString(R.string.set_effects_on, on, 8)
    }
    Page.Downloads -> if (s.mode == Mode.Audio) "${context.getString(R.string.mode_audio)} · ${s.audioFormat}"
        else "${context.getString(R.string.mode_video)} · ${heightLabel(s.height)} · ${s.container}"
    Page.Queue -> context.getString(R.string.set_queue_summary, s.parallel) + if (s.wifiOnly) " · " + context.getString(R.string.set_wifi_short) else ""
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
                        } else scope.launch { offset.animateTo(0f, Specs.settle()) }
                    },
                    onDragCancel = { scope.launch { offset.animateTo(0f) } },
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
            Page.Queue -> QueuePage()
            Page.Playback -> PlaybackPage()
            Page.Engine -> EnginePage()
            Page.Devices -> DevicesPage()
            Page.Hidden -> HiddenPage(onDelete, onHint)
            Page.About -> AboutPage(onUpdate)
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** Nearby: the shared settings, plus Bluetooth, which needs Android's permission and the adapter on. */
@Composable
private fun DevicesPage() {
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
    })
}

@Composable
private fun AppearancePage() {
    val s by Prefs.settings.collectAsState()
    Group {
        LookBlocks()
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
    }
}

/** Files hidden from the library: show them again, or delete them for good. */
@Composable
private fun HiddenPage(onDelete: (List<DownloadTask>) -> Unit, onHint: (String) -> Unit) {
    val c = Hamstr.colors
    val tasks by DownloadStore.tasks.collectAsState()
    val hidden = remember(tasks) { tasks.filter { it.hidden && it.status == Status.Done } }
    var confirm by remember { mutableStateOf(false) }
    val restored = stringResource(R.string.hidden_restored)
    if (hidden.isEmpty()) {
        Txt(stringResource(R.string.hidden_empty), color = c.muted, modifier = Modifier.padding(horizontal = 6.dp))
        return
    }
    Group {
        hidden.forEachIndexed { i, t ->
            if (i > 0) Divider()
            Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(52.dp).clip(RoundedCornerShape(Radii.thumb)).background(c.black)) {
                    AsciiImage(rememberImage(t.thumbnail, 160), reveal = 1f, cell = 4.dp, modifier = Modifier.size(52.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Txt(t.title, style = Hamstr.type.bodyMedium, maxLines = 2)
                    Spacer(Modifier.height(2.dp))
                    Txt(
                        listOfNotNull(t.uploader ?: t.source, t.sizeBytes.takeIf { it > 0 }?.let(::formatBytes)).joinToString(" · "),
                        style = Hamstr.type.small, color = c.ghost, maxLines = 1,
                    )
                }
                IconButton(Res.drawable.ic_restore, stringResource(R.string.hidden_restore), {
                    DownloadStore.setHidden(setOf(t.id), false)
                    onHint(restored)
                }, tint = c.accent, background = Color.Transparent)
                IconButton(Res.drawable.ic_delete, stringResource(R.string.menu_delete), { onDelete(listOf(t)) }, tint = c.red, background = Color.Transparent)
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        HButton(stringResource(R.string.hidden_restore_all), {
            DownloadStore.setHidden(hidden.map { it.id }.toSet(), false)
            onHint(restored)
        }, Modifier.weight(1f), kind = ButtonKind.Neutral, icon = Res.drawable.ic_restore)
        HButton(
            stringResource(if (confirm) R.string.hidden_delete_confirm else R.string.hidden_delete_all),
            { if (!confirm) confirm = true else { confirm = false; onDelete(hidden) } },
            Modifier.weight(1f), kind = ButtonKind.Ghost, icon = Res.drawable.ic_delete,
        )
    }
    Spacer(Modifier.height(12.dp))
    Txt(stringResource(R.string.hidden_note), style = Hamstr.type.small, color = c.ghost, modifier = Modifier.padding(horizontal = 6.dp))
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
        OpenWhenDoneRow()
    }
}

/**
 * Android only lets an app come forward on its own with "Display over other apps", so
 * turning this on asks for that; without it the finished notification stays the way in.
 */
@Composable
private fun OpenWhenDoneRow() {
    val context = LocalContext.current
    val s by Prefs.settings.collectAsState()
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
    val hint = stringResource(if (s.openWhenDone && !system) R.string.set_open_done_needs else R.string.set_open_done_hint)
    SettingRow(
        stringResource(R.string.set_open_done), subtitle = hint,
        onClick = { if (s.openWhenDone && !system) askSystem() else Prefs.edit { it.copy(openWhenDone = !s.openWhenDone) } },
    ) {
        HSwitch(s.openWhenDone, { v ->
            Prefs.edit { it.copy(openWhenDone = v) }
            if (v && !system) askSystem()
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
        FxRow(stringResource(R.string.set_wifi_only), stringResource(R.string.set_wifi_only_hint), s.wifiOnly) { v -> Prefs.edit { it.copy(wifiOnly = v) } }
    }
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
private fun languageName(code: String): String =
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
private fun AboutPage(onUpdate: () -> Unit) {
    val c = Hamstr.colors
    val context = LocalContext.current
    val s by Prefs.settings.collectAsState()
    val update by Updater.state.collectAsState()
    Group {
        SettingRow("Hamstr ${BuildConfig.VERSION_NAME}", subtitle = stringResource(R.string.set_about_local))
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
                is UpdateState.Downloading -> org.jetbrains.compose.resources.stringResource(Res.string.set_update_downloading, u.release.version, (u.progress * 100).toInt())
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
        SettingRow(stringResource(R.string.set_licenses), subtitle = "Hamstr (GPL-3.0), Cheek (Unlicense), yt-dlp (Unlicense), youtubedl-android (GPL-3.0), FFmpeg (LGPL/GPL), Inter & JetBrains Mono (OFL), Material Symbols & Icons (Apache-2.0)")
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
    SettingEntry(R.string.set_mini, Page.Appearance, "mini player docked floating slim compact miniplayer"),
    SettingEntry(R.string.set_resume_videos, Page.Playback, "youtube tiktok pause resume audio focus music fortsetzen wiedergabe"),
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
    SettingEntry(R.string.set_notifications, Page.Queue, "notifications notification benachrichtigungen permission progress alert"),
    SettingEntry(R.string.set_engine, Page.Engine, "cheek rust engine yt-dlp speed fast parallel connections"),
    SettingEntry(R.string.set_version, Page.Engine, "yt-dlp engine version"),
    SettingEntry(R.string.set_cheek_version, Page.Engine, "cheek engine installed bundled check version"),
    SettingEntry(R.string.set_ytdlp_auto, Page.Engine, "yt-dlp update auto"),
    SettingEntry(R.string.set_cheek_auto, Page.Engine, "cheek update auto release github"),
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
