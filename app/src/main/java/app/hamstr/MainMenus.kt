package app.hamstr

import app.hamstr.ui.PlaylistFan
import app.hamstr.ui.components.Badge
import androidx.compose.ui.res.pluralStringResource
import org.jetbrains.compose.resources.stringResource as sharedString
import org.jetbrains.compose.resources.DrawableResource
import app.hamstr.res.*
import app.hamstr.ui.rememberPlaylist
import app.hamstr.data.Playlists
import android.content.ClipboardManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.hamstr.data.DownloadStore
import androidx.compose.foundation.layout.heightIn
import app.hamstr.data.DownloadTask
import app.hamstr.data.Status
import app.hamstr.data.Mode
import app.hamstr.ui.toInfo
import app.hamstr.engine.DownloadService
import app.hamstr.engine.Engine
import app.hamstr.engine.Failures
import app.hamstr.engine.Fix
import app.hamstr.player.Kind
import app.hamstr.player.Playback
import app.hamstr.player.kindOf
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import app.hamstr.ui.formatBytes
import app.hamstr.ui.formatBadge
import app.hamstr.ui.rememberImage
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.Image
import app.hamstr.ui.canHaveVersions
import app.hamstr.ui.canSaveAudio
import app.hamstr.ui.DownloadPreview
import app.hamstr.ui.components.ButtonKind
import app.hamstr.ui.components.HButton
import app.hamstr.ui.components.Icon
import app.hamstr.ui.components.Txt
import app.hamstr.ui.theme.Hamstr
import app.hamstr.ui.theme.Radii
import java.io.File
import app.hamstr.ui.components.Sheet
import app.hamstr.ui.components.pressable

internal fun copy(context: android.content.Context, text: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("Hamstr", text))
    ClipOffers.copiedHere(context)
}

