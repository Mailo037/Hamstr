package app.hamstr.ui

import androidx.compose.ui.res.stringResource
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.hamstr.R
import app.hamstr.ui.components.Badge
import app.hamstr.ui.components.ButtonKind
import app.hamstr.ui.components.HButton
import app.hamstr.ui.components.HamstrMark
import app.hamstr.ui.components.Icon
import app.hamstr.ui.components.IconButton
import app.hamstr.ui.components.Txt
import app.hamstr.ui.components.pressable
import app.hamstr.ui.theme.Hamstr
import app.hamstr.ui.theme.Radii

private val LINK = Regex("""https?://\S+""")

/**
 * The "+" sheet: type or paste a link, take the one on the clipboard, or bring in files
 * that are already on the phone.
 */
@Composable
fun AddSheet(clipboardLink: String?, onLink: (String) -> Unit, onFiles: () -> Unit) {
    val c = Hamstr.colors
    var text by remember { mutableStateOf("") }
    val link = LINK.find(text)?.value
    val submit = { if (link != null) onLink(link) }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = Radii.sheet, topEnd = Radii.sheet))
            .background(c.overlay)
            .clickable(enabled = false) {}
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Box(Modifier.align(Alignment.CenterHorizontally).width(36.dp).height(4.dp).clip(RoundedCornerShape(4.dp)).background(c.line))
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
            HamstrMark(36.dp)
            Spacer(Modifier.width(12.dp))
            Txt(stringResource(R.string.add_title), style = Hamstr.type.section)
        }
        Spacer(Modifier.height(16.dp))

        BasicTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            textStyle = Hamstr.type.mono.copy(color = c.ink),
            cursorBrush = SolidColor(c.accent),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onGo = { submit() }),
            decorationBox = { field ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Radii.control))
                        .background(c.surface)
                        .padding(start = 14.dp, end = 6.dp)
                        .height(52.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(R.drawable.ic_link, tint = if (link != null) c.accent else c.ghost, size = 18.dp)
                    Spacer(Modifier.width(10.dp))
                    Box(Modifier.weight(1f)) {
                        if (text.isEmpty()) Txt(stringResource(R.string.add_hint), style = Hamstr.type.mono, color = c.ghost, maxLines = 1)
                        field()
                    }
                    if (text.isNotEmpty()) {
                        IconButton(R.drawable.ic_close, stringResource(R.string.clear), { text = "" }, background = androidx.compose.ui.graphics.Color.Transparent, size = 36.dp)
                    }
                }
            },
        )

        AnimatedVisibility(clipboardLink != null && text.isEmpty(), enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Row(
                Modifier
                    .padding(top = 8.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radii.control))
                    .pressable({ text = clipboardLink.orEmpty() })
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(R.drawable.ic_content_paste, tint = c.muted, size = 18.dp)
                Badge(stringResource(R.string.add_clipboard), accent = true)
                Txt(clipboardLink.orEmpty(), style = Hamstr.type.mono, color = c.muted, maxLines = 1, modifier = Modifier.weight(1f))
            }
        }

        Spacer(Modifier.height(12.dp))
        HButton(stringResource(R.string.add_stash), { submit() }, Modifier.fillMaxWidth(), kind = ButtonKind.Primary, icon = R.drawable.ic_download, enabled = link != null)

        Row(Modifier.padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).height(1.dp).background(c.line))
            Txt(stringResource(R.string.add_or), style = Hamstr.type.mono, color = c.ghost, modifier = Modifier.padding(horizontal = 12.dp))
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
                Icon(R.drawable.ic_folder_open, tint = c.accent, size = 20.dp)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Txt(stringResource(R.string.add_files), style = Hamstr.type.bodyMedium)
                Spacer(Modifier.height(2.dp))
                Txt(stringResource(R.string.add_files_hint), style = Hamstr.type.small, color = c.ghost)
            }
            Icon(R.drawable.ic_chevron_right, tint = c.ghost, size = 20.dp)
        }
        Spacer(Modifier.height(8.dp))
    }
}
