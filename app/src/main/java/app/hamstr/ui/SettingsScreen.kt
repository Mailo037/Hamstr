package app.hamstr.ui

import app.hamstr.data.DownloadEngine
import androidx.compose.runtime.CompositionLocalProvider
import app.hamstr.ui.components.searchHighlight
import app.hamstr.ui.components.LocalSettingHighlight
import app.hamstr.ui.components.SearchField
import androidx.compose.foundation.gestures.Orientation
import app.hamstr.ui.components.edgeFade
import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
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
import kotlinx.coroutines.CancellationException
import kotlin.math.abs

/** Settings are split into categories, each on its own page (Glyph: never one long page). */
private enum class Page(val title: String, val icon: Int) {
    Appearance("Appearance", R.drawable.ic_palette),
    Motion("Motion & effects", R.drawable.ic_auto_awesome),
    Downloads("Downloads", R.drawable.ic_download),
    Queue("Queue & storage", R.drawable.ic_folder_open),
    Engine("Engine", R.drawable.ic_code),
    About("About", R.drawable.ic_info),
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
                (slideInHorizontally(spring(dampingRatio = 0.86f, stiffness = 420f)) { it / 3 } + fadeIn(tween(200))) togetherWith
                    (slideOutHorizontally(tween(200)) { -it / 8 } + fadeOut(tween(140)))
            } else {
                (slideInHorizontally(spring(dampingRatio = 0.86f, stiffness = 420f)) { -it / 8 } + fadeIn(tween(200))) togetherWith
                    (slideOutHorizontally(tween(200)) { it / 3 } + fadeOut(tween(140)))
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
    val results = remember(query) { searchSettings(query) }
    Column(
        Modifier
            .fillMaxSize()
            .edgeFade(scroll, Orientation.Vertical, insets = contentPadding)
            .verticalScroll(scroll)
            .padding(contentPadding)
            .padding(horizontal = 12.dp),
    ) {
        DecodeText("Settings", style = Hamstr.type.title, modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp))
        Spacer(Modifier.height(10.dp))
        SearchField(query, { query = it }, "Search settings")
        Spacer(Modifier.height(12.dp))
        if (query.isNotBlank()) {
            if (results.isEmpty()) {
                Txt("Nothing matches \u201c${query.trim()}\u201d", color = Hamstr.colors.ghost, modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp))
            } else Group {
                results.forEach { r -> CategoryRow(r.page, r.page.title, title = r.title) { open(r.page, r.title) } }
            }
        } else Group {
            Page.entries.forEach { p -> CategoryRow(p, summary(p, s, engine)) { open(p, null) } }
        }
    }
}

private fun summary(p: Page, s: Settings, engine: EngineState): String = when (p) {
    Page.Appearance -> "${ACCENTS.firstOrNull { it.argb == s.accent }?.name ?: "Custom"} · ${s.base.label}"
    Page.Motion -> {
        val on = listOf(s.intro, s.raster, s.asciiThumbs, s.rain, s.decode, s.moods, s.stretch, s.haptics).count { it }
        "$on of 8 effects on"
    }
    Page.Downloads -> if (s.mode == Mode.Audio) "Audio · ${s.audioFormat}" else "Video · ${heightLabel(s.height)} · ${s.container}"
    Page.Queue -> "${s.parallel} at once · Download/Hamstr"
    Page.Engine -> when (engine) {
        is EngineState.Ready -> (if (s.engine == DownloadEngine.Cheek) "Cheek ${BuildConfig.CHEEK_VERSION} · yt-dlp " else "yt-dlp ") + engine.version
        EngineState.Starting -> "starting …"
        is EngineState.Broken -> "error"
    }
    Page.About -> "Hamstr ${BuildConfig.VERSION_NAME}" + if (s.appUpdates) " · checks for updates" else ""
}

