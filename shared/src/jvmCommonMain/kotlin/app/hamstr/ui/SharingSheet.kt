package app.hamstr.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.hamstr.data.DownloadTask
import app.hamstr.data.Status
import app.hamstr.res.*
import app.hamstr.ui.components.AsciiImage
import app.hamstr.ui.components.HButton
import app.hamstr.ui.components.Icon
import app.hamstr.ui.components.Segmented
import app.hamstr.ui.components.Sheet
import app.hamstr.ui.components.SheetHeader
import app.hamstr.ui.components.SquareButton
import app.hamstr.ui.components.Specs
import app.hamstr.ui.components.Txt
import app.hamstr.ui.theme.Hamstr
import app.hamstr.ui.theme.Radii
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import java.io.File

/** The outgoing share sheet; the Android ShareSheet is the incoming download picker. */
data class ShareContent(
    val url: String? = null,
    val title: String? = null,
    val thumbnail: String? = null,
    val files: List<DownloadTask> = emptyList(),
) {
    companion object {
        fun from(tasks: List<DownloadTask>, title: String? = null): ShareContent {
            val single = tasks.singleOrNull()
            return ShareContent(
                url = single?.url?.takeIf { it.startsWith("https://") || it.startsWith("http://") },
                title = title ?: single?.title,
                thumbnail = tasks.firstOrNull()?.thumbnail,
                // A partial download is never offered as a finished file.
                files = tasks.filter { it.status == Status.Done && it.filePath?.let(::File)?.isFile == true },
            )
        }
    }
}

private enum class ShareKind { Link, File }

/** One place to copy, share through the system, or send a link to another Hamstr. */
@Composable
fun SharingSheet(
    content: ShareContent,
    onCopyLink: (String) -> Unit,
    onShareLink: ((String) -> Unit)? = null,
    onShareFiles: (() -> Unit)? = null,
    onCopyFiles: (() -> Unit)? = null,
    fileActionLabel: StringResource = Res.string.share_other_apps,
    fileActionIcon: DrawableResource = Res.drawable.ic_more_vert,
) {
    val c = Hamstr.colors
    val kinds = buildList {
        if (content.url != null) add(ShareKind.Link)
        if (content.files.isNotEmpty()) add(ShareKind.File)
    }
    var selected by remember(content) { mutableStateOf(kinds.lastOrNull() ?: ShareKind.Link) }
    val targets = remember(content) { NearbyTargetsState() }
    val scope = rememberCoroutineScope()
    val linkLabel = stringResource(Res.string.share_link)
    val fileLabel = stringResource(if (content.files.size > 1) Res.string.share_files else Res.string.share_file)
    val previewTitle = content.title ?: if (content.files.isNotEmpty()) {
        pluralStringResource(Res.plurals.lib_files, content.files.size, content.files.size)
    } else content.url.orEmpty()
    val bitmap = rememberImage(content.thumbnail, 160)

    Sheet {
        SheetHeader(stringResource(Res.string.menu_tile_share))
        Spacer(Modifier.height(16.dp))
        SheetHeader(
            previewTitle,
            subtitle = if (selected == ShareKind.Link) content.url else content.files.singleOrNull()?.filePath?.let { File(it).name }
                ?: pluralStringResource(Res.plurals.lib_files, content.files.size, content.files.size),
            subtitleLines = 1,
            leading = {
                Box(Modifier.size(52.dp).clip(RoundedCornerShape(Radii.thumb)).background(c.surface), contentAlignment = Alignment.Center) {
                    if (content.thumbnail != null) AsciiImage(bitmap, reveal = 1f, cell = 5.dp, modifier = Modifier.size(52.dp))
                    else Icon(if (selected == ShareKind.Link) Res.drawable.ic_link else Res.drawable.ic_description, tint = c.accent, size = 24.dp)
                }
            },
        )
        Spacer(Modifier.height(16.dp))
        if (kinds.size > 1) {
            Segmented(kinds, selected, { if (it == ShareKind.Link) linkLabel else fileLabel }, { selected = it })
            Spacer(Modifier.height(16.dp))
        }
        AnimatedContent(
            selected,
            transitionSpec = { fadeIn(Specs.appear()) togetherWith fadeOut(Specs.disappear()) },
            label = "shareKind",
        ) { kind ->
            Column {
                if (kind == ShareKind.Link && content.url != null) {
                    Txt(stringResource(Res.string.nearby_send_title), style = Hamstr.type.bodyMedium)
                    Spacer(Modifier.height(10.dp))
                    NearbyTargets(content.url, content.title, targets, scope)
                    Spacer(Modifier.height(16.dp))
                    // Other apps sit in the small square before Copy, the one most taps want.
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (onShareLink != null) SquareButton(Res.drawable.ic_more_vert, stringResource(Res.string.share_other_apps), { onShareLink(content.url) })
                        HButton(stringResource(Res.string.menu_copy_link), { onCopyLink(content.url) }, Modifier.weight(1f), icon = Res.drawable.ic_link)
                    }
                } else if (kind == ShareKind.File && content.files.isNotEmpty()) {
                    Txt(stringResource(Res.string.nearby_send_title), style = Hamstr.type.bodyMedium)
                    Spacer(Modifier.height(4.dp))
                    Txt(stringResource(Res.string.share_file_nearby_hint), style = Hamstr.type.small, color = c.muted)
                    Spacer(Modifier.height(10.dp))
                    NearbyFileTargets(content.files.mapNotNull { it.filePath?.let(::File) }, targets, scope)
                    Spacer(Modifier.height(16.dp))
                    if (onCopyFiles != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (onShareFiles != null) SquareButton(fileActionIcon, stringResource(fileActionLabel), onShareFiles)
                            HButton(stringResource(if (content.files.size > 1) Res.string.share_copy_files else Res.string.share_copy_file), onCopyFiles, Modifier.weight(1f), icon = Res.drawable.ic_content_paste)
                        }
                    } else if (onShareFiles != null) {
                        HButton(stringResource(fileActionLabel), onShareFiles, Modifier.fillMaxWidth(), icon = Res.drawable.ic_share)
                    }
                } else {
                    Txt(stringResource(Res.string.share_unavailable), style = Hamstr.type.small, color = c.muted)
                }
            }
        }
    }
}
