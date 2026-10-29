package app.hamstr.ui

import app.hamstr.res.*
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.gestures.Orientation
import app.hamstr.ui.components.edgeFade
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.hamstr.BuildConfig
import app.hamstr.R
import app.hamstr.ui.components.ButtonKind
import app.hamstr.ui.components.HButton
import app.hamstr.ui.components.HamstrMark
import app.hamstr.ui.components.IconButton
import app.hamstr.ui.components.ProgressLine
import app.hamstr.ui.components.Txt
import app.hamstr.ui.components.pressable
import app.hamstr.ui.components.rememberMarkState
import app.hamstr.ui.theme.Hamstr
import app.hamstr.update.Release
import app.hamstr.update.UpdateState
import app.hamstr.update.Updater
import app.hamstr.ui.components.Sheet
import app.hamstr.ui.components.SheetHeader
import app.hamstr.ui.theme.Radii

val UpdateState.release: Release?
    get() = when (this) {
        is UpdateState.Available -> release
        is UpdateState.Downloading -> release
        is UpdateState.Installing -> release
        is UpdateState.Failed -> release
        else -> null
    }

/** Floating card at the top when a new release is out. Tap opens [UpdateSheet]. */
@Composable
fun UpdateBanner(release: Release, onOpen: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val c = Hamstr.colors
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(c.raised)
            .pressable(onOpen)
            .padding(start = 8.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HamstrMark(36.dp, interactive = false)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Txt(stringResource(R.string.set_update_out, release.version), style = Hamstr.type.bodyMedium, maxLines = 1)
            Txt(stringResource(R.string.upd_tap), style = Hamstr.type.small, color = c.ghost, maxLines = 1)
        }
        Txt(stringResource(R.string.upd_update), style = Hamstr.type.button, color = c.accent, modifier = Modifier.padding(horizontal = 8.dp))
        IconButton(Res.drawable.ic_close, stringResource(R.string.notify_ask_later), onDismiss, background = Color.Transparent, size = 34.dp)
    }
}

/** Release notes, the download progress and the install button. */
@Composable
fun UpdateSheet(onLater: () -> Unit, onClose: () -> Unit) {
    val c = Hamstr.colors
    val context = LocalContext.current
    val state by Updater.state.collectAsState()
    val release = state.release
    val mark = rememberMarkState()
    LaunchedEffect(state is UpdateState.Installing) { if (state is UpdateState.Installing) mark.munch() }

    // Header, status and buttons stay put; the release notes take the room that is left.
    Sheet(scroll = false) {
        SheetHeader(
            if (release != null) "Hamstr ${release.version}" else "Hamstr ${BuildConfig.VERSION_NAME}",
            subtitle = if (release != null) stringResource(R.string.upd_you_have, BuildConfig.VERSION_NAME, formatBytes(release.size)) else stringResource(R.string.upd_latest),
            badge = if (release != null) stringResource(R.string.upd_new) else null,
            leading = { HamstrMark(56.dp, state = mark) },
        )
        if (release != null && release.notes.isNotBlank()) {
            Spacer(Modifier.height(16.dp))
            val scroll = rememberScrollState()
            Box(Modifier.weight(1f, fill = false).fillMaxWidth().clip(RoundedCornerShape(Radii.row)).background(c.surface)) {
                Box(
                    Modifier
                        .edgeFade(scroll, Orientation.Vertical, length = 22.dp)
                        .verticalScroll(scroll)
                        .padding(14.dp),
                ) { Txt(plainNotes(release.notes), style = Hamstr.type.body, color = c.muted) }
            }
        }
        Spacer(Modifier.height(16.dp))
        AnimatedContent(state, contentKey = { it.javaClass }, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "updateStatus") { s ->
            when (s) {
                is UpdateState.Downloading -> Column {
                    ProgressLine(s.progress)
                    Spacer(Modifier.height(8.dp))
                    Row {
                        Txt(stringResource(R.string.upd_downloading), style = Hamstr.type.small, color = c.muted, modifier = Modifier.weight(1f))
                        Txt("${(s.progress * 100).toInt()} %", style = Hamstr.type.mono, color = c.ghost)
                    }
                }
                is UpdateState.Installing -> Txt(stringResource(R.string.upd_installing_hint), style = Hamstr.type.small, color = c.muted)
                is UpdateState.Failed -> Txt(s.message, style = Hamstr.type.small, color = c.red)
                else -> Spacer(Modifier.height(0.dp))
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (release == null) {
                HButton(stringResource(R.string.upd_close), onClose, Modifier.weight(1f))
            } else {
                HButton(stringResource(R.string.upd_later), onLater, Modifier.weight(1f), kind = ButtonKind.Ghost)
                val busy = state is UpdateState.Downloading || state is UpdateState.Installing
                HButton(
                    when (state) {
                        is UpdateState.Downloading -> stringResource(R.string.upd_downloading_dots)
                        is UpdateState.Installing -> stringResource(R.string.upd_installing)
                        is UpdateState.Failed -> stringResource(R.string.err_try_again)
                        else -> stringResource(R.string.upd_install)
                    },
                    onClick = { Updater.install(context, release) },
                    modifier = Modifier.weight(1.6f),
                    kind = ButtonKind.Primary,
                    icon = Res.drawable.ic_download,
                    enabled = !busy,
                )
            }
        }
    }
}

private val LINK = Regex("""\[([^]]+)]\([^)]+\)""")

/** GitHub release Markdown, reduced to plain lines: no headings marks, links or emphasis. */
fun plainNotes(md: String): String = md.lines()
    .map { line ->
        line.trim()
            .replace(LINK, "$1")
            .replace(Regex("""^#+\s*"""), "")
            .replace(Regex("""^[-*]\s+"""), "·  ")
            .replace("**", "").replace("`", "")
    }
    .filterNot { it.startsWith("<!--") }
    .joinToString("\n")
    .replace(Regex("\n{3,}"), "\n\n")
    .trim()
