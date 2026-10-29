package app.hamstr.ui

import app.hamstr.res.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.zIndex
import app.hamstr.ui.components.Specs
import app.hamstr.ui.components.SwipeAction
import app.hamstr.ui.components.SwipeRow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.hamstr.R
import app.hamstr.data.DownloadStore
import app.hamstr.data.DownloadTask
import app.hamstr.data.Playlist
import app.hamstr.data.Playlists
import app.hamstr.player.Playback
import app.hamstr.ui.components.AsciiImage
import app.hamstr.ui.components.Badge
import app.hamstr.ui.components.ButtonKind
import app.hamstr.ui.components.HButton
import app.hamstr.ui.components.Icon
import app.hamstr.ui.components.IconButton
import app.hamstr.ui.components.Txt
import app.hamstr.ui.components.edgeFade
import app.hamstr.ui.theme.Hamstr
import app.hamstr.ui.theme.Radii
import app.hamstr.ui.components.Sheet
import app.hamstr.ui.components.SheetField
import app.hamstr.ui.components.SheetHeader
import app.hamstr.ui.components.pressable

/** The live playlist behind [id], or null once it is gone. */
@Composable
fun rememberPlaylist(id: String): Playlist? {
    val tasks by DownloadStore.tasks.collectAsState()
    val lists by Playlists.lists.collectAsState()
    return remember(id, tasks, lists) { Playlists.find(id, tasks, lists) }
}

/**
 * One playlist: play it all or shuffled, start from any file, take files off it (the cross
 * or a swipe), rename it or delete it. Drag a file by its handle to move it, as in the
 * queue: the row lifts and follows the finger while the others make room. Holding a file
 * opens its menu. Deleting a list keeps its files; a downloaded one can drop its files too.
 */
@Composable
fun PlaylistSheet(
    id: String,
    onPlay: (DownloadTask, List<DownloadTask>) -> Unit,
    onMenu: (DownloadTask) -> Unit,
    onRename: () -> Unit,
    onDeleteFiles: (List<DownloadTask>) -> Unit,
    onClose: () -> Unit,
    onHint: (String) -> Unit,
) {
    val c = Hamstr.colors
    val resources = LocalResources.current
    val p = rememberPlaylist(id)
    LaunchedEffect(p == null) { if (p == null) onClose() }
    if (p == null) return
    val items = p.items
    val latest by rememberUpdatedState(items)
    val name = p.name.ifEmpty { stringResource(R.string.lib_playlist) }
    val playing by Playback.current.collectAsState()
    var confirm by remember(id) { mutableStateOf(false) }
    val list = rememberLazyListState()
    val haptics = LocalHapticFeedback.current
    // While a file is held, a copy of the order moves under the finger; the list is saved on
    // release, and the copy stays until the saved order is back, so nothing jumps.
    var held by remember(id) { mutableStateOf<List<DownloadTask>?>(null) }
    var dragKey by remember { mutableStateOf<String?>(null) }
    var dragFrom by remember { mutableIntStateOf(-1) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var rowH by remember { mutableIntStateOf(0) }
    val gap = with(LocalDensity.current) { 2.dp.toPx() }
    LaunchedEffect(items) { if (dragKey == null) held = null }
    val rows = held ?: items
    // Header and buttons stay put; the files take the room that is left.
    Sheet(scroll = false) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
            PlaylistFan(items, Modifier.width(if (p.songs) 96.dp else 132.dp), square = p.songs)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Txt(name, style = Hamstr.type.section, maxLines = 2)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Badge(pluralStringResource(R.plurals.lib_files, items.size, items.size), accent = true)
                    if (items.isNotEmpty()) Badge(formatBytes(items.sumOf { it.sizeBytes }))
                    if (p.downloaded) Badge(stringResource(R.string.pl_downloaded))
                }
            }
            IconButton(Res.drawable.ic_edit, stringResource(R.string.pl_rename), onRename)
        }
        Spacer(Modifier.height(12.dp))
        if (items.isEmpty()) {
            Txt(stringResource(R.string.pl_empty), color = c.muted, modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp))
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HButton(stringResource(R.string.pl_play), { onPlay(items.first(), items) }, Modifier.weight(1f), kind = ButtonKind.Primary, icon = Res.drawable.ic_play_arrow)
                HButton(stringResource(R.string.pl_shuffle), { val s = items.shuffled(); onPlay(s.first(), s) }, Modifier.weight(1f), icon = Res.drawable.ic_shuffle)
            }
            Spacer(Modifier.height(8.dp))
            LazyColumn(
                Modifier
                    .weight(1f, fill = false)
                    .edgeFade(list, Orientation.Vertical, length = 22.dp),
                state = list,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(rows, key = { it.id }) { t ->
                    val dragging = t.id == dragKey
                    val remove = {
                        Playlists.remove(id, t)
                        onHint(resources.getString(R.string.pl_removed, name))
                    }
                    Box(
                        (if (dragging) Modifier.zIndex(1f).graphicsLayer { translationY = dragOffset; scaleX = 1.02f; scaleY = 1.02f }
                        else Modifier.animateItem(fadeInSpec = Specs.listIn(), placementSpec = Specs.place, fadeOutSpec = Specs.listOut()))
                            .onSizeChanged { rowH = it.height },
                    ) {
                        SwipeRow(
                            start = null,
                            end = SwipeAction(Res.drawable.ic_close, stringResource(R.string.pl_remove), c.red, removes = true) { remove() },
                            background = c.overlay,
                        ) {
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(Radii.row)).background(if (dragging) c.surface else Color.Transparent),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                // The handle: drag it up or down.
                                Box(
                                    Modifier
                                        .size(width = 36.dp, height = 52.dp)
                                        .pointerInput(t.id) {
                                            detectDragGestures(
                                                onDragStart = {
                                                    haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                                                    held = latest; dragKey = t.id; dragOffset = 0f
                                                    dragFrom = latest.indexOfFirst { it.id == t.id }
                                                },
                                                onDragEnd = {
                                                    val to = held?.indexOfFirst { it.id == dragKey } ?: -1
                                                    if (dragFrom >= 0 && to >= 0 && to != dragFrom) {
                                                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                                                        Playlists.move(id, latest, dragFrom, to)
                                                    } else held = null
                                                    dragKey = null; dragOffset = 0f
                                                },
                                                onDragCancel = { held = null; dragKey = null; dragOffset = 0f },
                                            ) { change, drag ->
                                                change.consume()
                                                val now = held ?: return@detectDragGestures
                                                dragOffset += drag.y
                                                val step = rowH + gap
                                                val i = now.indexOfFirst { it.id == dragKey }
                                                val target = when {
                                                    dragOffset > step / 2 && i < now.lastIndex -> i + 1
                                                    dragOffset < -step / 2 && i > 0 -> i - 1
                                                    else -> i
                                                }
                                                if (target != i && i >= 0) {
                                                    held = now.toMutableList().apply { add(target, removeAt(i)) }
                                                    dragOffset -= (target - i) * step
                                                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                                }
                                            }
                                        },
                                    contentAlignment = Alignment.Center,
                                ) { Icon(Res.drawable.ic_drag_handle, tint = c.ghost, size = 20.dp) }
                                SongRow(
                                    t, now = playing?.id == t.id, wide = !p.songs,
                                    onClick = { onPlay(t, items) }, onLongClick = { onMenu(t) },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    IconButton(Res.drawable.ic_close, stringResource(R.string.pl_remove), remove, tint = c.ghost, background = Color.Transparent, size = 40.dp)
                                }
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HButton(
                stringResource(if (confirm) R.string.pl_delete_confirm else R.string.pl_delete),
                {
                    if (!confirm) confirm = true else {
                        Playlists.delete(id)
                        onHint(resources.getString(R.string.pl_deleted))
                        onClose()
                    }
                },
                Modifier.weight(1f), kind = ButtonKind.Ghost, icon = Res.drawable.ic_delete,
            )
            if (p.downloaded && items.isNotEmpty()) {
                HButton(stringResource(R.string.pl_delete_files), { onDeleteFiles(items); onClose() }, Modifier.weight(1f), kind = ButtonKind.Ghost, icon = Res.drawable.ic_delete)
            }
        }
    }
}

