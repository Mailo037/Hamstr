package app.hamstr.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import app.hamstr.Platform
import app.hamstr.data.Prefs
import app.hamstr.nearby.DeviceKind
import app.hamstr.nearby.Nearby
import app.hamstr.nearby.Peer
import app.hamstr.nearby.Request
import app.hamstr.nearby.SendResult
import app.hamstr.nearby.Via
import app.hamstr.res.*
import app.hamstr.ui.components.Badge
import app.hamstr.ui.components.ButtonKind
import app.hamstr.ui.components.Divider
import app.hamstr.ui.components.Group
import app.hamstr.ui.components.HButton
import app.hamstr.ui.components.HamstrMark
import app.hamstr.ui.components.Icon
import app.hamstr.ui.components.LocalReduceMotion
import app.hamstr.ui.components.SettingRow
import app.hamstr.ui.components.Sheet
import app.hamstr.ui.components.SheetField
import app.hamstr.ui.components.SheetHeader
import app.hamstr.ui.components.Txt
import app.hamstr.ui.components.pressable
import app.hamstr.ui.theme.Hamstr
import app.hamstr.ui.theme.Radii
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.stringResource

private enum class Sending { Busy, Sent, Queued, Failed }

/**
 * "Send to a device": the Hamstr apps around now, then remembered ones that are away. A tap
 * sends the link; one for a device that is away waits in the outbox until it shows up.
 */
@Composable
fun SendSheet(url: String, title: String?) {
    val c = Hamstr.colors
    val s by Prefs.settings.collectAsState()
    val peers by Nearby.peers.collectAsState()
    val known by Nearby.known.collectAsState()
    val state = remember { mutableStateMapOf<String, Sending>() }
    val scope = rememberCoroutineScope()
    DisposableEffect(Unit) {
        Nearby.discover(true)
        onDispose { Nearby.discover(false) }
    }
    val knownIds = remember(known) { known.map { it.id }.toSet() }
    val list = remember(peers, knownIds) {
        peers.values.filter { it.online || it.id in knownIds }
            .sortedWith(compareByDescending<Peer> { it.online }.thenByDescending { it.seen }.thenBy { it.name.lowercase() })
    }

    Sheet {
        SheetHeader(stringResource(Res.string.nearby_send_title), subtitle = title ?: url, subtitleLines = 1, leading = { HamstrMark(36.dp) })
        Spacer(Modifier.height(16.dp))
        if (!s.nearby) {
            Txt(stringResource(Res.string.nearby_off), style = Hamstr.type.small, color = c.muted, modifier = Modifier.padding(horizontal = 4.dp))
            Spacer(Modifier.height(12.dp))
            HButton(stringResource(Res.string.nearby_turn_on), { Prefs.edit { it.copy(nearby = true) } }, Modifier.fillMaxWidth(), kind = ButtonKind.Primary, icon = Res.drawable.ic_devices)
        } else {
            Looking()
            Spacer(Modifier.height(10.dp))
            if (list.isEmpty()) {
                Txt(
                    stringResource(if (Platform.desktop) Res.string.nearby_empty_desktop else Res.string.nearby_empty),
                    style = Hamstr.type.small, color = c.ghost, modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                )
            } else Group {
                list.forEachIndexed { i, p ->
                    if (i > 0) Divider()
                    PeerRow(p, state[p.id]) {
                        if (state[p.id] == Sending.Busy) return@PeerRow
                        state[p.id] = Sending.Busy
                        scope.launch {
                            state[p.id] = when (runCatching { Nearby.send(p, url, title) }.getOrDefault(SendResult.Failed)) {
                                SendResult.Sent -> Sending.Sent
                                SendResult.Queued -> Sending.Queued
                                SendResult.Failed -> Sending.Failed
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A mono scan line under the header: a dot runs back and forth while Hamstr looks around. */
@Composable
private fun Looking() {
    val c = Hamstr.colors
    val reduce = LocalReduceMotion.current
    val t = rememberInfiniteTransition(label = "looking")
    val at by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Reverse), label = "dot")
    val width = 9
    val pos = if (reduce) width / 2 else (at * (width - 1)).toInt()
    val line = (0 until width).joinToString(" ") { if (it == pos) "●" else "·" }
    Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Txt("[ $line ]", style = Hamstr.type.mono, color = c.accent, maxLines = 1)
        Txt(stringResource(Res.string.nearby_looking), style = Hamstr.type.small, color = c.ghost, maxLines = 1)
    }
}

@Composable
private fun PeerRow(p: Peer, sending: Sending?, onClick: () -> Unit) {
    val c = Hamstr.colors
    val sub = if (p.online) {
        val ways = buildList {
            if (Via.Wifi in p.via) add(stringResource(Res.string.nearby_via_wifi))
            if (Via.Bluetooth in p.via) add(stringResource(Res.string.nearby_via_bluetooth))
            if (p.trusted) add(stringResource(Res.string.nearby_trusted))
        }
        ways.joinToString(" · ")
    } else if (p.seen > 0) stringResource(Res.string.nearby_seen_ago, ago(p.seen)) else stringResource(Res.string.nearby_away)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radii.row))
            .pressable(onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DeviceIcon(p.kind, lit = p.online)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Txt(p.name, style = Hamstr.type.bodyMedium, maxLines = 1)
            Spacer(Modifier.height(2.dp))
            Txt(sub, style = Hamstr.type.small, color = c.ghost, maxLines = 1)
        }
        Spacer(Modifier.width(10.dp))
        when (sending) {
            Sending.Busy -> Badge(stringResource(Res.string.nearby_sending))
            Sending.Sent -> Badge(stringResource(Res.string.nearby_sent), accent = true)
            Sending.Queued -> Badge(stringResource(Res.string.nearby_queued))
            Sending.Failed -> Badge(stringResource(Res.string.nearby_failed))
            null -> Icon(Res.drawable.ic_send, tint = if (p.online) c.accent else c.ghost, size = 20.dp)
        }
    }
}

@Composable
private fun DeviceIcon(kind: DeviceKind, lit: Boolean = true) {
    val c = Hamstr.colors
    Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(c.surface), contentAlignment = Alignment.Center) {
        Icon(kindIcon(kind), tint = if (lit) c.accent else c.ghost, size = 20.dp)
    }
}

private fun kindIcon(kind: DeviceKind): DrawableResource = when (kind) {
    DeviceKind.Phone -> Res.drawable.ic_smartphone
    DeviceKind.Desktop -> Res.drawable.ic_computer
}

/** A device that isn't trusted yet wants to send a link: accept it once, always, or not. */
@Composable
fun RequestSheet(r: Request, onDone: () -> Unit) {
    val c = Hamstr.colors
    Sheet {
        SheetHeader(stringResource(Res.string.nearby_ask_title, r.from.name), subtitle = r.title ?: r.url, leading = { DeviceIcon(r.from.kind) })
        if (r.title != null) {
            Spacer(Modifier.height(6.dp))
            Txt(r.url, style = Hamstr.type.mono, color = c.ghost, maxLines = 1, modifier = Modifier.padding(horizontal = 4.dp))
        }
        Spacer(Modifier.height(16.dp))
        HButton(stringResource(Res.string.nearby_always), { Nearby.answer(r, accept = true, always = true); onDone() }, Modifier.fillMaxWidth(), kind = ButtonKind.Primary, icon = Res.drawable.ic_download)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HButton(stringResource(Res.string.nearby_once), { Nearby.answer(r, accept = true); onDone() }, Modifier.weight(1f))
            HButton(stringResource(Res.string.nearby_decline), { Nearby.answer(r, accept = false); onDone() }, Modifier.weight(1f), kind = ButtonKind.Ghost)
        }
        Spacer(Modifier.height(12.dp))
        Txt(stringResource(Res.string.nearby_ask_hint), style = Hamstr.type.small, color = c.ghost, modifier = Modifier.padding(horizontal = 4.dp))
    }
}

