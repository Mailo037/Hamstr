package app.hamstr.ui

import app.hamstr.ui.components.rememberAmbientClock
import kotlin.math.PI
import kotlin.math.cos
import app.hamstr.res.*
import java.io.File
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.Orientation
import app.hamstr.ui.components.edgeFade
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import app.hamstr.Platform
import app.hamstr.data.Downloads
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import app.hamstr.data.FileChecks
import app.hamstr.data.DownloadTask
import app.hamstr.data.Positions
import app.hamstr.data.collapseVersions
import app.hamstr.data.Mode
import app.hamstr.data.Stage
import app.hamstr.data.Status
import app.hamstr.data.heightLabel
import app.hamstr.engine.EngineState
import app.hamstr.player.Kind
import app.hamstr.player.kindOf
import app.hamstr.ui.components.AsciiImage
import app.hamstr.ui.components.Badge
import app.hamstr.ui.components.DecodeText
import app.hamstr.ui.components.HamstrMark
import app.hamstr.ui.components.Icon
import app.hamstr.ui.components.IconButton
import app.hamstr.ui.components.LocalReduceMotion
import app.hamstr.ui.components.ProgressLine
import app.hamstr.ui.components.SectionHeader
import app.hamstr.ui.components.SwipeAction
import app.hamstr.ui.components.SwipeRow
import app.hamstr.ui.components.Txt
import app.hamstr.ui.components.rememberMarkState
import app.hamstr.ui.theme.Hamstr
import app.hamstr.ui.theme.LocalFx
import app.hamstr.ui.theme.Radii
import kotlinx.coroutines.delay
import app.hamstr.ui.components.PictureLabel
import app.hamstr.ui.components.Specs
import app.hamstr.ui.components.iconSwap

/** How many finished downloads the stash lists; the library has all of them. */
private const val SHELF = 60