/** Picks the playlist [files] go into, or starts a new one. */
@Composable
fun AddToPlaylistSheet(files: List<DownloadTask>, onNew: () -> Unit, onDone: (String) -> Unit) {
    val c = Hamstr.colors
    val context = LocalContext.current
    val resources = LocalResources.current
    val tasks by DownloadStore.tasks.collectAsState()
    val lists by Playlists.lists.collectAsState()
    val all = remember(tasks, lists) { Playlists.all(tasks, lists) }
    val ids = files.map { it.id }.toSet()
    val list = rememberLazyListState()
    Sheet(scroll = false) {
        SheetHeader(
            stringResource(R.string.pl_add),
            subtitle = if (files.size == 1) files.first().title else pluralStringResource(R.plurals.lib_files, files.size, files.size),
        )
        Spacer(Modifier.height(10.dp))
        LazyColumn(
            Modifier.weight(1f, fill = false).edgeFade(list, Orientation.Vertical, length = 22.dp),
            state = list,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            item {
                ChoiceRow(onNew) {
                    Box(Modifier.size(width = 64.dp, height = 36.dp).clip(RoundedCornerShape(Radii.thumb)).background(c.accent), contentAlignment = Alignment.Center) {
                        Icon(Res.drawable.ic_add, tint = c.onAccent, size = 20.dp)
                    }
                    Spacer(Modifier.width(12.dp))
                    Txt(stringResource(R.string.pl_new), style = Hamstr.type.bodyMedium, color = c.accent)
                }
            }
            items(all, key = { it.id }) { p ->
                val inIt = p.items.map { it.id }.containsAll(ids)
                val name = p.name.ifEmpty { stringResource(R.string.lib_playlist) }
                ChoiceRow({
                    if (!inIt) Playlists.add(p.id, files)
                    onDone(if (files.size == 1) resources.getString(R.string.pl_added_one, name) else resources.getString(R.string.pl_added_many, files.size, name))
                }) {
                    Box(Modifier.width(64.dp), contentAlignment = Alignment.Center) { PlaylistFan(p.items, Modifier.width(if (p.songs) 40.dp else 64.dp), square = p.songs) }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Txt(name, style = Hamstr.type.bodyMedium, maxLines = 1)
                        Txt(
                            if (inIt) stringResource(R.string.pl_in_list) else pluralStringResource(R.plurals.lib_files, p.items.size, p.items.size),
                            style = Hamstr.type.small, color = if (inIt) c.accent else c.ghost,
                        )
                    }
                    if (inIt) Icon(Res.drawable.ic_check, tint = c.accent, size = 20.dp, modifier = Modifier.padding(end = 8.dp))
                }
            }
        }
    }
}

