package app.hamstr.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.hamstr.R
import app.hamstr.data.Choice
import app.hamstr.data.DownloadStore
import app.hamstr.data.Mode
import app.hamstr.data.Prefs
import app.hamstr.data.Status
import app.hamstr.data.heightLabel
import app.hamstr.engine.MediaInfo
import app.hamstr.engine.PlaylistEntry
import app.hamstr.ui.components.AsciiImage
import app.hamstr.ui.components.Badge
import app.hamstr.ui.components.ButtonKind
import app.hamstr.ui.components.DecodeText
import app.hamstr.ui.components.HButton
import app.hamstr.ui.components.Segmented
import app.hamstr.ui.components.Txt
import app.hamstr.ui.components.edgeFade
import app.hamstr.ui.theme.Hamstr
import app.hamstr.ui.theme.Radii
import app.hamstr.ui.components.Sheet
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.lazy.LazyListScope
import app.hamstr.ui.components.Chips

/**
 * A shared playlist, album or channel: every video in a list to tick off, one quality for all,
 * and each picked video becomes its own download in one group. Videos already stashed start unticked.
 */
@Composable
internal fun PlaylistPickSheet(
    playlist: MediaInfo,
    onStash: (List<PlaylistEntry>, Choice) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = Hamstr.colors
    val entries = playlist.entries.orEmpty()
    val defaults = remember { Prefs.settings.value }
    var mode by remember { mutableStateOf(if (defaults.mode == Mode.Audio) Mode.Audio else Mode.Video) }
    var height by remember { mutableStateOf(defaults.height) }
    var audio by remember { mutableStateOf(defaults.audioFormat) }

    val tasks by DownloadStore.tasks.collectAsState()
    // Indexes of videos that are already stashed or on their way, under the same video key or link.
    val stashed = remember(tasks, entries) {
        val kept = tasks.filter { it.status != Status.Canceled && it.status != Status.Failed }
        val keys = kept.mapNotNullTo(HashSet()) { it.mediaKey }
        val urls = kept.mapTo(HashSet()) { it.url }
        entries.indices.filterTo(HashSet()) { i -> entries[i].key?.let { it in keys } == true || entries[i].url in urls }
    }
    val pickable = remember(entries) { entries.indices.filter { entries[it].available } }
    var selected by remember(playlist.url) { mutableStateOf(pickable.filterNot { it in stashed }.toSet()) }
    val allOn = pickable.isNotEmpty() && pickable.all { it in selected }
    val totalSec = selected.sumOf { entries[it].durationSec }

    val video = stringResource(R.string.mode_video)
    val sound = stringResource(R.string.mode_audio)
    val detail = listOfNotNull(
        totalSec.takeIf { it > 0 }?.let(::formatDuration),
        stashed.size.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.share_list_already, it, it) },
    ).joinToString(" · ")
    Sheet(scroll = false) {
        PickList(
            entries, key = { i, e -> e.key ?: "$i:${e.url}" },
            header = {
                item(key = "head") {
                    Column {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            playlist.source?.let { Badge(it) }
                            Badge(pluralStringResource(R.plurals.share_list_videos, entries.size, entries.size), accent = true)
                        }
                        Spacer(Modifier.height(6.dp))
                        DecodeText(playlist.title, style = Hamstr.type.section, maxLines = 2)
                        Txt(
                            playlist.uploader?.let { stringResource(R.string.share_list_by, it) } ?: stringResource(R.string.share_list_hint),
                            style = Hamstr.type.small, color = c.muted, maxLines = 1,
                        )
                        Spacer(Modifier.height(12.dp))
                        Segmented(
                            options = listOf(Mode.Video, Mode.Audio),
                            selected = mode,
                            label = { if (it == Mode.Audio) sound else video },
                            onSelect = { mode = it },
                        )
                        Spacer(Modifier.height(2.dp))
                        if (mode == Mode.Video) Chips(Prefs.heights, height, ::heightLabel) { height = it }
                        else Chips(Prefs.audioFormats, audio, { it }) { audio = it }
                        Spacer(Modifier.height(6.dp))
                        PickBar(selected.size, entries.size, detail, allOn) { selected = if (allOn) emptySet() else pickable.toSet() }
                        Spacer(Modifier.height(4.dp))
                    }
                }
                if (entries.isEmpty()) item(key = "empty") {
                    Txt(stringResource(R.string.share_list_empty), color = c.muted, modifier = Modifier.padding(vertical = 24.dp))
                }
            },
        ) { i, e ->
            PickRow(
                checked = i in selected, thumbnail = e.thumbnail, title = e.title,
                meta = listOfNotNull("#${i + 1}", e.durationSec.takeIf { it > 0 }?.let(::formatDuration)),
                onToggle = { on -> selected = if (on) selected + i else selected - i },
                enabled = e.available,
                badge = when {
                    !e.available -> stringResource(R.string.share_list_unavailable)
                    i in stashed -> stringResource(R.string.share_list_here)
                    else -> null
                },
                badgeAccent = e.available,
            )
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HButton(stringResource(R.string.share_cancel), onDismiss, Modifier.weight(1f))
            HButton(
                if (selected.isEmpty()) stringResource(R.string.share_list_pick)
                else pluralStringResource(R.plurals.share_list_stash, selected.size, selected.size),
                {
                    val choice = Choice(mode, height, defaults.container, audio, subtitles = mode == Mode.Video && defaults.subtitles)
                    Prefs.edit { it.copy(mode = mode, height = height, audioFormat = audio) }
                    onStash(selected.sorted().map { entries[it] }, choice)
                },
                Modifier.weight(2f), kind = ButtonKind.Primary, icon = R.drawable.ic_download, enabled = selected.isNotEmpty(),
            )
        }
    }
}

