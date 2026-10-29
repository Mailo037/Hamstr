package app.hamstr.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.hamstr.ui.theme.Hamstr

/**
 * One choice: accent-filled while [on], its colours blending over. [sub] adds a smaller
 * second line (an estimated size). The chip shows at 36 dp; the finger gets 48 dp.
 * [background] is the chip's colour while off, one step above what it sits on.
 */
@Composable
fun Chip(
    label: String,
    on: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    sub: String? = null,
    background: Color = Hamstr.colors.surface,
) {
    val c = Hamstr.colors
    val p = animateSelection(on)
    Box(
        modifier
            .heightIn(min = 48.dp)
            .semantics { selected = on }
            .pressable(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .heightIn(min = 36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(lerp(background, c.accent, p))
                .padding(horizontal = 14.dp, vertical = if (sub != null) 6.dp else 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Txt(label, style = Hamstr.type.bodyMedium, color = lerp(c.ink, c.onAccent, p), maxLines = 1)
            if (sub != null) Txt(sub, style = Hamstr.type.small, color = lerp(c.ghost, c.onAccent, p), maxLines = 1)
        }
    }
}

/** A row of [Chip]s that never wraps: it scrolls sideways, with faded edges. */
@Composable
fun ChipRow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val scroll = rememberScrollState()
    Row(
        modifier.edgeFade(scroll, Orientation.Horizontal).horizontalScroll(scroll),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) { content() }
}

/** Pick one; [sub] adds a second, smaller line under a chip (an estimated size). */
@Composable
fun <T> Chips(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    sub: (T) -> String? = { null },
    background: Color = Hamstr.colors.surface,
    onPick: (T) -> Unit,
) {
    ChipRow {
        options.forEach { o -> Chip(label(o), o == selected, { onPick(o) }, sub = sub(o), background = background) }
    }
}
