package app.hamstr.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.hamstr.engine.Failures
import androidx.compose.ui.unit.dp
import app.hamstr.R
import app.hamstr.data.Choice
import app.hamstr.data.DownloadFiles
import app.hamstr.data.DownloadStore
import app.hamstr.data.DownloadTask
import app.hamstr.data.Mode
import app.hamstr.data.Prefs
import app.hamstr.data.Status
import app.hamstr.data.heightLabel
import app.hamstr.engine.DownloadService
import app.hamstr.engine.Engine
import app.hamstr.player.Kind
import app.hamstr.player.Playback
import app.hamstr.player.kindOf
import app.hamstr.ui.components.Badge
import app.hamstr.ui.components.ButtonKind
import app.hamstr.ui.components.HButton
import app.hamstr.ui.components.Icon
import app.hamstr.ui.components.IconButton
import app.hamstr.ui.components.Segmented
import app.hamstr.ui.components.Txt
import app.hamstr.ui.theme.Hamstr
import app.hamstr.ui.theme.Radii
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import app.hamstr.ui.components.Chip
import app.hamstr.ui.components.ChipRow
import app.hamstr.ui.components.Sheet
import app.hamstr.ui.components.SheetHeader

/** Only downloaded links can be fetched again, and only single videos and songs. */
fun canHaveVersions(t: DownloadTask): Boolean {
    if (t.status != Status.Done || t.group != null || !t.url.startsWith("http")) return false
    val file = t.filePath?.let(::File)?.takeIf { it.isFile } ?: return false
    return kindOf(file).let { it == Kind.Video || it == Kind.Audio }
}

/** A video whose link has no audio-only file yet; [family] is everything stashed from that link. */
fun canSaveAudio(t: DownloadTask, family: List<DownloadTask>): Boolean {
    val file = t.filePath?.let(::File)?.takeIf { it.isFile } ?: return false
    return t.status == Status.Done && kindOf(file) == Kind.Video && family.none { it.choice.mode == Mode.Audio && it.status != Status.Canceled && it.status != Status.Failed }
}

/** One quality to fetch: a video height, or 0 for audio only. */
private data class Pick(val height: Int)

/**
 * Every quality of one link: switch between the ones on the phone, delete one, or fetch
 * more. Fetched ones either join the current file or replace it once they are in.
 */
