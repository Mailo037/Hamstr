package app.hamstr.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
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
import app.hamstr.ui.components.Badge
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
            Txt("Hamstr ${release.version} is out", style = Hamstr.type.bodyMedium, maxLines = 1)
            Txt("Tap to update, no browser needed", style = Hamstr.type.small, color = c.ghost, maxLines = 1)
        }
        Txt("Update", style = Hamstr.type.button, color = c.accent, modifier = Modifier.padding(horizontal = 8.dp))
        IconButton(R.drawable.ic_close, "Not now", onDismiss, background = Color.Transparent, size = 34.dp)
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

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
            .background(c.overlay)
            .clickable(enabled = false) {}
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Box(Modifier.align(Alignment.CenterHorizontally).width(36.dp).height(4.dp).clip(RoundedCornerShape(4.dp)).background(c.line))
        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            HamstrMark(56.dp, state = mark)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Txt(if (release != null) "Hamstr ${release.version}" else "Hamstr ${BuildConfig.VERSION_NAME}", style = Hamstr.type.section)
                    if (release != null) Badge("new", accent = true)
                }
                Spacer(Modifier.height(2.dp))
                Txt(
                    if (release != null) "You have ${BuildConfig.VERSION_NAME} · ${formatBytes(release.size)}" else "You're on the latest version",
                    style = Hamstr.type.small, color = c.ghost,
                )
            }
        }
        if (release != null && release.notes.isNotBlank()) {
            Spacer(Modifier.height(16.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(c.surface)
                    .heightIn(max = 240.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(14.dp),
            ) { Txt(plainNotes(release.notes), style = Hamstr.type.body, color = c.muted) }
        }
        Spacer(Modifier.height(16.dp))
        AnimatedContent(state, contentKey = { it.javaClass }, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "updateStatus") { s ->
            when (s) {
                is UpdateState.Downloading -> Column {
                    ProgressLine(s.progress)
                    Spacer(Modifier.height(8.dp))
                    Row {
                        Txt("Downloading", style = Hamstr.type.small, color = c.muted, modifier = Modifier.weight(1f))
                        Txt("${(s.progress * 100).toInt()} %", style = Hamstr.type.mono, color = c.ghost)
                    }
                }
                is UpdateState.Installing -> Txt("Handing it to Android. Hamstr closes and comes back updated.", style = Hamstr.type.small, color = c.muted)
                is UpdateState.Failed -> Txt(s.message, style = Hamstr.type.small, color = c.red)
                else -> Spacer(Modifier.height(0.dp))
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (release == null) {
                HButton("Close", onClose, Modifier.weight(1f))
            } else {
                HButton("Later", onLater, Modifier.weight(1f), kind = ButtonKind.Ghost)
                val busy = state is UpdateState.Downloading || state is UpdateState.Installing
                HButton(
                    when (state) {
                        is UpdateState.Downloading -> "Downloading …"
                        is UpdateState.Installing -> "Installing …"
                        is UpdateState.Failed -> "Try again"
                        else -> "Install update"
                    },
                    onClick = { Updater.install(context, release) },
                    modifier = Modifier.weight(1.6f),
                    kind = ButtonKind.Primary,
                    icon = R.drawable.ic_download,
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
