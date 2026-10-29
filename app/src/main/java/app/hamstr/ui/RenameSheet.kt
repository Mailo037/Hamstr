package app.hamstr.ui

import app.hamstr.res.*
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import app.hamstr.R
import app.hamstr.data.DownloadFiles
import app.hamstr.data.DownloadTask
import app.hamstr.ui.components.ButtonKind
import app.hamstr.ui.components.HButton
import app.hamstr.ui.components.Sheet
import app.hamstr.ui.components.SheetField
import app.hamstr.ui.components.SheetHeader
import java.io.File

/** Renames a stashed file; the extension is shown but can't be changed. */
@Composable
fun RenameSheet(task: DownloadTask, onRename: (String) -> Unit) {
    val start = remember(task.id) { DownloadFiles.baseName(task) }
    val ext = remember(task.id) { task.filePath?.let { File(it).extension }.orEmpty() }
    var value by remember(task.id) { mutableStateOf(TextFieldValue(start, TextRange(0, start.length))) }
    val name = value.text.trim()
    val submit = { if (name.isNotEmpty()) onRename(name) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(task.id) { focus.requestFocus() }

    Sheet {
        SheetHeader(stringResource(R.string.rename_title))
        Spacer(Modifier.height(16.dp))
        SheetField(
            value, { value = it }, stringResource(R.string.rename_hint), Res.drawable.ic_edit,
            modifier = Modifier.focusRequester(focus),
            suffix = ext.takeIf { it.isNotEmpty() }?.let { ".$it" },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
        )
        Spacer(Modifier.height(12.dp))
        HButton(
            stringResource(R.string.menu_rename), { submit() }, Modifier.fillMaxWidth(),
            kind = ButtonKind.Primary, icon = Res.drawable.ic_edit, enabled = name.isNotEmpty() && name != start,
        )
    }
}