@Composable
fun StashScreen(
    tasks: List<DownloadTask>,
    engine: EngineState,
    contentPadding: PaddingValues,
    onAdd: () -> Unit,
    onOpenAdd: () -> Unit,
    onMenu: (DownloadTask) -> Unit,
    onPlay: (DownloadTask, List<DownloadTask>) -> Unit,
    onHide: (List<DownloadTask>) -> Unit,
    onRetryEngine: () -> Unit,
    onShowAll: () -> Unit,
) {
    val c = Hamstr.colors
    val haptics = LocalHapticFeedback.current
    val active = tasks.filter { it.status != Status.Done && it.status != Status.Canceled }
    val done = tasks.filter { it.status == Status.Done }
    val shelf = remember(done) { collapseVersions(done) }
    val heads = remember(shelf) { shelf.map { it.first } }
    val totalBytes = done.sumOf { it.sizeBytes }
    val running = tasks.count { it.status.active }
    val mark = rememberMarkState()
    val moods = LocalFx.current.moods
    val missing by FileChecks.missing.collectAsState()
    val positions by Positions.all.collectAsState()
    // Groups whose waiting downloads are shown too, not just the ones that are moving.
    var openGroups by remember { mutableStateOf(emptySet<String>()) }
    val rows = remember(tasks, openGroups) { activeRows(tasks, active, openGroups) }

    // Something finished: the hamster stuffs its cheeks and chews.
    var lastDone by remember { mutableIntStateOf(done.size) }
    LaunchedEffect(done.size) {
        if (done.size > lastDone && moods) mark.munch()
        lastDone = done.size
    }

    // Pull to stash: pull the list down, the cheeks fill up, release past the line to paste.
    val threshold = with(LocalDensity.current) { 96.dp.toPx() }
    var pull by remember { mutableFloatStateOf(0f) }
    var armed by remember { mutableStateOf(false) }
    val add by rememberUpdatedState(onAdd)
    LaunchedEffect(mark) { snapshotFlow { pull }.collect { mark.pull = (it / threshold).coerceIn(0f, 1.25f) } }
    val connection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y < 0f && pull > 0f) {
                    val used = maxOf(available.y, -pull)
                    pull += used
                    return Offset(0f, used)
                }
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (available.y > 0f && source == NestedScrollSource.UserInput) {
                    // Heavier the further it goes.
                    pull += available.y * 0.5f * (1f - (pull / (threshold * 2.2f)).coerceIn(0f, 0.9f))
                    val nowArmed = pull >= threshold
                    if (nowArmed != armed) {
                        armed = nowArmed
                        haptics.performHapticFeedback(if (nowArmed) HapticFeedbackType.GestureThresholdActivate else HapticFeedbackType.SegmentTick)
                    }
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (pull <= 0f) return Velocity.Zero
                if (armed) {
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    add()
                }
                armed = false
                animate(pull, 0f, animationSpec = Specs.settle()) { v, _ -> pull = v }
                pull = 0f
                return available
            }
        }
    }

    Box(Modifier.fillMaxSize().nestedScroll(connection)) {
        // The hint sits above the list and is revealed as the list moves down.
        Box(
            Modifier
                .fillMaxWidth()
                .padding(top = (contentPadding.calculateTopPadding() - 4.dp).coerceAtLeast(0.dp))
                .graphicsLayer { alpha = (pull / threshold).coerceIn(0f, 1f) },
            contentAlignment = Alignment.TopCenter,
        ) {
            Txt(
                stringResource(if (armed) Res.string.stash_pull_release else Res.string.stash_pull),
                style = Hamstr.type.badge,
                color = if (armed) c.accent else c.ghost,
            )
        }
        val listState = rememberLazyListState()
        LazyColumn(
            // The fade stays put while the list itself follows the pull.
            Modifier.fillMaxSize().edgeFade(listState, Orientation.Vertical, insets = contentPadding).graphicsLayer { translationY = pull },
            state = listState,
            contentPadding = contentPadding,
        ) {
            item(key = "header") {
                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    HamstrMark(34.dp, state = mark)
                    Spacer(Modifier.width(10.dp))
                    DecodeText("Hamstr", style = Hamstr.type.title, modifier = Modifier.weight(1f))
                    IconButton(Res.drawable.ic_add, stringResource(Res.string.stash_add), onOpenAdd, tint = c.ink)
                }
            }
            item(key = "hero") {
                Column(Modifier.padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 6.dp)) {
                    DecodeText(formatBytes(totalBytes), style = Hamstr.type.hero, delayMs = 120)
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Txt(pluralStringResource(Res.plurals.stash_files, done.size, done.size), color = c.muted)
                        if (running > 0) Badge(stringResource(Res.string.stash_active, running), accent = true)
                        when (engine) {
                            EngineState.Starting -> Badge(stringResource(Res.string.stash_engine_starting))
                            // A broken start can be tried again right here.
                            is EngineState.Broken -> Badge(
                                stringResource(Res.string.stash_engine_broken), accent = true,
                                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onRetryEngine).padding(vertical = 8.dp, horizontal = 2.dp),
                            )
                            is EngineState.Ready -> {}
                        }
                    }
                }
            }
            if (tasks.none { it.status != Status.Canceled }) {
                item(key = "empty") { EmptyState() }
            }
            if (active.isNotEmpty()) {
                item(key = "h_active") {
                    SectionHeader(
                        stringResource(Res.string.stash_downloading),
                        trailing = stringResource(Res.string.stash_running, active.count { it.status == Status.Running }),
                        modifier = Modifier.padding(horizontal = 12.dp).animateItem(),
                    )
                }
                itemsIndexed(rows, key = { _, r -> r.key }) { i, r ->
                    GroupedItem(i, rows.size) {
                        when (r) {
                            is ActiveRow.Header -> GroupRow(r.title, r.members, expanded = r.group in openGroups) {
                                openGroups = if (r.group in openGroups) openGroups - r.group else openGroups + r.group
                            }
                            is ActiveRow.More -> MoreRow(r.count) { openGroups = openGroups + r.group }
                            is ActiveRow.Item -> Box(Modifier.padding(start = if (r.nested) 12.dp else 0.dp)) {
                                SwipeRow(start = primarySwipe(r.task), end = cancelSwipe(r.task, onHide)) {
                                    TaskRow(r.task, highlighted = r.task.status == Status.Running, onClick = { onMenu(r.task) }, onLongClick = { onMenu(r.task) })
                                }
                            }
                        }
                    }
                }
            }
            if (done.isNotEmpty()) {
                item(key = "h_done") {
                    SectionHeader(stringResource(Res.string.stash_stashed), trailing = "${done.size}", modifier = Modifier.padding(horizontal = 12.dp).animateItem())
                }
                itemsIndexed(shelf.take(SHELF), key = { _, e -> e.first.id }) { i, (t, versions) ->
                    val gone = t.id in missing
                    GroupedItem(i, minOf(shelf.size, SHELF)) {
                        SwipeRow(
                            start = if (gone) null else SwipeAction(Res.drawable.ic_play_arrow, stringResource(Res.string.player_play), c.accent) { onPlay(t, heads) },
                            end = SwipeAction(Res.drawable.ic_visibility_off, stringResource(Res.string.stash_hide), c.muted, removes = true) { onHide(versions) },
                        ) {
                            TaskRow(
                                t, versions = versions, gone = gone, resume = positions[t.id]?.fraction,
                                onClick = { if (gone) onMenu(t) else onPlay(t, heads) }, onLongClick = { onMenu(t) },
                            )
                        }
                    }
                }
                if (shelf.size > SHELF) item(key = "all") {
                    Box(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp).clip(RoundedCornerShape(Radii.row))
                            .clickable(onClick = onShowAll).heightIn(min = 48.dp).padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) { Txt(stringResource(Res.string.stash_show_all, shelf.size), style = Hamstr.type.bodyMedium, color = c.accent) }
                }
            }
            item(key = "end") { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/** What the Downloading section lists: single downloads, and playlists as a header over their videos. */
private sealed interface ActiveRow {
    val key: String

    data class Item(val task: DownloadTask, val nested: Boolean) : ActiveRow { override val key get() = task.id }
    data class Header(val group: String, val title: String, val members: List<DownloadTask>) : ActiveRow { override val key get() = "g:$group" }
    data class More(val group: String, val count: Int) : ActiveRow { override val key get() = "m:$group" }
}

/**
 * Downloads that arrived together sit under one header with their overall progress. A closed
 * group shows only what moves, failed or was paused; the queued rest is one line until opened.
 */
private fun activeRows(tasks: List<DownloadTask>, active: List<DownloadTask>, open: Set<String>): List<ActiveRow> {
    val groups = tasks.filter { it.group != null && it.status != Status.Canceled }.groupBy { it.group!! }.filterValues { it.size > 1 }
    val seen = HashSet<String>()
    val out = ArrayList<ActiveRow>()
    for (t in active) {
        val g = t.group
        val members = g?.let(groups::get)
        if (g == null || members == null) {
            out += ActiveRow.Item(t, nested = false)
            continue
        }
        if (!seen.add(g)) continue
        out += ActiveRow.Header(g, t.groupTitle ?: t.title, members)
        // In playlist order, the order they also start in.
        val waiting = members.filter { it.status != Status.Done }.sortedBy { it.createdAt }
        val shown = if (g in open) waiting else waiting.filter { it.status != Status.Queued || it.stage != null }
        shown.forEach { out += ActiveRow.Item(it, nested = true) }
        if (shown.size < waiting.size) out += ActiveRow.More(g, waiting.size - shown.size)
    }
    return out
}

/** A playlist on its way: how many are in, one bar for all of it, and pause or resume for the whole group. */
@Composable
private fun GroupRow(title: String, members: List<DownloadTask>, expanded: Boolean, onToggle: () -> Unit) {
    val c = Hamstr.colors
    val done = members.count { it.status == Status.Done }
    val failed = members.count { it.status == Status.Failed }
    val moving = members.filter { it.status.active }
    val paused = members.filter { it.status == Status.Paused || it.status == Status.Failed }
    val overall = (members.sumOf { if (it.status == Status.Done) 1.0 else it.progress.toDouble() } / members.size).toFloat()
    val progress by animateFloatAsState(overall, Specs.glide(), label = "groupProgress")
    val turn by animateFloatAsState(if (expanded) 180f else 0f, Specs.blend(), label = "chevron")
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radii.row))
            .clickable(onClick = onToggle)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(52.dp).clip(RoundedCornerShape(Radii.thumb)).background(c.surface),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Txt("$done", style = Hamstr.type.mono, color = c.accent)
                Txt("/${members.size}", style = Hamstr.type.small, color = c.ghost)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Txt(title, style = Hamstr.type.bodyMedium, maxLines = 1)
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Badge(pluralStringResource(Res.plurals.share_list_videos, members.size, members.size))
                Txt(stringResource(Res.string.stash_group_count, done, members.size), style = Hamstr.type.small, color = c.ghost, maxLines = 1)
                if (failed > 0) Txt(stringResource(Res.string.stash_group_failed, failed), style = Hamstr.type.small, color = c.red, maxLines = 1)
            }
            Spacer(Modifier.height(7.dp))
            ProgressLine(progress, color = if (moving.isEmpty()) c.ghost else c.accent)
        }
        Spacer(Modifier.width(8.dp))
        Txt("${(overall * 100).toInt()}%", style = Hamstr.type.mono, modifier = Modifier.padding(end = 2.dp))
        if (moving.isNotEmpty()) {
            IconButton(Res.drawable.ic_pause, stringResource(Res.string.stash_group_pause), {
                moving.forEach { Downloads.control.pause(it.id) }
            }, size = 34.dp)
        } else if (paused.isNotEmpty()) {
            IconButton(Res.drawable.ic_play_arrow, stringResource(Res.string.stash_group_resume), {
                paused.forEach { Downloads.control.resume(it.id) }
            }, size = 34.dp, tint = c.accent)
        }
        Icon(Res.drawable.ic_expand_more, tint = c.muted, size = 20.dp, modifier = Modifier.graphicsLayer { rotationZ = turn })
    }
}

