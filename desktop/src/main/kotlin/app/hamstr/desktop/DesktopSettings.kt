package app.hamstr.desktop

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.hamstr.data.Prefs
import app.hamstr.engine.EngineState
import app.hamstr.res.*
import app.hamstr.ui.NearbySettings
import app.hamstr.ui.AsciiEffects
import app.hamstr.ui.EngineChoice
import app.hamstr.ui.FormatDefaults
import app.hamstr.ui.FxRow
import app.hamstr.ui.LookBlocks
import app.hamstr.ui.SettingBlock
import app.hamstr.ui.SettingLabel
import app.hamstr.ui.components.ButtonKind
import app.hamstr.ui.components.Divider
import app.hamstr.ui.components.Group
import app.hamstr.ui.components.HButton
import app.hamstr.ui.components.Icon
import app.hamstr.ui.components.Segmented
import app.hamstr.ui.components.SettingRow
import app.hamstr.ui.components.Specs
import app.hamstr.ui.components.Txt
import app.hamstr.ui.components.edgeFade
import app.hamstr.ui.components.pressable
import app.hamstr.ui.theme.Hamstr
import app.hamstr.ui.theme.Radii
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import java.text.DateFormat
import java.util.Date
import java.util.Locale

internal enum class Page(val title: StringResource, val icon: DrawableResource) {
    Appearance(Res.string.page_appearance, Res.drawable.ic_palette),
    Motion(Res.string.page_motion, Res.drawable.ic_auto_awesome),
    Downloads(Res.string.page_downloads, Res.drawable.ic_download),
    Engine(Res.string.page_engine, Res.drawable.ic_code),
    Devices(Res.string.page_devices, Res.drawable.ic_devices),
    About(Res.string.page_about, Res.drawable.ic_info),
}

