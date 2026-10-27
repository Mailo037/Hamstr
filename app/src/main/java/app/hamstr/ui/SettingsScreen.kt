package app.hamstr.ui

import app.hamstr.data.DownloadEngine
import androidx.compose.runtime.CompositionLocalProvider
import app.hamstr.ui.components.searchHighlight
import app.hamstr.ui.components.LocalSettingHighlight
import app.hamstr.ui.components.SearchField
import androidx.compose.foundation.gestures.Orientation
import app.hamstr.ui.components.edgeFade
import android.Manifest
import android.content.Intent
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
private enum class Page(val title: Int, val icon: Int) {
    Appearance(R.string.page_appearance, R.drawable.ic_palette),
    Motion(R.string.page_motion, R.drawable.ic_auto_awesome),
    Downloads(R.string.page_downloads, R.drawable.ic_download),
    Queue(R.string.page_queue, R.drawable.ic_folder_open),
    Engine(R.string.page_engine, R.drawable.ic_code),
    About(R.string.page_about, R.drawable.ic_info),
}

@Composable
fun SettingsScreen(contentPadding: PaddingValues, onUpdate: () -> Unit) {
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
            SubPage(p, contentPadding, onUpdate, onBack = { page = null; highlight = null })
        }
    }
}

@Composable
private fun Root(contentPadding: PaddingValues, open: (Page, String?) -> Unit) {
    val s by Prefs.settings.collectAsState()
    val engine by Engine.state.collectAsState()
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
            Page.entries.forEach { p -> CategoryRow(p, summary(context, p, s, engine)) { open(p, null) } }
        }
    }
}

private fun summary(context: android.content.Context, p: Page, s: Settings, engine: EngineState): String = when (p) {
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
        Icon(R.drawable.ic_chevron_right, tint = c.ghost, size = 20.dp)
    }
}

/**
 * A category page. Besides the back button and system back, it can be dragged to the
 * right: it follows the finger, squashes a little with its speed and goes back past 30 %.
 */