@Composable
private fun MoreRow(count: Int, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(Radii.row)).clickable(onClick = onClick).heightIn(min = 40.dp).padding(start = 76.dp, end = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) { Txt(pluralStringResource(Res.plurals.stash_group_waiting, count, count), style = Hamstr.type.small, color = Hamstr.colors.muted) }
}

/** One row of a rounded group, so each row can be its own animated lazy item. */
@Composable
private fun LazyItemScope.GroupedItem(index: Int, count: Int, content: @Composable () -> Unit) {
    val first = index == 0
    val last = index == count - 1
    Box(
        Modifier
            .animateItem(
                fadeInSpec = Specs.listIn(),
                placementSpec = Specs.place,
                fadeOutSpec = Specs.listOut(),
            )
            .padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(topStart = if (first) Radii.panel else 0.dp, topEnd = if (first) Radii.panel else 0.dp, bottomStart = if (last) Radii.panel else 0.dp, bottomEnd = if (last) Radii.panel else 0.dp))
            .background(Hamstr.colors.overlay)
            .padding(start = 4.dp, end = 4.dp, top = if (first) 4.dp else 1.dp, bottom = if (last) 4.dp else 1.dp),
    ) { content() }
}

@Composable
private fun primarySwipe(t: DownloadTask): SwipeAction? {
    val c = Hamstr.colors
    return when (t.status) {
        Status.Running -> SwipeAction(Res.drawable.ic_pause, stringResource(Res.string.notif_pause), c.muted) { Downloads.control.pause(t.id) }
        Status.Paused -> SwipeAction(Res.drawable.ic_play_arrow, stringResource(Res.string.stash_resume), c.accent) { Downloads.control.resume(t.id) }
        Status.Failed -> SwipeAction(Res.drawable.ic_refresh, stringResource(Res.string.notif_retry), c.accent) { Downloads.control.resume(t.id) }
        else -> null
    }
}

