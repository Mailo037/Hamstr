package app.hamstr.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import app.hamstr.R
import app.hamstr.data.DownloadFiles
import app.hamstr.data.DownloadTask
import app.hamstr.ui.components.ButtonKind
import app.hamstr.ui.components.sheetScroll
import app.hamstr.ui.components.HButton
import app.hamstr.ui.components.Icon
import app.hamstr.ui.components.IconButton
import app.hamstr.ui.components.Txt
import app.hamstr.ui.theme.Hamstr
import app.hamstr.ui.theme.Radii
import java.io.File

/** Renames a stashed file; the extension is shown but can't be changed. */
@Composable
fun RenameSheet(task: DownloadTask, onRename: (String) -> Unit) {
    val c = Hamstr.colors
    val start = remember(task.id) { DownloadFiles.baseName(task) }
    val ext = remember(task.id) { task.filePath?.let { File(it).extension }.orEmpty() }
    var value by remember(task.id) { mutableStateOf(TextFieldValue(start, TextRange(0, start.length))) }
    val name = value.text.trim()
    val submit = { if (name.isNotEmpty()) onRename(name) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(task.id) { focus.requestFocus() }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = Radii.sheet, topEnd = Radii.sheet))
            .background(c.overlay)
            .clickable(enabled = false) {}
            .sheetScroll()
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Box(Modifier.align(Alignment.CenterHorizontally).width(36.dp).height(4.dp).clip(RoundedCornerShape(4.dp)).background(c.line))
        Spacer(Modifier.height(16.dp))
        Txt("Rename file", style = Hamstr.type.section, modifier = Modifier.padding(horizontal = 4.dp))
        Spacer(Modifier.height(16.dp))

        BasicTextField(
            value = value,
            onValueChange = { value = it },
            singleLine = true,
            textStyle = Hamstr.type.body.copy(color = c.ink),
            cursorBrush = SolidColor(c.accent),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            modifier = Modifier.focusRequester(focus),
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
                    Icon(R.drawable.ic_edit, tint = if (name.isNotEmpty()) c.accent else c.ghost, size = 18.dp)
                    Spacer(Modifier.width(10.dp))
                    Box(Modifier.weight(1f)) {
                        if (value.text.isEmpty()) Txt("File name", color = c.ghost, maxLines = 1)
                        field()
                    }
                    if (ext.isNotEmpty()) Txt(".$ext", style = Hamstr.type.mono, color = c.ghost, modifier = Modifier.padding(start = 6.dp))
                    if (value.text.isNotEmpty()) {
                        IconButton(R.drawable.ic_close, "Clear", { value = TextFieldValue("") }, background = Color.Transparent, size = 36.dp)
                    }
                }
            },
        )

        Spacer(Modifier.height(12.dp))
        HButton("Rename", { submit() }, Modifier.fillMaxWidth(), kind = ButtonKind.Primary, icon = R.drawable.ic_edit, enabled = name.isNotEmpty() && name != start)
        Spacer(Modifier.height(8.dp))
    }
}
