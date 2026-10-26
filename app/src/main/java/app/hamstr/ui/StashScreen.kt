package app.hamstr.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import app.hamstr.R
import app.hamstr.data.DownloadStore
import app.hamstr.data.DownloadTask
import app.hamstr.data.Mode
import app.hamstr.data.Status
import app.hamstr.data.heightLabel
import app.hamstr.engine.DownloadService
import app.hamstr.engine.EngineState
import app.hamstr.ui.components.AsciiImage
import app.hamstr.ui.components.Badge
import app.hamstr.ui.components.DecodeText
import app.hamstr.ui.components.HamstrMark
import app.hamstr.ui.components.Icon
import app.hamstr.ui.components.IconButton
import app.hamstr.ui.components.ProgressLine
import app.hamstr.ui.components.SectionHeader
import app.hamstr.ui.components.SwipeAction
import app.hamstr.ui.components.SwipeRow
import app.hamstr.ui.components.Txt
import app.hamstr.ui.components.rememberMarkState
import app.hamstr.ui.theme.Hamstr
import app.hamstr.ui.theme.LocalFx
import app.hamstr.ui.theme.Radii
import kotlinx.coroutines.launch

@Composable
fun StashScreen(
    tasks: List<DownloadTask>,
    engine: EngineState,
    contentPadding: PaddingValues,
    onAdd: () -> Unit,
    onMenu: (DownloadTask) -> Unit,
    onPlay: (DownloadTask) -> Unit,
) {
    val c = Hamstr.colors
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val active = tasks.filter { it.status != Status.Done && it.status != Status.Canceled }
    val done = tasks.filter { it.status == Status.Done }
    val totalBytes = done.sumOf { it.sizeBytes }
    val running = tasks.count { it.status.active }
    val mark = rememberMarkState()
    val moods = LocalFx.current.moods

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
                animate(pull, 0f, animationSpec = spring(dampingRatio = 0.55f, stiffness = 380f)) { v, _ -> pull = v }
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
                .padding(top = contentPadding.calculateTopPadding() - 4.dp)
                .graphicsLayer { alpha = (pull / threshold).coerceIn(0f, 1f) },
            contentAlignment = Alignment.TopCenter,
        ) {
            Txt(
                if (armed) "Release to stash the copied link" else "Pull to stash from clipboard",
                style = Hamstr.type.badge,
                color = if (armed) c.accent else c.ghost,
            )
        }
        LazyColumn(
            Modifier.fillMaxSize().graphicsLayer { translationY = pull },
            contentPadding = contentPadding,
        ) {
            item(key = "header") {
                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    HamstrMark(34.dp, state = mark)
                    Spacer(Modifier.width(10.dp))
                    DecodeText("Hamstr", style = Hamstr.type.title, modifier = Modifier.weight(1f))
                    IconButton(R.drawable.ic_add, "Link from clipboard", onAdd, tint = c.ink)
                }
            }
            item(key = "hero") {
                Column(Modifier.padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 6.dp)) {
                    DecodeText(formatBytes(totalBytes), style = Hamstr.type.hero, delayMs = 120)
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Txt("${done.size} ${if (done.size == 1) "file" else "files"} stashed", color = c.muted)
                        if (running > 0) Badge("$running active", accent = true)
                        when (engine) {
                            EngineState.Starting -> Badge("yt-dlp starting")
                            is EngineState.Broken -> Badge("yt-dlp broken", accent = true)
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
                    SectionHeader("Downloading", trailing = "${active.count { it.status == Status.Running }} running", modifier = Modifier.padding(horizontal = 12.dp).animateItem())
                }
                itemsIndexed(active, key = { _, t -> t.id }) { i, t ->
                    GroupedItem(i, active.size) {
                        SwipeRow(start = primarySwipe(t), end = cancelSwipe(t)) {
                            TaskRow(t, highlighted = t.status == Status.Running, onClick = { onMenu(t) }, onLongClick = { onMenu(t) })
                        }
                    }
                }
            }
            if (done.isNotEmpty()) {
                item(key = "h_done") {
                    SectionHeader("Stashed", trailing = "${done.size}", modifier = Modifier.padding(horizontal = 12.dp).animateItem())
                }
                itemsIndexed(done.take(60), key = { _, t -> t.id }) { i, t ->
                    GroupedItem(i, minOf(done.size, 60)) {
                        SwipeRow(
                            start = SwipeAction(R.drawable.ic_play_arrow, "Play", c.accent) { onPlay(t) },
                            end = SwipeAction(R.drawable.ic_close, "Remove", c.red, removes = true) { DownloadStore.remove(t.id) },
                        ) {
                            TaskRow(t, onClick = { onPlay(t) }, onLongClick = { onMenu(t) })
                        }
                    }
                }
            }
            item(key = "end") { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/** One row of a rounded group, so each row can be its own animated lazy item. */
@Composable
private fun LazyItemScope.GroupedItem(index: Int, count: Int, content: @Composable () -> Unit) {
    val first = index == 0
    val last = index == count - 1
    Box(
        Modifier
            .animateItem(
                fadeInSpec = tween(320),
                placementSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow, visibilityThreshold = IntOffset(1, 1)),
                fadeOutSpec = tween(160),
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
    val context = LocalContext.current
    return when (t.status) {
        Status.Running -> SwipeAction(R.drawable.ic_pause, "Pause", c.muted) { DownloadService.send(context, DownloadService.ACTION_PAUSE, t.id) }
        Status.Paused -> SwipeAction(R.drawable.ic_play_arrow, "Resume", c.accent) { DownloadService.send(context, DownloadService.ACTION_RESUME, t.id) }
        Status.Failed -> SwipeAction(R.drawable.ic_refresh, "Retry", c.accent) { DownloadService.send(context, DownloadService.ACTION_RESUME, t.id) }
        else -> null
    }
}

@Composable
private fun cancelSwipe(t: DownloadTask): SwipeAction {
    val c = Hamstr.colors
    val context = LocalContext.current
    return if (t.status == Status.Failed) SwipeAction(R.drawable.ic_close, "Remove", c.red, removes = true) { DownloadStore.remove(t.id) }
    else SwipeAction(R.drawable.ic_close, "Cancel", c.red, removes = true) { DownloadService.send(context, DownloadService.ACTION_CANCEL, t.id) }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TaskRow(t: DownloadTask, highlighted: Boolean = false, onClick: () -> Unit, onLongClick: () -> Unit) {
    val c = Hamstr.colors
    val context = LocalContext.current
    val bitmap = rememberBitmap(t.thumbnail, 160)
    // Progress glides instead of jumping between yt-dlp's updates.
    val progress by animateFloatAsState(t.progress, spring(stiffness = Spring.StiffnessLow), label = "progress")
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radii.row))
            .background(if (highlighted) c.surface else c.overlay)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsciiImage(
            bitmap = bitmap,
            reveal = if (t.status == Status.Done) 1f else progress,
            rain = if (bitmap == null && t.status.active) 1f else 0f,
            cell = 5.dp,
            modifier = Modifier.size(52.dp).clip(RoundedCornerShape(Radii.thumb)),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            if (t.status.active) DecodeText(t.title, style = Hamstr.type.bodyMedium) else Txt(t.title, style = Hamstr.type.bodyMedium, maxLines = 1)
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Badge(formatBadge(t))
                Txt(statusLine(t), style = Hamstr.type.small, color = if (t.status == Status.Failed) c.red else c.ghost, maxLines = 1)
            }
            if (t.status == Status.Running || t.status == Status.Paused || t.status == Status.Queued) {
                Spacer(Modifier.height(7.dp))
                ProgressLine(progress, color = if (t.status == Status.Paused) c.ghost else c.accent)
            }
        }
        Spacer(Modifier.width(8.dp))
        if (t.status == Status.Running) {
            Txt("${(t.progress * 100).toInt()}%", style = Hamstr.type.mono, modifier = Modifier.padding(end = 6.dp))
        }
        // State changes swap the icon with Slate's 220 ms scale crossfade.
        AnimatedContent(
            targetState = t.status,
            transitionSpec = {
                (scaleIn(spring(dampingRatio = 0.5f, stiffness = 600f), initialScale = 0.4f) + fadeIn(tween(160))) togetherWith
                    (scaleOut(tween(150), targetScale = 0.4f) + fadeOut(tween(120)))
            },
            label = "statusIcon",
        ) { status ->
            when (status) {
                Status.Running -> IconButton(R.drawable.ic_pause, "Pause", { DownloadService.send(context, DownloadService.ACTION_PAUSE, t.id) }, size = 34.dp)
                Status.Queued -> IconButton(R.drawable.ic_close, "Cancel", { DownloadService.send(context, DownloadService.ACTION_CANCEL, t.id) }, size = 34.dp)
                Status.Paused -> IconButton(R.drawable.ic_play_arrow, "Resume", { DownloadService.send(context, DownloadService.ACTION_RESUME, t.id) }, size = 34.dp, tint = c.accent)
                Status.Failed -> IconButton(R.drawable.ic_refresh, "Retry", { DownloadService.send(context, DownloadService.ACTION_RESUME, t.id) }, size = 34.dp, tint = c.accent)
                Status.Done -> DoneCheck()
                Status.Canceled -> Spacer(Modifier.size(34.dp))
            }
        }
    }
}

