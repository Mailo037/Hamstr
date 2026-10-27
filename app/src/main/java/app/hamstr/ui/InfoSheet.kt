package app.hamstr.ui

import android.media.MediaMetadataRetriever
import android.text.format.DateUtils
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.hamstr.R
import app.hamstr.data.DownloadTask
import app.hamstr.ui.components.IconButton
import app.hamstr.ui.components.Txt
import app.hamstr.ui.components.edgeFade
import app.hamstr.ui.theme.Hamstr
import app.hamstr.ui.theme.Radii
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** A button at the end of an info line that acts on it, such as renaming from the title. */
class InfoAction(@DrawableRes val icon: Int, @StringRes val label: Int, val onClick: () -> Unit)

/**
 * Everything known about a stashed file: what Hamstr saved when it was downloaded, and
 * what the file itself says (resolution, bitrate, embedded tags). Tap a line to copy it;
 * lines in [actions] also get a button that changes or opens what they show.
 */
@Composable
fun InfoSheet(task: DownloadTask, onCopy: (String) -> Unit, actions: Map<Int, InfoAction> = emptyMap()) {
    val c = Hamstr.colors
    val context = LocalContext.current
    val file = task.filePath?.let(::File)?.takeIf { it.isFile }
    var fromFile by remember(task.id) { mutableStateOf<List<Pair<Int, String>>>(emptyList()) }
    LaunchedEffect(task.id) { if (file != null) fromFile = withContext(Dispatchers.IO) { readFile(file) } }

    val saved = listOfNotNull(
        R.string.info_name to task.title,
        task.uploader?.let { R.string.info_channel to it },
        task.source?.let { R.string.info_site to it },
        R.string.info_link to task.url,
        R.string.info_quality to formatBadge(task),
        task.sizeBytes.takeIf { it > 0 }?.let { R.string.info_size to formatBytes(it) },
        task.durationSec.takeIf { it > 0 }?.let { R.string.info_duration to formatDuration(it) },
        R.string.info_added to DateUtils.formatDateTime(
            context, task.finishedAt.takeIf { it > 0 } ?: task.createdAt,
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_YEAR,
        ),
        task.mediaKey?.let { R.string.info_id to it },
        task.filePath?.let { R.string.info_file to it },
    )

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = Radii.sheet, topEnd = Radii.sheet))
            .background(c.overlay)
            .clickable(enabled = false) {}
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Box(Modifier.align(Alignment.CenterHorizontally).width(36.dp).height(4.dp).clip(RoundedCornerShape(4.dp)).background(c.line))
        Spacer(Modifier.height(16.dp))
        Txt(stringResource(R.string.info_title), style = Hamstr.type.section, modifier = Modifier.padding(horizontal = 4.dp))
        Txt(stringResource(R.string.info_tap_copy), style = Hamstr.type.small, color = c.ghost, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
        Spacer(Modifier.height(12.dp))
        val scroll = rememberScrollState()
        Column(
            Modifier.heightIn(max = 520.dp).edgeFade(scroll, Orientation.Vertical, length = 22.dp).verticalScroll(scroll),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            (saved + fromFile).forEach { (label, value) -> InfoRow(label, value, actions[label]) { onCopy(value) } }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun InfoRow(@StringRes label: Int, value: String, action: InfoAction?, onClick: () -> Unit) {
    val c = Hamstr.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radii.row))
            // Each line on its own card, so neighbouring values don't run together.
            .background(c.surface)
            .clickable(onClick = onClick)
            .padding(start = 14.dp, end = if (action != null) 2.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
            Txt(stringResource(label), style = Hamstr.type.badge, color = c.ghost)
            Spacer(Modifier.height(2.dp))
            Txt(value, style = Hamstr.type.mono, color = c.ink)
        }
        if (action != null) IconButton(action.icon, stringResource(action.label), action.onClick, background = c.overlay, size = 34.dp)
    }
}

/** What the container itself reports; empty for files Android can't read. */
private fun readFile(file: File): List<Pair<Int, String>> = runCatching {
    val r = MediaMetadataRetriever()
    try {
        r.setDataSource(file.absolutePath)
        fun key(k: Int) = r.extractMetadata(k)?.trim()?.takeIf { it.isNotEmpty() }
        val w = key(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
        val h = key(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
        val tracks = listOfNotNull(
            key(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)?.let { "video" },
            key(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)?.let { "audio" },
        )
        listOfNotNull(
            key(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)?.let { R.string.info_type to it },
            if (w != null && h != null) R.string.info_resolution to "$w × $h" else null,
            key(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.takeIf { it != "0" }?.let { R.string.info_rotation to "$it°" },
            key(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull()?.let { R.string.info_bitrate to "${it / 1000} kbit/s" },
            tracks.takeIf { it.isNotEmpty() }?.let { R.string.info_tracks to it.joinToString(" + ") },
            key(MediaMetadataRetriever.METADATA_KEY_TITLE)?.let { R.string.info_tag_title to it },
            key(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.let { R.string.info_tag_artist to it },
            key(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.let { R.string.info_tag_album to it },
            key(MediaMetadataRetriever.METADATA_KEY_DATE)?.let { R.string.info_tag_date to it },
            key(MediaMetadataRetriever.METADATA_KEY_GENRE)?.let { R.string.info_tag_genre to it },
        )
    } finally {
        r.release()
    }
}.getOrDefault(emptyList())