@Composable
private fun SubPage(p: Page, contentPadding: PaddingValues, onUpdate: () -> Unit, onBack: () -> Unit) {
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
            IconButton(R.drawable.ic_arrow_back, stringResource(R.string.set_back), onBack, tint = Hamstr.colors.ink)
            Spacer(Modifier.width(12.dp))
            DecodeText(stringResource(p.title), style = Hamstr.type.title)
        }
        Spacer(Modifier.height(14.dp))
        when (p) {
            Page.Appearance -> AppearancePage()
            Page.Motion -> MotionPage()
            Page.Downloads -> DownloadsPage()
            Page.Queue -> QueuePage()
            Page.Engine -> EnginePage()
            Page.About -> AboutPage(onUpdate)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Label(text: String, modifier: Modifier = Modifier) = Txt(text, style = Hamstr.type.small, color = Hamstr.colors.ghost, modifier = modifier)

@Composable
private fun Block(label: String, content: @Composable ColumnScope.() -> Unit) =
    Column(Modifier.searchHighlight(label).padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Label(label)
        content()
    }

@Composable
private fun AppearancePage() {
    val s by Prefs.settings.collectAsState()
    Group {
        Block(stringResource(R.string.set_accent)) {
            AccentPicker(ACCENTS, s.accent) { a -> Prefs.edit { it.copy(accent = a) } }
        }
        Block(stringResource(R.string.set_background)) {
            Segmented(Base.entries.toList(), s.base, { it.label }, { b -> Prefs.edit { it.copy(base = b) } })
        }
    }
    Spacer(Modifier.height(12.dp))
    Txt(stringResource(R.string.set_appearance_note), style = Hamstr.type.small, color = Hamstr.colors.ghost, modifier = Modifier.padding(horizontal = 6.dp))
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
    Txt(stringResource(R.string.set_ascii), style = Hamstr.type.small, color = Hamstr.colors.ghost, modifier = Modifier.padding(horizontal = 6.dp))
    Spacer(Modifier.height(8.dp))
    Group {
        FxRow(stringResource(R.string.set_raster), stringResource(R.string.set_raster_hint), s.raster) { v -> Prefs.edit { it.copy(raster = v) } }
        Divider()
        FxRow(stringResource(R.string.set_ascii_thumbs), stringResource(R.string.set_ascii_thumbs_hint), s.asciiThumbs) { v -> Prefs.edit { it.copy(asciiThumbs = v) } }
        Divider()
        FxRow(stringResource(R.string.set_rain), stringResource(R.string.set_rain_hint), s.rain) { v -> Prefs.edit { it.copy(rain = v) } }
        Divider()
        FxRow(stringResource(R.string.set_decode), stringResource(R.string.set_decode_hint), s.decode) { v -> Prefs.edit { it.copy(decode = v) } }
    }
}

@Composable
private fun DownloadsPage() {
    val s by Prefs.settings.collectAsState()
    val videoLabel = stringResource(R.string.mode_video)
    val audioLabel = stringResource(R.string.mode_audio)
    Group {
        Block(stringResource(R.string.set_default_mode)) {
            Segmented(listOf(Mode.Video, Mode.Audio), s.mode, { if (it == Mode.Video) videoLabel else audioLabel }, { m -> Prefs.edit { it.copy(mode = m) } })
        }
        Block(stringResource(R.string.set_video_quality)) {
            Chips(Prefs.heights, s.height, ::heightLabel) { h -> Prefs.edit { it.copy(height = h) } }
        }
        Block(stringResource(R.string.set_video_format)) {
            Chips(Prefs.containers, s.container, { it }) { f -> Prefs.edit { it.copy(container = f) } }
        }
        Block(stringResource(R.string.share_audio_format)) {
            Chips(Prefs.audioFormats, s.audioFormat, { it }) { f -> Prefs.edit { it.copy(audioFormat = f) } }
        }
    }
    Spacer(Modifier.height(12.dp))
    Group {
        FxRow(stringResource(R.string.set_subtitles), stringResource(R.string.set_subtitles_hint, s.subtitleLanguages().joinToString(", ") { languageName(it) }), s.subtitles) { v -> Prefs.edit { it.copy(subtitles = v) } }
        AnimatedVisibility(s.subtitles, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Block(stringResource(R.string.set_sub_langs)) { SubtitleLanguages(s) }
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
        Block(stringResource(R.string.set_parallel)) {
            Segmented(listOf(1, 2, 3), s.parallel, { it.toString() }, { n -> Prefs.edit { it.copy(parallel = n) } })
        }
        val context = LocalContext.current
        val where = Engine.outputDir.absolutePath.replace("/storage/emulated/0", stringResource(R.string.set_internal_storage))
        SettingRow(
            stringResource(R.string.set_save_location), subtitle = where + "\n" + stringResource(R.string.set_open_folder),
            onClick = { openFolder(context) },
        ) { Icon(R.drawable.ic_folder_open, tint = Hamstr.colors.muted, size = 20.dp) }
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
    val system = java.util.Locale.getDefault().language
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
    Group {
        Block(stringResource(R.string.set_engine)) {
            Segmented(DownloadEngine.entries.toList(), s.engine, { it.label }, { e -> Prefs.edit { it.copy(engine = e) } })
            Txt(
                when (s.engine) {
                    DownloadEngine.YtDlp -> stringResource(R.string.set_engine_ytdlp)
                    DownloadEngine.Cheek -> stringResource(R.string.set_engine_cheek)
                },
                style = Hamstr.type.small, color = c.ghost,
            )
        }
    }
    if (s.engine == DownloadEngine.Cheek) {
        Spacer(Modifier.height(12.dp))
        Label("Cheek", Modifier.padding(horizontal = 6.dp))
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
                            else cheekMsg = CheekUpdates.update().fold({ context.getString(R.string.set_cheek_installed, it) }, { context.getString(R.string.set_update_failed_why, it.message) })
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
    Label(if (s.engine == DownloadEngine.Cheek) stringResource(R.string.set_ytdlp_resolver) else "yt-dlp", Modifier.padding(horizontal = 6.dp))
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
                        else updateMsg = Engine.update(context).exceptionOrNull()?.let { context.getString(R.string.set_update_failed_why, it.message) }
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
        }) { Icon(R.drawable.ic_open_in_new, tint = c.ghost) }
    }
    Spacer(Modifier.height(16.dp))
    Label(stringResource(R.string.set_updates), Modifier.padding(horizontal = 6.dp))
    Spacer(Modifier.height(8.dp))
    Group {
        FxRow(stringResource(R.string.set_app_updates), stringResource(R.string.set_app_updates_hint), s.appUpdates) { v -> Prefs.edit { it.copy(appUpdates = v) } }
        Divider()
        SettingRow(
            when (val u = update) {
                UpdateState.Checking -> stringResource(R.string.set_checking)
                is UpdateState.Available -> stringResource(R.string.set_update_out, u.release.version)
                is UpdateState.Downloading -> stringResource(R.string.set_update_downloading, u.release.version, (u.progress * 100).toInt())
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
                HButton(stringResource(R.string.set_update_to, release.version), onUpdate, Modifier.fillMaxWidth(), kind = ButtonKind.Primary, icon = R.drawable.ic_download)
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

@Composable
private fun FxRow(title: String, subtitle: String, on: Boolean, set: (Boolean) -> Unit) {
    SettingRow(title, subtitle = subtitle, onClick = { set(!on) }) { HSwitch(on, set) }
}

private class SettingEntry(val title: Int, val page: Page, val words: String)

/** A search hit: the setting's title as shown on its page, so it can be highlighted there. */
private class Found(val title: String, val page: Page)

private val SETTINGS_INDEX = listOf(
    SettingEntry(R.string.set_accent, Page.Appearance, "color colour farbe accent hue theme pink orange"),
    SettingEntry(R.string.set_background, Page.Appearance, "theme dark oled black cocoa graphite hintergrund"),
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