/** The check pops in with a little overshoot. */
@Composable
private fun DoneCheck() {
    val pop = remember { Animatable(0f) }
    LaunchedEffect(Unit) { pop.animateTo(1f, spring(dampingRatio = 0.38f, stiffness = 520f)) }
    Box(Modifier.size(34.dp).graphicsLayer { scaleX = pop.value; scaleY = pop.value; rotationZ = (1f - pop.value) * -40f }, contentAlignment = Alignment.Center) {
        Icon(R.drawable.ic_check, tint = Hamstr.colors.green, size = 20.dp)
    }
}

@Composable
private fun EmptyState() {
    val c = Hamstr.colors
    val mark = rememberMarkState()
    // A slow breath while it waits for food.
    val breath = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            breath.animateTo(1f, tween(1800))
            breath.animateTo(0f, tween(1800))
        }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        HamstrMark(
            96.dp,
            state = mark,
            background = false,
            modifier = Modifier.graphicsLayer {
                val s = 1f + breath.value * 0.025f
                scaleX = s; scaleY = s
            },
        )
        Spacer(Modifier.height(16.dp))
        Txt("The cheeks are still empty", style = Hamstr.type.section)
        Spacer(Modifier.height(6.dp))
        Txt("Share a link from YouTube, Instagram, TikTok, SoundCloud … to Hamstr, copy it and tap +, or pull this screen down.", color = c.muted)
    }
}

