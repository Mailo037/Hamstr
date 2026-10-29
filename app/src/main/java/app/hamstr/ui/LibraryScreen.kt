package app.hamstr.ui

import androidx.compose.foundation.combinedClickable
import app.hamstr.ui.components.onRightClick
import app.hamstr.res.*
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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import app.hamstr.data.Playlists
import app.hamstr.player.Kind
import app.hamstr.player.Playback
import app.hamstr.player.kindOf
import app.hamstr.data.Status
import app.hamstr.ui.components.AsciiImage
import app.hamstr.ui.components.Icon
import app.hamstr.ui.components.Segmented
import app.hamstr.ui.components.Txt
import app.hamstr.ui.theme.Hamstr
import androidx.compose.ui.graphics.lerp
import app.hamstr.ui.components.animateSelection
import app.hamstr.ui.components.Chip
import app.hamstr.ui.components.ChipRow
import app.hamstr.ui.components.Chips
import app.hamstr.ui.components.PictureLabel
import app.hamstr.ui.components.pressable
import app.hamstr.ui.components.Specs
import app.hamstr.ui.components.SwipeAction
import app.hamstr.data.PlaylistPlacement
import app.hamstr.ui.theme.Radii
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.ui.res.pluralStringResource
import app.hamstr.ui.components.SwipeRow

private enum class Filter(val label: Int) { All(R.string.lib_all), Video(R.string.mode_video), Audio(R.string.mode_audio) }

/** Video and Audio go by what the file is, so a stashed installer or picture only shows under All. */
private fun Filter.takes(t: DownloadTask) = when (this) {
    Filter.All -> true
    Filter.Video -> kindOf(t) == Kind.Video
    Filter.Audio -> kindOf(t) == Kind.Audio
}

private fun PlaylistPlacement.label() = when (this) {
    PlaylistPlacement.Row -> R.string.lib_pl_row
    PlaylistPlacement.Grid -> R.string.lib_pl_grid
    PlaylistPlacement.Hidden -> R.string.lib_pl_hidden
}

private fun LibrarySort.label() = when (this) {
    LibrarySort.Newest -> R.string.lib_sort_newest
    LibrarySort.Oldest -> R.string.lib_sort_oldest
    LibrarySort.Name -> R.string.lib_sort_name
    LibrarySort.Size -> R.string.lib_sort_size
}

