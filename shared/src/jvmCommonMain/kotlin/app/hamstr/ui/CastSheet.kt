package app.hamstr.ui

import androidx.compose.foundation.background
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.hamstr.Platform
import app.hamstr.cast.AirPlay
import app.hamstr.cast.Cast
import app.hamstr.cast.CastDevice
import app.hamstr.cast.CastSession
import app.hamstr.cast.CastStatus
import app.hamstr.res.*
import app.hamstr.ui.components.Badge
import app.hamstr.ui.components.Divider
import app.hamstr.ui.components.Group
import app.hamstr.ui.components.HSlider
import app.hamstr.ui.components.Icon
import app.hamstr.ui.components.MenuGroup
import app.hamstr.ui.components.MenuRow
import app.hamstr.ui.components.Sheet
import app.hamstr.ui.components.SheetHeader
import app.hamstr.ui.components.Txt
import app.hamstr.ui.components.pressable
import app.hamstr.ui.theme.Hamstr
import app.hamstr.ui.theme.Radii
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.stringResource

/**
 * "Play on": the TVs and speakers on this network, and the one something plays on now with
 * its volume. Picking one moves what plays there; the player stays the remote. [onOutputs]
 * opens the phone's own output switcher (Bluetooth, cars), which the computer doesn't have.
 */
@Composable
fun CastSheet(onOutputs: (() -> Unit)? = null, onClose: () -> Unit) {
    val devices by Cast.devices.collectAsState()
    val session by Cast.session.collectAsState()
    DisposableEffect(Unit) {
        Cast.discover(true)
        onDispose { Cast.discover(false) }
    }
    CastSheetContent(devices, session, onOutputs, onClose)
}

/** The sheet as it draws [devices] and [session]; split out so tests can show any state. */
@Composable
internal fun CastSheetContent(
    devices: List<CastDevice>,
    session: CastSession?,
    onOutputs: (() -> Unit)?,
    onClose: () -> Unit,
    connect: suspend (CastDevice) -> Result<*> = { Cast.connect(it) },
    desktop: Boolean = Platform.desktop,
    connectingId: String? = null,
) {
    val c = Hamstr.colors
    val scope = rememberCoroutineScope()
    var connecting by remember { mutableStateOf(connectingId) }
    var failed by remember { mutableStateOf<String?>(null) }
    val status by (session?.status ?: remember { kotlinx.coroutines.flow.MutableStateFlow(CastStatus()) }).collectAsState()
    val error by (session?.error ?: remember { kotlinx.coroutines.flow.MutableStateFlow<Throwable?>(null) }).collectAsState()
    val preparing by (session?.preparing ?: remember { kotlinx.coroutines.flow.MutableStateFlow<Float?>(null) }).collectAsState()

    Sheet {
        SheetHeader(
            stringResource(Res.string.cast_title),
            subtitle = session?.let { stringResource(Res.string.cast_playing_on, it.device.name) } ?: stringResource(if (desktop) Res.string.cast_hint_desktop else Res.string.cast_hint),
            leading = { CastIcon(Res.drawable.ic_cast, lit = session != null) },
        )
        Spacer(Modifier.height(16.dp))
        val now = session
        if (now != null) {
            MenuGroup {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    CastIcon(iconOf(now.device), lit = true)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Txt(now.device.name, style = Hamstr.type.bodyMedium, maxLines = 1)
                        val p = preparing
                        if (p != null) Txt(stringResource(Res.string.cast_preparing, now.device.name, (p * 100).toInt()), style = Hamstr.type.small, color = c.accent, maxLines = 1)
                        else Txt(describe(now.device, status), style = Hamstr.type.small, color = c.ghost, maxLines = 1)
                    }
                }
                val volume = status.volume
                if (volume != null) {
                    Column(Modifier.padding(horizontal = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Txt(stringResource(Res.string.cast_volume), style = Hamstr.type.small, color = c.muted, modifier = Modifier.weight(1f))
                            Txt("${(volume * 100).toInt()} %", style = Hamstr.type.mono, color = c.accent)
                        }
                        HSlider((volume * 20).toInt(), 0..20, stringResource(Res.string.cast_volume), describe = { "${it * 5} %" }) { now.setVolume(it / 20f) }
                    }
                }
                error?.let { e ->
                    Txt(
                        if (e is AirPlay.PairingNeeded) stringResource(Res.string.cast_pairing) else stringResource(Res.string.cast_refused, now.device.name),
                        style = Hamstr.type.small, color = c.red, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            MenuGroup {
                MenuRow(
                    if (desktop) Res.drawable.ic_computer else Res.drawable.ic_smartphone,
                    stringResource(if (desktop) Res.string.cast_here_desktop else Res.string.cast_here),
                    subtitle = stringResource(Res.string.cast_here_hint),
                ) { Cast.disconnect(); onClose() }
            }
            Spacer(Modifier.height(16.dp))
        }

        Looking(stringResource(Res.string.cast_looking))
        Spacer(Modifier.height(10.dp))
        val others = devices.filter { it.id != now?.device?.id }
        if (others.isEmpty()) {
            Txt(
                stringResource(if (desktop) Res.string.cast_empty_desktop else Res.string.cast_empty),
                style = Hamstr.type.small, color = c.ghost, modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
            )
        } else Group {
            others.forEachIndexed { i, d ->
                if (i > 0) Divider()
                DeviceRow(d, busy = connecting == d.id, failed = failed == d.id) {
                    if (connecting != null) return@DeviceRow
                    connecting = d.id; failed = null
                    scope.launch {
                        val r = connect(d)
                        connecting = null
                        if (r.isSuccess) onClose() else failed = d.id
                    }
                }
            }
        }
        if (onOutputs != null) {
            Spacer(Modifier.height(12.dp))
            MenuGroup {
                MenuRow(Res.drawable.ic_headphones, stringResource(Res.string.cast_outputs), subtitle = stringResource(Res.string.cast_outputs_hint)) { onOutputs() }
            }
        }
    }
}

@Composable
private fun DeviceRow(d: CastDevice, busy: Boolean, failed: Boolean, onClick: () -> Unit) {
    val c = Hamstr.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(Radii.row)).pressable(onClick).padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CastIcon(iconOf(d), lit = true)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Txt(d.name, style = Hamstr.type.bodyMedium, maxLines = 1)
            Spacer(Modifier.height(2.dp))
            Txt(
                if (failed) stringResource(Res.string.cast_failed) else describe(d, null),
                style = Hamstr.type.small, color = if (failed) c.red else c.ghost, maxLines = 1,
            )
        }
        Spacer(Modifier.width(10.dp))
        if (busy) Badge(stringResource(Res.string.cast_connecting))
        else Icon(Res.drawable.ic_chevron_right, tint = c.ghost, size = 20.dp)
    }
}

