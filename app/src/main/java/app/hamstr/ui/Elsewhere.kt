package app.hamstr.ui

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
    data class Found(val hit: Engine.Elsewhere) : Search
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
 * Under a song that is locked with DRM: looks for the same song on YouTube Music and offers
 * to stash that one instead. Shows nothing more than a quiet line when there is none.
 */
@Composable
fun ElsewhereCard(title: String, artist: String?, durationSec: Int, onUse: (Engine.Elsewhere) -> Unit) {
    val c = Hamstr.colors
    var search by remember(title, artist) { mutableStateOf<Search>(Search.Looking) }
    LaunchedEffect(title, artist) {
        search = Engine.findOnYoutubeMusic(title, artist, durationSec)?.let { Search.Found(it) } ?: Search.Nothing
    }
    AnimatedContent(search, transitionSpec = { fadeIn(Specs.appear()) togetherWith fadeOut(Specs.disappear()) }, label = "elsewhere") { s ->
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(Radii.row)).background(c.surface).padding(12.dp)) {
            when (s) {
                Search.Looking -> Txt(stringResource(R.string.drm_looking), style = Hamstr.type.mono, color = c.ghost)
                Search.Nothing -> Txt(stringResource(R.string.drm_not_found), style = Hamstr.type.small, color = c.muted)
                is Search.Found -> {
                    Txt(stringResource(R.string.drm_found), style = Hamstr.type.bodyMedium, color = c.accent)
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Res.drawable.ic_music_note, tint = c.accent, size = 18.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Txt(s.hit.title, style = Hamstr.type.bodyMedium, maxLines = 2)
                            Txt(
                                listOfNotNull(s.hit.uploader, formatDuration(s.hit.durationSec).takeIf { s.hit.durationSec > 0 }, "YouTube Music").joinToString(" · "),
                                style = Hamstr.type.small, color = c.ghost, maxLines = 1,
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    HButton(stringResource(R.string.drm_use), { onUse(s.hit) }, Modifier.fillMaxWidth(), kind = ButtonKind.Primary, icon = Res.drawable.ic_download)
                }
            }
        }
    }
}
