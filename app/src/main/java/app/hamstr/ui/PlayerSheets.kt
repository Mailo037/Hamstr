package app.hamstr.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import app.hamstr.R
import app.hamstr.data.DownloadTask
import app.hamstr.data.Lyrics
import app.hamstr.player.Playback
import app.hamstr.ui.components.AsciiImage
import app.hamstr.ui.components.ButtonKind
import app.hamstr.ui.components.HButton
import app.hamstr.ui.components.Icon
import app.hamstr.ui.components.IconButton
import app.hamstr.ui.components.Sheet
import app.hamstr.ui.components.SheetHeader
import app.hamstr.ui.components.Specs
import app.hamstr.ui.components.SwipeAction
import app.hamstr.ui.components.SwipeRow
import app.hamstr.ui.components.Txt
import app.hamstr.ui.components.edgeFade
import app.hamstr.ui.theme.Hamstr
import app.hamstr.ui.theme.Radii
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// Rows have one height, so a dragged row knows which place it is over.
private val QUEUE_ROW = 60.dp
private val QUEUE_GAP = 2.dp

/**
 * What plays and what comes after it. Tap a row to jump there, drag its handle to move it,
 * swipe it away (or tap the cross) to take it off. Closing the player is here too, as the
 * visible way of pulling the mini player down.
 */