/** "Samsung TV · DLNA", and while it plays there, how it is doing. */
@Composable
private fun describe(d: CastDevice, status: CastStatus?): String {
    val state = when (status?.state) {
        CastStatus.State.Playing -> stringResource(Res.string.cast_state_playing)
        CastStatus.State.Paused -> stringResource(Res.string.cast_state_paused)
        CastStatus.State.Loading -> stringResource(Res.string.cast_connecting)
        CastStatus.State.Failed -> stringResource(Res.string.cast_failed)
        else -> null
    }
    val model = d.model?.takeIf { !it.equals(d.name, true) && !it.equals(d.protocol.label, true) }
    return listOfNotNull(state, model, d.protocol.label).joinToString(" · ")
}

private fun iconOf(d: CastDevice): DrawableResource = if (d.video) Res.drawable.ic_tv else Res.drawable.ic_speaker

@Composable
private fun CastIcon(icon: DrawableResource, lit: Boolean) {
    val c = Hamstr.colors
    Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(c.surface), contentAlignment = Alignment.Center) {
        Icon(icon, tint = if (lit) c.accent else c.ghost, size = 20.dp)
    }
}

/** In place of a video's picture while it plays on a TV: where it is, and that this is the remote. */
@Composable
fun CastingCard(device: CastDevice, modifier: Modifier = Modifier, preparing: Float? = null) {
    val c = Hamstr.colors
    Column(modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center) {
        Box(Modifier.size(64.dp).clip(RoundedCornerShape(20.dp)).background(c.accent.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            Icon(iconOf(device), tint = c.accent, size = 32.dp)
        }
        Spacer(Modifier.height(14.dp))
        Txt(stringResource(Res.string.cast_playing_on, device.name), style = Hamstr.type.bodyMedium, color = androidx.compose.ui.graphics.Color.White, maxLines = 1)
        Spacer(Modifier.height(4.dp))
        Txt(
            if (preparing != null) stringResource(Res.string.cast_preparing, device.name, (preparing * 100).toInt())
            else stringResource(Res.string.cast_remote, device.name),
            style = Hamstr.type.small.copy(textAlign = androidx.compose.ui.text.style.TextAlign.Center),
            color = if (preparing != null) c.accent else c.ghost, maxLines = 2,
        )
    }
}
