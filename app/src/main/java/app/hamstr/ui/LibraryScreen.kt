package app.hamstr.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.expandVertically
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.togetherWith
import androidx.compose.ui.Alignment
import app.hamstr.R
import app.hamstr.ui.components.IconButton
import app.hamstr.ui.components.SearchField
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.gestures.Orientation
import app.hamstr.ui.components.edgeFade
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.hamstr.data.DownloadFiles
import app.hamstr.data.DownloadTask
import app.hamstr.data.LibrarySort
import app.hamstr.data.Positions
import app.hamstr.data.Prefs
import app.hamstr.data.collapseVersions
import app.hamstr.data.Mode
import app.hamstr.data.Status
import app.hamstr.ui.components.AsciiImage
import app.hamstr.ui.components.Badge
import app.hamstr.ui.components.Icon
import app.hamstr.ui.components.Segmented
import app.hamstr.ui.components.Txt
import app.hamstr.ui.theme.Hamstr

private enum class Filter(val label: Int) { All(R.string.lib_all), Video(R.string.mode_video), Audio(R.string.mode_audio) }

private fun LibrarySort.label() = when (this) {
    LibrarySort.Newest -> R.string.lib_sort_newest
    LibrarySort.Oldest -> R.string.lib_sort_oldest
    LibrarySort.Name -> R.string.lib_sort_name
    LibrarySort.Size -> R.string.lib_sort_size
}

