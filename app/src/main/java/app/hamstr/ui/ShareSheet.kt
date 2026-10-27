package app.hamstr.ui

import androidx.compose.foundation.gestures.Orientation
import app.hamstr.ui.components.edgeFade
import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.hamstr.R
import app.hamstr.data.Choice
import app.hamstr.data.Mode
import app.hamstr.data.Prefs
import app.hamstr.data.heightLabel
import app.hamstr.engine.Engine
import app.hamstr.engine.MediaInfo
import app.hamstr.ui.components.AsciiImage
import app.hamstr.ui.components.Badge
import app.hamstr.ui.components.ButtonKind
import app.hamstr.ui.components.DecodeText
import app.hamstr.ui.components.Divider
import app.hamstr.ui.components.HButton
import app.hamstr.ui.components.HSwitch
import app.hamstr.ui.components.Icon
import app.hamstr.ui.components.Segmented
import app.hamstr.ui.components.SettingRow
import app.hamstr.ui.components.Txt
import app.hamstr.ui.theme.Hamstr
import app.hamstr.ui.theme.Radii
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException

sealed interface InfoState {
    data object Loading : InfoState
    data class Loaded(val info: MediaInfo) : InfoState
    data class Failed(val message: String) : InfoState
}

/**
 * The sheet that appears after sharing a link: the preview loads as ASCII rain, settles
 * into the thumbnail's characters and then reveals the real image; below it the choices.
 */
