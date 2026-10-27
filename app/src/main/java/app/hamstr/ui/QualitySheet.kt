package app.hamstr.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
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
import app.hamstr.ui.components.edgeFade
import app.hamstr.ui.theme.Hamstr
import app.hamstr.ui.theme.Radii
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

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
fun QualitySheet(task: DownloadTask, onSwitch: (DownloadTask) -> Unit, onSaveAudio: (DownloadTask) -> Unit, onDone: (String) -> Unit) {
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

    var state by remember(task.url) { mutableStateOf<InfoState>(InfoState.Loading) }
    LaunchedEffect(task.url) {
        state = try { InfoState.Loaded(Engine.info(task.url)) } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            InfoState.Failed(e.message?.lines()?.lastOrNull { it.contains("ERROR") }?.substringAfter("ERROR: ") ?: "Couldn't read the link")
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

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = Radii.sheet, topEnd = Radii.sheet))
            .background(c.overlay)
            .clickable(enabled = false) {}
            .navigationBarsPadding()
            .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 16.dp)
            .animateContentSize(tween(220)),
    ) {
        Box(Modifier.align(Alignment.CenterHorizontally).width(36.dp).height(4.dp).clip(RoundedCornerShape(4.dp)).background(c.line))
        Spacer(Modifier.height(12.dp))
        Txt("Quality", style = Hamstr.type.section, modifier = Modifier.padding(horizontal = 8.dp))
        Spacer(Modifier.height(2.dp))
        Txt(current.title, style = Hamstr.type.small, color = c.ghost, maxLines = 1, modifier = Modifier.padding(horizontal = 8.dp))
        Spacer(Modifier.height(12.dp))

        Txt("On this phone", style = Hamstr.type.badge, color = c.ghost, modifier = Modifier.padding(horizontal = 8.dp))
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
                if (on && playing != null) Txt("playing", style = Hamstr.type.badge, color = c.accent, modifier = Modifier.padding(end = 10.dp))
                if (versions.size > 1) {
                    IconButton(R.drawable.ic_delete, "Delete ${formatBadge(v)}", {
                        scope.launch {
                            val result = DownloadFiles.delete(context, v)
                            result.exceptionOrNull()?.let { onDone(it.message ?: "Couldn't delete the file") }
                        }
                    }, tint = c.red, background = c.overlay, size = 34.dp)
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
                Txt("Save the audio from this video", style = Hamstr.type.bodyMedium, modifier = Modifier.weight(1f))
                Txt("no download", style = Hamstr.type.badge, color = c.ghost)
            }
        }
        pending.forEach { p ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Badge(formatBadge(p))
                Spacer(Modifier.width(10.dp))
                Txt(
                    if (p.status == Status.Paused) "paused" else if (p.replaces != null) "on its way · replaces ${DownloadStore.get(p.replaces)?.let(::formatBadge) ?: "the old file"}" else "on its way",
                    style = Hamstr.type.small, color = c.ghost, maxLines = 1,
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        Txt("Get another quality", style = Hamstr.type.badge, color = c.ghost, modifier = Modifier.padding(horizontal = 8.dp))
        Spacer(Modifier.height(8.dp))
        when (val s = state) {
            InfoState.Loading -> Txt("Sniffing the link …", color = c.muted, modifier = Modifier.padding(horizontal = 8.dp))
            is InfoState.Failed -> Txt(s.message, style = Hamstr.type.small, color = c.red, maxLines = 3, modifier = Modifier.padding(horizontal = 8.dp))
            is InfoState.Loaded -> if (offers.isEmpty()) {
                Txt("Every quality this link offers is already here.", color = c.muted, modifier = Modifier.padding(horizontal = 8.dp))
            } else {
                val scroll = rememberScrollState()
                Row(
                    Modifier.edgeFade(scroll, Orientation.Horizontal).horizontalScroll(scroll).padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    offers.forEach { o ->
                        val on = o in picked
                        val bytes = if (o.height == 0) s.info.audioBytes else s.info.videoBytes[o.height] ?: 0
                        Column(
                            Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (on) c.accent else c.surface)
                                .clickable { picked = if (on) picked - o else picked + o }
                                .padding(horizontal = 14.dp, vertical = 7.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Txt(if (o.height == 0) "Audio" else heightLabel(o.height), style = Hamstr.type.bodyMedium, color = if (on) c.onAccent else c.ink)
                            Txt(if (bytes > 0) "~" + formatBytes(bytes) else "size ?", style = Hamstr.type.small, color = if (on) c.onAccent else c.ghost)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Segmented(
                    listOf(false, true), replace,
                    { if (it) "Replace ${formatBadge(current)}" else "Keep both" },
                    { replace = it },
                    background = c.canvas,
                )
                Spacer(Modifier.height(6.dp))
                Txt(
                    if (replace) "${formatBadge(current)} stays until the new file is in, then it's deleted."
                    else "The new files sit next to ${formatBadge(current)}. Switch between them any time.",
                    style = Hamstr.type.small, color = c.ghost, modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        HButton(
            when (picked.size) { 0 -> "Pick a quality"; 1 -> "Stash it"; else -> "Stash ${picked.size}" },
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
                onDone(if (picked.size == 1) "Fetching another quality" else "Fetching ${picked.size} qualities")
            },
            modifier = Modifier.fillMaxWidth(),
            kind = ButtonKind.Primary,
            icon = R.drawable.ic_download,
            enabled = picked.isNotEmpty(),
        )
    }
}
