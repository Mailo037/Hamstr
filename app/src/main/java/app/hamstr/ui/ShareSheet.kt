package app.hamstr.ui

import org.jetbrains.compose.resources.stringResource as sharedString
import app.hamstr.res.*
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.hamstr.R
import app.hamstr.data.Choice
import app.hamstr.data.DownloadFiles
import app.hamstr.data.DownloadStore
import app.hamstr.data.DownloadTask
import app.hamstr.data.Mode
import app.hamstr.data.Prefs
import app.hamstr.data.Status
import app.hamstr.data.heightLabel
import app.hamstr.engine.DownloadService
import app.hamstr.engine.Engine
import app.hamstr.engine.Failures
import app.hamstr.engine.Fix
import app.hamstr.engine.MediaInfo
import app.hamstr.engine.Network
import app.hamstr.engine.Peek
import app.hamstr.engine.Peeker
import app.hamstr.ui.components.AsciiImage
import app.hamstr.ui.components.Badge
import app.hamstr.ui.components.ButtonKind
import app.hamstr.ui.components.DecodeText
import app.hamstr.ui.components.Divider
import app.hamstr.ui.components.HButton
import app.hamstr.ui.components.HSwitch
import app.hamstr.ui.components.Icon
import app.hamstr.ui.components.Segmented
import app.hamstr.ui.components.SettingRow
import app.hamstr.ui.components.Txt
import app.hamstr.ui.theme.Hamstr
import app.hamstr.ui.theme.Radii
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.File
import app.hamstr.ui.components.Chips
import app.hamstr.ui.components.Sheet
import app.hamstr.ui.components.SheetField
import app.hamstr.ui.components.SquareButton
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import app.hamstr.ui.components.Specs
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import app.hamstr.ui.components.LocalReduceMotion
import app.hamstr.ui.components.iconSwap
import app.hamstr.ui.components.pressable

sealed interface InfoState {
    data object Loading : InfoState
    data class Loaded(val info: MediaInfo) : InfoState
    data class Failed(val message: String) : InfoState
}

/** yt-dlp's last ERROR line, which says what went wrong. */
fun errorLine(e: Throwable) = e.message?.lines()?.lastOrNull { it.contains("ERROR") }?.substringAfter("ERROR: ")
    ?: e.message?.lines()?.lastOrNull { it.isNotBlank() } ?: ""

/** Sites that are about sound; their links start in Audio. */
private val AUDIO_SITES = setOf("soundcloud", "bandcamp", "mixcloud", "audiomack", "youtubemusic", "applepodcasts", "podbean", "spotify", "applemusic", "deezer", "glyph")

internal fun looksLikeAudio(url: String, info: MediaInfo): Boolean =
    (info.entries == null && info.heights.isEmpty()) || url.contains("music.youtube.com") || info.source?.lowercase()?.replace(Regex("[^a-z]"), "") in AUDIO_SITES

/** Whether [t] already is the file this [choice] would bring. */
private fun sameChoice(t: DownloadTask, choice: Choice) = when (choice.mode) {
    Mode.Audio -> t.choice.mode == Mode.Audio && t.choice.audioFormat == choice.audioFormat
    Mode.Video -> t.choice.mode == Mode.Video && t.choice.height == choice.height
    Mode.Custom -> false
}

/**
 * The sheet that appears after sharing a link: a first look (title and picture from the site)
 * arrives at once, the preview resolves out of ASCII rain, and below it the choices.
 * A link that is already stashed says so and offers to open it or add another quality.
 */
