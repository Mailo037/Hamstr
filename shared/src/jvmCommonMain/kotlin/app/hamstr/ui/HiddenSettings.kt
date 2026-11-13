package app.hamstr.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.hamstr.Platform
import app.hamstr.data.DownloadStore
import app.hamstr.data.DownloadTask
import app.hamstr.data.Status
import app.hamstr.res.*
import app.hamstr.ui.components.AsciiImage
import app.hamstr.ui.components.ButtonKind
import app.hamstr.ui.components.Divider
import app.hamstr.ui.components.Group
import app.hamstr.ui.components.HButton
import app.hamstr.ui.components.SwipeAction
import app.hamstr.ui.components.SwipeRow
import app.hamstr.ui.components.Txt
import app.hamstr.ui.components.pressable
import app.hamstr.ui.components.swipeActions
import app.hamstr.ui.theme.Hamstr
import app.hamstr.ui.theme.Radii
import org.jetbrains.compose.resources.stringResource

/**
 * Settings › Hidden, on both apps: the files hidden from the library, to show again or delete for
 * good. A click (or a right-click) opens the file's menu. Without [onDelete] (the computer for
 * now) there is only showing them again.
 */
@Composable
fun HiddenSettings(onDelete: ((List<DownloadTask>) -> Unit)?, onHint: (String) -> Unit, onMenu: (DownloadTask) -> Unit) {
    val c = Hamstr.colors
    val tasks by DownloadStore.tasks.collectAsState()
    val hidden = remember(tasks) { tasks.filter { it.hidden && it.status == Status.Done } }
    var confirm by remember { mutableStateOf(false) }
    val restored = stringResource(Res.string.hidden_restored)
    if (hidden.isEmpty()) {
        Txt(stringResource(if (Platform.desktop) Res.string.hidden_empty_desktop else Res.string.hidden_empty), color = c.muted, modifier = Modifier.padding(horizontal = 6.dp))
        return
    }
    Group {
        hidden.forEachIndexed { i, t ->
            if (i > 0) Divider()
            // Like the stash: swipe right to show it again, left to delete it (icons on a desktop).
            key(t.id) {
                SwipeRow(
                    start = SwipeAction(Res.drawable.ic_restore, stringResource(Res.string.hidden_restore), c.accent, removes = true) {
                        DownloadStore.setHidden(setOf(t.id), false)
                        onHint(restored)
                    },
                    end = onDelete?.let { delete -> SwipeAction(Res.drawable.ic_delete, stringResource(Res.string.menu_delete), c.red, removes = true) { delete(listOf(t)) } },
                    background = c.overlay,
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(Radii.row))
                            .swipeActions()
                            .pressable({ onMenu(t) }, onLongClick = { onMenu(t) })
                            .heightIn(min = 64.dp)
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(52.dp).clip(RoundedCornerShape(Radii.thumb)).background(c.black)) {
                            AsciiImage(rememberImage(t.thumbnail, 160), reveal = 1f, cell = 4.dp, modifier = Modifier.size(52.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Txt(t.title, style = Hamstr.type.bodyMedium, maxLines = 2)
                            Spacer(Modifier.height(2.dp))
                            Txt(
                                listOfNotNull(t.uploader ?: t.source, t.sizeBytes.takeIf { it > 0 }?.let(::formatBytes)).joinToString(" · "),
                                style = Hamstr.type.small, color = c.ghost, maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        HButton(stringResource(Res.string.hidden_restore_all), {
            DownloadStore.setHidden(hidden.map { it.id }.toSet(), false)
            onHint(restored)
        }, Modifier.weight(1f), kind = ButtonKind.Neutral, icon = Res.drawable.ic_restore)
        if (onDelete != null) {
            HButton(
                stringResource(if (confirm) Res.string.hidden_delete_confirm else Res.string.hidden_delete_all),
                { if (!confirm) confirm = true else { confirm = false; onDelete(hidden) } },
                Modifier.weight(1f), kind = ButtonKind.Ghost, icon = Res.drawable.ic_delete,
            )
        }
    }
    Spacer(Modifier.height(12.dp))
    Txt(
        stringResource(if (Platform.desktop) Res.string.hidden_note_desktop else Res.string.hidden_note),
        style = Hamstr.type.small, color = c.ghost, modifier = Modifier.padding(horizontal = 6.dp),
    )
}