@Composable
fun QualitySheet(task: DownloadTask, onSwitch: (DownloadTask) -> Unit, onSaveAudio: (DownloadTask) -> Unit, onDelete: (DownloadTask) -> Unit, onDone: (String) -> Unit) {
    val c = Hamstr.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tasks by DownloadStore.tasks.collectAsState()
    val playing by Playback.current.collectAsState()
    val family = tasks.filter { it.family == task.family }
    val versions = family.filter { it.status == Status.Done }.sortedByDescending { it.videoHeight }
    val pending = family.filter { it.status.active || it.status == Status.Paused }
    // The sheet's own file can be replaced or deleted while it is open.
    val current = versions.firstOrNull { it.id == task.id } ?: versions.firstOrNull() ?: return

    // Older downloads don't know their height yet; measure the files once.
    LaunchedEffect(versions.map { it.id }) {
        versions.filter { it.videoHeight == 0 && it.choice.mode != Mode.Audio }.forEach { v ->
            val h = withContext(Dispatchers.IO) { v.filePath?.let { DownloadFiles.probeHeight(File(it)) } ?: 0 }
            if (h > 0) DownloadStore.update(v.id) { it.copy(videoHeight = h) }
        }
    }

    var state by remember(task.url) {
        mutableStateOf<InfoState>(Engine.cachedInfo(task.url)?.let(InfoState::Loaded) ?: InfoState.Loading)
    }
    LaunchedEffect(task.url) {
        state = try { InfoState.Loaded(Engine.info(task.url)) } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            InfoState.Failed(errorLine(e))
        }
    }
    val info = (state as? InfoState.Loaded)?.info
    val have = (versions + pending).map { if (it.choice.mode == Mode.Audio) 0 else it.videoHeight.takeIf { h -> h > 0 } ?: it.choice.height ?: -1 }.toSet()
    // The best offered height under each usual step, minus what is already here or on its way.
    val offers = remember(info, have) {
        val offered = info?.heights.orEmpty()
        Prefs.heights.filterNotNull().mapNotNull { cap -> offered.firstOrNull { it <= cap } }
            .distinct().filter { it !in have }.map(::Pick) +
            listOfNotNull(Pick(0).takeIf { 0 !in have && info != null })
    }
    var picked by remember { mutableStateOf(emptySet<Pick>()) }
    var replace by remember { mutableStateOf(false) }

    Sheet {
        SheetHeader(stringResource(R.string.menu_quality), subtitle = current.title)
        Spacer(Modifier.height(12.dp))

        Txt(stringResource(R.string.q_on_phone), style = Hamstr.type.badge, color = c.ghost, modifier = Modifier.padding(horizontal = 4.dp))
        Spacer(Modifier.height(4.dp))
        versions.forEach { v ->
            val on = playing?.let { Playback.isCurrent(v) } ?: (v.id == current.id)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radii.row))
                    .background(if (on) c.surface else c.overlay)
                    .clickable { if (!on || playing == null) onSwitch(v) }
                    .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Badge(formatBadge(v), accent = on)
                Spacer(Modifier.width(10.dp))
                Txt(
                    listOfNotNull(v.filePath?.let { File(it).extension }, formatBytes(v.sizeBytes).takeIf { v.sizeBytes > 0 }).joinToString(" · "),
                    style = Hamstr.type.small, color = c.ghost, modifier = Modifier.weight(1f),
                )
                if (on && playing != null) Txt(stringResource(R.string.q_playing), style = Hamstr.type.badge, color = c.accent, modifier = Modifier.padding(end = 10.dp))
                if (versions.size > 1) {
                    IconButton(R.drawable.ic_delete, stringResource(R.string.q_delete, formatBadge(v)), { onDelete(v) }, tint = c.red, background = c.overlay, size = 34.dp)
                } else Spacer(Modifier.height(34.dp))
            }
        }
        if (canSaveAudio(current, family)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radii.row))
                    .clickable { onSaveAudio(current) }
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(R.drawable.ic_music_note, tint = c.accent, size = 18.dp)
                Spacer(Modifier.width(10.dp))
                Txt(stringResource(R.string.q_save_audio), style = Hamstr.type.bodyMedium, modifier = Modifier.weight(1f))
                Txt(stringResource(R.string.q_no_download), style = Hamstr.type.badge, color = c.ghost)
            }
        }
        pending.forEach { p ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Badge(formatBadge(p))
                Spacer(Modifier.width(10.dp))
                Txt(
                    when {
                        p.status == Status.Paused -> stringResource(R.string.status_paused_short)
                        p.replaces != null -> stringResource(R.string.q_coming_replaces, DownloadStore.get(p.replaces)?.let(::formatBadge) ?: stringResource(R.string.q_old_file))
                        else -> stringResource(R.string.q_coming)
                    },
                    style = Hamstr.type.small, color = c.ghost, maxLines = 1,
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        Txt(stringResource(R.string.q_get_another), style = Hamstr.type.badge, color = c.ghost, modifier = Modifier.padding(horizontal = 4.dp))
        Spacer(Modifier.height(8.dp))
        when (val s = state) {
            InfoState.Loading -> Txt(stringResource(R.string.share_sniffing), color = c.muted, modifier = Modifier.padding(horizontal = 4.dp))
            is InfoState.Failed -> Txt(stringResource(Failures.explain(s.message).title), style = Hamstr.type.small, color = c.red, maxLines = 3, modifier = Modifier.padding(horizontal = 4.dp))
            is InfoState.Loaded -> if (offers.isEmpty()) {
                Txt(stringResource(R.string.q_all_here), color = c.muted, modifier = Modifier.padding(horizontal = 4.dp))
            } else {
                // Several can be picked at once.
                ChipRow {
                    offers.forEach { o ->
                        val on = o in picked
                        val bytes = if (o.height == 0) s.info.audioBytes else s.info.videoBytes[o.height] ?: 0
                        Chip(
                            if (o.height == 0) stringResource(R.string.mode_audio) else heightLabel(o.height),
                            on, { picked = if (on) picked - o else picked + o },
                            sub = if (bytes > 0) "~" + formatBytes(bytes) else stringResource(R.string.q_size_unknown),
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                val replaceLabel = stringResource(R.string.q_replace, formatBadge(current))
                val keepLabel = stringResource(R.string.q_keep_both)
                Segmented(
                    listOf(false, true), replace,
                    { if (it) replaceLabel else keepLabel },
                    { replace = it },
                    background = c.canvas,
                )
                Spacer(Modifier.height(6.dp))
                Txt(
                    if (replace) stringResource(R.string.q_replace_hint, formatBadge(current))
                    else stringResource(R.string.q_keep_hint, formatBadge(current)),
                    style = Hamstr.type.small, color = c.ghost, modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        HButton(
            when (picked.size) { 0 -> stringResource(R.string.q_pick); 1 -> stringResource(R.string.share_stash); else -> stringResource(R.string.q_stash_n, picked.size) },
            onClick = {
                val defaults = Prefs.settings.value
                picked.sortedByDescending { it.height }.forEach { p ->
                    val choice = if (p.height == 0) {
                        Choice(Mode.Audio, null, defaults.container, current.choice.audioFormat.takeIf { current.choice.mode == Mode.Audio } ?: defaults.audioFormat, false)
                    } else {
                        Choice(Mode.Video, p.height, current.choice.container.takeIf { current.choice.mode == Mode.Video } ?: defaults.container, defaults.audioFormat, false)
                    }
                    DownloadService.stashVersion(context, current, choice, replace)
                }
                onDone(if (picked.size == 1) context.getString(R.string.q_fetching_one) else context.getString(R.string.q_fetching_many, picked.size))
            },
            modifier = Modifier.fillMaxWidth(),
            kind = ButtonKind.Primary,
            icon = R.drawable.ic_download,
            enabled = picked.isNotEmpty(),
        )
    }
}
