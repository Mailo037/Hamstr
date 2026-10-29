package app.hamstr.ui.components

import org.jetbrains.compose.resources.DrawableResource
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import app.hamstr.res.*
import app.hamstr.ui.theme.Hamstr
import app.hamstr.ui.theme.Radii

/**
 * A bottom sheet for [Overlay][app.hamstr.ui.Overlay]: the handle, the corners, the padding
 * and room for the system bars and the keyboard. It changes height smoothly as its content
 * does. With [scroll] the whole body scrolls when it doesn't fit (see [sheetScroll]); a sheet
 * with a list of its own passes false and gives that list `Modifier.weight(1f, fill = false)`,
 * so the list takes the room that is left and the buttons stay put.
 */
@Composable
fun Sheet(scroll: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    val c = Hamstr.colors
    val body = if (scroll) Modifier.sheetScroll() else Modifier.heightIn(max = windowHeight() * SHEET_MAX)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = Radii.sheet, topEnd = Radii.sheet))
            .background(c.overlay)
            // Taps on the sheet stay on it instead of reaching the backdrop behind.
            .clickable(enabled = false) {}
            .then(body)
            .navigationBarsPadding()
            .imePadding()
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp)
            .animateContentSize(Specs.blend()),
    ) {
        Box(Modifier.align(Alignment.CenterHorizontally).width(36.dp).height(4.dp).clip(RoundedCornerShape(4.dp)).background(c.line))
        Spacer(Modifier.height(16.dp))
        content()
    }
}

/** How much of the screen a sheet may cover. */
internal const val SHEET_MAX = 0.9f

/**
 * A sheet's title, with an optional line under it, something in front (the mark) and an
 * accent [badge] after the title.
 */
@Composable
fun SheetHeader(
    title: String,
    subtitle: String? = null,
    badge: String? = null,
    subtitleLines: Int = 2,
    leading: (@Composable () -> Unit)? = null,
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Txt(title, style = Hamstr.type.section, maxLines = 2, modifier = Modifier.weight(1f, fill = false))
                if (badge != null) Badge(badge, accent = true, modifier = Modifier.padding(start = 8.dp))
            }
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Txt(subtitle, style = Hamstr.type.small, color = Hamstr.colors.ghost, maxLines = subtitleLines)
            }
        }
    }
}

/**
 * A text field on a sheet. [icon] turns accent while [lit] (by default, once there is text),
 * a clear button appears with the text, and [suffix] shows what can't be changed (a file
 * extension). [mono] is for links and format codes.
 */
@Composable
fun SheetField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    placeholder: String,
    icon: DrawableResource,
    modifier: Modifier = Modifier,
    mono: Boolean = false,
    lit: Boolean = value.text.isNotEmpty(),
    suffix: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    val c = Hamstr.colors
    val style = if (mono) Hamstr.type.mono else Hamstr.type.body
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = style.copy(color = c.ink),
        cursorBrush = SolidColor(c.accent),
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        modifier = modifier,
        decorationBox = { field ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .clip(RoundedCornerShape(Radii.control))
                    .background(c.surface)
                    .padding(start = 14.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, tint = if (lit) c.accent else c.ghost, size = 18.dp)
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f)) {
                    if (value.text.isEmpty()) Txt(placeholder, style = style, color = c.ghost, maxLines = 1)
                    field()
                }
                if (suffix != null) Txt(suffix, style = Hamstr.type.mono, color = c.ghost, modifier = Modifier.padding(start = 6.dp))
                if (value.text.isNotEmpty()) {
                    IconButton(Res.drawable.ic_close, stringResource(Res.string.clear), { onValueChange(TextFieldValue("")) }, background = Color.Transparent, size = 36.dp)
                }
            }
        },
    )
}