fun formatBadge(t: DownloadTask) = when (t.choice.mode) {
    Mode.Audio -> t.choice.audioFormat
    Mode.Video -> heightLabel(t.choice.height)
    Mode.Custom -> "custom"
}

private fun statusLine(t: DownloadTask): String = when (t.status) {
    Status.Queued -> if (t.progress > 0f) "queued · ${(t.progress * 100).toInt()} %" else "queued"
    Status.Running -> listOf(t.speed, DownloadService.eta(t.etaSec)).filter { it.isNotEmpty() }.joinToString(" · ").ifEmpty { t.source ?: "loading" }
    Status.Paused -> "paused · ${(t.progress * 100).toInt()} %"
    Status.Failed -> t.error ?: "failed"
    Status.Done -> listOfNotNull(t.source, formatBytes(t.sizeBytes).takeIf { t.sizeBytes > 0 }, ago(t.finishedAt)).joinToString(" · ")
    Status.Canceled -> "canceled"
}

private fun ago(ts: Long): String {
    if (ts <= 0) return ""
    val m = (System.currentTimeMillis() - ts) / 60_000
    return when {
        m < 1 -> "just now"
        m < 60 -> "$m min ago"
        m < 60 * 24 -> "${m / 60} h ago"
        else -> "${m / 60 / 24} d ago"
    }
}
