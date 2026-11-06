package app.hamstr.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.hamstr.ui.theme.Hamstr
import org.jetbrains.compose.resources.DrawableResource

/*
 * The pieces of the menus that slide up from the bottom (a file's, a playlist's, a device's):
 * a row of tiles for what is done most, then rows grouped on panels.
 */

/** A button in the menu's top row: icon over a short label. */
@Composable
fun RowScope.ActionTile(icon: DrawableResource, label: String, accent: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val c = Hamstr.colors
    Column(
        Modifier
            .weight(1f)
            .alpha(if (enabled) 1f else 0.4f)
            .clip(RoundedCornerShape(16.dp))
            .background(if (accent && enabled) c.accent else c.surface)
            .then(if (enabled) Modifier.pressable(onClick) else Modifier)
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, tint = if (accent && enabled) c.onAccent else c.ink, size = 22.dp)
        Spacer(Modifier.height(6.dp))
        Txt(label, style = Hamstr.type.small, color = if (accent && enabled) c.onAccent else c.muted, maxLines = 1)
    }
}

/** Rows that belong together, on one panel. */
@Composable
fun MenuGroup(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Hamstr.colors.surface).padding(4.dp),
        content = content,
    )
}

@Composable
fun MenuRow(icon: DrawableResource, label: String, danger: Boolean = false, subtitle: String? = null, onClick: () -> Unit) {
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

/** A row with a switch at its end; the whole row switches it, and reads as one switch. */
@Composable
fun MenuSwitchRow(icon: DrawableResource, label: String, checked: Boolean, subtitle: String? = null, onChange: (Boolean) -> Unit) {
    val c = Hamstr.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .toggleable(checked, role = Role.Switch, onValueChange = onChange)
            .heightIn(min = 48.dp)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, tint = if (checked) c.accent else c.muted)
        Column(Modifier.weight(1f)) {
            Txt(label, color = c.ink)
            if (subtitle != null) Txt(subtitle, style = Hamstr.type.small, color = c.ghost)
        }
        HSwitch(checked)
    }
}