@Composable
private fun cancelSwipe(t: DownloadTask, onHide: (List<DownloadTask>) -> Unit): SwipeAction {
    val c = Hamstr.colors
    return if (t.status == Status.Failed) SwipeAction(Res.drawable.ic_close, stringResource(Res.string.stash_remove), c.red, removes = true) { onHide(listOf(t)) }
    else SwipeAction(Res.drawable.ic_close, stringResource(Res.string.notif_cancel), c.red, removes = true) { Downloads.control.cancel(t.id) }
}

/** Ticks once a second while a retry counts down, so its "in 12 s" stays true. */
@Composable
private fun rememberStageText(t: DownloadTask): String? {
    val now by produceState(System.currentTimeMillis(), t.stage, t.retryAt) {
        while (t.stage == Stage.Retrying) {
            delay(1_000)
            value = System.currentTimeMillis()
        }
    }
    return stageText(t, now)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TaskRow(
    t: DownloadTask,
    highlighted: Boolean = false,
    versions: List<DownloadTask> = listOf(t),
    gone: Boolean = false,
    resume: Float? = null,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val c = Hamstr.colors
    val bitmap = rememberImage(t.thumbnail, 160)
    // Progress glides instead of jumping between yt-dlp's updates.
    val progress by animateFloatAsState(t.progress, Specs.glide(), label = "progress")
    val stage = rememberStageText(t)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radii.row))
            .background(if (highlighted) c.surface else c.overlay)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(52.dp).clip(RoundedCornerShape(Radii.thumb))) {
            AsciiImage(
                bitmap = bitmap,
                reveal = if (t.status == Status.Done) 1f else progress,
                rain = if (bitmap == null && t.status.active) 1f else 0f,
                cell = 5.dp,
                modifier = Modifier.size(52.dp).graphicsLayer { alpha = if (gone) 0.35f else 1f },
            )
            // Where it was left off.
            if (resume != null) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(resume).height(3.dp).background(c.accent))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            if (t.status.active) DecodeText(t.title, style = Hamstr.type.bodyMedium) else Txt(t.title, style = Hamstr.type.bodyMedium, maxLines = 1)
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Badge(versionsBadge(versions))
                Txt(
                    if (gone) stringResource(Res.string.file_gone) else stage ?: statusLine(t, versions),
                    style = Hamstr.type.small,
                    color = when {
                        t.status == Status.Failed || gone -> c.red
                        stage != null -> c.muted
                        else -> c.ghost
                    },
                    maxLines = 1,
                )
            }
            if (t.status == Status.Running || t.status == Status.Paused || t.status == Status.Queued) {
                Spacer(Modifier.height(7.dp))
                StageLine(progress, t)
            }
        }
        Spacer(Modifier.width(8.dp))
        if (t.status == Status.Running && t.stage == null) {
            Txt("${(t.progress * 100).toInt()}%", style = Hamstr.type.mono, modifier = Modifier.padding(end = 6.dp))
        }
        // State changes swap the icon with Slate's 220 ms scale crossfade.
        AnimatedContent(
            targetState = t.status,
            transitionSpec = { iconSwap() },
            label = "statusIcon",
        ) { status ->
            when (status) {
                Status.Running -> IconButton(Res.drawable.ic_pause, stringResource(Res.string.notif_pause), { Downloads.control.pause(t.id) }, size = 34.dp)
                Status.Queued -> IconButton(Res.drawable.ic_close, stringResource(Res.string.notif_cancel), { Downloads.control.cancel(t.id) }, size = 34.dp)
                Status.Paused -> IconButton(Res.drawable.ic_play_arrow, stringResource(Res.string.stash_resume), { Downloads.control.resume(t.id) }, size = 34.dp, tint = c.accent)
                Status.Failed -> IconButton(Res.drawable.ic_refresh, stringResource(Res.string.notif_retry), { Downloads.control.resume(t.id) }, size = 34.dp, tint = c.accent)
                Status.Done -> if (gone) Icon(Res.drawable.ic_error, tint = c.red, size = 20.dp, modifier = Modifier.padding(7.dp)) else DoneCheck()
                Status.Canceled -> Spacer(Modifier.size(34.dp))
            }
        }
    }
}

