package app.hamstr.ui

import app.hamstr.res.*
import org.jetbrains.compose.resources.stringResource
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import app.hamstr.Platform
import app.hamstr.ui.components.Badge
import app.hamstr.ui.components.ButtonKind
import app.hamstr.ui.components.HButton
import app.hamstr.ui.components.HamstrMark
import app.hamstr.ui.components.Icon
import app.hamstr.ui.components.Sheet
import app.hamstr.ui.components.SheetField
import app.hamstr.ui.components.SheetHeader
import app.hamstr.ui.components.Txt
import app.hamstr.ui.components.pressable
import app.hamstr.ui.theme.Hamstr
import app.hamstr.ui.theme.Radii

/**
 * The "+" sheet: type or paste a link, take the one on the clipboard, or bring in files
 * that are already on the phone or the computer. [onSend] sends the link to another device instead.
 */
@Composable
fun AddSheet(clipboardLink: String?, onLink: (String) -> Unit, onFiles: () -> Unit, onSend: ((String) -> Unit)? = null) {
    val c = Hamstr.colors
    var text by remember { mutableStateOf(TextFieldValue("")) }
    val link = app.hamstr.engine.MediaLinks.find(text.text)
    val submit = { if (link != null) onLink(link) }

    Sheet {
        SheetHeader(stringResource(Res.string.add_title), leading = { HamstrMark(36.dp) })
        Spacer(Modifier.height(16.dp))
        SheetField(
            text, { text = it }, stringResource(Res.string.add_hint), Res.drawable.ic_link,
            mono = true,
            lit = link != null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onGo = { submit() }),
        )

        AnimatedVisibility(clipboardLink != null && text.text.isEmpty(), enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Row(
                Modifier
                    .padding(top = 8.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radii.control))
                    .pressable({ clipboardLink?.let { text = TextFieldValue(it, TextRange(it.length)) } })
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(Res.drawable.ic_content_paste, tint = c.muted, size = 18.dp)
                Badge(stringResource(Res.string.add_clipboard), accent = true)
                Txt(clipboardLink.orEmpty(), style = Hamstr.type.mono, color = c.muted, maxLines = 1, modifier = Modifier.weight(1f))
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HButton(stringResource(Res.string.add_stash), { submit() }, Modifier.weight(1f), kind = ButtonKind.Primary, icon = Res.drawable.ic_download, enabled = link != null)
            // Or hand it to another Hamstr nearby, like AirDrop.
            if (onSend != null) HButton(stringResource(Res.string.nearby_send_short), { link?.let(onSend) }, icon = Res.drawable.ic_send, enabled = link != null)
        }

        Row(Modifier.padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).height(1.dp).background(c.line))
            Txt(stringResource(Res.string.add_or), style = Hamstr.type.mono, color = c.ghost, modifier = Modifier.padding(horizontal = 12.dp))
            Box(Modifier.weight(1f).height(1.dp).background(c.line))
        }

        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Radii.row))
                .background(c.surface)
                .pressable(onFiles)
                .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(c.raised), contentAlignment = Alignment.Center) {
                Icon(Res.drawable.ic_folder_open, tint = c.accent, size = 20.dp)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Txt(stringResource(if (Platform.desktop) Res.string.add_files_desktop else Res.string.add_files), style = Hamstr.type.bodyMedium)
                Spacer(Modifier.height(2.dp))
                Txt(stringResource(if (Platform.desktop) Res.string.add_files_hint_desktop else Res.string.add_files_hint), style = Hamstr.type.small, color = c.ghost)
            }
            Icon(Res.drawable.ic_chevron_right, tint = c.ghost, size = 20.dp)
        }
    }
}
