package app.hamstr.desktop

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.hamstr.data.DownloadTask
import app.hamstr.data.FileChecks
import app.hamstr.data.LibrarySort
import app.hamstr.data.Positions
import app.hamstr.data.Prefs
import app.hamstr.data.Status
import app.hamstr.data.collapseVersions
import app.hamstr.player.Kind
import app.hamstr.player.kindOf
import app.hamstr.res.*
import app.hamstr.ui.TaskRow
import app.hamstr.ui.components.DropMenu
import app.hamstr.ui.components.IconButton
import app.hamstr.ui.components.MenuDivider
import app.hamstr.ui.components.MenuItem
import app.hamstr.ui.components.MenuLabel
import app.hamstr.ui.components.SearchField
import app.hamstr.ui.components.Txt
import app.hamstr.ui.components.edgeFade
import app.hamstr.ui.theme.Hamstr
import org.jetbrains.compose.resources.stringResource

private enum class Shelf { All, Video, Audio, Other }

/**
 * Everything that finished, to find and play: search over titles, channels and sites, and
 * behind the filter button beside it a shelf for videos, songs or the rest and the sort the
 * phone's library uses, so the list starts right under the search. The button lights up while
 * either is off its default. A click plays a file in Hamstr's own player when it can; a
 * right-click has the rest.
 */
@Composable
internal fun DesktopLibrary(tasks: List<DownloadTask>, onPlay: (DownloadTask, List<DownloadTask>) -> Unit, onMenu: (DownloadTask) -> Unit) {
    val c = Hamstr.colors
    val s by Prefs.settings.collectAsState()
    val missing by FileChecks.missing.collectAsState()
    val resume by Positions.all.collectAsState()
    var query by remember { mutableStateOf("") }
    var shelf by remember { mutableStateOf(Shelf.All) }
    var filtering by remember { mutableStateOf(false) }
    val done = remember(tasks) { collapseVersions(tasks.filter { it.status == Status.Done && !it.hidden }) }
    val shown = remember(done, query, shelf, s.librarySort) {
        val q = query.trim().lowercase()
        done.filter { (t, _) ->
            val kind = kindOf(t)
            val onShelf = when (shelf) {
                Shelf.All -> true
                Shelf.Video -> kind == Kind.Video
                Shelf.Audio -> kind == Kind.Audio
                Shelf.Other -> kind != Kind.Video && kind != Kind.Audio
            }
            onShelf && (q.isEmpty() || listOfNotNull(t.title, t.uploader, t.source, t.groupTitle).any { it.lowercase().contains(q) })
        }.let { list ->
            when (s.librarySort) {
                LibrarySort.Newest -> list.sortedByDescending { it.first.finishedAt }
                LibrarySort.Oldest -> list.sortedBy { it.first.finishedAt }
                LibrarySort.Name -> list.sortedBy { it.first.title.lowercase() }
                LibrarySort.Size -> list.sortedByDescending { (_, v) -> v.sumOf { it.sizeBytes } }
            }
        }
    }
    val labels = mapOf(
        Shelf.All to stringResource(Res.string.lib_all),
        Shelf.Video to stringResource(Res.string.player_video),
        Shelf.Audio to stringResource(Res.string.lib_songs),
        Shelf.Other to stringResource(Res.string.player_file),
    )
    val sorts = mapOf(
        LibrarySort.Newest to stringResource(Res.string.lib_sort_newest),
        LibrarySort.Oldest to stringResource(Res.string.lib_sort_oldest),
        LibrarySort.Name to stringResource(Res.string.lib_sort_name),
        LibrarySort.Size to stringResource(Res.string.lib_sort_size),
    )

    Column(Modifier.fillMaxSize().padding(top = 8.dp)) {
        Txt(stringResource(Res.string.tab_library), style = Hamstr.type.title, modifier = Modifier.padding(start = 6.dp, bottom = 12.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SearchField(query, { query = it }, stringResource(Res.string.lib_search_hint), Modifier.weight(1f))
            val changed = shelf != Shelf.All || s.librarySort != LibrarySort.Newest
            Box {
                IconButton(
                    Res.drawable.ic_sort, stringResource(Res.string.lib_filter), { filtering = !filtering },
                    tint = if (filtering || changed) c.accent else c.ink, background = c.overlay, size = 44.dp,
                )
                DropMenu(filtering, { filtering = false }, end = true) {
                    MenuLabel(stringResource(Res.string.lib_filter))
                    Shelf.entries.forEach { o -> MenuItem(labels.getValue(o), { shelf = o; filtering = false }, checked = o == shelf) }
                    MenuDivider()
                    MenuLabel(stringResource(Res.string.lib_sort))
                    LibrarySort.entries.forEach { o ->
                        MenuItem(sorts.getValue(o), { Prefs.edit { it.copy(librarySort = o) }; filtering = false }, checked = o == s.librarySort)
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        if (shown.isEmpty()) {
            Txt(
                if (query.isBlank()) stringResource(Res.string.lib_empty) else stringResource(Res.string.lib_no_match, query.trim()),
                style = Hamstr.type.small, color = c.ghost, modifier = Modifier.padding(6.dp),
            )
        } else {
            val list = rememberLazyListState()
            LazyColumn(Modifier.fillMaxSize().edgeFade(list, Orientation.Vertical), state = list, contentPadding = PaddingValues(bottom = 24.dp)) {
                items(shown, key = { it.first.id }) { (t, versions) ->
                    TaskRow(
                        t, versions = versions, gone = t.id in missing,
                        resume = resume[t.id]?.fraction,
                        onClick = { onPlay(t, shown.map { it.first }) }, onLongClick = { onMenu(t) },
                    )
                }
            }
        }
    }
}
