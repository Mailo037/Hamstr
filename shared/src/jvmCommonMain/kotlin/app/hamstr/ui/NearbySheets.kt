package app.hamstr.ui

import androidx.compose.runtime.key
import app.hamstr.ui.components.MenuSwitchRow
import app.hamstr.ui.components.MenuRow
import app.hamstr.ui.components.MenuGroup
import app.hamstr.ui.components.ActionTile
import app.hamstr.ui.components.SwipeAction
import app.hamstr.ui.components.SwipeRow
import app.hamstr.ui.components.rememberAmbientClock
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import app.hamstr.Platform
import app.hamstr.data.Prefs
import app.hamstr.engine.MediaLinks
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
import app.hamstr.ui.components.Segmented
import app.hamstr.ui.components.SettingRow
import app.hamstr.ui.components.Sheet
import app.hamstr.ui.components.SheetField
import app.hamstr.ui.components.SheetHeader
import app.hamstr.ui.components.Specs
import app.hamstr.ui.components.Txt
import app.hamstr.ui.components.pressable
import app.hamstr.ui.theme.Hamstr
import app.hamstr.ui.theme.Radii
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CoroutineScope
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

internal enum class Sending { Busy, Sent, Queued, Failed, Declined }

/** Kept by the share sheet so switching Link / File preserves device order and send status. */
internal class NearbyTargetsState {
    val sending = mutableStateMapOf<String, Sending>()
    val sendingFiles = mutableStateMapOf<String, Sending>()
    /** How far files to each device have got, 0 to 1. */
    val progress = mutableStateMapOf<String, Float>()
    val order = LinkedHashMap<String, Peer>()
    val here = HashSet<String>()
}

/** The link to another Hamstr; one for a device that is away waits in the outbox until it shows up. */
@Composable
internal fun NearbyTargets(url: String, title: String?, targets: NearbyTargetsState, scope: CoroutineScope) =
    NearbyTargets(targets, scope, targets.sending, files = false) { p, _ -> Nearby.send(p, url, title) }

/** The files themselves, to another Hamstr on the same network. */
@Composable
internal fun NearbyFileTargets(files: List<java.io.File>, targets: NearbyTargetsState, scope: CoroutineScope) =
    NearbyTargets(targets, scope, targets.sendingFiles, files = true) { p, progress -> Nearby.sendFiles(p, files, progress) }

/**
 * Inline targets in the share sheet: nearby Hamstr apps, then remembered ones that are away. A tap
 * sends; [files] only go to a device on the same network, so the others wait greyed out.
 */
@Composable
private fun NearbyTargets(
    targets: NearbyTargetsState,
    scope: CoroutineScope,
    state: MutableMap<String, Sending>,
    files: Boolean,
    send: suspend (Peer, (Float) -> Unit) -> SendResult,
) {
    val c = Hamstr.colors
    val s by Prefs.settings.collectAsState()
    val peers by Nearby.peers.collectAsState()
    val known by Nearby.known.collectAsState()
    DisposableEffect(Unit) {
        Nearby.discover(true)
        onDispose { Nearby.discover(false) }
    }
    val knownIds = remember(known) { known.map { it.id }.toSet() }
    // Every row keeps the place it first showed up in, so nothing moves under the pointer; a
    // device that leaves (switched off, out of range, Hamstr closed) stays, marked as no longer
    // available. The first batch goes devices around first, then by name.
    val order = targets.order
    val here = targets.here
    val list = remember(targets, peers, knownIds) {
        peers.values.forEach { p -> if (p.online) here += p.id }
        peers.values.filter { (it.online || it.id in knownIds) && it.id !in order }
            .sortedWith(compareByDescending<Peer> { it.online }.thenBy { it.name.lowercase() }.thenBy { it.id })
            .forEach { order[it.id] = it }
        order.keys.map { id -> peers[id] ?: order.getValue(id).copy(via = emptySet()) }
    }

    Column {
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
                    val reachable = !files || Nearby.takesFiles(p)
                    PeerRow(
                        p, state[p.id], left = !p.online && p.id in here, progress = if (files) targets.progress[p.id] else null,
                        unreachable = !reachable, slow = files && reachable && Nearby.filesOverBluetooth(p),
                    ) {
                        if (state[p.id] == Sending.Busy || !reachable) return@PeerRow
                        state[p.id] = Sending.Busy
                        targets.progress.remove(p.id)
                        scope.launch {
                            state[p.id] = sendingOf(runCatching { send(p) { f -> targets.progress[p.id] = f } }.getOrDefault(SendResult.Failed))
                        }
                    }
                }
            }
        }
    }
}

