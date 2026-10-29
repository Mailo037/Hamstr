package app.hamstr.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.hamstr.data.ACCENTS
import app.hamstr.data.Base
import app.hamstr.data.DownloadEngine
import app.hamstr.data.Mode
import app.hamstr.data.Prefs
import app.hamstr.data.UiSize
import app.hamstr.data.heightLabel
import app.hamstr.res.*
import app.hamstr.ui.components.AccentPicker
import app.hamstr.ui.components.Chips
import app.hamstr.ui.components.Divider
import app.hamstr.ui.components.Group
import app.hamstr.ui.components.HSwitch
import app.hamstr.ui.components.Segmented
import app.hamstr.ui.components.SettingRow
import app.hamstr.ui.components.Txt
import app.hamstr.ui.components.searchHighlight
import app.hamstr.ui.theme.Hamstr
import org.jetbrains.compose.resources.stringResource

// Settings both apps show the same way. A setting that exists on one platform only stays in
// that platform's settings page, next to these.

@Composable
fun SettingLabel(text: String, modifier: Modifier = Modifier) = Txt(text, style = Hamstr.type.small, color = Hamstr.colors.ghost, modifier = modifier)

/** A labelled setting inside a [Group]: a picker, chips or a segmented control under its name. */
@Composable
fun SettingBlock(label: String, content: @Composable ColumnScope.() -> Unit) =
    Column(Modifier.searchHighlight(label).padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingLabel(label)
        content()
    }

/** A setting that is on or off; tapping the row flips it. */
@Composable
fun FxRow(title: String, subtitle: String, on: Boolean, set: (Boolean) -> Unit) {
    SettingRow(title, subtitle = subtitle, onClick = { set(!on) }) { HSwitch(on, set) }
}

/** Accent, background and size, the start of Appearance. */
@Composable
fun LookBlocks() {
    val s by Prefs.settings.collectAsState()
    SettingBlock(stringResource(Res.string.set_accent)) {
        AccentPicker(ACCENTS, s.accent) { a -> Prefs.edit { it.copy(accent = a) } }
    }
    SettingBlock(stringResource(Res.string.set_background)) {
        Chips(Base.entries.toList(), s.base, { it.label }) { b -> Prefs.edit { it.copy(base = b) } }
    }
    SettingBlock(stringResource(Res.string.set_size)) {
        val labels = mapOf(UiSize.Small to stringResource(Res.string.size_small), UiSize.Normal to stringResource(Res.string.size_normal), UiSize.Large to stringResource(Res.string.size_large))
        Segmented(UiSize.entries.toList(), s.size, { labels.getValue(it) }, { v -> Prefs.edit { it.copy(size = v) } })
    }
}

/** The ASCII effects of Motion & effects, under their label. */
@Composable
fun AsciiEffects() {
    val s by Prefs.settings.collectAsState()
    Txt(stringResource(Res.string.set_ascii), style = Hamstr.type.small, color = Hamstr.colors.ghost, modifier = Modifier.padding(horizontal = 6.dp))
    Spacer(Modifier.height(8.dp))
    Group {
        FxRow(stringResource(Res.string.set_raster), stringResource(Res.string.set_raster_hint), s.raster) { v -> Prefs.edit { it.copy(raster = v) } }
        Divider()
        FxRow(stringResource(Res.string.set_ascii_thumbs), stringResource(Res.string.set_ascii_thumbs_hint), s.asciiThumbs) { v -> Prefs.edit { it.copy(asciiThumbs = v) } }
        Divider()
        FxRow(stringResource(Res.string.set_rain), stringResource(Res.string.set_rain_hint), s.rain) { v -> Prefs.edit { it.copy(rain = v) } }
        Divider()
        FxRow(stringResource(Res.string.set_decode), stringResource(Res.string.set_decode_hint), s.decode) { v -> Prefs.edit { it.copy(decode = v) } }
    }
}

/** Who fetches the bytes: Cheek over many connections, or yt-dlp alone. yt-dlp always finds the streams. */
@Composable
fun EngineChoice() {
    val s by Prefs.settings.collectAsState()
    SettingBlock(stringResource(Res.string.set_engine)) {
        Segmented(DownloadEngine.entries.toList(), s.engine, { it.label }, { e -> Prefs.edit { it.copy(engine = e) } })
        Txt(
            when (s.engine) {
                DownloadEngine.YtDlp -> stringResource(Res.string.set_engine_ytdlp)
                DownloadEngine.Cheek -> stringResource(Res.string.set_engine_cheek)
            },
            style = Hamstr.type.small, color = Hamstr.colors.ghost,
        )
    }
}

/** What a new download is by default: video or sound, quality and formats. */
@Composable
fun FormatDefaults() {
    val s by Prefs.settings.collectAsState()
    val videoLabel = stringResource(Res.string.mode_video)
    val audioLabel = stringResource(Res.string.mode_audio)
    Group {
        SettingBlock(stringResource(Res.string.set_default_mode)) {
            Segmented(listOf(Mode.Video, Mode.Audio), s.mode, { if (it == Mode.Video) videoLabel else audioLabel }, { m -> Prefs.edit { it.copy(mode = m) } })
        }
        SettingBlock(stringResource(Res.string.set_video_quality)) {
            Chips(Prefs.heights, s.height, ::heightLabel) { h -> Prefs.edit { it.copy(height = h) } }
        }
        SettingBlock(stringResource(Res.string.set_video_format)) {
            Chips(Prefs.containers, s.container, { it }) { f -> Prefs.edit { it.copy(container = f) } }
        }
        SettingBlock(stringResource(Res.string.share_audio_format)) {
            Chips(Prefs.audioFormats, s.audioFormat, { it }) { f -> Prefs.edit { it.copy(audioFormat = f) } }
        }
    }
}