@Composable
fun ShareSheet(
    url: String,
    onStash: (MediaInfo?, Choice) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = Hamstr.colors
    val context = LocalContext.current
    val defaults = remember { Prefs.settings.value }
    var state by remember(url) { mutableStateOf<InfoState>(InfoState.Loading) }
    var mode by remember { mutableStateOf(defaults.mode) }
    var height by remember { mutableStateOf(defaults.height) }
    var container by remember { mutableStateOf(defaults.container) }
    var audio by remember { mutableStateOf(defaults.audioFormat) }
    var subs by remember { mutableStateOf(defaults.subtitles) }
    var custom by remember { mutableStateOf("bv*+ba/b") }
    var open by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(url) {
        state = try { InfoState.Loaded(Engine.info(url)) } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            InfoState.Failed(e.message?.lines()?.lastOrNull { it.contains("ERROR") }?.substringAfter("ERROR: ") ?: "Couldn't read the link")
        }
    }
    val info = (state as? InfoState.Loaded)?.info
    val bitmap: Bitmap? = rememberBitmap(info?.thumbnail)

    // rain 1 -> 0 once the thumbnail is in, then the image wipes in from the top.
    val rain = remember(url) { Animatable(1f) }
    val reveal = remember(url) { Animatable(0f) }
    LaunchedEffect(bitmap, state) {
        if (state is InfoState.Failed) {
            rain.snapTo(0.5f)
            reveal.snapTo(0f)
        } else if (bitmap != null) {
            delay(250)
            rain.animateTo(0f, tween(600))
            reveal.animateTo(1f, tween(900))
        } else if (state is InfoState.Loaded && info?.thumbnail == null) {
            rain.animateTo(0.5f, tween(400))
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = Radii.sheet, topEnd = Radii.sheet))
            .background(c.overlay)
            .clickable(enabled = false) {}
            .navigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 16.dp)
            .animateContentSize(tween(220)),
    ) {
        Box(Modifier.align(Alignment.CenterHorizontally).width(36.dp).height(4.dp).clip(RoundedCornerShape(4.dp)).background(c.line))
        Spacer(Modifier.height(12.dp))
        AsciiImage(
            bitmap = bitmap,
            reveal = reveal.value,
            rain = rain.value,
            animate = rain.value > 0f && (state is InfoState.Loading || (state is InfoState.Loaded && bitmap != null)),
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(14.dp)),
        )
        Spacer(Modifier.height(10.dp))
        when (val s = state) {
            InfoState.Loading -> {
                Txt("Sniffing the link …", style = Hamstr.type.section, color = c.muted)
                Spacer(Modifier.height(3.dp))
                Txt(url, style = Hamstr.type.mono, color = c.ghost, maxLines = 1)
            }
            is InfoState.Loaded -> {
                DecodeText(s.info.title, style = Hamstr.type.section, maxLines = 2)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    s.info.source?.let { Badge(it) }
                    if (s.info.durationSec > 0) Badge(formatDuration(s.info.durationSec))
                    if (s.info.approxBytes > 0) Badge("~" + formatBytes(s.info.approxBytes), accent = true)
                }
            }
            is InfoState.Failed -> {
                Txt("Nothing to stash here", style = Hamstr.type.section)
                Spacer(Modifier.height(3.dp))
                Txt(s.message, style = Hamstr.type.small, color = c.red, maxLines = 3)
                Spacer(Modifier.height(2.dp))
                Txt(url, style = Hamstr.type.mono, color = c.ghost, maxLines = 1)
            }
        }
        Spacer(Modifier.height(12.dp))
        Segmented(
            options = Mode.entries.toList(),
            selected = mode,
            label = { when (it) { Mode.Video -> "Video"; Mode.Audio -> "Audio"; Mode.Custom -> "Custom" } },
            onSelect = { mode = it; open = null },
        )
        Spacer(Modifier.height(4.dp))
        when (mode) {
            Mode.Video -> {
                val offered = info?.heights?.takeIf { it.isNotEmpty() }
                val heights = Prefs.heights.filter { h -> h == null || offered == null || offered.any { it >= h } || h == offered.max() }
                PickRow("Quality", heightLabel(height), open == "q", { open = if (open == "q") null else "q" }) {
                    Chips(heights, height, ::heightLabel) { height = it; open = null }
                }
                Divider()
                PickRow("Format", container, open == "f", { open = if (open == "f") null else "f" }) {
                    Chips(Prefs.containers, container, { it }) { container = it; open = null }
                }
                Divider()
                SettingRow("Subtitles (de, en)", onClick = { subs = !subs }) { HSwitch(subs, { subs = it }) }
            }
            Mode.Audio -> {
                PickRow("Audio format", audio, open == "a", { open = if (open == "a") null else "a" }) {
                    Chips(Prefs.audioFormats, audio, { it }) { audio = it; open = null }
                }
            }
            Mode.Custom -> {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                    Txt("yt-dlp format selector (-f)", style = Hamstr.type.small, color = c.ghost)
                    Spacer(Modifier.height(6.dp))
                    BasicTextField(
                        value = custom,
                        onValueChange = { custom = it },
                        singleLine = true,
                        textStyle = Hamstr.type.mono.copy(color = c.ink),
                        cursorBrush = SolidColor(c.accent),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(c.canvas)
                            .padding(horizontal = 12.dp, vertical = 11.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HButton("Cancel", onDismiss, Modifier.weight(1f))
            HButton(
                "Stash it",
                onClick = {
                    Prefs.edit { it.copy(mode = if (mode == Mode.Custom) it.mode else mode, height = height, container = container, audioFormat = audio, subtitles = subs) }
                    onStash(info, Choice(mode, height, container, audio, subs, custom))
                },
                modifier = Modifier.weight(2f),
                kind = ButtonKind.Primary,
                icon = R.drawable.ic_download,
            )
        }
    }
}

@Composable
private fun PickRow(title: String, value: String, expanded: Boolean, onToggle: () -> Unit, content: @Composable () -> Unit) {
    Column {
        SettingRow(title, onClick = onToggle) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Txt(value, color = Hamstr.colors.muted)
                Icon(R.drawable.ic_expand_more, tint = Hamstr.colors.muted, size = 18.dp)
            }
        }
        AnimatedVisibility(expanded, enter = expandVertically(tween(220)) + fadeIn(tween(220)), exit = shrinkVertically(tween(150)) + fadeOut(tween(150))) {
            Box(Modifier.padding(start = 8.dp, end = 8.dp, bottom = 10.dp)) { content() }
        }
    }
}

@Composable
fun <T> Chips(options: List<T>, selected: T, label: (T) -> String, onPick: (T) -> Unit) {
    val c = Hamstr.colors
    val scroll = rememberScrollState()
    Row(Modifier.edgeFade(scroll, Orientation.Horizontal).horizontalScroll(scroll), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { o ->
            val on = o == selected
            Box(
                Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (on) c.accent else c.surface)
                    .clickable { onPick(o) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) { Txt(label(o), style = Hamstr.type.bodyMedium, color = if (on) c.onAccent else c.ink) }
        }
    }
}

fun formatDuration(sec: Int): String = if (sec >= 3600) "%d:%02d:%02d".format(sec / 3600, sec / 60 % 60, sec % 60) else "%d:%02d".format(sec / 60, sec % 60)

fun formatBytes(b: Long): String = when {
    b >= 1L shl 30 -> "%.1f GB".format(b / (1L shl 30).toDouble())
    b >= 1L shl 20 -> "%.0f MB".format(b / (1L shl 20).toDouble())
    b >= 1L shl 10 -> "%.0f KB".format(b / 1024.0)
    else -> "$b B"
}