@Composable
fun QueueSheet(onClose: () -> Unit) {
    val c = Hamstr.colors
    val haptics = LocalHapticFeedback.current
    val queue by Playback.queue.collectAsState()
    val current by Playback.current.collectAsState()
    LaunchedEffect(queue.isEmpty()) { if (queue.isEmpty()) onClose() }
    // A local copy moves under the finger; the player hears about it once the row is let go.
    var rows by remember { mutableStateOf(queue) }
    var dragKey by remember { mutableStateOf<String?>(null) }
    var dragFrom by remember { mutableIntStateOf(-1) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(queue) { if (dragKey == null) rows = queue }
    val step = with(LocalDensity.current) { (QUEUE_ROW + QUEUE_GAP).toPx() }
    val list = rememberLazyListState()
    val at = rows.indexOfFirst { it.id == current?.id }
    LaunchedEffect(Unit) { if (at > 1) list.scrollToItem(at - 1) }
    Sheet(scroll = false) {
        SheetHeader(
            stringResource(R.string.queue_title),
            subtitle = if (rows.size > 1) stringResource(R.string.queue_position, at + 1, rows.size) else pluralStringResource(R.plurals.lib_files, rows.size, rows.size),
        )
        Spacer(Modifier.height(10.dp))
        LazyColumn(
            Modifier.weight(1f, fill = false).edgeFade(list, Orientation.Vertical, length = 22.dp),
            state = list,
            verticalArrangement = Arrangement.spacedBy(QUEUE_GAP),
        ) {
            itemsIndexed(rows, key = { _, t -> t.id }) { i, t ->
                val dragging = t.id == dragKey
                val now = t.id == current?.id
                Box(
                    if (dragging) Modifier.zIndex(1f).graphicsLayer { translationY = dragOffset; scaleX = 1.02f; scaleY = 1.02f }
                    else Modifier.animateItem(fadeInSpec = Specs.listIn(), placementSpec = Specs.place, fadeOutSpec = Specs.listOut()),
                ) {
                    SwipeRow(
                        start = null,
                        end = SwipeAction(R.drawable.ic_close, stringResource(R.string.queue_remove), c.red, removes = true) {
                            Playback.removeAt(queue.indexOfFirst { it.id == t.id })
                        },
                    ) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .height(QUEUE_ROW)
                                .clip(RoundedCornerShape(Radii.row))
                                .background(if (now || dragging) c.surface else c.overlay)
                                .clickable { Playback.jumpTo(queue.indexOfFirst { it.id == t.id }) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // The handle: drag it up or down.
                            Box(
                                Modifier
                                    .size(width = 40.dp, height = QUEUE_ROW)
                                    .pointerInput(t.id) {
                                        detectDragGestures(
                                            onDragStart = {
                                                haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                                                dragKey = t.id; dragOffset = 0f; dragFrom = rows.indexOfFirst { it.id == t.id }
                                            },
                                            onDragEnd = {
                                                val to = rows.indexOfFirst { it.id == dragKey }
                                                if (dragFrom >= 0 && to >= 0 && to != dragFrom) Playback.move(dragFrom, to)
                                                dragKey = null; dragOffset = 0f
                                            },
                                            onDragCancel = { rows = queue; dragKey = null; dragOffset = 0f },
                                        ) { change, drag ->
                                            change.consume()
                                            dragOffset += drag.y
                                            val idx = rows.indexOfFirst { it.id == dragKey }
                                            val target = when {
                                                dragOffset > step / 2 && idx < rows.lastIndex -> idx + 1
                                                dragOffset < -step / 2 && idx > 0 -> idx - 1
                                                else -> idx
                                            }
                                            if (target != idx) {
                                                rows = rows.toMutableList().apply { add(target, removeAt(idx)) }
                                                dragOffset -= (target - idx) * step
                                                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                            }
                                        }
                                    },
                                contentAlignment = Alignment.Center,
                            ) { Icon(R.drawable.ic_drag_handle, tint = c.ghost, size = 20.dp) }
                            Box(Modifier.size(44.dp).clip(RoundedCornerShape(Radii.thumb)).background(c.black)) {
                                AsciiImage(rememberBitmap(t.thumbnail, 160), reveal = 1f, cell = 4.dp, modifier = Modifier.size(44.dp))
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Txt(t.title, style = Hamstr.type.bodyMedium, color = if (now) c.accent else c.ink, maxLines = 1)
                                Spacer(Modifier.height(2.dp))
                                Txt(
                                    listOfNotNull(
                                        if (now) stringResource(R.string.queue_now) else null,
                                        t.uploader?.let(Lyrics::cleanArtist) ?: t.source,
                                        formatDuration(t.durationSec).takeIf { t.durationSec > 0 },
                                    ).joinToString(" · "),
                                    style = Hamstr.type.small, color = if (now) c.accent else c.ghost, maxLines = 1,
                                )
                            }
                            IconButton(
                                R.drawable.ic_close, stringResource(R.string.queue_remove),
                                { Playback.removeAt(queue.indexOfFirst { it.id == t.id }) },
                                tint = c.ghost, background = Color.Transparent, size = 36.dp,
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        HButton(stringResource(R.string.player_close), { Playback.close(); onClose() }, Modifier.fillMaxWidth(), kind = ButtonKind.Ghost, icon = R.drawable.ic_close)
    }
}

/** Stops playback after a while, or when the song that plays now ends. */
@Composable
fun SleepSheet(onDone: (String?) -> Unit) {
    val c = Hamstr.colors
    val sleep by Playback.sleep.collectAsState()
    Sheet {
        SheetHeader(
            stringResource(R.string.sleep_title),
            subtitle = stringResource(if (sleep == null) R.string.sleep_hint else R.string.sleep_running),
        )
        Spacer(Modifier.height(12.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.surface).padding(4.dp)) {
            listOf(5, 10, 15, 30, 45, 60, 90).forEach { m ->
                val label = stringResource(R.string.sleep_minutes, m)
                SleepRow(label, on = false) { Playback.sleepIn(m); onDone(label) }
            }
            val end = stringResource(R.string.sleep_end)
            SleepRow(end, on = sleep == Playback.Sleep.EndOfTrack) { Playback.sleepAtEnd(); onDone(end) }
        }
        if (sleep != null) {
            Spacer(Modifier.height(10.dp))
            HButton(stringResource(R.string.sleep_off), { Playback.cancelSleep(); onDone(null) }, Modifier.fillMaxWidth(), kind = ButtonKind.Neutral, icon = R.drawable.ic_close)
        }
    }
}

@Composable
private fun SleepRow(label: String, on: Boolean, onClick: () -> Unit) {
    val c = Hamstr.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(R.drawable.ic_timer, tint = if (on) c.accent else c.muted)
        Txt(label, color = if (on) c.accent else c.ink, modifier = Modifier.weight(1f))
        if (on) Icon(R.drawable.ic_check, tint = c.accent)
    }
}

/**
 * The lyrics of a song, to type in, paste or fix. Lines with times ("[01:23.45] …") make
 * the player follow along; plain text just shows. Can also ask LRCLIB again.
 */
@Composable
fun LyricsSheet(t: DownloadTask, onDone: (String) -> Unit) {
    val c = Hamstr.colors
    val scope = rememberCoroutineScope()
    val saved by Lyrics.saved.collectAsState()
    var text by remember(t.id) { mutableStateOf(saved[t.id].orEmpty()) }
    var searching by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    val notFound = stringResource(R.string.lyrics_none)
    val savedMsg = stringResource(R.string.lyrics_saved)
    val removedMsg = stringResource(R.string.lyrics_removed)
    Sheet(scroll = false) {
        SheetHeader(stringResource(R.string.lyrics), subtitle = t.title)
        Spacer(Modifier.height(12.dp))
        val scroll = rememberScrollState()
        BasicTextField(
            text, { text = it },
            textStyle = Hamstr.type.body.copy(color = c.ink),
            cursorBrush = SolidColor(c.accent),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier.weight(1f, fill = false),
            decorationBox = { field ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 180.dp)
                        .clip(RoundedCornerShape(Radii.control))
                        .background(c.surface)
                        .edgeFade(scroll, Orientation.Vertical, length = 22.dp)
                        .verticalScroll(scroll)
                        .padding(14.dp),
                ) {
                    if (text.isEmpty()) Txt(stringResource(R.string.lyrics_placeholder), color = c.ghost)
                    field()
                }
            },
        )
        note?.let {
            Spacer(Modifier.height(8.dp))
            Txt(it, style = Hamstr.type.small, color = c.muted, modifier = Modifier.padding(horizontal = 4.dp))
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HButton(
                stringResource(if (searching) R.string.lyrics_searching else R.string.lyrics_find),
                {
                    if (!searching) scope.launch {
                        searching = true
                        Lyrics.find(t).fold({ text = it; note = null }, { note = notFound })
                        searching = false
                    }
                },
                Modifier.weight(1f), kind = ButtonKind.Neutral, icon = R.drawable.ic_search, enabled = !searching,
            )
            HButton(
                stringResource(R.string.pl_save),
                { scope.launch { Lyrics.save(t.id, text); onDone(if (text.isBlank()) removedMsg else savedMsg) } },
                Modifier.weight(2f), kind = ButtonKind.Primary, icon = R.drawable.ic_check,
                enabled = text.trim() != saved[t.id].orEmpty(),
            )
        }
    }
}