@Composable
fun ShareSheet(
    url: String,
    onStash: (String, MediaInfo?, Choice) -> Unit,
    onDismiss: () -> Unit,
    onOpen: (DownloadTask) -> Unit,
    onStashVersion: (DownloadTask, Choice) -> Unit,
    onBatchQueued: () -> Unit = onDismiss,
    onSend: ((String, String?) -> Unit)? = null,
) {
    val target = remember(url) { runCatching { app.hamstr.engine.CineSrcTarget.parse(url) }.getOrNull()?.takeIf { it.needsEpisodes } }
    var episodeUrl by remember(url) { mutableStateOf<String?>(null) }
    if (target != null && episodeUrl == null) {
        CineSrcSeasonSheet(target, onEpisode = { episodeUrl = it }, onDismiss = onDismiss, onQueued = onBatchQueued)
    } else {
        val mediaUrl = episodeUrl ?: url
        key(mediaUrl) {
            SingleMediaShareSheet(mediaUrl, { info, choice -> onStash(mediaUrl, info, choice) },
                onStashOther = onStash,
                onDismiss = { if (episodeUrl != null) episodeUrl = null else onDismiss() }, onOpen = onOpen, onStashVersion = onStashVersion,
                onBatchQueued = onBatchQueued, onSend = onSend)
        }
    }
}

@Composable
private fun SingleMediaShareSheet(
    url: String,
    onStash: (MediaInfo?, Choice) -> Unit,
    onStashOther: (String, MediaInfo?, Choice) -> Unit,
    onDismiss: () -> Unit,
    onOpen: (DownloadTask) -> Unit,
    onStashVersion: (DownloadTask, Choice) -> Unit,
    onBatchQueued: () -> Unit,
    onSend: ((String, String?) -> Unit)?,
) {
    // A playlist is known only once yt-dlp has listed it; until then the usual sheet loads.
    val listed = Engine.cachedInfo(url)?.takeIf { it.entries != null }
    var playlist by remember(url) { mutableStateOf(listed) }
    val context = LocalContext.current
    val p = playlist
    if (p != null) {
        PlaylistPickSheet(p, onStash = { entries, choice ->
            DownloadService.stashEntries(context, p, entries, choice)
            onBatchQueued()
        }, onDismiss = onDismiss)
    } else {
        MediaSheet(url, onStash, onStashOther, onDismiss, onOpen, onStashVersion, onPlaylist = { playlist = it }, onSend = onSend)
    }
}

