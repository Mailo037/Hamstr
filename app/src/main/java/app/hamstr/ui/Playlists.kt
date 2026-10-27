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
import androidx.compose.ui.res.stringResource
import app.hamstr.R
import app.hamstr.data.DownloadStore
import app.hamstr.data.DownloadTask
import app.hamstr.data.Status
import app.hamstr.ui.components.AsciiImage
import app.hamstr.ui.components.Badge
import app.hamstr.ui.components.Icon
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
    val groups = done.filter { it.group != null }.groupBy { it.group }
    return done.mapNotNull { t ->
        val g = t.group
        when {
            g == null -> Shelf.One(t)
            !seen.add(g) -> null
            else -> {
                val items = groups.getValue(g).sortedBy { it.createdAt }
                if (items.size > 1) Shelf.Group(g, t.groupTitle ?: "", items) else Shelf.One(t)
            }
        }
    }
}

/**
 * Three thumbnails fanned out: one tilted left at the bottom, one tilted right with "+N"
 * for everything else, and the first one upright on top in the middle.
 */
@Composable
fun PlaylistFan(items: List<DownloadTask>, modifier: Modifier = Modifier, square: Boolean = false) {
    // Songs have square covers, so a list of songs fans out square cards.
    val ratio = if (square) 1f else 16f / 9f
    BoxWithConstraints(modifier.aspectRatio(ratio)) {
        if (items.isEmpty()) {
            // A new list: an empty card with the add mark, so it still reads as a playlist.
            Box(
                Modifier.align(Alignment.Center).width(maxWidth * 0.53f).aspectRatio(ratio)
                    .clip(RoundedCornerShape(10.dp)).border(2.dp, Hamstr.colors.line, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) { Icon(R.drawable.ic_playlist_add, tint = Hamstr.colors.ghost, size = 22.dp) }
            return@BoxWithConstraints
        }
        val w = maxWidth * 0.5f
        // Card centres as fractions of the tile, measured from its middle.
        fun at(x: Float, y: Float) = Modifier.align(Alignment.Center).offset(x = maxWidth * (x - 0.5f), y = maxHeight * (y - 0.5f))
        items.getOrNull(1)?.let { FanThumb(it, w, at(0.29f, 0.62f).graphicsLayer { rotationZ = -10f }, ratio) }
        items.getOrNull(2)?.let { FanThumb(it, w, at(0.71f, 0.62f).graphicsLayer { rotationZ = 10f }, ratio, more = items.size - 2) }
        FanThumb(items.first(), w * 1.06f, at(0.5f, 0.4f), ratio)
    }
}

@Composable
private fun FanThumb(t: DownloadTask, width: Dp, modifier: Modifier, ratio: Float, more: Int = 0) {
    val c = Hamstr.colors
    Box(
        modifier
            .width(width)
            .aspectRatio(ratio)
            .clip(RoundedCornerShape(10.dp))
            .border(2.dp, c.canvas, RoundedCornerShape(10.dp)),
    ) {
        AsciiImage(rememberBitmap(t.thumbnail, 240), reveal = 1f, cell = 4.dp, modifier = Modifier.fillMaxWidth().aspectRatio(ratio).background(c.black))
        if (more > 0) {
            // The middle card covers this one's top left, so the count sits bottom right.
            Box(Modifier.fillMaxWidth().aspectRatio(ratio).background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.BottomEnd) {
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