/** One video or episode to tick off, shared by the playlist and the episode sheets. */
@Composable
internal fun PickRow(
    checked: Boolean,
    thumbnail: String?,
    title: String,
    meta: List<String>,
    onToggle: (Boolean) -> Unit,
    enabled: Boolean = true,
    badge: String? = null,
    badgeAccent: Boolean = false,
) {
    val c = Hamstr.colors
    val bitmap = rememberBitmap(thumbnail, 200)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radii.row))
            .background(if (checked) c.surface else c.overlay)
            .toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = onToggle)
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.4f },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Txt(if (checked) "[×]" else "[ ]", style = Hamstr.type.mono, color = if (checked) c.accent else c.ghost)
        Box(Modifier.size(width = 64.dp, height = 36.dp).clip(RoundedCornerShape(Radii.thumb))) {
            AsciiImage(bitmap = bitmap, reveal = 1f, cell = 4.dp, modifier = Modifier.size(width = 64.dp, height = 36.dp))
        }
        Column(Modifier.weight(1f)) {
            Txt(title, style = Hamstr.type.bodyMedium, maxLines = 2)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                meta.forEach { Txt(it, style = Hamstr.type.small, color = c.ghost) }
                badge?.let { Badge(it, accent = badgeAccent) }
            }
        }
    }
}

/** "3 of 12 picked" with a second line, and one button to pick all or clear. */
@Composable
internal fun PickBar(picked: Int, total: Int, detail: String, allOn: Boolean, onToggleAll: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Txt(stringResource(R.string.share_list_selected, picked, total), style = Hamstr.type.bodyMedium)
            Txt(detail.ifEmpty { " " }, style = Hamstr.type.small, color = Hamstr.colors.ghost, maxLines = 1)
        }
        HButton(stringResource(if (allOn) R.string.share_list_none else R.string.share_list_all), onToggleAll, kind = ButtonKind.Ghost)
    }
}

/**
 * The scrolling part of a pick sheet, with its edges fading. It takes the room the sheet has
 * left, and [header] rides along as its first items: on a short screen the header scrolls
 * away and the buttons under the list stay put.
 */
@Composable
internal fun <T> ColumnScope.PickList(items: List<T>, key: (Int, T) -> Any, header: LazyListScope.() -> Unit = {}, row: @Composable (Int, T) -> Unit) {
    val listState = rememberLazyListState()
    LazyColumn(
        Modifier.weight(1f, fill = false).fillMaxWidth().edgeFade(listState, Orientation.Vertical, length = 22.dp),
        state = listState,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        header()
        itemsIndexed(items, key = key) { i, item -> row(i, item) }
    }
}