/**
 * Settings › Devices on both apps: whether others can find this one, its name, and the
 * remembered devices with their trust. [bluetooth] is the phone's Bluetooth row.
 */
@Composable
fun NearbySettings(bluetooth: (@Composable () -> Unit)? = null) {
    val c = Hamstr.colors
    val s by Prefs.settings.collectAsState()
    val known by Nearby.known.collectAsState()
    val peers by Nearby.peers.collectAsState()
    val outbox by Nearby.outbox.collectAsState()
    var name by remember { mutableStateOf(TextFieldValue(s.deviceName, TextRange(s.deviceName.length))) }

    Group {
        FxRow(stringResource(Res.string.set_nearby), stringResource(Res.string.set_nearby_hint), s.nearby) { v -> Prefs.edit { it.copy(nearby = v) } }
        if (bluetooth != null && s.nearby) {
            Divider()
            bluetooth()
        }
    }
    Spacer(Modifier.height(12.dp))
    Group {
        SettingBlock(stringResource(Res.string.set_device_name)) {
            SheetField(name, { v -> name = v; Prefs.edit { it.copy(deviceName = v.text.take(40).trim()) } }, Nearby.defaultName, kindIcon(Nearby.kind))
        }
    }
    Spacer(Modifier.height(16.dp))
    SettingLabel(stringResource(Res.string.set_known_devices), Modifier.padding(horizontal = 6.dp))
    Spacer(Modifier.height(8.dp))
    if (known.isEmpty()) {
        Txt(stringResource(Res.string.set_known_none), style = Hamstr.type.small, color = c.ghost, modifier = Modifier.padding(horizontal = 6.dp))
    } else Group {
        known.forEachIndexed { i, k ->
            if (i > 0) Divider()
            val live = peers[k.id]
            val waiting = outbox.count { it.to == k.id }
            val sub = buildList {
                add(stringResource(if (k.trusted) Res.string.set_device_trusted else Res.string.set_device_asks))
                if (waiting > 0) add(stringResource(Res.string.nearby_waiting, waiting))
            }.joinToString(" · ")
            SettingRow(k.name, subtitle = sub) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (live?.online == true) Icon(if (Via.Wifi in live.via) Res.drawable.ic_wifi else Res.drawable.ic_bluetooth, tint = c.accent, size = 16.dp)
                    if (!k.trusted) HButton(stringResource(Res.string.set_trust), { Nearby.trust(live ?: k, true) })
                    HButton(stringResource(Res.string.set_forget), { Nearby.forget(k.id) }, kind = ButtonKind.Ghost)
                }
            }
        }
    }
}
