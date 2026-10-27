package app.hamstr.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.hamstr.data.DownloadStore
import app.hamstr.data.DownloadTask
import app.hamstr.data.Status
import app.hamstr.ui.components.AsciiImage
import app.hamstr.ui.components.Badge
import app.hamstr.ui.components.Txt
import app.hamstr.ui.components.edgeFade
import app.hamstr.ui.theme.Hamstr
import app.hamstr.ui.theme.Radii

/** A library tile: one file, or a group of files that arrived together. */
sealed interface Shelf {
    val key: String

    data class One(val task: DownloadTask) : Shelf {
        override val key get() = task.id
    }

    data class Group(val id: String, val title: String, val items: List<DownloadTask>) : Shelf {
        override val key get() = "group:$id"
    }
}

/** Collapses grouped files into one tile at the position of their newest member. */
fun shelves(done: List<DownloadTask>): List<Shelf> {
    val seen = HashSet<String>()
    return done.mapNotNull { t ->
        val g = t.group
        when {
            g == null -> Shelf.One(t)
            !seen.add(g) -> null
            else -> {
                val items = done.filter { it.group == g }.sortedBy { it.createdAt }
                if (items.size > 1) Shelf.Group(g, t.groupTitle ?: "Playlist", items) else Shelf.One(t)
            }
        }
    }
}

/**
 * Three thumbnails fanned out: one tilted left at the bottom, one tilted right with "+N"
 * for everything else, and the first one upright on top in the middle.
 */
@Composable
fun PlaylistFan(items: List<DownloadTask>, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.aspectRatio(16f / 9f)) {
        val w = maxWidth * 0.5f
        // Card centres as fractions of the tile, measured from its middle.
        fun at(x: Float, y: Float) = Modifier.align(Alignment.Center).offset(x = maxWidth * (x - 0.5f), y = maxHeight * (y - 0.5f))
        items.getOrNull(1)?.let { FanThumb(it, w, at(0.29f, 0.62f).graphicsLayer { rotationZ = -10f }) }
        items.getOrNull(2)?.let { FanThumb(it, w, at(0.71f, 0.62f).graphicsLayer { rotationZ = 10f }, more = items.size - 2) }
        FanThumb(items.first(), w * 1.06f, at(0.5f, 0.4f))
    }
}

@Composable
private fun FanThumb(t: DownloadTask, width: Dp, modifier: Modifier, more: Int = 0) {
    val c = Hamstr.colors
    Box(
        modifier
            .width(width)
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(10.dp))
            .border(2.dp, c.canvas, RoundedCornerShape(10.dp)),
    ) {
        AsciiImage(rememberBitmap(t.thumbnail, 240), reveal = 1f, cell = 4.dp, modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(c.black))
        if (more > 0) {
            // The middle card covers this one's top left, so the count sits bottom right.
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.BottomEnd) {
                Txt(
                    "+$more",
                    style = Hamstr.type.mono.copy(fontSize = 18.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
                    color = Color.White,
                    modifier = Modifier.padding(end = 10.dp, bottom = 6.dp),
                )
            }
        }
    }
}

/** Everything in a group, in the order it arrived. */
@Composable
fun GroupSheet(groupId: String, onPlay: (DownloadTask) -> Unit, onMenu: (DownloadTask) -> Unit) {
    val c = Hamstr.colors
    val tasks by DownloadStore.tasks.collectAsState()
    val items = tasks.filter { it.group == groupId && it.status == Status.Done }.sortedBy { it.createdAt }
    if (items.isEmpty()) return
    val title = items.first().groupTitle ?: "Playlist"
    val list = rememberLazyListState()
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = Radii.sheet, topEnd = Radii.sheet))
            .background(c.overlay)
            .clickable(enabled = false) {}
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        Box(Modifier.align(Alignment.CenterHorizontally).width(36.dp).height(4.dp).clip(RoundedCornerShape(4.dp)).background(c.line))
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
            PlaylistFan(items, Modifier.width(132.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Txt(title, style = Hamstr.type.section, maxLines = 2)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Badge("${items.size} files", accent = true)
                    Badge(formatBytes(items.sumOf { it.sizeBytes }))
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        LazyColumn(
            Modifier
                .heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.55f).dp)
                .edgeFade(list, Orientation.Vertical),
            state = list,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(items, key = { it.id }) { t ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Radii.row))
                        .combinedClickable(onClick = { onPlay(t) }, onLongClick = { onMenu(t) })
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AsciiImage(rememberBitmap(t.thumbnail, 160), reveal = 1f, cell = 5.dp, modifier = Modifier.size(width = 72.dp, height = 42.dp).clip(RoundedCornerShape(Radii.thumb)).background(c.black))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Txt(t.title, style = Hamstr.type.bodyMedium, maxLines = 1)
                        Spacer(Modifier.height(3.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Badge(formatBadge(t))
                            Txt(
                                listOfNotNull(formatDuration(t.durationSec).takeIf { t.durationSec > 0 }, formatBytes(t.sizeBytes).takeIf { t.sizeBytes > 0 }).joinToString(" · "),
                                style = Hamstr.type.small, color = c.ghost,
                            )
                        }
                    }
                }
            }
        }
    }
}
