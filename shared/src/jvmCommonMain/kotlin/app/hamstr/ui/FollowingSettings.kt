package app.hamstr.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
 * The queue does the looking ([Downloads.control]); [Subscriptions] keeps the list.
 */
@Composable
fun FollowingSettings() {
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
                FollowRow(sub)
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

/** A followed channel: its picture and name, how the last look went, its format and a way to stop. */
@Composable
private fun FollowRow(sub: Subscription) {
    val c = Hamstr.colors
    val picture = rememberImage(sub.thumbnail, 160)
    val audio = sub.mode == Mode.Audio
    val meta = when {
        sub.pending -> stringResource(Res.string.follow_looking)
        sub.error == Subscriptions.NOT_A_FEED -> stringResource(Res.string.follow_not_feed)
        sub.error != null -> stringResource(Res.string.follow_failed)
        else -> listOfNotNull(
            stringResource(if (audio) Res.string.mode_audio else Res.string.mode_video),
            ago(sub.checkedAt).takeIf { it.isNotEmpty() }?.let { stringResource(Res.string.follow_looked, it) },
            sub.stashed.takeIf { it > 0 }?.let { pluralStringResource(Res.plurals.follow_stashed, it, it) },
        ).joinToString(" · ")
    }
    SettingRow(
        sub.title, subtitle = meta,
        leading = {
            Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(c.surface), contentAlignment = Alignment.Center) {
                if (picture != null) Image(picture, null, Modifier.size(38.dp), contentScale = ContentScale.Crop)
                else Icon(Res.drawable.ic_person, tint = if (sub.error != null && !sub.pending) c.red else c.accent, size = 20.dp)
            }
        },
    ) {
        // Tapping the format switches it: new videos come as the other one from now on.
        IconButton(
            if (audio) Res.drawable.ic_music_note else Res.drawable.ic_movie,
            stringResource(if (audio) Res.string.follow_as_audio else Res.string.follow_as_video),
            { Subscriptions.setMode(sub.id, if (audio) Mode.Video else Mode.Audio) },
            background = Color.Transparent, size = 34.dp,
        )
        IconButton(Res.drawable.ic_close, stringResource(Res.string.follow_remove), { Subscriptions.unfollow(sub.id) }, background = Color.Transparent, size = 34.dp)
    }
}
