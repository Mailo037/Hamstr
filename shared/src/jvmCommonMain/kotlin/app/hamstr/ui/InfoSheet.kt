package app.hamstr.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.hamstr.data.DownloadStore
import app.hamstr.data.DownloadTask
import app.hamstr.data.FileChecks
import app.hamstr.res.*
import app.hamstr.ui.components.IconButton
import app.hamstr.ui.components.Sheet
import app.hamstr.ui.components.SheetHeader
import app.hamstr.ui.components.Txt
import app.hamstr.ui.components.edgeFade
import app.hamstr.ui.theme.Hamstr
import app.hamstr.ui.theme.Radii
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import java.io.File

/** A button at the end of an info line that acts on it, such as renaming from the title. */
class InfoAction(val icon: DrawableResource, val label: StringResource, val onClick: () -> Unit)

/** Whether the file is still what was saved, as the sheet found it just now. */
private enum class Check { Checking, Unchanged, Changed }

/**
 * Everything known about a stashed file: what Hamstr saved when it was downloaded, its SHA-256
 * and whether the file still matches it, and what the file itself says ([readFile]: resolution,
 * bitrate, embedded tags). Tap a line to copy it; lines in [actions] also get a button that
 * changes or opens what they show.
 */
@Composable
fun InfoSheet(
    entry: DownloadTask,
    onCopy: (String) -> Unit,
    formatDate: (Long) -> String,
    actions: Map<StringResource, InfoAction> = emptyMap(),
    readFile: (File) -> List<Pair<StringResource, String>> = { emptyList() },
) {
    // As the list has it now: the hash may come in while the sheet is open.
    val tasks by DownloadStore.tasks.collectAsState()
    val task = tasks.firstOrNull { it.id == entry.id } ?: entry
    val file = task.filePath?.let(::File)?.takeIf { it.isFile }
    var fromFile by remember(task.id) { mutableStateOf<List<Pair<StringResource, String>>>(emptyList()) }
    LaunchedEffect(task.id) { if (file != null) fromFile = withContext(Dispatchers.IO) { readFile(file) } }
    // Hashed again on every look, so the answer is about the file as it is now.
    var check by remember(task.id) { mutableStateOf(Check.Checking) }
    LaunchedEffect(task.id, task.sha256) {
        val saved = task.sha256 ?: return@LaunchedEffect
        if (file == null) return@LaunchedEffect
        check = Check.Checking
        val now = withContext(Dispatchers.IO) { FileChecks.sha256(file) } ?: return@LaunchedEffect
        check = if (now == saved) Check.Unchanged else Check.Changed
        // The lists find out too, without waiting for the next return to the app.
        if ((check == Check.Changed) != (task.id in FileChecks.changed.value)) FileChecks.refresh()
    }

    val saved = listOfNotNull(
        Res.string.info_name to task.title,
        task.uploader?.let { Res.string.info_channel to it },
        task.source?.let { Res.string.info_site to it },
        Res.string.info_link to task.url,
        Res.string.info_quality to formatBadge(task),
        task.sizeBytes.takeIf { it > 0 }?.let { Res.string.info_size to formatBytes(it) },
        task.durationSec.takeIf { it > 0 }?.let { Res.string.info_duration to formatDuration(it) },
        Res.string.info_added to formatDate(task.finishedAt.takeIf { it > 0 } ?: task.createdAt),
        task.mediaKey?.let { Res.string.info_id to it },
        task.filePath?.let { Res.string.info_file to it },
        task.sha256?.let { Res.string.info_sha256 to it },
    )
    val checkText = when (check) {
        Check.Checking -> stringResource(Res.string.info_check_running)
        Check.Unchanged -> stringResource(Res.string.info_check_same)
        Check.Changed -> stringResource(Res.string.info_check_changed)
    }
    val checkLine = if (task.sha256 != null && file != null) listOf(Res.string.info_check to checkText) else emptyList()

    // The title stays put; the lines take the room that is left and scroll under it.
    Sheet(scroll = false) {
        SheetHeader(stringResource(Res.string.info_title), subtitle = stringResource(Res.string.info_tap_copy))
        Spacer(Modifier.height(12.dp))
        val scroll = rememberScrollState()
        Column(
            Modifier.weight(1f, fill = false).edgeFade(scroll, Orientation.Vertical, length = 22.dp).verticalScroll(scroll),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            (saved + checkLine + fromFile).forEach { (label, value) ->
                val tint = if (label == Res.string.info_check && check == Check.Changed) Hamstr.colors.red else null
                InfoRow(label, value, actions[label], tint) { onCopy(value) }
            }
        }
    }
}

@Composable
private fun InfoRow(label: StringResource, value: String, action: InfoAction?, tint: Color?, onClick: () -> Unit) {
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
            Txt(value, style = Hamstr.type.mono, color = tint ?: c.ink)
        }
        if (action != null) IconButton(action.icon, stringResource(action.label), action.onClick, background = c.overlay, size = 34.dp)
    }
}