/**
 * Everything stashed, as a grid. Search covers titles, channels and sites; the list can be
 * sorted and narrowed to one site. A long press starts selecting, to share, hide or delete
 * several files at once.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    tasks: List<DownloadTask>,
    contentPadding: PaddingValues,
    onMenu: (DownloadTask) -> Unit,
    onPlay: (DownloadTask, List<DownloadTask>) -> Unit,
    onGroup: (String) -> Unit,
    onHide: (List<DownloadTask>) -> Unit,
    onDelete: (List<DownloadTask>) -> Unit,
    onShare: (List<DownloadTask>) -> Unit,
) {
    val c = Hamstr.colors
    val haptics = LocalHapticFeedback.current
    val settings by Prefs.settings.collectAsState()
    val sort = settings.librarySort
    var filter by rememberSaveable { mutableStateOf(Filter.All) }
    var site by rememberSaveable { mutableStateOf<String?>(null) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var sorting by remember { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf(emptySet<String>()) }
    val selecting = selected.isNotEmpty()
    BackHandler(enabled = selecting) { selected = emptySet() }
    val missing by DownloadFiles.missing.collectAsState()
    val positions by Positions.all.collectAsState()
    val q = query.trim().lowercase()
    val completed = remember(tasks) { tasks.filter { it.status == Status.Done } }
    val sites = remember(completed) { completed.mapNotNull { it.source }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key } }
    // Active download progress shouldn't rebuild the completed library's groups.
    val done = remember(completed, q, filter, site, sort) {
        completed.filter {
            q.isEmpty() || listOfNotNull(it.title, it.source, it.groupTitle, it.uploader).any { s -> s.contains(q, ignoreCase = true) }
        }.filter {
            when (filter) {
                Filter.All -> true
                Filter.Video -> it.choice.mode != Mode.Audio
                Filter.Audio -> it.choice.mode == Mode.Audio
            }
        }.filter { site == null || it.source == site }
            .let { list -> collapseVersions(list).map { it.first } }
            .let { list ->
                when (sort) {
                    LibrarySort.Newest -> list
                    LibrarySort.Oldest -> list.reversed()
                    LibrarySort.Name -> list.sortedBy { it.title.lowercase() }
                    LibrarySort.Size -> list.sortedByDescending { it.sizeBytes }
                }
            }
    }
    val tiles = remember(done) { shelves(done) }
    val versions = remember(completed) { completed.groupBy { it.family } }
    // A selected tile stands for all its qualities, or all files of its playlist.
    fun filesOf(ids: Set<String>) = completed.filter { t -> t.family in ids || t.group in ids || t.id in ids }
    fun toggle(key: String) {
        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
        selected = if (key in selected) selected - key else selected + key
    }
    val gridState = rememberLazyGridState()
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        state = gridState,
        modifier = Modifier.fillMaxSize().edgeFade(gridState, Orientation.Vertical, insets = contentPadding),
        contentPadding = PaddingValues(
            start = 12.dp, end = 12.dp,
            top = contentPadding.calculateTopPadding(),
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(span = { GridItemSpan(2) }) {
            Column(Modifier.padding(horizontal = 6.dp, vertical = 6.dp)) {
                AnimatedContent(selecting, transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(120)) }, label = "libraryBar") { sel ->
                    if (sel) {
                        val files = filesOf(selected)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            IconButton(R.drawable.ic_close, stringResource(R.string.lib_select_done), { selected = emptySet() }, tint = c.ink)
                            Txt(stringResource(R.string.lib_selected, files.size), style = Hamstr.type.section, modifier = Modifier.weight(1f).padding(start = 4.dp))
                            IconButton(R.drawable.ic_select_all, stringResource(R.string.lib_select_all), {
                                selected = tiles.map { if (it is Shelf.Group) it.id else (it as Shelf.One).task.family }.toSet()
                            })
                            IconButton(R.drawable.ic_share, stringResource(R.string.menu_share_file), { onShare(files) })
                            IconButton(R.drawable.ic_visibility_off, stringResource(R.string.menu_hide), { onHide(files); selected = emptySet() })
                            IconButton(R.drawable.ic_delete, stringResource(R.string.menu_delete), { onDelete(files); selected = emptySet() }, tint = c.red)
                        }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Txt(stringResource(R.string.tab_library), style = Hamstr.type.title, modifier = Modifier.weight(1f))
                            IconButton(R.drawable.ic_sort, stringResource(R.string.lib_sort), { sorting = !sorting }, tint = if (sorting || sort != LibrarySort.Newest) c.accent else c.ink)
                            Spacer(Modifier.width(8.dp))
                            IconButton(
                                if (searching) R.drawable.ic_close else R.drawable.ic_search,
                                stringResource(if (searching) R.string.lib_close_search else R.string.lib_search),
                                { searching = !searching; if (!searching) query = "" },
                                tint = c.ink,
                            )
                        }
                    }
                }
                AnimatedVisibility(searching && !selecting, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                    SearchField(query, { query = it }, stringResource(R.string.lib_search_hint), Modifier.padding(top = 12.dp), autoFocus = true)
                }
                AnimatedVisibility(sorting && !selecting, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                    Column(Modifier.padding(top = 12.dp)) {
                        Txt(stringResource(R.string.lib_sort), style = Hamstr.type.badge, color = c.ghost, modifier = Modifier.padding(start = 2.dp, bottom = 6.dp))
                        val labels = LibrarySort.entries.associateWith { stringResource(it.label()) }
                        Chips(LibrarySort.entries.toList(), sort, { labels.getValue(it) }) { s -> Prefs.edit { it.copy(librarySort = s) } }
                    }
                }
                Spacer(Modifier.height(14.dp))
                val filterLabels = Filter.entries.associateWith { stringResource(it.label) }
                Segmented(Filter.entries.toList(), filter, { filterLabels.getValue(it) }, { filter = it }, background = c.overlay)
                // One chip per site once there is more than one.
                if (sites.size > 1) {
                    Spacer(Modifier.height(10.dp))
                    val scroll = rememberScrollState()
                    Row(Modifier.edgeFade(scroll, Orientation.Horizontal).horizontalScroll(scroll), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        sites.forEach { s ->
                            val on = s == site
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (on) c.accent else c.overlay)
                                    .clickable { site = if (on) null else s }
                                    .heightIn(min = 36.dp)
                                    .padding(horizontal = 12.dp),
                                contentAlignment = Alignment.Center,
                            ) { Txt(s, style = Hamstr.type.small, color = if (on) c.onAccent else c.muted) }
                        }
                    }
                }
            }
        }
        if (done.isEmpty()) {
            item(span = { GridItemSpan(2) }) {
                Txt(
                    if (q.isNotEmpty()) stringResource(R.string.lib_no_match, query.trim()) else stringResource(R.string.lib_empty),
                    color = c.muted, modifier = Modifier.padding(6.dp),
                )
            }
        }
        items(tiles, key = { it.key }) { shelf ->
            val anim = Modifier.animateItem(fadeInSpec = tween(320), placementSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow, visibilityThreshold = IntOffset(1, 1)), fadeOutSpec = tween(160))
            if (shelf is Shelf.Group) {
                val on = shelf.id in selected
                Column(
                    anim
                        .clip(RoundedCornerShape(14.dp))
                        .combinedClickable(
                            onClick = { if (selecting) toggle(shelf.id) else onGroup(shelf.id) },
                            onLongClick = { toggle(shelf.id) },
                        ),
                ) {
                    Box {
                        PlaylistFan(shelf.items, Modifier.fillMaxWidth())
                        SelectMark(selecting, on, Modifier.align(Alignment.TopStart))
                    }
                    Spacer(Modifier.height(7.dp))
                    Txt(shelf.title.ifEmpty { stringResource(R.string.lib_playlist) }, style = Hamstr.type.bodyMedium, maxLines = 2, modifier = Modifier.padding(horizontal = 2.dp))
                    Spacer(Modifier.height(3.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(horizontal = 2.dp)) {
                        Badge(stringResource(R.string.lib_files, shelf.items.size), accent = true)
                        Badge(formatBytes(shelf.items.sumOf { it.sizeBytes }))
                    }
                }
                return@items
            }
            val t = (shelf as Shelf.One).task
            val gone = t.id in missing
            val on = t.family in selected
            Column(
                anim
                    .clip(RoundedCornerShape(14.dp))
                    .combinedClickable(
                        onClick = { if (selecting) toggle(t.family) else if (gone) onMenu(t) else onPlay(t, done) },
                        onLongClick = { toggle(t.family) },
                    ),
            ) {
                Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(14.dp)).background(c.black)) {
                    AsciiImage(
                        bitmap = rememberBitmap(t.thumbnail, 360),
                        reveal = 1f,
                        modifier = Modifier.fillMaxSize().graphicsLayer { alpha = if (gone) 0.35f else 1f },
                    )
                    if (gone) {
                        Badge(stringResource(R.string.lib_gone), modifier = Modifier.align(Alignment.Center).clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 8.dp, vertical = 4.dp))
                    } else if (t.durationSec > 0) {
                        Txt(
                            formatDuration(t.durationSec), style = Hamstr.type.badge, color = Color.White,
                            modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.65f)).padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                    positions[t.id]?.let { r -> Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(r.fraction).height(3.dp).background(c.accent)) }
                    SelectMark(selecting, on, Modifier.align(Alignment.TopStart))
                }
                Spacer(Modifier.height(7.dp))
                Row(verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        Txt(t.title, style = Hamstr.type.bodyMedium, maxLines = 2, modifier = Modifier.padding(horizontal = 2.dp))
                        (t.uploader ?: versions[t.family]?.firstNotNullOfOrNull { it.uploader })?.let { Txt(it, style = Hamstr.type.small, color = c.ghost, maxLines = 1, modifier = Modifier.padding(horizontal = 2.dp, vertical = 1.dp)) }
                    }
                    if (!selecting) {
                        // The file menu, also reachable with a long press on the player.
                        Box(
                            Modifier.size(40.dp, 44.dp).clip(RoundedCornerShape(10.dp)).clickable { onMenu(t) },
                            contentAlignment = Alignment.TopCenter,
                        ) { Icon(R.drawable.ic_more_vert, tint = c.ghost, size = 18.dp, modifier = Modifier.padding(top = 2.dp)) }
                    }
                }
                Spacer(Modifier.height(3.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(horizontal = 2.dp)) {
                    // Under Video or Audio, a tile only counts the files of that kind.
                    val all = versions[t.family].orEmpty().filter { filter == Filter.All || (it.choice.mode == Mode.Audio) == (filter == Filter.Audio) }.ifEmpty { listOf(t) }
                    Badge(versionsBadge(all))
                    all.sumOf { it.sizeBytes }.takeIf { it > 0 }?.let { Badge(formatBytes(it)) }
                }
            }
        }
    }
}

/** The tick in a tile's corner while selecting. */
@Composable
private fun SelectMark(selecting: Boolean, on: Boolean, modifier: Modifier = Modifier) {
    val c = Hamstr.colors
    val shown by animateFloatAsState(if (selecting) 1f else 0f, tween(160), label = "selectMark")
    if (shown <= 0f) return
    Box(
        modifier
            .padding(8.dp)
            .graphicsLayer { alpha = shown; scaleX = 0.6f + 0.4f * shown; scaleY = 0.6f + 0.4f * shown }
            .size(24.dp)
            .clip(CircleShape)
            .background(if (on) c.accent else Color.Black.copy(alpha = 0.45f))
            .border(2.dp, if (on) c.accent else Color.White.copy(alpha = 0.8f), CircleShape),
        contentAlignment = Alignment.Center,
    ) { if (on) Icon(R.drawable.ic_check, tint = c.onAccent, size = 16.dp) }
}