/** A mono scan line under the header: a dot runs back and forth while Hamstr looks around. */
@Composable
internal fun Looking(text: String = stringResource(Res.string.nearby_looking)) {
    val c = Hamstr.colors
    // The dot only has nine places, so ten steps a second are plenty.
    val clock by rememberAmbientClock(10)
    val width = 9
    val period = 2 * (width - 1)
    val step = ((clock / 160) % period).toInt()
    val pos = if (clock == 0L) width / 2 else if (step < width) step else period - step
    val line = (0 until width).joinToString(" ") { if (it == pos) "●" else "·" }
    Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Txt("[ $line ]", style = Hamstr.type.mono, color = c.accent, maxLines = 1)
        Txt(text, style = Hamstr.type.small, color = c.ghost, maxLines = 1)
    }
}

@Composable
private fun PeerRow(p: Peer, sending: Sending?, left: Boolean, progress: Float? = null, unreachable: Boolean = false, slow: Boolean = false, onClick: () -> Unit) {
    val c = Hamstr.colors
    val dim by animateFloatAsState(if (p.online && !unreachable) 1f else 0.55f, Specs.blend(), label = "dim")
    val sub = if (unreachable) stringResource(Res.string.nearby_files_here)
    else if (slow) stringResource(Res.string.nearby_files_bluetooth)
    else if (p.online) {
        val ways = buildList {
            if (Via.Wifi in p.via) add(stringResource(Res.string.nearby_via_wifi))
            if (Via.Bluetooth in p.via) add(stringResource(Res.string.nearby_via_bluetooth))
            if (p.trusted) add(stringResource(Res.string.nearby_trusted))
        }
        ways.joinToString(" · ")
    } else if (left) stringResource(Res.string.nearby_gone)
    else if (p.seen > 0) stringResource(Res.string.nearby_seen_ago, ago(p.seen)) else stringResource(Res.string.nearby_away)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radii.row))
            .pressable(onClick)
            .graphicsLayer { alpha = dim }
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
        val label = sendLabel(sending, progress)
        if (label != null) Badge(label, accent = sending == Sending.Sent)
        else Icon(Res.drawable.ic_send, tint = if (p.online && !unreachable) c.accent else c.ghost, size = 20.dp)
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

/** A device that isn't trusted yet wants to send a link or files: accept once, always, or not. */
@Composable
fun RequestSheet(r: Request, onDone: () -> Unit) {
    val c = Hamstr.colors
    Sheet {
        val subtitle = when {
            r.play -> stringResource(if (Platform.desktop) Res.string.nearby_play_offer_desktop else Res.string.nearby_play_offer)
            r.files.isNotEmpty() -> pluralStringResource(Res.plurals.nearby_files_offer, r.files.size, r.files.size, formatBytes(r.files.sumOf { it.size }))
            else -> r.title ?: r.url
        }
        SheetHeader(stringResource(if (r.play) Res.string.nearby_play_title else Res.string.nearby_ask_title, r.from.name), subtitle = subtitle, leading = { DeviceIcon(r.from.kind) })
        if (r.play) Unit
        else if (r.files.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            r.files.take(4).forEach { f ->
                Txt("${f.name} · ${formatBytes(f.size)}", style = Hamstr.type.mono, color = c.ghost, maxLines = 1, modifier = Modifier.padding(horizontal = 4.dp))
            }
            if (r.files.size > 4) Txt("…", style = Hamstr.type.mono, color = c.ghost, modifier = Modifier.padding(horizontal = 4.dp))
        } else if (r.title != null) {
            Spacer(Modifier.height(6.dp))
            Txt(r.url, style = Hamstr.type.mono, color = c.ghost, maxLines = 1, modifier = Modifier.padding(horizontal = 4.dp))
        }
        Spacer(Modifier.height(16.dp))
        HButton(stringResource(Res.string.nearby_always), { Nearby.answer(r, accept = true, always = true); onDone() }, Modifier.fillMaxWidth(), kind = ButtonKind.Primary, icon = if (r.play) Res.drawable.ic_play_arrow else Res.drawable.ic_download)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HButton(stringResource(Res.string.nearby_once), { Nearby.answer(r, accept = true); onDone() }, Modifier.weight(1f))
            HButton(stringResource(Res.string.nearby_decline), { Nearby.answer(r, accept = false); onDone() }, Modifier.weight(1f), kind = ButtonKind.Ghost)
        }
        Spacer(Modifier.height(12.dp))
        Txt(stringResource(if (r.play) Res.string.nearby_play_hint else Res.string.nearby_ask_hint), style = Hamstr.type.small, color = c.ghost, modifier = Modifier.padding(horizontal = 4.dp))
    }
}