/**
 * The progress line, which also says what's going on: full and breathing while FFmpeg works
 * on the file, grey while it waits for the network or a retry.
 */
@Composable
private fun StageLine(progress: Float, t: DownloadTask) {
    val c = Hamstr.colors
    val stage = t.stage
    val working = stage != null && !stage.waiting && stage != Stage.Resolving
    // A slow pulse at 20 steps a second, not every display frame; still in the background.
    val clock by rememberAmbientClock(20, enabled = working)
    val pulse = if (working && clock > 0) 0.45f + 0.55f * (0.5f - 0.5f * cos(clock * 2 * PI.toFloat() / 1400f)) else 1f
    ProgressLine(
        if (working) 1f else progress,
        modifier = Modifier.graphicsLayer { alpha = pulse },
        color = if (t.status == Status.Paused || stage?.waiting == true) c.ghost else c.accent,
    )
}

/** The check pops in with a little overshoot. */
@Composable
private fun DoneCheck() {
    val reduce = LocalReduceMotion.current
    val pop = remember { Animatable(if (reduce) 1f else 0f) }
    if (!reduce) LaunchedEffect(Unit) { pop.animateTo(1f, Specs.pop()) }
    Box(Modifier.size(34.dp).graphicsLayer { scaleX = pop.value; scaleY = pop.value; rotationZ = (1f - pop.value) * -40f }, contentAlignment = Alignment.Center) {
        Icon(Res.drawable.ic_check, tint = Hamstr.colors.green, size = 20.dp)
    }
}

