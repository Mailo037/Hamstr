package app.hamstr.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.LaunchedEffect
import org.jetbrains.compose.resources.DrawableResource
import app.hamstr.ui.components.Segmented
import app.hamstr.ui.components.Sheet
import app.hamstr.ui.components.SheetHeader
import app.hamstr.ui.components.SwipeAction
import app.hamstr.ui.components.SwipeRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import app.hamstr.data.Downloads
import app.hamstr.data.Mode
import app.hamstr.data.Prefs
import app.hamstr.data.Subscription
import app.hamstr.data.Subscriptions
import app.hamstr.engine.MediaLinks
import app.hamstr.res.*
import app.hamstr.ui.components.ButtonKind
import app.hamstr.ui.components.Divider
import app.hamstr.ui.components.Group
import app.hamstr.ui.components.HButton
import app.hamstr.ui.components.Icon
import app.hamstr.ui.components.IconButton
import app.hamstr.ui.components.SettingRow
import app.hamstr.ui.components.SheetField
import app.hamstr.ui.components.Txt
import app.hamstr.ui.theme.Hamstr
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Settings › Following, the same on both apps: a field to follow a channel or playlist, the
 * ones followed with their format and how they are doing, and a look for new videos right now.
 * A row opens its [FollowSheet] ([onOpen]); swiped away, it stops being followed and
 * [onUnfollowed] can offer Undo ([Subscriptions.restore]). The queue does the looking
 * ([Downloads.control]); [Subscriptions] keeps the list.
 */