/** A playlist to put files into, or the row that starts a new one. */
@Composable
private fun ChoiceRow(onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(Radii.row)).clickable(onClick = onClick).heightIn(min = 56.dp).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

/** Asks for a playlist's name, for a new list or a rename. */
@Composable
fun NameSheet(title: String, initial: String, action: String, onDone: (String) -> Unit) {
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    val name = value.text.trim()
    val submit = { if (name.isNotEmpty()) onDone(name) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Sheet {
        SheetHeader(title)
        Spacer(Modifier.height(16.dp))
        SheetField(
            value, { value = it }, stringResource(R.string.pl_name), Res.drawable.ic_playlist_add,
            modifier = Modifier.focusRequester(focus),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
        )
        Spacer(Modifier.height(12.dp))
        HButton(action, { submit() }, Modifier.fillMaxWidth(), kind = ButtonKind.Primary, icon = Res.drawable.ic_check, enabled = name.isNotEmpty() && name != initial)
    }
}

/** A playlist in the library's row: the fanned covers, its name and how many files. */
@Composable
fun PlaylistTile(p: Playlist, width: Dp, onClick: () -> Unit, square: Boolean = p.songs, onLongClick: (() -> Unit)? = null) {
    // A square fan as tall as a wide one would be too big; it gets narrower instead.
    Column(Modifier.width(if (square) width * 0.72f else width).clip(RoundedCornerShape(14.dp)).pressable(onClick, onLongClick = onLongClick)) {
        PlaylistFan(p.items, Modifier.fillMaxWidth(), square = square)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 2.dp)) {
            if (p.favorites) Icon(Res.drawable.ic_favorite, tint = Hamstr.colors.accent, size = 14.dp, modifier = Modifier.padding(end = 4.dp))
            Txt(p.name.ifEmpty { stringResource(R.string.lib_playlist) }, style = Hamstr.type.bodyMedium, maxLines = 1)
        }
        Txt(pluralStringResource(R.plurals.lib_files, p.items.size, p.items.size), style = Hamstr.type.small, color = Hamstr.colors.ghost, modifier = Modifier.padding(horizontal = 2.dp))
    }
}

/** The tile that starts a new playlist, at the end of the row. */
@Composable
fun NewPlaylistTile(width: Dp, onClick: () -> Unit, square: Boolean = false) {
    val c = Hamstr.colors
    Column(Modifier.width(if (square) width * 0.72f else width).clip(RoundedCornerShape(14.dp)).pressable(onClick)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(if (square) 1f else 16f / 9f).clip(RoundedCornerShape(14.dp)).border(2.dp, c.line, RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center,
        ) { Icon(Res.drawable.ic_add, tint = c.accent, size = 28.dp) }
        Spacer(Modifier.height(6.dp))
        Txt(stringResource(R.string.pl_new), style = Hamstr.type.bodyMedium, color = c.accent, maxLines = 1, modifier = Modifier.padding(horizontal = 2.dp))
    }
}

/**
 * A file as a song: a small square cover, title, artist and length, in a row. Several fit
 * where one video tile would go. [trailing] holds the row's button (menu, remove).
 */
@Composable
fun SongRow(
    t: DownloadTask,
    now: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    wide: Boolean = false,
    gone: Boolean = false,
    trailing: @Composable () -> Unit = {},
) {
    val c = Hamstr.colors
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radii.row))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(width = if (wide) 74.dp else 52.dp, height = if (wide) 42.dp else 52.dp)
                .clip(RoundedCornerShape(Radii.thumb)).background(c.black),
        ) {
            AsciiImage(rememberImage(t.thumbnail, 160), reveal = 1f, cell = 4.dp, modifier = Modifier.fillMaxWidth().aspectRatio(if (wide) 16f / 9f else 1f))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Txt(t.title, style = Hamstr.type.bodyMedium, color = if (now) c.accent else if (gone) c.ghost else c.ink, maxLines = 1)
            Spacer(Modifier.height(2.dp))
            Txt(
                listOfNotNull(
                    if (gone) stringResource(R.string.lib_gone) else t.uploader ?: t.source,
                    formatDuration(t.durationSec).takeIf { t.durationSec > 0 },
                ).joinToString(" · "),
                style = Hamstr.type.small, color = c.ghost, maxLines = 1,
            )
        }
        trailing()
    }
}
