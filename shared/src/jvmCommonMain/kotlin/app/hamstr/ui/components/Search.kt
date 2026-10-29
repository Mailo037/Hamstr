package app.hamstr.ui.components

import org.jetbrains.compose.resources.stringResource
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.hamstr.res.*
import app.hamstr.ui.theme.Hamstr
import app.hamstr.ui.theme.Radii
import kotlinx.coroutines.delay

/** Slate search pill: magnifier, the field, and a clear button once something is typed. */
@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    autoFocus: Boolean = false,
    background: Color = Hamstr.colors.overlay,
) {
    val c = Hamstr.colors
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    if (autoFocus) LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = Hamstr.type.body.copy(color = c.ink),
        cursorBrush = SolidColor(c.accent),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
        modifier = modifier.focusRequester(focus),
        decorationBox = { field ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .clip(RoundedCornerShape(Radii.control))
                    .background(background)
                    .padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Res.drawable.ic_search, tint = if (value.isEmpty()) c.ghost else c.accent, size = 18.dp)
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) Txt(placeholder, color = c.ghost, maxLines = 1)
                    field()
                }
                if (value.isNotEmpty()) IconButton(Res.drawable.ic_close, stringResource(Res.string.clear), { onValueChange("") }, background = Color.Transparent, size = 36.dp)
            }
        },
    )
}

/** Title of the setting a search result points at; its row flashes and scrolls into view. */
val LocalSettingHighlight = compositionLocalOf<String?> { null }

/** Flashes the accent behind a setting the search jumped to and brings it on screen. */
fun Modifier.searchHighlight(title: String): Modifier = composed {
    val target = LocalSettingHighlight.current
    val hit = target != null && target.equals(title, ignoreCase = true)
    val requester = remember { BringIntoViewRequester() }
    val flash = remember { Animatable(0f) }
    val accent = Hamstr.colors.accent
    LaunchedEffect(hit) {
        if (!hit) return@LaunchedEffect
        delay(320) // let the page slide in first
        requester.bringIntoView()
        repeat(2) {
            flash.animateTo(1f, tween(200))
            flash.animateTo(0.25f, tween(380))
        }
        flash.animateTo(0f, tween(700))
    }
    bringIntoViewRequester(requester).background(accent.copy(alpha = 0.3f * flash.value), RoundedCornerShape(Radii.row))
}