@Composable
private fun MediaSheet(
    url: String,
    onStash: (MediaInfo?, Choice) -> Unit,
    onStashOther: (String, MediaInfo?, Choice) -> Unit,
    onDismiss: () -> Unit,
    onOpen: (DownloadTask) -> Unit,
    onStashVersion: (DownloadTask, Choice) -> Unit,
    onPlaylist: (MediaInfo) -> Unit,
    onSend: ((String, String?) -> Unit)?,
) {
    val c = Hamstr.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val defaults = remember { Prefs.settings.value }
    var state by remember(url) {
        mutableStateOf<InfoState>(Engine.cachedInfo(url)?.let(InfoState::Loaded) ?: InfoState.Loading)
    }
    var peek by remember(url) { mutableStateOf<Peek?>(null) }
    // Video or Audio; a custom format (behind More options) stands in for both while it is on.
    var base by remember { mutableStateOf(defaults.mode.takeIf { it != Mode.Custom } ?: Mode.Video) }
    var customOn by remember { mutableStateOf(false) }
    val mode = if (customOn) Mode.Custom else base
    var modeTouched by remember { mutableStateOf(false) }
    var height by remember { mutableStateOf(defaults.height) }
    var container by remember { mutableStateOf(defaults.container) }
    var audio by remember { mutableStateOf(defaults.audioFormat) }
    var subs by remember { mutableStateOf(defaults.subtitles) }
    var openDone by remember { mutableStateOf(defaults.openWhenDone) }
    var custom by remember { mutableStateOf(TextFieldValue("bv*+ba/b")) }
    var open by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var updating by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    val net by Network.state.collectAsState()
    val haptics = LocalHapticFeedback.current
    // Stashed: the primary button says so until the sheet leaves, and what the sheet shows stays as it was.
    var stashed by remember(url) { mutableStateOf(false) }
    var frozen by remember(url) { mutableStateOf<List<DownloadTask>?>(null) }

    LaunchedEffect(url) { if (Engine.cachedInfo(url) == null) peek = Peeker.peek(url) }
    LaunchedEffect(url, reload, net.online) {
        Engine.cachedInfo(url)?.let {
            state = InfoState.Loaded(it)
            return@LaunchedEffect
        }
        if (!net.online) {
            state = InfoState.Failed("Network is unreachable")
            return@LaunchedEffect
        }
        state = InfoState.Loading
        state = try { InfoState.Loaded(Engine.info(url)) } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            InfoState.Failed(errorLine(e))
        }
    }
    val info = (state as? InfoState.Loaded)?.info
    LaunchedEffect(info) { if (info?.entries != null) onPlaylist(info) }
    // Stashed before yt-dlp is done: the first look's title and picture go with the task until yt-dlp's arrive.
    val stashInfo = info ?: peek?.let { MediaInfo(url, it.title ?: url, it.thumbnail, null, 0, null, emptyList(), 0) }
    // Sound-only links (SoundCloud, YouTube Music, a podcast) start in Audio unless picked otherwise.
    LaunchedEffect(info) { if (info != null && !modeTouched && looksLikeAudio(url, info)) base = Mode.Audio }

    // The site's own picture shows first; yt-dlp's takes over when it's in.
    val infoBitmap: ImageBitmap? = rememberImage(info?.thumbnail)
    val peekBitmap: ImageBitmap? = rememberImage(peek?.thumbnail)
    val bitmap = infoBitmap ?: peekBitmap

    // rain 1 -> 0 once a picture is in, then the image wipes in from the top.
    val rain = remember(url) { Animatable(1f) }
    val reveal = remember(url) { Animatable(0f) }
    val failed = state is InfoState.Failed
    LaunchedEffect(bitmap != null, failed, info != null) {
        if (bitmap != null) {
            delay(250)
            rain.animateTo(0f, tween(600))
            reveal.animateTo(1f, tween(900))
        } else if (failed) {
            rain.snapTo(0.5f)
            reveal.snapTo(0f)
        } else if (info != null && info.thumbnail == null) {
            rain.animateTo(0.5f, tween(400))
        }
    }

    // Already stashed or on its way: the same video under any of its links.
    val tasks by DownloadStore.tasks.collectAsState()
    val missing by DownloadFiles.missing.collectAsState()
    val same = frozen ?: tasks.filter { t ->
        t.status != Status.Canceled && t.status != Status.Failed &&
            (t.url == url || (info != null && (t.url == info.url || (info.key != null && t.mediaKey == info.key))))
    }
    val here = same.filter { it.status == Status.Done && it.id !in missing && it.filePath?.let { p -> File(p).isFile } == true }
    val coming = same.filter { it.status.active || it.status == Status.Paused }
    val head = here.firstOrNull { it.versionOf == null } ?: here.firstOrNull()
    val choice = Choice(mode, height, container, audio, subs, custom.text, open = openDone)
    val exact = here.firstOrNull { sameChoice(it, choice) }

    val bytes = when (mode) {
        Mode.Video -> info?.bytesFor(height) ?: 0
        Mode.Audio -> info?.audioBytes ?: 0
        Mode.Custom -> 0
    }
    val size = if (bytes > 0) "~" + formatBytes(bytes) else null
    // A stash answers at once: a tick in the hand, the button turns into "In the stash", and the
    // sheet leaves a moment later ([STASHED_HOLD_MS], the caller waits for it).
    fun confirm(stash: () -> Unit) {
        if (stashed) return
        frozen = same
        stashed = true
        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        stash()
    }

    Sheet {
        AsciiImage(
            bitmap = bitmap,
            reveal = reveal.value,
            rain = rain.value,
            animate = rain.value > 0f && (state is InfoState.Loading || bitmap != null),
            modifier = Modifier.fillMaxWidth().aspectRatio(if (mode == Mode.Audio) 1f else 16f / 9f).clip(RoundedCornerShape(14.dp)),
        )
        Spacer(Modifier.height(10.dp))
        when (val s = state) {
            InfoState.Loading -> {
                val p = peek
                if (p?.title != null) {
                    DecodeText(p.title, style = Hamstr.type.section, maxLines = 2)
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        p.site?.let { Badge(it) }
                        Txt(stringResource(R.string.share_reading_formats), style = Hamstr.type.small, color = c.ghost)
                    }
                } else {
                    Txt(stringResource(R.string.share_sniffing), style = Hamstr.type.section, color = c.muted)
                    Spacer(Modifier.height(3.dp))
                    Txt(url, style = Hamstr.type.mono, color = c.ghost, maxLines = 1)
                }
            }
            is InfoState.Loaded -> {
                DecodeText(s.info.title, style = Hamstr.type.section, maxLines = 2)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    s.info.source?.let { Badge(it) }
                    if (s.info.durationSec > 0) Badge(formatDuration(s.info.durationSec))
                    s.info.uploader?.let { Txt(it, style = Hamstr.type.small, color = c.ghost, maxLines = 1, modifier = Modifier.weight(1f, fill = false)) }
                }
            }
            is InfoState.Failed -> {
                val why = Failures.explain(s.message)
                Txt(sharedString(why.title), style = Hamstr.type.section)
                Spacer(Modifier.height(3.dp))
                Txt(sharedString(why.hint), style = Hamstr.type.small, color = c.muted)
                // Locked here, maybe free on YouTube Music: offer that one instead. Taking it closes
                // the sheet and stashes it right away, as a song unless Video was picked on purpose.
                if (why.title == Res.string.err_drm) {
                    Spacer(Modifier.height(10.dp))
                    // The page names the song, the artist and the length where it can (Spotify, Apple
                    // Music, SoundCloud); without them the card reads the page again itself.
                    ElsewhereCard(url, peek?.title, peek?.artist, peek?.durationSec ?: 0) { hit ->
                        confirm { onStashOther(hit.url, hit.toInfo(), if (modeTouched && mode == Mode.Video) choice else choice.copy(mode = Mode.Audio)) }
                    }
                }
                if (s.message.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Row(
                        Modifier.clip(RoundedCornerShape(8.dp)).clickable { details = !details }.padding(vertical = 4.dp, horizontal = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Txt(stringResource(R.string.err_details), style = Hamstr.type.badge, color = c.ghost)
                        Icon(Res.drawable.ic_expand_more, tint = c.ghost, size = 16.dp)
                    }
                    AnimatedVisibility(details, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                        Txt(s.message, style = Hamstr.type.mono, color = c.red, maxLines = 6, modifier = Modifier.padding(top = 2.dp))
                    }
                }
            }
        }
        if (here.isNotEmpty() || coming.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(Radii.control)).background(c.surface).padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(if (here.isNotEmpty()) Res.drawable.ic_check_circle else Res.drawable.ic_download, tint = c.accent, size = 18.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Txt(
                        stringResource(if (here.isNotEmpty()) R.string.share_already_here else R.string.share_already_coming),
                        style = Hamstr.type.bodyMedium,
                    )
                    Txt(
                        if (here.isNotEmpty()) listOfNotNull(versionsBadge(here), here.sumOf { it.sizeBytes }.takeIf { it > 0 }?.let(::formatBytes)).joinToString(" · ")
                        else rememberCurrent(coming.first()).let { "${formatBadge(it)} · ${(it.progress * 100).toInt()} %" },
                        style = Hamstr.type.small, color = c.ghost, maxLines = 1,
                    )
                }
                head?.let { HButton(stringResource(R.string.share_open), { onOpen(exact ?: it) }, kind = ButtonKind.Ghost, icon = Res.drawable.ic_play_arrow) }
            }
        }
        Spacer(Modifier.height(12.dp))
        // Every share asks only this: Video or Audio, and its quality. The rest waits behind More options.
        val modeLabels = mapOf(
            Mode.Video to stringResource(R.string.mode_video),
            Mode.Audio to stringResource(R.string.mode_audio),
        )
        Segmented(
            options = listOf(Mode.Video, Mode.Audio),
            selected = base,
            label = { modeLabels.getValue(it) },
            // Picking one of the two leaves a custom format.
            onSelect = { base = it; customOn = false; modeTouched = true; open = null },
        )
        Spacer(Modifier.height(4.dp))
        if (!customOn) when (base) {
            Mode.Audio -> ExpandRow(
                stringResource(R.string.share_audio_format),
                listOfNotNull(audio, size).joinToString(" · "),
                open == "a", { open = if (open == "a") null else "a" },
            ) {
                Chips(Prefs.audioFormats, audio, { it }) { audio = it; open = null }
            }
            else -> {
                val offered = info?.heights?.takeIf { it.isNotEmpty() }
                val heights = Prefs.heights.filter { h -> h == null || offered == null || offered.any { it >= h } || h == offered.max() }
                val sizeOf = { h: Int? -> info?.bytesFor(h)?.takeIf { it > 0 }?.let { "~" + formatBytes(it) } }
                ExpandRow(
                    stringResource(R.string.share_quality),
                    listOfNotNull(heightLabel(height), sizeOf(height)).joinToString(" · "),
                    open == "q", { open = if (open == "q") null else "q" },
                ) {
                    Chips(heights, height, ::heightLabel, sub = sizeOf) { height = it; open = null }
                }
            }
        }
        if (!customOn) Divider()
        val opensDone = stringResource(R.string.share_opens_done).takeIf { openDone }
        val summary = when {
            customOn -> "${stringResource(R.string.mode_custom)} · ${custom.text}"
            base == Mode.Video -> listOfNotNull(container, stringResource(if (subs) R.string.add_subs_short else R.string.share_no_subs), opensDone).joinToString(" · ")
            else -> opensDone
        }
        SettingRow(stringResource(R.string.share_more), subtitle = summary, onClick = { more = !more; open = null }) {
            Chevron(more, c.muted)
        }
        AnimatedVisibility(more, enter = expandVertically(Specs.blend()) + fadeIn(Specs.blend()), exit = shrinkVertically(tween(150)) + fadeOut(tween(150))) {
            Column {
                if (base == Mode.Video && !customOn) {
                    ExpandRow(stringResource(R.string.share_format), container, open == "f", { open = if (open == "f") null else "f" }) {
                        Chips(Prefs.containers, container, { it }) { container = it; open = null }
                    }
                    Divider()
                    SettingRow(
                        // The languages by name, as Settings shows them, not as codes.
                        stringResource(R.string.share_subtitles, defaults.subtitleLanguages().joinToString(", ") { languageName(it) }),
                        onClick = { subs = !subs },
                        checked = subs,
                    ) { HSwitch(subs, { subs = it }) }
                    Divider()
                }
                // Here it only switches on; the permission it may need is for Settings, not for the middle of a share.
                OpenWhenDoneRow(openDone, hint = false, sendsAway = false) { openDone = it }
                Divider()
                SettingRow(
                    stringResource(R.string.share_custom),
                    subtitle = stringResource(R.string.share_custom_hint),
                    onClick = { customOn = !customOn; open = null },
                    checked = customOn,
                ) { HSwitch(customOn, { customOn = it; open = null }) }
                AnimatedVisibility(customOn, enter = expandVertically(Specs.blend()) + fadeIn(Specs.blend()), exit = shrinkVertically(tween(150)) + fadeOut(tween(150))) {
                    Box(Modifier.padding(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 8.dp)) {
                        SheetField(
                            custom, { custom = it }, stringResource(R.string.share_custom_placeholder), Res.drawable.ic_code,
                            mono = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        val keepChoice = {
            Prefs.edit { it.copy(mode = if (customOn) it.mode else base, height = height, container = container, audioFormat = audio, subtitles = subs, openWhenDone = openDone) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Or hand the link to another Hamstr nearby, like AirDrop: a small square before the rest.
            if (onSend != null) SquareButton(Res.drawable.ic_share, stringResource(R.string.menu_tile_share), { onSend(url, stashInfo?.title) })
            HButton(stringResource(R.string.share_cancel), onDismiss, Modifier.weight(1f))
            val primary = Modifier.weight(2f)
            val s = state
            // What the primary button stashes, if it stashes: its words and what it does.
            val stash: Pair<String, () -> Unit>? = when {
                // Offline: it can still be queued; it starts on its own once the phone is back online.
                !net.online -> stringResource(R.string.share_stash_later) to { onStash(stashInfo, choice) }
                s is InfoState.Failed || exact != null -> null
                head != null -> stringResource(R.string.share_stash_too, if (mode == Mode.Audio) audio else heightLabel(height)) to { onStashVersion(head, choice) }
                else -> (if (size != null) stringResource(R.string.share_stash_sized, size) else stringResource(R.string.share_stash)) to { onStash(stashInfo, choice) }
            }
            when {
                stashed || stash != null -> StashButton(
                    stash?.first.orEmpty(), done = stashed,
                    onClick = { stash?.let { (_, run) -> keepChoice(); confirm(run) } },
                    modifier = primary,
                )
                s is InfoState.Failed -> {
                    val fix = Failures.explain(s.message).fix
                    HButton(
                        stringResource(
                            when {
                                updating -> R.string.err_updating
                                fix == Fix.UpdateYtDlp -> R.string.err_update_retry
                                else -> R.string.err_try_again
                            },
                        ),
                        {
                            if (fix == Fix.UpdateYtDlp && !updating) scope.launch {
                                updating = true
                                Engine.update(context)
                                updating = false
                                reload++
                            } else if (!updating) reload++
                        },
                        primary, kind = ButtonKind.Primary, icon = Res.drawable.ic_refresh, enabled = !updating && fix != Fix.None,
                    )
                }
                exact != null -> HButton(stringResource(R.string.share_open_it), { onOpen(exact) }, primary, kind = ButtonKind.Primary, icon = Res.drawable.ic_play_arrow)
            }
        }
    }
}

/** How long the share sheet shows "In the stash" before it leaves; the callers wait this long. */
const val STASHED_HOLD_MS = 650L

/**
 * The sheet's primary button for stashing. Once [done] it turns into its own confirmation:
 * the icon swaps to a check and the words to "In the stash", and it takes no second tap.
 */
@Composable
private fun StashButton(text: String, done: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val c = Hamstr.colors
    Row(
        modifier
            .height(46.dp)
            .clip(RoundedCornerShape(Radii.control))
            .background(c.accent)
            .pressable({ if (!done) onClick() })
            // A screen reader hears the confirmation as it comes.
            .semantics { liveRegion = LiveRegionMode.Polite }
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimatedContent(done, transitionSpec = { iconSwap() }, label = "stashIcon") { d ->
            Icon(if (d) Res.drawable.ic_check else Res.drawable.ic_download, tint = c.onAccent, size = 18.dp)
        }
        AnimatedContent(
            if (done) stringResource(R.string.share_stashed) else text,
            transitionSpec = { fadeIn(Specs.appear()) togetherWith fadeOut(Specs.disappear()) },
            label = "stashLabel",
        ) { label -> Txt(label, style = Hamstr.type.button, color = c.onAccent, maxLines = 1) }
    }
}

/** A setting that opens in place to show its choices. */
@Composable
private fun ExpandRow(title: String, value: String, expanded: Boolean, onToggle: () -> Unit, content: @Composable () -> Unit) {
    Column {
        SettingRow(title, onClick = onToggle) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Txt(value, color = Hamstr.colors.muted)
                Chevron(expanded, Hamstr.colors.muted)
            }
        }
        AnimatedVisibility(expanded, enter = expandVertically(Specs.blend()) + fadeIn(Specs.blend()), exit = shrinkVertically(tween(150)) + fadeOut(tween(150))) {
            Box(Modifier.padding(start = 8.dp, end = 8.dp, bottom = 4.dp)) { content() }
        }
    }
}

/** The chevron of a row that opens in place: it turns over while the row is open. */
@Composable
private fun Chevron(open: Boolean, tint: Color) {
    val turn by animateFloatAsState(if (open) 180f else 0f, if (LocalReduceMotion.current) snap() else Specs.blend(), label = "chevron")
    Icon(Res.drawable.ic_expand_more, tint = tint, size = 18.dp, modifier = Modifier.graphicsLayer { rotationZ = turn })
}