/**
 * Settings › Devices on both apps: whether others can find this one, its name, and the
 * remembered devices with their trust. [bluetooth] is the phone's Bluetooth row; a tap on a
 * device hands its id to [onDevice], which shows [DeviceSheet].
 */
@Composable
fun NearbySettings(bluetooth: (@Composable () -> Unit)? = null, onDevice: (String) -> Unit = {}) {
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
            val online = live?.online == true
            val waiting = outbox.count { it.to == k.id }
            val sub = listOfNotNull(
                stringResource(if (online) Res.string.device_connected else Res.string.device_offline),
                stringResource(if (k.trusted) Res.string.nearby_trusted else Res.string.set_device_asks),
                if (waiting > 0) stringResource(Res.string.nearby_waiting, waiting) else null,
            ).joinToString(" · ")
            // Right switches whether it asks first, left forgets it, like a row in the stash; icons on the desktop.
            key(k.id) {
                SwipeRow(
                    start = SwipeAction(
                        if (k.trusted) Res.drawable.ic_help else Res.drawable.ic_check_circle,
                        stringResource(if (k.trusted) Res.string.device_always_ask else Res.string.nearby_always),
                        c.accent,
                    ) { Nearby.trust(live ?: k, !k.trusted) },
                    end = SwipeAction(Res.drawable.ic_close, stringResource(Res.string.set_forget), c.red, removes = true) { Nearby.forget(k.id) },
                    background = c.overlay,
                ) {
                    SettingRow(k.name, subtitle = sub, onClick = { onDevice(k.id) }, leading = {
                        Icon(kindIcon(k.kind), tint = if (online) c.accent else c.muted, size = 22.dp)
                    }) {
                        if (live != null && online) Icon(if (Via.Wifi in live.via) Res.drawable.ic_wifi else Res.drawable.ic_bluetooth, tint = c.accent, size = 16.dp)
                    }
                }
            }
        }
    }
}

/**
 * One remembered device: whether it is connected and how, a link or files to send it, whether
 * it may send without asking, and forgetting it. [onPickFiles] lets the user choose files
 * (null where that can't be done); it returns none when they cancel.
 */
@Composable
fun DeviceSheet(id: String, clipboardLink: String?, onPickFiles: (suspend () -> List<java.io.File>)?, onClose: () -> Unit) {
    val known by Nearby.known.collectAsState()
    val peers by Nearby.peers.collectAsState()
    val outbox by Nearby.outbox.collectAsState()
    val scope = rememberCoroutineScope()
    var link by remember { mutableStateOf(TextFieldValue(clipboardLink.orEmpty(), TextRange(clipboardLink.orEmpty().length))) }
    var linkSent by remember { mutableStateOf<Sending?>(null) }
    var filesSent by remember { mutableStateOf<Sending?>(null) }
    var progress by remember { mutableStateOf<Float?>(null) }
    // Looks harder while the sheet is open, so the status is current.
    DisposableEffect(Unit) {
        Nearby.discover(true)
        onDispose { Nearby.discover(false) }
    }
    val k = known.firstOrNull { it.id == id } ?: return
    val live = peers[id]
    val peer = live ?: k
    val online = live?.online == true
    val waiting = outbox.count { it.to == id }
    val status = buildList {
        if (live != null && online) {
            add(stringResource(Res.string.device_connected))
            if (Via.Wifi in live.via) add(stringResource(Res.string.nearby_via_wifi))
            if (Via.Bluetooth in live.via) add(stringResource(Res.string.nearby_via_bluetooth))
        } else {
            add(stringResource(Res.string.device_offline))
            val seen = live?.seen?.takeIf { it > 0 } ?: k.seen
            if (seen > 0) add(stringResource(Res.string.device_seen, ago(seen)))
        }
        if (waiting > 0) add(stringResource(Res.string.nearby_waiting, waiting))
    }.joinToString(" · ")
    val url = MediaLinks.find(link.text)
    val takesFiles = online && Nearby.takesFiles(peer)

    Sheet {
        SheetHeader(k.name, subtitle = status, leading = { DeviceIcon(k.kind, lit = online) })
        Spacer(Modifier.height(18.dp))
        SheetField(link, { link = it; linkSent = null }, stringResource(Res.string.add_hint), Res.drawable.ic_link, mono = true)
        Spacer(Modifier.height(10.dp))
        // What is done most as tiles, as in a file's menu; then whether it may send unasked, then forgetting it.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionTile(
                Res.drawable.ic_send, sendLabel(linkSent, null) ?: stringResource(Res.string.device_send_link),
                accent = true, enabled = url != null && linkSent != Sending.Busy,
            ) {
                val u = url ?: return@ActionTile
                linkSent = Sending.Busy
                scope.launch { linkSent = sendingOf(runCatching { Nearby.send(peer, u, null) }.getOrDefault(SendResult.Failed)) }
            }
            if (onPickFiles != null) ActionTile(
                Res.drawable.ic_description, sendLabel(filesSent, progress) ?: stringResource(Res.string.device_send_files),
                enabled = takesFiles && filesSent != Sending.Busy,
            ) {
                scope.launch {
                    val files = onPickFiles()
                    if (files.isEmpty()) return@launch
                    filesSent = Sending.Busy
                    progress = null
                    filesSent = sendingOf(runCatching { Nearby.sendFiles(peer, files) { progress = it } }.getOrDefault(SendResult.Failed))
                }
            }
        }
        if (onPickFiles != null && !takesFiles) {
            Txt(
                stringResource(Res.string.nearby_files_here), style = Hamstr.type.small, color = Hamstr.colors.ghost,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
            )
        }
        Spacer(Modifier.height(10.dp))
        MenuGroup {
            MenuSwitchRow(
                Res.drawable.ic_check_circle, stringResource(Res.string.nearby_always), k.trusted,
                subtitle = stringResource(if (k.trusted) Res.string.device_trusted_hint else Res.string.device_ask_hint),
            ) { Nearby.trust(peer, it) }
        }
        Spacer(Modifier.height(10.dp))
        MenuGroup {
            MenuRow(Res.drawable.ic_delete, stringResource(Res.string.device_forget), danger = true) { onClose(); Nearby.forget(id) }
        }
    }
}