@Composable
internal fun TaskMenu(
    task: DownloadTask,
    gone: Boolean,
    onPlay: (DownloadTask) -> Unit,
    onPlayNext: (DownloadTask) -> Unit,
    onAddToQueue: (DownloadTask) -> Unit,
    onFavorite: (DownloadTask) -> Unit,
    onClose: () -> Unit,
    onQuality: (DownloadTask) -> Unit,
    onSaveAudio: (DownloadTask) -> Unit,
    onRename: (DownloadTask) -> Unit,
    onInfo: (DownloadTask) -> Unit,
    onAddToPlaylist: (DownloadTask) -> Unit,
    onHide: (List<DownloadTask>) -> Unit,
    onDelete: (DownloadTask) -> Unit,
    onAgain: (DownloadTask) -> Unit,
    onCopied: (String) -> Unit,
    onShare: (DownloadTask) -> Unit,
) {
    val c = Hamstr.colors
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    // The menu stays open while the download moves on, so it follows the live task.
    val tasks by DownloadStore.tasks.collectAsState()
    val t = tasks.firstOrNull { it.id == task.id } ?: task
    val file = t.filePath?.let(::File)?.takeIf { it.exists() }
    var updating by remember { mutableStateOf(false) }
    Sheet {
        if (t.status != Status.Done && t.status != Status.Canceled) {
            DownloadPreview(t)
            Spacer(Modifier.height(12.dp))
        }
        MenuHeader(t, showPicture = t.status == Status.Done)
        Spacer(Modifier.height(14.dp))
        if (t.status == Status.Failed) {
            // What happened, in plain words, with the engine's own message underneath.
            val why = Failures.explain(t.error)
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(Radii.row)).background(c.surface).padding(12.dp)) {
                Txt(sharedString(why.title), style = Hamstr.type.bodyMedium, color = c.red)
                Spacer(Modifier.height(2.dp))
                Txt(sharedString(why.hint), style = Hamstr.type.small, color = c.muted)
                t.error?.let {
                    Spacer(Modifier.height(8.dp))
                    Txt(it, style = Hamstr.type.mono, color = c.ghost)
                }
                if (why.fix == Fix.UpdateYtDlp) {
                    Spacer(Modifier.height(10.dp))
                    HButton(
                        stringResource(if (updating) R.string.err_updating else R.string.err_update_retry),
                        {
                            if (!updating) scope.launch {
                                updating = true
                                Engine.update(context)
                                updating = false
                                DownloadService.send(context, DownloadService.ACTION_RESUME, t.id)
                                onClose()
                            }
                        },
                        Modifier.fillMaxWidth(), kind = ButtonKind.Primary, icon = Res.drawable.ic_refresh, enabled = !updating,
                    )
                }
            }
            // Locked with DRM where it was shared: maybe YouTube Music has it.
            if (why.title == Res.string.err_drm) {
                Spacer(Modifier.height(6.dp))
                app.hamstr.ui.ElsewhereCard(t.url, t.title, t.uploader, t.durationSec) { hit ->
                    DownloadService.stashAlternative(context, t.id, hit.url, hit.toInfo(), t.choice.copy(mode = Mode.Audio))
                    onCopied(resources.getString(R.string.drm_stashing))
                    onClose()
                }
            }
            Spacer(Modifier.height(6.dp))
            t.error?.let { err ->
                MenuRow(Res.drawable.ic_content_paste, stringResource(R.string.err_copy)) { copy(context, err); onCopied(resources.getString(R.string.err_copied)) }
            }
        }
        if (gone && t.status == Status.Done) {
            Txt(stringResource(R.string.file_gone_long), style = Hamstr.type.small, color = c.muted, modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp))
            MenuRow(Res.drawable.ic_refresh, stringResource(R.string.download_again)) { onAgain(t) }
        }
        if (file != null) {
            val kind = kindOf(t)
            val playable = kind == Kind.Video || kind == Kind.Audio
            // The everyday actions as tiles, then what changes the file, then what removes it.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionTile(
                    if (playable) Res.drawable.ic_play_arrow else Res.drawable.ic_open_in_new,
                    stringResource(if (playable) R.string.menu_tile_play else R.string.menu_tile_view),
                    accent = true,
                ) { onPlay(t); onClose() }
                ActionTile(Res.drawable.ic_share, stringResource(R.string.menu_tile_share)) { onShare(t) }
                ActionTile(Res.drawable.ic_open_in_new, stringResource(R.string.menu_tile_open)) { Playback.openWith(context, t); onClose() }
            }
            Spacer(Modifier.height(10.dp))
            if (playable) {
                val lists by Playlists.lists.collectAsState()
                val fav = remember(lists, t.id) { Playlists.isFavorite(t) }
                MenuGroup {
                    MenuRow(Res.drawable.ic_play_next, stringResource(R.string.play_next)) { onPlayNext(t) }
                    MenuRow(Res.drawable.ic_add_queue, stringResource(R.string.add_to_queue)) { onAddToQueue(t) }
                    MenuRow(if (fav) Res.drawable.ic_favorite else Res.drawable.ic_favorite_border, stringResource(if (fav) R.string.fav_remove else R.string.fav_add)) { onFavorite(t) }
                }
                Spacer(Modifier.height(10.dp))
            }
            MenuGroup {
                if (canHaveVersions(t)) MenuRow(Res.drawable.ic_movie, stringResource(R.string.menu_quality)) { onQuality(t) }
                if (canSaveAudio(t, tasks.filter { it.family == t.family })) MenuRow(Res.drawable.ic_music_note, stringResource(R.string.menu_save_audio)) { onSaveAudio(t) }
                if (playable) MenuRow(Res.drawable.ic_playlist_add, stringResource(R.string.pl_add)) { onAddToPlaylist(t) }
                MenuRow(Res.drawable.ic_edit, stringResource(R.string.menu_rename)) { onRename(t) }
                MenuRow(Res.drawable.ic_info, stringResource(R.string.menu_info)) { onInfo(t) }
            }
            Spacer(Modifier.height(10.dp))
        } else {
            if (t.url.startsWith("http")) MenuRow(Res.drawable.ic_share, stringResource(R.string.menu_tile_share)) { onShare(t) }
        }
        if (t.status == Status.Failed || t.status == Status.Paused) {
            MenuRow(Res.drawable.ic_refresh, stringResource(R.string.err_try_again)) { DownloadService.send(context, DownloadService.ACTION_RESUME, t.id); onClose() }
        }
        when {
            t.status.active -> MenuRow(Res.drawable.ic_close, stringResource(R.string.menu_cancel)) { DownloadService.send(context, DownloadService.ACTION_CANCEL, t.id); onClose() }
            file != null -> MenuGroup {
                // Opened from Settings › Hidden, the file comes back instead.
                if (t.hidden) MenuRow(Res.drawable.ic_restore, stringResource(R.string.hidden_restore)) {
                    DownloadStore.setHidden(setOf(t.id), false)
                    onCopied(resources.getString(R.string.hidden_restored))
                    onClose()
                } else MenuRow(Res.drawable.ic_visibility_off, stringResource(R.string.menu_hide), subtitle = stringResource(R.string.menu_hide_hint)) { onHide(listOf(t)) }
                MenuRow(Res.drawable.ic_delete, stringResource(R.string.menu_delete), danger = true) { onDelete(t) }
            }
            else -> MenuRow(Res.drawable.ic_close, stringResource(R.string.menu_remove)) { onHide(listOf(t)) }
        }
    }
}