@Composable
private fun EmptyState() {
    val c = Hamstr.colors
    val mark = rememberMarkState()
    // A slow breath while it waits for food (one of its moods, so off under reduced motion),
    // at 30 steps a second and still while the window is in the background.
    val clock by rememberAmbientClock(30, enabled = LocalFx.current.moods)
    Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        HamstrMark(
            96.dp,
            state = mark,
            background = false,
            modifier = Modifier.graphicsLayer {
                val s = 1f + (0.5f - 0.5f * cos(clock * 2 * PI.toFloat() / 3600f)) * 0.025f
                scaleX = s; scaleY = s
            },
        )
        Spacer(Modifier.height(16.dp))
        Txt(stringResource(Res.string.stash_empty_title), style = Hamstr.type.section)
        Spacer(Modifier.height(6.dp))
        Txt(stringResource(if (Platform.desktop) Res.string.stash_empty_text_desktop else Res.string.stash_empty_text), color = c.muted)
    }
}

/** All qualities of a link in one badge, best first: [ 1080P · 480P ]. */
fun versionsBadge(versions: List<DownloadTask>) =
    versions.sortedWith(compareByDescending<DownloadTask> { it.choice.mode != Mode.Audio }.thenByDescending { it.videoHeight })
        .map(::formatBadge).distinct().joinToString(" · ")

fun formatBadge(t: DownloadTask): String {
    val file = t.filePath?.let(::File)
    if (file != null && kindOf(t).let { it == Kind.Other || it == Kind.Image }) {
        return file.extension.ifBlank { "?" }
    }
    return when (t.choice.mode) {
        Mode.Audio -> t.choice.audioFormat
        Mode.Video -> heightLabel(t.videoHeight.takeIf { it > 0 } ?: t.choice.height)
        Mode.Custom -> "custom"
    }
}

/**
 * A download, big: the thumbnail resolves out of falling characters as the file fills up,
 * with the percentage on top. Shown when a row that is still downloading is tapped.
 */
@Composable
fun DownloadPreview(t: DownloadTask, modifier: Modifier = Modifier) {
    val c = Hamstr.colors
    val bitmap = rememberImage(t.thumbnail, 480)
    val progress by animateFloatAsState(t.progress, Specs.glide(), label = "previewProgress")
    val moving = t.status == Status.Running || t.status == Status.Queued
    val stage = rememberStageText(t)
    Box(modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(14.dp))) {
        AsciiImage(
            bitmap = bitmap,
            reveal = progress,
            // Matrix columns keep running through the part that isn't there yet.
            rain = when {
                bitmap == null -> if (moving) 1f else 0.6f
                moving -> 0.3f
                else -> 0f
            },
            animate = moving,
            cell = 7.dp,
            modifier = Modifier.matchParentSize(),
        )
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.4f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.78f))))
        PictureLabel(
            stringResource(
                when (t.status) {
                    Status.Queued -> Res.string.status_queued
                    Status.Running -> Res.string.status_downloading
                    Status.Paused -> Res.string.status_paused_short
                    Status.Failed -> Res.string.status_failed
                    else -> Res.string.status_canceled
                },
            ),
            Modifier.align(Alignment.TopEnd).padding(10.dp),
            accent = t.status == Status.Running,
            badge = true,
        )
        Column(Modifier.align(Alignment.BottomStart).padding(horizontal = 16.dp, vertical = 12.dp)) {
            Txt(
                "${(progress * 100).toInt()}%",
                style = Hamstr.type.mono.copy(fontSize = 40.sp, lineHeight = 44.sp, fontWeight = FontWeight.SemiBold),
                color = if (t.status == Status.Failed) c.red else Color.White,
            )
            Txt(stage ?: statusLine(t), style = Hamstr.type.small, color = Color.White.copy(alpha = 0.75f), maxLines = 1)
        }
    }
}