@Composable
private fun CategoryRow(p: Page, summary: String, title: String = p.title, onClick: () -> Unit) {
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
                        } else scope.launch { offset.animateTo(0f, spring(dampingRatio = 0.6f, stiffness = 500f)) }
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
            IconButton(R.drawable.ic_arrow_back, "Back", onBack, tint = Hamstr.colors.ink)
            Spacer(Modifier.width(12.dp))
            DecodeText(p.title, style = Hamstr.type.title)
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
        Block("Accent colour") {
            AccentPicker(ACCENTS, s.accent) { a -> Prefs.edit { it.copy(accent = a) } }
        }
        Block("Background") {
            Segmented(Base.entries.toList(), s.base, { it.label }, { b -> Prefs.edit { it.copy(base = b) } })
        }
    }
    Spacer(Modifier.height(12.dp))
    Txt("Changes apply right away. The launcher icon keeps its orange cheeks.", style = Hamstr.type.small, color = Hamstr.colors.ghost, modifier = Modifier.padding(horizontal = 6.dp))
}

@Composable
private fun MotionPage() {
    val s by Prefs.settings.collectAsState()
    Group {
        FxRow("Startup animation", "The hamster assembles itself and waves hello", s.intro) { v -> Prefs.edit { it.copy(intro = v) } }
        Divider()
        FxRow("Hamster moods", "Blinking, winking and munching when something finishes", s.moods) { v -> Prefs.edit { it.copy(moods = v) } }
        Divider()
        FxRow("Stretchy motion", "Pills, switches and sheets deform with their speed", s.stretch) { v -> Prefs.edit { it.copy(stretch = v) } }
        Divider()
        FxRow("Haptics", "Ticks on drags, swipes and toggles", s.haptics) { v -> Prefs.edit { it.copy(haptics = v) } }
    }
    Spacer(Modifier.height(16.dp))
    Txt("ASCII", style = Hamstr.type.small, color = Hamstr.colors.ghost, modifier = Modifier.padding(horizontal = 6.dp))
    Spacer(Modifier.height(8.dp))
    Group {
        FxRow("ASCII background", "The drifting character raster at the top", s.raster) { v -> Prefs.edit { it.copy(raster = v) } }
        Divider()
        FxRow("ASCII thumbnails", "Thumbnails resolve from characters while downloading", s.asciiThumbs) { v -> Prefs.edit { it.copy(asciiThumbs = v) } }
        Divider()
        FxRow("Matrix rain", "Falling characters while a link loads", s.rain) { v -> Prefs.edit { it.copy(rain = v) } }
        Divider()
        FxRow("Decoding text", "Titles arrive out of character noise", s.decode) { v -> Prefs.edit { it.copy(decode = v) } }
    }
}

@Composable
private fun DownloadsPage() {
    val s by Prefs.settings.collectAsState()
    Group {
        Block("Default when sharing") {
            Segmented(listOf(Mode.Video, Mode.Audio), s.mode, { if (it == Mode.Video) "Video" else "Audio" }, { m -> Prefs.edit { it.copy(mode = m) } })
        }
        Block("Video quality") {
            Chips(Prefs.heights, s.height, ::heightLabel) { h -> Prefs.edit { it.copy(height = h) } }
        }
        Block("Video format") {
            Chips(Prefs.containers, s.container, { it }) { f -> Prefs.edit { it.copy(container = f) } }
        }
        Block("Audio format") {
            Chips(Prefs.audioFormats, s.audioFormat, { it }) { f -> Prefs.edit { it.copy(audioFormat = f) } }
        }
    }
    Spacer(Modifier.height(12.dp))
    Group {
        FxRow("Embed subtitles", "German and English, when available", s.subtitles) { v -> Prefs.edit { it.copy(subtitles = v) } }
        Divider()
        FxRow("Instant stash", "Sharing starts right away with these defaults, no sheet", s.quickStash) { v -> Prefs.edit { it.copy(quickStash = v) } }
        Divider()
        FxRow("Whole playlists", "Otherwise only the shared video", s.playlists) { v -> Prefs.edit { it.copy(playlists = v) } }
    }
}