/** Picture, title and one line of facts (channel, quality, size) at the top of the menu. */
@Composable
internal fun MenuHeader(t: DownloadTask, showPicture: Boolean) {
    val c = Hamstr.colors
    Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (showPicture) {
            val bitmap = rememberImage(t.thumbnail, 240)
            // A song's cover is square, as everywhere else songs show.
            val song = kindOf(t) == Kind.Audio
            Box(Modifier.width(if (song) 56.dp else 88.dp).aspectRatio(if (song) 1f else 16f / 9f).clip(RoundedCornerShape(10.dp)).background(c.black)) {
                bitmap?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            }
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Txt(t.title, style = Hamstr.type.bodyMedium, maxLines = 2)
            Spacer(Modifier.height(3.dp))
            val facts = listOfNotNull(
                t.uploader ?: t.source,
                formatBadge(t).takeIf { t.status == Status.Done }?.uppercase(),
                t.sizeBytes.takeIf { it > 0 }?.let(::formatBytes),
            )
            Txt(facts.joinToString(" · ").ifEmpty { t.url }, style = Hamstr.type.mono, color = c.ghost, maxLines = 1)
        }
    }
}

/** A button in the menu's top row: icon over a short label. */
@Composable
internal fun RowScope.ActionTile(icon: DrawableResource, label: String, accent: Boolean = false, onClick: () -> Unit) {
    val c = Hamstr.colors
    Column(
        Modifier
            .weight(1f)
            .clip(RoundedCornerShape(16.dp))
            .background(if (accent) c.accent else c.surface)
            .pressable(onClick)
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, tint = if (accent) c.onAccent else c.ink, size = 22.dp)
        Spacer(Modifier.height(6.dp))
        Txt(label, style = Hamstr.type.small, color = if (accent) c.onAccent else c.muted, maxLines = 1)
    }
}

/**
 * Holding a playlist: what is done with it as a whole, without opening it. Play or shuffle
 * it, share its files, queue them, rename it, or delete it (its files stay; a downloaded
 * one can take its files along).
 */
@Composable
internal fun PlaylistMenu(
    id: String,
    onPlay: (DownloadTask, List<DownloadTask>) -> Unit,
    onQueue: (List<DownloadTask>, Boolean, String) -> Unit,
    onRename: () -> Unit,
    onDeleteFiles: (List<DownloadTask>) -> Unit,
    onShare: (List<DownloadTask>) -> Unit,
    onClose: () -> Unit,
    onHint: (String) -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val p = rememberPlaylist(id)
    LaunchedEffect(p == null) { if (p == null) onClose() }
    if (p == null) return
    val items = p.items
    val name = p.name.ifEmpty { stringResource(R.string.lib_playlist) }
    var confirm by remember(id) { mutableStateOf(false) }
    Sheet {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
            PlaylistFan(items, Modifier.width(if (p.songs) 72.dp else 104.dp), square = p.songs)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Txt(name, style = Hamstr.type.section, maxLines = 2)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Badge(pluralStringResource(R.plurals.lib_files, items.size, items.size), accent = true)
                    if (items.isNotEmpty()) Badge(formatBytes(items.sumOf { it.sizeBytes }))
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        if (items.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionTile(Res.drawable.ic_play_arrow, stringResource(R.string.pl_play), accent = true) { onPlay(items.first(), items); onClose() }
                ActionTile(Res.drawable.ic_shuffle, stringResource(R.string.pl_shuffle)) { val s = items.shuffled(); onPlay(s.first(), s); onClose() }
                ActionTile(Res.drawable.ic_share, stringResource(R.string.menu_tile_share)) { onShare(items) }
            }
            Spacer(Modifier.height(10.dp))
            MenuGroup {
                MenuRow(Res.drawable.ic_play_next, stringResource(R.string.play_next)) { onQueue(items, true, name); onClose() }
                MenuRow(Res.drawable.ic_add_queue, stringResource(R.string.add_to_queue)) { onQueue(items, false, name); onClose() }
            }
            Spacer(Modifier.height(10.dp))
        }
        MenuGroup { MenuRow(Res.drawable.ic_edit, stringResource(R.string.pl_rename)) { onRename() } }
        Spacer(Modifier.height(10.dp))
        MenuGroup {
            MenuRow(Res.drawable.ic_delete, stringResource(if (confirm) R.string.pl_delete_confirm else R.string.pl_delete), danger = true) {
                if (!confirm) confirm = true else {
                    Playlists.delete(id)
                    onHint(resources.getString(R.string.pl_deleted))
                    onClose()
                }
            }
            if (p.downloaded && items.isNotEmpty()) {
                MenuRow(Res.drawable.ic_delete, stringResource(R.string.pl_delete_files), danger = true) { onDeleteFiles(items); onClose() }
            }
        }
    }
}

/** Rows that belong together, on one panel. */
@Composable
internal fun MenuGroup(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Hamstr.colors.surface).padding(4.dp),
        content = content,
    )
}

@Composable
internal fun MenuRow(icon: DrawableResource, label: String, danger: Boolean = false, subtitle: String? = null, onClick: () -> Unit) {
    val c = Hamstr.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, tint = if (danger) c.red else c.muted)
        Column {
            Txt(label, color = if (danger) c.red else c.ink)
            if (subtitle != null) Txt(subtitle, style = Hamstr.type.small, color = c.ghost)
        }
    }
}