/**
 * A link shared straight to one device (a device in Android's share sheet): it gives the device
 * a moment to be heard, since the radios only just came up, sends, and closes once it's there.
 * One that is away waits in the outbox, and the sheet says so.
 */
@Composable
fun SendToDeviceSheet(id: String, url: String, title: String?, onDone: () -> Unit) {
    val known by Nearby.known.collectAsState()
    val peers by Nearby.peers.collectAsState()
    var sending by remember { mutableStateOf(Sending.Busy) }
    LaunchedEffect(id, url) {
        val k = Nearby.known.value.firstOrNull { it.id == id }
        if (k == null) {
            sending = Sending.Failed
            return@LaunchedEffect
        }
        Nearby.discover(true)
        try {
            withTimeoutOrNull(6_000) { Nearby.peers.first { it[id]?.online == true } }
            sending = sendingOf(runCatching { Nearby.send(Nearby.peers.value[id] ?: k, url, title) }.getOrDefault(SendResult.Failed))
        } finally {
            Nearby.discover(false)
        }
        if (sending == Sending.Sent) {
            delay(900)
            onDone()
        }
    }
    val k = known.firstOrNull { it.id == id }
    Sheet {
        SheetHeader(
            k?.name ?: stringResource(Res.string.nearby_send_title),
            subtitle = if (sending == Sending.Queued) stringResource(Res.string.nearby_away) else title ?: url,
            badge = sendLabel(sending, null),
            subtitleLines = 1,
            leading = { DeviceIcon(k?.kind ?: DeviceKind.Desktop, lit = peers[id]?.online == true) },
        )
        Spacer(Modifier.height(16.dp))
        HButton(stringResource(Res.string.nearby_done), onDone, Modifier.fillMaxWidth(), kind = if (sending == Sending.Busy) ButtonKind.Neutral else ButtonKind.Primary)
    }
}

private fun sendingOf(r: SendResult) = when (r) {
    SendResult.Sent -> Sending.Sent
    SendResult.Queued -> Sending.Queued
    SendResult.Failed -> Sending.Failed
    SendResult.Declined -> Sending.Declined
}

/** What a send button says while and after it sends; null before. */
@Composable
private fun sendLabel(s: Sending?, progress: Float?): String? = when (s) {
    Sending.Busy -> progress?.let { stringResource(Res.string.nearby_sending_percent, (it * 100).toInt()) } ?: stringResource(Res.string.nearby_sending)
    Sending.Sent -> stringResource(Res.string.nearby_sent)
    Sending.Queued -> stringResource(Res.string.nearby_queued)
    Sending.Failed -> stringResource(Res.string.nearby_failed)
    Sending.Declined -> stringResource(Res.string.nearby_declined)
    null -> null
}