@Composable
private fun QueuePage() {
    val s by Prefs.settings.collectAsState()
    Group {
        Block("Parallel downloads") {
            Segmented(listOf(1, 2, 3), s.parallel, { it.toString() }, { n -> Prefs.edit { it.copy(parallel = n) } })
        }
        SettingRow("Save location", subtitle = Engine.outputDir.absolutePath.replace("/storage/emulated/0", "Internal storage"))
    }
}

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
    var cheekVersion by remember { mutableStateOf(BuildConfig.CHEEK_VERSION) }
    var checkingVersion by remember { mutableStateOf(false) }
    var cheekMsg by remember { mutableStateOf<String?>(null) }
    Group {
        Block("Download engine") {
            Segmented(DownloadEngine.entries.toList(), s.engine, { it.label }, { e -> Prefs.edit { it.copy(engine = e) } })
            Txt(
                when (s.engine) {
                    DownloadEngine.YtDlp -> "yt-dlp finds the streams and downloads them, one connection at a time."
                    DownloadEngine.Cheek -> "yt-dlp finds the streams, Cheek (Rust) fetches them over many connections at once. " +
                        "Playlists, subtitles and custom formats still go through yt-dlp."
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
            SettingRow("Cheek version", subtitle = "$cheekVersion · included with Hamstr ${BuildConfig.VERSION_NAME}")
            Divider()
            Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Txt("Cheek is included with Hamstr. Manage app updates in About.", style = Hamstr.type.small, color = c.ghost)
                HButton(
                    if (checkingVersion) "Checking version …" else "Check version",
                    onClick = {
                        checkingVersion = true
                        scope.launch {
                            try {
                                cheekVersion = Cheek.version()
                                cheekMsg = "Installed Cheek: $cheekVersion"
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                cheekMsg = e.message ?: "Version check failed"
                            } finally { checkingVersion = false }
                        }
                    }, enabled = !checkingVersion,
                )
                cheekMsg?.let { Txt(it, style = Hamstr.type.small, color = c.muted) }
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    Label(if (s.engine == DownloadEngine.Cheek) "yt-dlp · link resolver" else "yt-dlp", Modifier.padding(horizontal = 6.dp))
    Spacer(Modifier.height(8.dp))
    Group {
        SettingRow(
            "Version",
            subtitle = when (val e = engine) {
                is EngineState.Ready -> e.version
                EngineState.Starting -> "starting …"
                is EngineState.Broken -> e.message
            },
        )
        Divider()
        FxRow("Update automatically", "Once a day, so sites don't break", s.autoUpdate) { v -> Prefs.edit { it.copy(autoUpdate = v) } }
        Column(Modifier.padding(8.dp)) {
            val available = versionCheck as? EngineVersionCheck.Available
            val checking = versionCheck == EngineVersionCheck.Checking
            HButton(
                when {
                    updating -> "Updating …"
                    checking -> "Checking version …"
                    available != null -> "Update now"
                    else -> "Check version"
                },
                onClick = {
                    if (!updating && !checking) scope.launch {
                        updateMsg = null
                        if (available == null) Engine.checkVersion()
                        else updateMsg = Engine.update(context).exceptionOrNull()?.let { "Update failed: ${it.message}" }
                    }
                },
                enabled = !updating && !checking && engine is EngineState.Ready,
            )
            val message = updateMsg ?: when (val check = versionCheck) {
                is EngineVersionCheck.Available -> "Version ${check.version} available"
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
        SettingRow("Hamstr ${BuildConfig.VERSION_NAME}", subtitle = "Everything runs on the device: yt-dlp and ffmpeg are built in, no server.")
        Divider()
        SettingRow("Source code", subtitle = "github.com/${Updater.REPO}", onClick = {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/${Updater.REPO}")))
        }) { Icon(R.drawable.ic_open_in_new, tint = c.ghost) }
    }
    Spacer(Modifier.height(16.dp))
    Label("Updates", Modifier.padding(horizontal = 6.dp))
    Spacer(Modifier.height(8.dp))
    Group {
        FxRow("Check on launch", "Looks for a new release on GitHub when Hamstr starts", s.appUpdates) { v -> Prefs.edit { it.copy(appUpdates = v) } }
        Divider()
        SettingRow(
            when (val u = update) {
                UpdateState.Checking -> "Checking …"
                is UpdateState.Available -> "Hamstr ${u.release.version} is out"
                is UpdateState.Downloading -> "Downloading ${u.release.version} · ${(u.progress * 100).toInt()} %"
                is UpdateState.Installing -> "Installing ${u.release.version} …"
                is UpdateState.Failed -> "Update failed"
                UpdateState.UpToDate -> "You're up to date"
                UpdateState.Idle -> "Updates"
            },
            subtitle = when (val u = update) {
                is UpdateState.Failed -> u.message
                else -> if (s.lastAppCheck == 0L) "Not checked yet"
                else "Last checked " + DateUtils.getRelativeTimeSpanString(s.lastAppCheck, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
            },
        )
        Column(Modifier.padding(8.dp)) {
            val release = update.release
            if (release != null) {
                HButton("Update to ${release.version}", onUpdate, Modifier.fillMaxWidth(), kind = ButtonKind.Primary, icon = R.drawable.ic_download)
            } else {
                HButton(
                    if (update == UpdateState.Checking) "Checking …" else "Check now",
                    onClick = { Updater.check(context) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = update != UpdateState.Checking,
                )
            }
        }
    }
    Spacer(Modifier.height(16.dp))
    Group {
        SettingRow("Licenses", subtitle = "Hamstr (GPL-3.0), yt-dlp (Unlicense), youtubedl-android (GPL-3.0), FFmpeg (LGPL/GPL), Inter & JetBrains Mono (OFL), Material Symbols (Apache-2.0)")
    }
}

@Composable
private fun FxRow(title: String, subtitle: String, on: Boolean, set: (Boolean) -> Unit) {
    SettingRow(title, subtitle = subtitle, onClick = { set(!on) }) { HSwitch(on, set) }
}

private class SettingEntry(val title: String, val page: Page, val words: String)

private val SETTINGS_INDEX = listOf(
    SettingEntry("Accent colour", Page.Appearance, "color colour farbe accent hue theme pink orange"),
    SettingEntry("Background", Page.Appearance, "theme dark oled black cocoa graphite hintergrund"),
    SettingEntry("Startup animation", Page.Motion, "intro splash start animation"),
    SettingEntry("Hamster moods", Page.Motion, "blink wink munch hamster mark"),
    SettingEntry("Stretchy motion", Page.Motion, "stretch deform drag speed animation"),
    SettingEntry("Haptics", Page.Motion, "vibration haptic vibrate"),
    SettingEntry("ASCII background", Page.Motion, "raster characters top"),
    SettingEntry("ASCII thumbnails", Page.Motion, "characters thumbnails images"),
    SettingEntry("Matrix rain", Page.Motion, "rain falling loading effect"),
    SettingEntry("Decoding text", Page.Motion, "noise titles text effect"),
    SettingEntry("Default when sharing", Page.Downloads, "mode video audio default share"),
    SettingEntry("Video quality", Page.Downloads, "resolution 1080p 4k 720p qualitat qualität"),
    SettingEntry("Video format", Page.Downloads, "container mp4 mkv webm"),
    SettingEntry("Audio format", Page.Downloads, "opus m4a mp3 music musik"),
    SettingEntry("Embed subtitles", Page.Downloads, "subtitles captions untertitel"),
    SettingEntry("Instant stash", Page.Downloads, "quick instant share sheet direct"),
    SettingEntry("Whole playlists", Page.Downloads, "playlist album"),
    SettingEntry("Parallel downloads", Page.Queue, "parallel concurrent queue at once"),
    SettingEntry("Save location", Page.Queue, "folder storage path speicher ordner"),
    SettingEntry("Download engine", Page.Engine, "cheek rust engine yt-dlp speed fast parallel connections"),
    SettingEntry("Version", Page.Engine, "yt-dlp engine version"),
    SettingEntry("Cheek version", Page.Engine, "cheek engine installed bundled check version"),
    SettingEntry("Update automatically", Page.Engine, "yt-dlp update auto"),
    SettingEntry("Source code", Page.About, "github code source"),
    SettingEntry("Check on launch", Page.About, "app update release github"),
    SettingEntry("Licenses", Page.About, "license open source gpl"),
)

private fun searchSettings(query: String): List<SettingEntry> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return emptyList()
    return SETTINGS_INDEX
        .filter { e -> q.split(' ').all { w -> e.title.lowercase().contains(w) || e.words.contains(w) || e.page.title.lowercase().contains(w) } }
        .sortedByDescending { it.title.lowercase().startsWith(q) }
}