/** A settings page, the same categories as on the phone; the side bar lists them. */
@Composable
internal fun DesktopSettings(page: Page, engine: EngineState, onQuit: () -> Unit) {
    AnimatedContent(page, Modifier.fillMaxSize(), transitionSpec = { fadeIn(Specs.appear()) togetherWith fadeOut(Specs.disappear()) }, label = "page") { p ->
        val scroll = rememberScrollState()
        // A readable column in the middle of the window, its title over it, like the stash.
        Column(Modifier.fillMaxSize().edgeFade(scroll, Orientation.Vertical).verticalScroll(scroll), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
                Txt(stringResource(p.title), style = Hamstr.type.title, modifier = Modifier.padding(start = 6.dp, top = 8.dp, bottom = 14.dp))
                when (p) {
                    Page.Appearance -> Group { LookBlocks() }
                    Page.Motion -> MotionPage()
                    Page.Downloads -> DownloadsPage()
                    Page.Engine -> EnginePage(engine)
                    Page.Devices -> NearbySettings()
                    Page.About -> AboutPage(onQuit)
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun MotionPage() {
    val s by Prefs.settings.collectAsState()
    Group {
        FxRow(stringResource(Res.string.set_intro), stringResource(Res.string.set_intro_hint), s.intro) { v -> Prefs.edit { it.copy(intro = v) } }
        Divider()
        FxRow(stringResource(Res.string.set_moods), stringResource(Res.string.set_moods_hint), s.moods) { v -> Prefs.edit { it.copy(moods = v) } }
        Divider()
        FxRow(stringResource(Res.string.set_stretch), stringResource(Res.string.set_stretch_hint), s.stretch) { v -> Prefs.edit { it.copy(stretch = v) } }
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
        val languages = s.subtitleLanguages().joinToString(", ") { Locale.forLanguageTag(it).getDisplayLanguage(Locale.getDefault()).ifBlank { it } }
        FxRow(stringResource(Res.string.set_subtitles), stringResource(Res.string.set_subtitles_hint, languages), s.subtitles) { v -> Prefs.edit { it.copy(subtitles = v) } }
        Divider()
        FxRow(stringResource(Res.string.set_clean_names), stringResource(Res.string.set_clean_names_hint), s.cleanNames) { v -> Prefs.edit { it.copy(cleanNames = v) } }
        Divider()
        FxRow(stringResource(Res.string.set_playlists), stringResource(Res.string.set_playlists_hint), s.playlists) { v -> Prefs.edit { it.copy(playlists = v) } }
    }
    Spacer(Modifier.height(12.dp))
    Group {
        SettingBlock(stringResource(Res.string.set_parallel)) {
            Segmented(listOf(1, 2, 3), s.parallel.coerceIn(1, 3), { "$it" }, { n -> Prefs.edit { it.copy(parallel = n) } })
        }
        Divider()
        SettingRow(stringResource(Res.string.set_save_location), subtitle = Paths.downloads.path, onClick = { Paths.downloads.mkdirs(); Files.open(Paths.downloads) }) {
            Txt(stringResource(Res.string.desktop_open_folder), style = Hamstr.type.small, color = Hamstr.colors.ghost)
        }
    }
}

@Composable
private fun EnginePage(engine: EngineState) {
    val updating by Queue.updatingYtDlp.collectAsState()
    val failed by Queue.ytDlpUpdateError.collectAsState()
    Group { EngineChoice() }
    Spacer(Modifier.height(12.dp))
    Group {
        SettingRow("yt-dlp", subtitle = when (engine) {
            is EngineState.Ready -> engine.version
            EngineState.Starting -> stringResource(Res.string.set_starting)
            is EngineState.Broken -> engine.message
        })
        Divider()
        SettingRow("Cheek", subtitle = if (Tools.hasCheek) System.getProperty("hamstr.cheekVersion", "") else stringResource(Res.string.desktop_cheek_missing))
    }
    Spacer(Modifier.height(16.dp))
    HButton(
        stringResource(if (updating) Res.string.set_updating else Res.string.desktop_ytdlp_update),
        Queue::updateYtDlp,
        Modifier.fillMaxWidth(), kind = ButtonKind.Neutral, icon = Res.drawable.ic_refresh, enabled = !updating && engine is EngineState.Ready,
    )
    failed?.let {
        Spacer(Modifier.height(8.dp))
        Txt(stringResource(Res.string.set_update_failed_why, it), style = Hamstr.type.small, color = Hamstr.colors.ghost, modifier = Modifier.padding(horizontal = 6.dp))
    }
}

@Composable
private fun AboutPage(onQuit: () -> Unit) {
    val c = Hamstr.colors
    val s by Prefs.settings.collectAsState()
    val update by Updater.state.collectAsState()
    Group {
        SettingRow(stringResource(Res.string.set_version), subtitle = "Hamstr " + Updater.version)
        Divider()
        SettingRow(stringResource(Res.string.set_source), subtitle = "github.com/${Updater.REPO}", onClick = { Files.browse("https://github.com/${Updater.REPO}") }) {
            Icon(Res.drawable.ic_open_in_new, tint = c.ghost, size = 18.dp)
        }
    }
    // What only an installed app on a computer does: the tray and starting at sign-in.
    val tray = remember { java.awt.SystemTray.isSupported() }
    if (tray || Autostart.available) {
        Spacer(Modifier.height(16.dp))
        SettingLabel(stringResource(Res.string.set_this_computer), Modifier.padding(horizontal = 6.dp))
        Spacer(Modifier.height(8.dp))
        Group {
            if (tray) FxRow(stringResource(Res.string.set_tray), stringResource(Res.string.set_tray_hint), s.trayOnClose) { v -> Prefs.edit { it.copy(trayOnClose = v) } }
            if (tray && Autostart.available) Divider()
            if (Autostart.available) FxRow(stringResource(Res.string.set_autostart), stringResource(Res.string.set_autostart_hint), s.startWithSystem) { v -> Prefs.edit { it.copy(startWithSystem = v) } }
        }
    }
    Spacer(Modifier.height(16.dp))
    SettingLabel(stringResource(Res.string.set_updates), Modifier.padding(horizontal = 6.dp))
    Spacer(Modifier.height(8.dp))
    Group {
        FxRow(stringResource(Res.string.set_app_updates), stringResource(Res.string.set_app_updates_hint), s.appUpdates) { v -> Prefs.edit { it.copy(appUpdates = v) } }
        Divider()
        val release = update.release
        SettingRow(
            when (val u = update) {
                UpdateState.Checking -> stringResource(Res.string.set_checking)
                is UpdateState.Coming -> stringResource(Res.string.desktop_update_coming, u.version)
                is UpdateState.Available -> stringResource(Res.string.set_update_out, u.release.version)
                is UpdateState.Downloading -> stringResource(Res.string.set_update_downloading, u.release.version, (u.progress * 100).toInt())
                is UpdateState.Installing -> stringResource(Res.string.set_update_installing, u.release.version)
                is UpdateState.Failed -> stringResource(Res.string.set_update_failed)
                UpdateState.UpToDate -> stringResource(Res.string.set_up_to_date)
                UpdateState.Idle -> stringResource(Res.string.set_updates)
            },
            subtitle = when (val u = update) {
                is UpdateState.Failed -> if (u.offline) stringResource(Res.string.upd_no_github, u.message) else u.message
                is UpdateState.Installing -> stringResource(
                    when {
                        !u.restarts -> Res.string.desktop_update_opened
                        Paths.windows || Paths.mac -> Res.string.desktop_update_restarts
                        else -> Res.string.desktop_update_password
                    },
                )
                is UpdateState.Coming -> stringResource(Res.string.desktop_update_coming_hint)
                is UpdateState.Available -> null
                else -> if (s.lastAppCheck == 0L) stringResource(Res.string.set_not_checked)
                else stringResource(Res.string.set_last_checked) + " " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(s.lastAppCheck))
            },
            // The release page has the notes.
            onClick = release?.let { r -> { Files.browse(r.page) } },
        ) {
            if (release != null) Icon(Res.drawable.ic_open_in_new, tint = c.ghost, size = 18.dp)
        }
        Column(Modifier.padding(8.dp)) {
            val busy = update == UpdateState.Checking || update is UpdateState.Downloading || update is UpdateState.Installing
            if (release != null) {
                HButton(
                    stringResource(Res.string.set_update_to, release.version), { Updater.install(release, onQuit) },
                    Modifier.fillMaxWidth(), kind = ButtonKind.Primary, icon = Res.drawable.ic_download, enabled = !busy,
                )
            } else {
                HButton(
                    stringResource(if (update == UpdateState.Checking) Res.string.set_checking else Res.string.set_check_now),
                    Updater::check, Modifier.fillMaxWidth(), icon = Res.drawable.ic_refresh, enabled = !busy,
                )
            }
        }
    }
    Spacer(Modifier.height(16.dp))
    Group {
        SettingRow(stringResource(Res.string.set_licenses), subtitle = "Hamstr (GPL-3.0), Cheek (Unlicense), yt-dlp (Unlicense), FFmpeg (LGPL/GPL), Inter & JetBrains Mono (OFL), Material Symbols & Icons (Apache-2.0)")
    }
    Spacer(Modifier.height(12.dp))
    Txt(stringResource(Res.string.desktop_about_local), style = Hamstr.type.small, color = c.ghost, modifier = Modifier.padding(horizontal = 6.dp))
}
