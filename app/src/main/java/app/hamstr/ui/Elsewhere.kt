package app.hamstr.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import app.hamstr.player.Preview
import app.hamstr.ui.components.Badge
import app.hamstr.ui.components.IconButton
import kotlin.math.abs
import app.hamstr.res.*
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.hamstr.R
import app.hamstr.engine.Engine
import app.hamstr.engine.MediaInfo
import app.hamstr.ui.components.ButtonKind
import app.hamstr.ui.components.HButton
import app.hamstr.ui.components.Icon
import app.hamstr.ui.components.Specs
import app.hamstr.ui.components.Txt
import app.hamstr.ui.theme.Hamstr
import app.hamstr.ui.theme.Radii

private sealed interface Search {
    data object Looking : Search
    data class Found(val hits: List<Engine.Elsewhere>) : Search
    data object Nothing : Search
}

/** "artist/track-name" from a SoundCloud-style link, for when the site gave no title. */
fun guessFromLink(url: String): Pair<String, String?>? {
    val parts = runCatching { java.net.URI(url).path }.getOrNull()?.trim('/')?.split('/')?.filter { it.isNotBlank() } ?: return null
    if (parts.size < 2) return null
    fun words(s: String) = s.replace(Regex("[-_]+"), " ").trim()
    return words(parts[1]) to words(parts[0])
}

/** What is known of a hit before yt-dlp looks at it, so its download has a name from the start. */
fun Engine.Elsewhere.toInfo() = MediaInfo(url, title, null, null, durationSec, uploader, emptyList(), 0)

/**
 * Under a song that is locked with DRM: looks for the same song on YouTube Music (and YouTube)
 * by title, artist and length. A hit that fits well is offered on its own; the other matches
 * open below it, each with a button to listen in, so the right one can be picked by ear.
 */
@Composable
fun ElsewhereCard(title: String, artist: String?, durationSec: Int, onUse: (Engine.Elsewhere) -> Unit) {
    val c = Hamstr.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val preview = remember { Preview(context, scope) }
    DisposableEffect(preview) { onDispose { preview.release() } }
    var search by remember(title, artist) { mutableStateOf<Search>(Search.Looking) }
    LaunchedEffect(title, artist) {
        search = Engine.searchElsewhere(title, artist, durationSec).takeIf { it.isNotEmpty() }?.let { Search.Found(it) } ?: Search.Nothing
    }
    val use = { hit: Engine.Elsewhere -> preview.stop(); onUse(hit) }
    AnimatedContent(search, transitionSpec = { fadeIn(Specs.appear()) togetherWith fadeOut(Specs.disappear()) }, label = "elsewhere") { s ->
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(Radii.row)).background(c.surface).padding(12.dp).animateContentSize(Specs.blend())) {
            when (s) {
                Search.Looking -> Txt(stringResource(R.string.drm_looking), style = Hamstr.type.mono, color = c.ghost)
                Search.Nothing -> Txt(stringResource(R.string.drm_not_found), style = Hamstr.type.small, color = c.muted)
                is Search.Found -> {
                    val best = Engine.best(s.hits)
                    val others = s.hits.filter { it != best }.take(7)
                    // Without a clear fit the matches are open from the start: pick one by ear.
                    var open by remember(s) { mutableStateOf(best == null) }
                    Txt(stringResource(if (best != null) R.string.drm_found else R.string.drm_pick), style = Hamstr.type.bodyMedium, color = c.accent)
                    Spacer(Modifier.height(6.dp))
                    if (best != null) {
                        HitRow(best, durationSec, preview, onUse = null)
                        Spacer(Modifier.height(8.dp))
                        HButton(stringResource(R.string.drm_use), { use(best) }, Modifier.fillMaxWidth(), kind = ButtonKind.Primary, icon = Res.drawable.ic_download)
                    }
                    if (others.isNotEmpty()) {
                        if (best != null) {
                            Row(
                                Modifier.padding(top = 6.dp).clip(RoundedCornerShape(8.dp)).clickable { open = !open }.padding(vertical = 6.dp, horizontal = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Txt(stringResource(if (open) R.string.drm_fewer else R.string.drm_more, others.size), style = Hamstr.type.small, color = c.muted)
                                Icon(Res.drawable.ic_expand_more, tint = c.muted, size = 16.dp, modifier = Modifier.graphicsLayer { rotationZ = if (open) 180f else 0f })
                            }
                        }
                        AnimatedVisibility(open, enter = expandVertically(Specs.blend()) + fadeIn(Specs.appear()), exit = shrinkVertically(Specs.blend()) + fadeOut(Specs.disappear())) {
                            Column { others.forEach { HitRow(it, durationSec, preview, onUse = use) } }
                        }
                    }
                }
            }
        }
    }
}

/** One match: listen in on the left, its name, channel and length, and stash it on the right. */
@Composable
private fun HitRow(hit: Engine.Elsewhere, durationSec: Int, preview: Preview, onUse: ((Engine.Elsewhere) -> Unit)?) {
    val c = Hamstr.colors
    val playing = preview.playing == hit.url
    val loading = preview.loading == hit.url
    val sameLength = durationSec > 0 && hit.durationSec > 0 && abs(hit.durationSec - durationSec) <= 3
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(
            if (playing || loading) Res.drawable.ic_pause else Res.drawable.ic_play_arrow,
            stringResource(if (playing || loading) R.string.drm_stop else R.string.drm_listen),
            { preview.toggle(hit.url, hit.durationSec) },
            tint = if (playing) c.accent else c.ink,
            background = c.raised,
            size = 36.dp,
        )
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)) {
            Txt(hit.title, style = Hamstr.type.bodyMedium, maxLines = 2)
            val line = listOfNotNull(
                hit.uploader?.removeSuffix(" - Topic"),
                formatDuration(hit.durationSec).takeIf { hit.durationSec > 0 },
                when {
                    loading -> stringResource(R.string.drm_loading)
                    preview.failed == hit.url -> stringResource(R.string.drm_no_preview)
                    else -> null
                },
            ).joinToString(" · ")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (sameLength) Badge(stringResource(R.string.drm_same_length), accent = true)
                Txt(line, style = Hamstr.type.small, color = c.ghost, maxLines = 1)
            }
        }
        if (onUse != null) IconButton(Res.drawable.ic_download, stringResource(R.string.drm_use), { onUse(hit) }, tint = c.accent, background = Color.Transparent, size = 36.dp)
    }
}