/**
 * Everything stashed: playlists in a row on top, then the files as a grid, or under Audio
 * as a list of songs. Search covers titles, channels and sites; the list can be sorted and
 * narrowed to one site. A long press starts selecting, to share, hide, delete or put
 * several files into a playlist at once. In the songs list, swiped right a file plays
 * next, swiped left it goes into Favorites (or out again); both are in its menu too.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    tasks: List<DownloadTask>,
    contentPadding: PaddingValues,
    onMenu: (DownloadTask) -> Unit,
    onPlay: (DownloadTask, List<DownloadTask>) -> Unit,
    onPlayList: (DownloadTask, List<DownloadTask>) -> Unit,
    onPlayNext: (DownloadTask) -> Unit,
    onFavorite: (DownloadTask) -> Unit,
    onPlaylist: (String) -> Unit,
    /** Holding a playlist: its quick actions. */
    onPlaylistMenu: (String) -> Unit,
    onNewPlaylist: () -> Unit,
    onAddToPlaylist: (List<DownloadTask>) -> Unit,
    onHide: (List<DownloadTask>) -> Unit,
    onDelete: (List<DownloadTask>) -> Unit,
    onShare: (List<DownloadTask>) -> Unit,
) {
    val c = Hamstr.colors
    val haptics = LocalHapticFeedback.current
    val settings by Prefs.settings.collectAsState()
    val sort = settings.librarySort
    val placement = settings.libraryPlaylists
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
        }.filter { filter.takes(it) }.filter { site == null || it.source == site }
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
    // Files of a downloaded playlist live in its playlist; with playlists hidden, every file shows.
    val tiles = remember(done, placement) {
        if (placement == PlaylistPlacement.Hidden) done.map { Shelf.One(it) } else shelves(done).filterIsInstance<Shelf.One>()
    }
    val songs = filter == Filter.Audio
    val lists by Playlists.lists.collectAsState()
    val playlists = remember(completed, lists, filter, q) {
        Playlists.all(completed, lists).filter { p ->
            val kind = p.items.isEmpty() || p.items.any { filter.takes(it) }
            kind && (q.isEmpty() || p.name.contains(q, ignoreCase = true) || p.items.any { it.title.contains(q, ignoreCase = true) })
        }
    }
    val playing by Playback.current.collectAsState()
    val favorites = remember(lists) { lists.firstOrNull { "list:" + it.id == Playlists.FAVORITES }?.ids?.toSet().orEmpty() }
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
                AnimatedContent(selecting, transitionSpec = { fadeIn(Specs.appear()) togetherWith fadeOut(Specs.disappear()) }, label = "libraryBar") { sel ->
                    if (sel) {
                        val files = filesOf(selected)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            IconButton(Res.drawable.ic_close, stringResource(R.string.lib_select_done), { selected = emptySet() }, tint = c.ink)
                            Txt(stringResource(R.string.lib_selected, files.size), style = Hamstr.type.small, color = c.muted, maxLines = 1, modifier = Modifier.weight(1f).padding(start = 6.dp))
                            IconButton(Res.drawable.ic_select_all, stringResource(R.string.lib_select_all), {
                                selected = (if (songs) done else tiles.map { it.task }).map { it.family }.toSet()
                            })
                            IconButton(Res.drawable.ic_playlist_add, stringResource(R.string.pl_add), {
                                // One entry per video: its other qualities would only play it twice.
                                onAddToPlaylist(files.groupBy { it.family }.map { (_, v) -> v.firstOrNull { it.versionOf == null } ?: v.first() })
                                selected = emptySet()
                            })
                            IconButton(Res.drawable.ic_share, stringResource(R.string.menu_share_file), { onShare(files) })
                            IconButton(Res.drawable.ic_visibility_off, stringResource(R.string.menu_hide), { onHide(files); selected = emptySet() })
                            IconButton(Res.drawable.ic_delete, stringResource(R.string.menu_delete), { onDelete(files); selected = emptySet() }, tint = c.red)
                        }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Txt(stringResource(R.string.tab_library), style = Hamstr.type.title, modifier = Modifier.weight(1f))
                            // Playlists among the files have no tile of their own to start one, so it sits up here.
                            if (placement == PlaylistPlacement.Grid) IconButton(Res.drawable.ic_playlist_add, stringResource(R.string.pl_new), onNewPlaylist, tint = c.ink)
                            IconButton(Res.drawable.ic_sort, stringResource(R.string.lib_sort), { sorting = !sorting }, tint = if (sorting || sort != LibrarySort.Newest) c.accent else c.ink)
                            IconButton(
                                if (searching) Res.drawable.ic_close else Res.drawable.ic_search,
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
                        Txt(stringResource(R.string.lib_sort), style = Hamstr.type.badge, color = c.ghost, modifier = Modifier.padding(start = 2.dp))
                        val labels = LibrarySort.entries.associateWith { stringResource(it.label()) }
                        Chips(LibrarySort.entries.toList(), sort, { labels.getValue(it) }, background = c.overlay) { s -> Prefs.edit { it.copy(librarySort = s) } }
                        Spacer(Modifier.height(6.dp))
                        Txt(stringResource(R.string.lib_playlists), style = Hamstr.type.badge, color = c.ghost, modifier = Modifier.padding(start = 2.dp))
                        val places = PlaylistPlacement.entries.associateWith { stringResource(it.label()) }
                        Chips(PlaylistPlacement.entries.toList(), placement, { places.getValue(it) }, background = c.overlay) { v -> Prefs.edit { it.copy(libraryPlaylists = v) } }
                    }
                }
                Spacer(Modifier.height(14.dp))
                val filterLabels = Filter.entries.associateWith { stringResource(it.label) }
                Segmented(Filter.entries.toList(), filter, { filterLabels.getValue(it) }, { filter = it }, background = c.overlay)
                // One chip per site once there is more than one.
                if (sites.size > 1) {
                    Spacer(Modifier.height(4.dp))
                    ChipRow {
                        sites.forEach { s ->
                            val on = s == site
                            // Tapping the chosen site again shows every site.
                            Chip(s, on, { site = if (on) null else s }, background = c.overlay)
                        }
                    }
                }
                // Stays while selecting, so the grid doesn't jump under the finger.
                if (completed.isNotEmpty() && placement == PlaylistPlacement.Row) {
                    Spacer(Modifier.height(if (sites.size > 1) 12.dp else 18.dp))
                    Txt(stringResource(R.string.lib_playlists), style = Hamstr.type.badge, color = c.ghost, modifier = Modifier.padding(start = 2.dp, bottom = 8.dp))
                    val scroll = rememberScrollState()
                    // Square song lists and wide video lists stand on one line, names level.
                    Row(
                        Modifier.edgeFade(scroll, Orientation.Horizontal).horizontalScroll(scroll),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        playlists.forEach { p -> app.hamstr.ui.PlaylistTile(p, 148.dp, { onPlaylist(p.id) }, square = p.songs || (songs && p.items.isNotEmpty()), onLongClick = { onPlaylistMenu(p.id) }) }
                        app.hamstr.ui.NewPlaylistTile(148.dp, onNewPlaylist, square = songs)
                    }
                }
                if (songs && done.isNotEmpty()) {
                    Spacer(Modifier.height(18.dp))
                    Txt("${stringResource(R.string.lib_songs)} · ${done.size}", style = Hamstr.type.badge, color = c.ghost, modifier = Modifier.padding(start = 2.dp))
                }
            }
        }
        // Playlists among the files: tiles in the grid, rows in the songs list.
        if (placement == PlaylistPlacement.Grid && !selecting) {
            if (songs) items(playlists, key = { "plrow:" + it.id }, span = { GridItemSpan(2) }) { p ->
                PlaylistRow(p, Modifier.animateItem(fadeInSpec = Specs.listIn(), fadeOutSpec = Specs.listOut()), onLongClick = { onPlaylistMenu(p.id) }) { onPlaylist(p.id) }
            } else {
                items(playlists, key = { "pltile:" + it.id }) { p ->
                    PlaylistTile(p, Modifier.animateItem(fadeInSpec = Specs.listIn(), placementSpec = Specs.place, fadeOutSpec = Specs.listOut()), onLongClick = { onPlaylistMenu(p.id) }) { onPlaylist(p.id) }
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
        if (songs) {
            items(done, key = { "song:" + it.id }, span = { GridItemSpan(2) }) { t ->
                val gone = t.id in missing
                val on = t.family in selected
                SwipeRow(
                    start = if (selecting || gone) null else playNextAction { onPlayNext(t) },
                    end = if (selecting || gone) null else favoriteAction(t, favorites) { onFavorite(t) },
                    modifier = Modifier.animateItem(fadeInSpec = Specs.listIn(), fadeOutSpec = Specs.listOut()),
                ) {
                    SongRow(
                        t, now = playing?.id == t.id, gone = gone,
                        onClick = { if (selecting) toggle(t.family) else if (gone) onMenu(t) else onPlayList(t, done.filter { it.id !in missing }) },
                        onLongClick = { toggle(t.family) },
                    ) {
                        if (selecting) SelectMark(true, on)
                        else IconButton(Res.drawable.ic_more_vert, stringResource(R.string.player_more), { onMenu(t) }, tint = c.ghost, background = Color.Transparent, size = 40.dp)
                    }
                }
            }
        } else items(tiles, key = { it.key }) { shelf ->
            val anim = Modifier.animateItem(fadeInSpec = Specs.listIn(), placementSpec = Specs.place, fadeOutSpec = Specs.listOut())
            val t = shelf.task
            val gone = t.id in missing
            val on = t.family in selected
            // Under Video or Audio, a tile only counts the files of that kind.
            val all = versions[t.family].orEmpty().filter { filter.takes(it) }.ifEmpty { listOf(t) }
            // Tiles don't swipe: half a screen wide, they would slide over their neighbours.
            Column(
                anim
                    .clip(RoundedCornerShape(14.dp))
                    .pressable(
                        onClick = { if (selecting) toggle(t.family) else if (gone) onMenu(t) else onPlay(t, done) },
                        onLongClick = { toggle(t.family) },
                    ),
            ) {
                Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(14.dp)).background(c.black)) {
                    AsciiImage(
                        bitmap = rememberImage(t.thumbnail, 360),
                        reveal = 1f,
                        modifier = Modifier.fillMaxSize().graphicsLayer { alpha = if (gone) 0.35f else 1f },
                    )
                    if (gone) {
                        PictureLabel(stringResource(R.string.lib_gone), Modifier.align(Alignment.Center), badge = true)
                    } else {
                        // Quality and length sit on the picture, so the tile needs no extra line for them.
                        PictureLabel(versionsBadge(all), Modifier.align(Alignment.BottomStart).padding(6.dp))
                        if (t.durationSec > 0) PictureLabel(formatDuration(t.durationSec), Modifier.align(Alignment.BottomEnd).padding(6.dp))
                    }
                    positions[t.id]?.let { r -> Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(r.fraction).height(3.dp).background(c.accent)) }
                    SelectMark(selecting, on, Modifier.align(Alignment.TopStart))
                }
                Spacer(Modifier.height(7.dp))
                Row(verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        Txt(t.title, style = Hamstr.type.bodyMedium, maxLines = 2, modifier = Modifier.padding(horizontal = 2.dp))
                        val size = all.sumOf { it.sizeBytes }.takeIf { it > 0 }?.let(::formatBytes)
                        listOfNotNull(t.uploader ?: versions[t.family]?.firstNotNullOfOrNull { it.uploader }, size).joinToString(" · ").takeIf { it.isNotEmpty() }
                            ?.let { Txt(it, style = Hamstr.type.small, color = c.ghost, maxLines = 1, modifier = Modifier.padding(horizontal = 2.dp, vertical = 1.dp)) }
                    }
                    if (!selecting) {
                        // The file menu, also reachable with a long press on the player. The 48 dp target
                        // reaches into the gutter, so the dots line up with the picture's edge.
                        Box(
                            Modifier.offset(x = 14.dp).size(48.dp).pressable({ onMenu(t) }, label = stringResource(R.string.player_more), pressedScale = 0.92f),
                            contentAlignment = Alignment.TopCenter,
                        ) { Icon(Res.drawable.ic_more_vert, tint = c.ghost, size = 18.dp, modifier = Modifier.padding(top = 2.dp)) }
                    }
                }
            }
        }
    }
}