@Composable
fun FollowingSettings(onOpen: (Subscription) -> Unit = {}, onUnfollowed: (Subscription, Int) -> Unit = { _, _ -> }) {
    val subs by Subscriptions.all.collectAsState()
    val checking by Subscriptions.checking.collectAsState()
    var text by remember { mutableStateOf(TextFieldValue("")) }
    val link = MediaLinks.find(text.text)
    val follow = {
        if (link != null) {
            Subscriptions.follow(link, Prefs.settings.value.mode) { Downloads.control.feed(it) }
            text = TextFieldValue("")
        }
    }
    Group {
        SettingBlock(stringResource(Res.string.follow_add)) {
            SheetField(
                text, { text = it }, stringResource(Res.string.follow_hint), Res.drawable.ic_link,
                mono = true, lit = link != null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onGo = { follow() }),
            )
            HButton(stringResource(Res.string.follow_button), { follow() }, Modifier.fillMaxWidth(), kind = ButtonKind.Primary, icon = Res.drawable.ic_add, enabled = link != null)
        }
    }
    Spacer(Modifier.height(12.dp))
    if (subs.isEmpty()) {
        Txt(stringResource(Res.string.follow_empty), color = Hamstr.colors.muted, modifier = Modifier.padding(horizontal = 6.dp))
    } else {
        Group {
            subs.forEachIndexed { i, sub ->
                if (i > 0) Divider()
                androidx.compose.runtime.key(sub.id) {
                    SwipeRow(
                        start = null,
                        end = SwipeAction(Res.drawable.ic_close, stringResource(Res.string.follow_remove), Hamstr.colors.red, removes = true) {
                            Subscriptions.unfollow(sub.id)
                            onUnfollowed(sub, i)
                        },
                        background = Hamstr.colors.overlay,
                    ) { FollowRow(sub) { onOpen(sub) } }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        HButton(
            stringResource(if (checking) Res.string.follow_checking else Res.string.follow_check_now), { Downloads.control.checkFeeds() },
            Modifier.fillMaxWidth(), icon = Res.drawable.ic_refresh, enabled = !checking,
        )
    }
    Spacer(Modifier.height(8.dp))
    Txt(
        stringResource(Res.string.follow_note, Subscriptions.MAX_NEW),
        style = Hamstr.type.small, color = Hamstr.colors.ghost, modifier = Modifier.padding(horizontal = 6.dp),
    )
}

/** A followed channel: its picture and name, how the last look went; a tap opens its sheet. */
@Composable
private fun FollowRow(sub: Subscription, onClick: () -> Unit) {
    SettingRow(
        sub.title, subtitle = followMeta(sub), onClick = onClick,
        leading = { Avatar(sub) },
    ) { Icon(Res.drawable.ic_chevron_right, tint = Hamstr.colors.ghost, size = 20.dp) }
}

/** How a followed channel is doing: still looking, what went wrong, or its format, last look and count. */
@Composable
private fun followMeta(sub: Subscription): String = when {
    sub.pending -> stringResource(Res.string.follow_looking)
    sub.error == Subscriptions.NOT_A_FEED -> stringResource(Res.string.follow_not_feed)
    sub.error != null -> stringResource(Res.string.follow_failed)
    else -> listOfNotNull(
        stringResource(if (sub.mode == Mode.Audio) Res.string.mode_audio else Res.string.mode_video),
        ago(sub.checkedAt).takeIf { it.isNotEmpty() }?.let { stringResource(Res.string.follow_looked, it) },
        sub.stashed.takeIf { it > 0 }?.let { pluralStringResource(Res.plurals.follow_stashed, it, it) },
    ).joinToString(" · ")
}

/** Its picture, or a person while there is none (red when its last look failed). */
@Composable
private fun Avatar(sub: Subscription?, size: androidx.compose.ui.unit.Dp = 38.dp) {
    val c = Hamstr.colors
    val picture = rememberImage(sub?.thumbnail, 160)
    Box(Modifier.size(size).clip(RoundedCornerShape(12.dp)).background(c.surface), contentAlignment = Alignment.Center) {
        if (picture != null) Image(picture, null, Modifier.size(size), contentScale = ContentScale.Crop)
        else Icon(Res.drawable.ic_person, tint = if (sub?.error != null && !sub.pending) c.red else c.accent, size = 20.dp)
    }
}

/**
 * A channel or playlist to follow, or followed already: from the player's artist, a followed
 * row, or a link that looks like one. With only a name, [resolve] finds its link first. Shows
 * how it is doing, in which format new videos come, opens or shares its link, looks for new
 * videos and follows it or stops. With [onBrowse] its videos can be looked through and picked
 * to stash right away (the phone's playlist sheet).
 */
@Composable
fun FollowSheet(
    name: String?,
    link: String?,
    onOpen: (String) -> Unit,
    onShare: (String) -> Unit,
    onClose: () -> Unit,
    resolve: (suspend () -> String?)? = null,
    onBrowse: ((String) -> Unit)? = null,
) {
    var url by remember(link) { mutableStateOf(link) }
    var looking by remember(link) { mutableStateOf(link == null && resolve != null) }
    if (looking) LaunchedEffect(Unit) {
        url = runCatching { resolve?.invoke() }.getOrNull()
        looking = false
    }
    val subs by Subscriptions.all.collectAsState()
    val sub = url?.let(Subscriptions::feedUrl)?.let { u -> subs.firstOrNull { it.url == u } }
    var mode by remember { mutableStateOf(Prefs.settings.value.mode) }
    val video = stringResource(Res.string.mode_video)
    val audio = stringResource(Res.string.mode_audio)
    Sheet {
        SheetHeader(
            sub?.title ?: name ?: url.orEmpty(),
            subtitle = when {
                looking -> stringResource(Res.string.follow_finding)
                url == null -> stringResource(Res.string.follow_no_channel)
                sub != null -> followMeta(sub)
                else -> stringResource(Res.string.follow_sheet_hint)
            },
            leading = { Avatar(sub, 52.dp) },
        )
        Spacer(Modifier.height(16.dp))
        Txt(stringResource(Res.string.follow_new_as), style = Hamstr.type.small, color = Hamstr.colors.ghost, modifier = Modifier.padding(horizontal = 4.dp))
        Spacer(Modifier.height(8.dp))
        Segmented(
            listOf(Mode.Video, Mode.Audio), sub?.mode ?: mode, { if (it == Mode.Audio) audio else video },
            { m -> if (sub != null) Subscriptions.setMode(sub.id, m) else mode = m },
            background = Hamstr.colors.surface,
        )
        url?.let { u ->
            Spacer(Modifier.height(10.dp))
            if (onBrowse != null) SheetAction(Res.drawable.ic_queue_music, stringResource(Res.string.follow_browse)) { onBrowse(Subscriptions.feedUrl(u)) }
            SheetAction(Res.drawable.ic_open_in_new, stringResource(Res.string.info_open_link)) { onOpen(u) }
            SheetAction(Res.drawable.ic_share, stringResource(Res.string.follow_share)) { onShare(u) }
            if (sub != null && !sub.pending) SheetAction(Res.drawable.ic_refresh, stringResource(Res.string.follow_check_now)) {
                Downloads.control.checkFeeds()
                onClose()
            }
        }
        Spacer(Modifier.height(14.dp))
        if (sub != null) {
            HButton(stringResource(Res.string.follow_remove), { Subscriptions.unfollow(sub.id); onClose() }, Modifier.fillMaxWidth(), icon = Res.drawable.ic_close)
        } else {
            HButton(
                stringResource(Res.string.follow_button), { url?.let { u -> Subscriptions.follow(u, mode) { Downloads.control.feed(it) } } },
                Modifier.fillMaxWidth(), kind = ButtonKind.Primary, icon = Res.drawable.ic_add, enabled = url != null,
            )
        }
    }
}

@Composable
private fun SheetAction(icon: DrawableResource, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, tint = Hamstr.colors.muted)
        Txt(label)
    }
}

/**
 * Over the share sheet or under the + sheet's field: the link is a channel or a playlist
 * ([MediaLinks.feed]), so it can be followed instead of (or as well as) stashed once.
 */
@Composable
fun FollowOffer(link: String, modifier: Modifier = Modifier) {
    val c = Hamstr.colors
    val kind = MediaLinks.feed(link) ?: return
    val subs by Subscriptions.all.collectAsState()
    val followed = subs.any { it.url == Subscriptions.feedUrl(link) }
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surface).padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (followed) Res.drawable.ic_check else Res.drawable.ic_person, tint = c.accent, size = 20.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
            Txt(
                stringResource(if (kind == MediaLinks.Feed.Channel) Res.string.follow_offer_channel else Res.string.follow_offer_playlist),
                style = Hamstr.type.bodyMedium,
            )
            Txt(stringResource(if (followed) Res.string.follow_started else Res.string.follow_sheet_hint), style = Hamstr.type.small, color = c.ghost, maxLines = 2)
        }
        if (!followed) Box(
            Modifier.clip(RoundedCornerShape(10.dp)).clickable { Subscriptions.follow(link, Prefs.settings.value.mode) { Downloads.control.feed(it) } }
                .heightIn(min = 40.dp).padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) { Txt(stringResource(Res.string.follow_button), style = Hamstr.type.button, color = c.accent) }
    }
}
