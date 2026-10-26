package app.hamstr.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.hamstr.data.DownloadTask
import app.hamstr.data.Mode
import app.hamstr.data.Status
import app.hamstr.engine.DownloadService
import app.hamstr.ui.components.AsciiImage
import app.hamstr.ui.components.Badge
import app.hamstr.ui.components.Segmented
import app.hamstr.ui.components.Txt
import app.hamstr.ui.theme.Hamstr

private enum class Filter(val label: String) { All("All"), Video("Video"), Audio("Audio") }

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(tasks: List<DownloadTask>, contentPadding: PaddingValues, onMenu: (DownloadTask) -> Unit, onPlay: (DownloadTask) -> Unit) {
    val c = Hamstr.colors
    val context = LocalContext.current
    var filter by rememberSaveable { mutableStateOf(Filter.All) }
    val done = tasks.filter { it.status == Status.Done }.filter {
        when (filter) {
            Filter.All -> true
            Filter.Video -> it.choice.mode != Mode.Audio
            Filter.Audio -> it.choice.mode == Mode.Audio
        }
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
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
                Txt("Library", style = Hamstr.type.title)
                Spacer(Modifier.height(14.dp))
                Segmented(Filter.entries.toList(), filter, { it.label }, { filter = it }, background = c.overlay)
            }
        }
        if (done.isEmpty()) {
            item(span = { GridItemSpan(2) }) {
                Txt("Everything you finish stashing ends up here.", color = c.muted, modifier = Modifier.padding(6.dp))
            }
        }
        items(done, key = { it.id }) { t ->
            Column(
                Modifier
                    .animateItem(fadeInSpec = tween(320), placementSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow, visibilityThreshold = IntOffset(1, 1)), fadeOutSpec = tween(160))
                    .clip(RoundedCornerShape(14.dp))
                    .combinedClickable(
                        onClick = { onPlay(t) },
                        onLongClick = { onMenu(t) },
                    ),
            ) {
                AsciiImage(
                    bitmap = rememberBitmap(t.thumbnail, 360),
                    reveal = 1f,
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(14.dp)).background(c.black),
                )
                Spacer(Modifier.height(7.dp))
                Txt(t.title, style = Hamstr.type.bodyMedium, maxLines = 2, modifier = Modifier.padding(horizontal = 2.dp))
                Spacer(Modifier.height(3.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(horizontal = 2.dp)) {
                    Badge(formatBadge(t))
                    if (t.sizeBytes > 0) Badge(formatBytes(t.sizeBytes))
                }
            }
        }
    }
}