/** A playlist as a tile among the files: the fan as wide as a file's picture. */
@Composable
private fun PlaylistTile(p: app.hamstr.data.Playlist, modifier: Modifier, onLongClick: () -> Unit, onClick: () -> Unit) {
    val c = Hamstr.colors
    Column(modifier.clip(RoundedCornerShape(14.dp)).pressable(onClick, onLongClick = onLongClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f), contentAlignment = Alignment.Center) {
            PlaylistFan(p.items, if (p.songs) Modifier.fillMaxHeight() else Modifier.fillMaxWidth(), square = p.songs)
        }
        Spacer(Modifier.height(7.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 2.dp)) {
            Icon(if (p.favorites) Res.drawable.ic_favorite else Res.drawable.ic_queue_music, tint = c.accent, size = 14.dp, modifier = Modifier.padding(end = 4.dp))
            Txt(p.name.ifEmpty { stringResource(R.string.lib_playlist) }, style = Hamstr.type.bodyMedium, maxLines = 1)
        }
        Txt(pluralStringResource(R.plurals.lib_files, p.items.size, p.items.size), style = Hamstr.type.small, color = c.ghost, modifier = Modifier.padding(horizontal = 2.dp, vertical = 1.dp))
    }
}

/** A playlist in the songs list: a small square fan, its name and how many songs. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlaylistRow(p: app.hamstr.data.Playlist, modifier: Modifier, onLongClick: () -> Unit, onClick: () -> Unit) {
    val c = Hamstr.colors
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(Radii.row)).onRightClick(onLongClick).combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = 6.dp, top = 6.dp, bottom = 6.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlaylistFan(p.items, Modifier.size(52.dp), square = true)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Txt(p.name.ifEmpty { stringResource(R.string.lib_playlist) }, style = Hamstr.type.bodyMedium, maxLines = 1)
            Spacer(Modifier.height(2.dp))
            Txt(pluralStringResource(R.plurals.lib_files, p.items.size, p.items.size), style = Hamstr.type.small, color = c.ghost, maxLines = 1)
        }
        Icon(if (p.favorites) Res.drawable.ic_favorite else Res.drawable.ic_chevron_right, tint = if (p.favorites) c.accent else c.ghost, size = 18.dp)
    }
}

@Composable
private fun playNextAction(run: () -> Unit) = SwipeAction(Res.drawable.ic_play_next, stringResource(R.string.play_next), Hamstr.colors.accent, onTrigger = run)

@Composable
private fun favoriteAction(t: DownloadTask, favorites: Set<String>, run: () -> Unit): SwipeAction {
    val on = t.id in favorites
    return SwipeAction(
        if (on) Res.drawable.ic_favorite_border else Res.drawable.ic_favorite,
        stringResource(if (on) R.string.fav_remove else R.string.fav_add),
        Hamstr.colors.accent, onTrigger = run,
    )
}

/** The tick in a tile's corner while selecting. */
@Composable
private fun SelectMark(selecting: Boolean, on: Boolean, modifier: Modifier = Modifier) {
    val c = Hamstr.colors
    val shown by animateFloatAsState(if (selecting) 1f else 0f, Specs.appear(), label = "selectMark")
    val p = animateSelection(on)
    if (shown <= 0f) return
    Box(
        modifier
            .padding(8.dp)
            .graphicsLayer { alpha = shown; scaleX = 0.6f + 0.4f * shown; scaleY = 0.6f + 0.4f * shown }
            .size(24.dp)
            .clip(CircleShape)
            .background(lerp(Color.Black.copy(alpha = 0.45f), c.accent, p))
            .border(2.dp, lerp(Color.White.copy(alpha = 0.8f), c.accent, p), CircleShape),
        contentAlignment = Alignment.Center,
    ) { if (on) Icon(Res.drawable.ic_check, tint = c.onAccent, size = 16.dp) }
}
