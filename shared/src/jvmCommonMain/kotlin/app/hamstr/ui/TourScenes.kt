package app.hamstr.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.hamstr.data.Prefs
import app.hamstr.res.*
import app.hamstr.ui.components.AsciiImage
import app.hamstr.ui.components.Badge
import app.hamstr.ui.components.ButtonKind
import app.hamstr.ui.components.DecodeText
import app.hamstr.ui.components.Divider
import app.hamstr.ui.components.Group
import app.hamstr.ui.components.HButton
import app.hamstr.ui.components.HamstrMark
import app.hamstr.ui.components.Icon
import app.hamstr.ui.components.LocalReduceMotion
import app.hamstr.ui.components.MARK
import app.hamstr.ui.components.MarkState
import app.hamstr.ui.components.PictureLabel
import app.hamstr.ui.components.ProgressLine
import app.hamstr.ui.components.SectionHeader
import app.hamstr.ui.components.Specs
import app.hamstr.ui.components.Txt
import app.hamstr.ui.components.animateSelection
import app.hamstr.ui.components.edgeFade
import app.hamstr.ui.components.rememberAmbientClock
import app.hamstr.ui.components.rememberMarkState
import app.hamstr.ui.theme.Hamstr
import app.hamstr.ui.theme.LocalFx
import app.hamstr.ui.theme.Radii
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/** What the pictures show: made up like the demo's entries, never the user's own downloads. */
private object Sample {
    const val VIDEO = "Northern Lights Over Lofoten"
    const val CHANNEL = "Polar Nights"
    const val LINK = "youtu.be/9fLm2qS7Uak"
    const val URL = "https://www.youtube.com/watch?v=9fLm2qS7Uak"
    const val TRAIN = "Night Train Through the Alps"
    const val TRAIN_BY = "Slow Travel Club"
    const val SONG = "Lo-fi Beats for Rainy Evenings"
    const val ARTIST = "Paper Moon"
    const val PASTA = "Making Fresh Pasta by Hand"
    const val PASTA_BY = "Kitchen Notes"
    const val COMPUTER = "Studio PC"
    const val PHONE = "Pixel"
    const val MB = 1L shl 20
}

/** Sites the welcome page names around the hamster; the last one stands for the rest. */
private val SITES = listOf("YouTube", "TikTok", "Instagram", "SoundCloud", "Reddit", "Vimeo", "Twitch", "+1800")

/**
 * The picture of a tour page. The made-up screens are pictures only: screen readers read the
 * words beside them. On the computer, choosing a format is the real setting.
 */
@Composable
internal fun TourScene(step: TourStep, active: Boolean, desktop: Boolean) {
    val picture = Modifier.clearAndSetSemantics { }
    when (step) {
        TourStep.Hello -> HelloScene(active, picture)
        TourStep.Share -> if (desktop) PasteScene(active, picture) else ShareScene(active, picture)
        TourStep.Choose -> if (desktop) ChooseNow() else ChooseScene(active, picture)
        TourStep.Arrive -> ArriveScene(active, desktop, picture)
        TourStep.Play -> PlayScene(active, picture)
        TourStep.Send -> SendScene(active, picture)
        TourStep.Look -> LookScene(picture)
    }
}

// ---------------------------------------------------------------------------------------------
// Pages

/** The hamster builds itself, the sites it knows circle it, and now and then it munches. */
@Composable
private fun HelloScene(active: Boolean, modifier: Modifier) {
    val reduce = LocalReduceMotion.current
    val moods = LocalFx.current.moods
    val mark = remember { MarkState(intro = if (reduce) 1f else 0f) }
    val shown = remember { Animatable(if (reduce) 1f else 0f) }
    LaunchedEffect(active, reduce) {
        if (!active || reduce) return@LaunchedEffect
        if (mark.intro.value < 1f) mark.assemble(700)
        shown.animateTo(1f, tween(900))
        while (moods) {
            delay(2600)
            mark.munch()
            delay(2400)
            mark.blink()
        }
    }
    // One slow turn a minute and a half, at 30 steps a second.
    val clock = rememberAmbientClock(30, enabled = active && moods)
    Fit(380.dp, 400.dp, modifier) {
        val c = Hamstr.colors
        SITES.forEachIndexed { i, site ->
            Box(
                Modifier
                    .align(Alignment.Center)
                    .offset {
                        val a = (clock.value % 90_000L) / 90_000f * 2f * PI.toFloat() + i * 2f * PI.toFloat() / SITES.size - PI.toFloat() / 2f
                        IntOffset((cos(a) * 134.dp.toPx()).roundToInt(), (sin(a) * 166.dp.toPx()).roundToInt())
                    }
                    .graphicsLayer {
                        val k = (shown.value * SITES.size - i).coerceIn(0f, 1f)
                        alpha = k
                        scaleX = 0.8f + 0.2f * k
                        scaleY = 0.8f + 0.2f * k
                    }
                    .clip(RoundedCornerShape(8.dp))
                    .background(c.overlay)
                    .padding(horizontal = 8.dp, vertical = 5.dp),
            ) { Badge(site, accent = i == SITES.lastIndex) }
        }
        HamstrMark(176.dp, Modifier.align(Alignment.Center), state = mark, background = false)
    }
}

/** Another app plays a video; Share opens Android's sheet, Hamstr is picked and its own sheet comes up. */
@Composable
private fun ShareScene(active: Boolean, modifier: Modifier) {
    val c = Hamstr.colors
    val reduce = LocalReduceMotion.current
    var tapShare by remember { mutableStateOf(false) }
    var system by remember { mutableStateOf(reduce) }
    var tapHamstr by remember { mutableStateOf(reduce) }
    var ours by remember { mutableStateOf(false) }
    LaunchedEffect(active, reduce) {
        if (!active || reduce) return@LaunchedEffect
        while (true) {
            tapShare = false; system = false; tapHamstr = false; ours = false
            delay(900)
            tapShare = true
            delay(560)
            system = true
            delay(1000)
            tapShare = false
            tapHamstr = true
            delay(640)
            system = false
            delay(220)
            tapHamstr = false
            ours = true
            delay(2800)
            ours = false
            delay(700)
        }
    }
    val poster = rememberPoster(152f, 3)
    PhoneFrame(modifier, fade = false) {
        Column(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
            Spacer(Modifier.height(6.dp))
            // Some other app, playing a video.
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(14.dp))) {
                Image(poster, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                Box(Modifier.align(Alignment.Center).size(44.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
                    Icon(Res.drawable.ic_play_arrow, tint = Color.White, size = 26.dp)
                }
                PictureLabel("12:34", Modifier.align(Alignment.BottomEnd).padding(6.dp))
            }
            Spacer(Modifier.height(10.dp))
            Txt(Sample.VIDEO, style = Hamstr.type.bodyMedium, maxLines = 2)
            Spacer(Modifier.height(2.dp))
            Txt(Sample.CHANNEL, style = Hamstr.type.small, color = c.ghost)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppPill(Res.drawable.ic_favorite_border, "12K")
                AppPill(Res.drawable.ic_share, stringResource(Res.string.menu_tile_share), tap = tapShare)
                AppPill(Res.drawable.ic_playlist_add, stringResource(Res.string.tour_demo_save))
            }
            Spacer(Modifier.height(16.dp))
            // The rest of that app: comments, out of focus.
            repeat(5) { i ->
                Row(Modifier.padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(26.dp).clip(CircleShape).background(c.surface))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Box(Modifier.fillMaxWidth(0.3f + 0.12f * (i % 3)).height(7.dp).clip(CircleShape).background(c.surface))
                        Spacer(Modifier.height(6.dp))
                        Box(Modifier.fillMaxWidth(0.9f - 0.1f * (i % 4)).height(7.dp).clip(CircleShape).background(c.overlay))
                    }
                }
            }
        }
        AnimatedVisibility(system || ours, enter = fadeIn(Specs.blend()), exit = fadeOut(tween(150))) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)))
        }
        SceneSheet(system, Modifier.align(Alignment.BottomCenter)) { SystemShareSheet(poster, tapHamstr) }
        SceneSheet(ours, Modifier.align(Alignment.BottomCenter)) { HamstrSheetPeek(poster) }
    }
}

/** On a computer: the link is copied in the browser, Ctrl+V lands it in the stash. */
@Composable
private fun PasteScene(active: Boolean, modifier: Modifier) {
    val c = Hamstr.colors
    val reduce = LocalReduceMotion.current
    var selected by remember { mutableStateOf(reduce) }
    var key by remember { mutableStateOf<String?>(null) }
    var arrived by remember { mutableStateOf(reduce) }
    val progress = remember { Animatable(if (reduce) 0.42f else 0f) }
    LaunchedEffect(active, reduce) {
        if (!active || reduce) return@LaunchedEffect
        while (true) {
            selected = false; key = null; arrived = false
            progress.snapTo(0f)
            delay(800)
            selected = true
            delay(500)
            key = "C"
            delay(420)
            key = null
            delay(650)
            key = "V"
            delay(360)
            arrived = true
            delay(220)
            key = null
            progress.animateTo(0.86f, tween(3400, easing = LinearEasing))
            delay(900)
        }
    }
    val poster = rememberPoster(152f, 3)
    val second = rememberPoster(28f, 11)
    WindowFrame(modifier) {
        Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
            Spacer(Modifier.height(10.dp))
            // The browser the link comes from.
            Row(
                Modifier.fillMaxWidth().height(38.dp).clip(RoundedCornerShape(12.dp)).background(c.overlay).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Res.drawable.ic_link, tint = c.ghost, size = 15.dp)
                Spacer(Modifier.width(8.dp))
                val lit = animateSelection(selected)
                Txt(
                    Sample.URL, style = Hamstr.type.mono, maxLines = 1,
                    modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(c.accent.copy(alpha = 0.32f * lit)).padding(horizontal = 3.dp, vertical = 1.dp),
                )
            }
            Spacer(Modifier.height(18.dp))
            Row(Modifier.align(Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Keycap(if (pasteKeys.startsWith("⌘")) "⌘" else "Ctrl", pressed = key != null)
                Txt("+", style = Hamstr.type.mono, color = c.ghost)
                Keycap(key ?: "V", pressed = key != null)
            }
            SectionHeader(stringResource(Res.string.stash_downloading), trailing = stringResource(Res.string.stash_running, if (arrived) 1 else 0))
            Group {
                AnimatedVisibility(arrived, enter = expandVertically(Specs.enter()) + fadeIn(Specs.listIn()), exit = shrinkVertically(Specs.leave()) + fadeOut(Specs.listOut())) {
                    MockRow(Sample.VIDEO, "1080p", speedLine(progress.value), poster, progress.value, done = false, highlighted = true)
                }
                MockRow(Sample.TRAIN, "720p", doneLine(Sample.TRAIN_BY, 212 * Sample.MB, 3), second, 1f, done = true)
            }
        }
    }
}

/** Hamstr's own share sheet: video or sound, the quality, and Stash it. */
@Composable
private fun ChooseScene(active: Boolean, modifier: Modifier) {
    val c = Hamstr.colors
    val reduce = LocalReduceMotion.current
    var mode by remember { mutableIntStateOf(0) }
    var tapStash by remember { mutableStateOf(false) }
    LaunchedEffect(active, reduce) {
        if (!active || reduce) return@LaunchedEffect
        while (true) {
            mode = 0; tapStash = false
            delay(1700)
            mode = 1
            delay(2100)
            mode = 0
            delay(1300)
            tapStash = true
            delay(1100)
        }
    }
    val poster = rememberPoster(152f, 3)
    val video = mode == 0
    PhoneFrame(modifier, fade = false) {
        // The stash behind the sheet.
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
            StashHeader(rememberMarkState(autoBlink = false))
            Txt(formatBytes(1_503_238_553L), style = Hamstr.type.hero, modifier = Modifier.padding(start = 6.dp, top = 14.dp))
        }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)))
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .background(c.overlay)
                .padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 16.dp),
        ) {
            Handle()
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(14.dp))) {
                Image(poster, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                PictureLabel("12:34", Modifier.align(Alignment.BottomEnd).padding(6.dp))
            }
            Spacer(Modifier.height(10.dp))
            Txt(Sample.VIDEO, style = Hamstr.type.bodyMedium, maxLines = 1)
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Badge("youtube")
                Badge("12:34")
                Txt(Sample.CHANNEL, style = Hamstr.type.small, color = c.ghost, maxLines = 1)
            }
            Spacer(Modifier.height(12.dp))
            MockSegmented(
                listOf(stringResource(Res.string.mode_video), stringResource(Res.string.mode_audio), stringResource(Res.string.mode_custom)),
                mode,
            )
            Spacer(Modifier.height(8.dp))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(Radii.row)).background(c.surface)) {
                ValueRow(stringResource(Res.string.share_quality), if (video) "1080p · ~431 MB" else "Best · ~12 MB")
                Divider()
                ValueRow(stringResource(Res.string.share_format), if (video) "mp4" else "opus")
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HButton(stringResource(Res.string.share_cancel), {}, Modifier.weight(1f))
                Box(Modifier.weight(2f), contentAlignment = Alignment.Center) {
                    val press by animateFloatAsState(if (tapStash) 0.96f else 1f, Specs.press(), label = "stashPress")
                    // The size is in the row above; a phone's width fits it on the button too.
                    HButton(
                        stringResource(Res.string.share_stash), {},
                        Modifier.fillMaxWidth().graphicsLayer { scaleX = press; scaleY = press },
                        kind = ButtonKind.Primary, icon = Res.drawable.ic_download,
                    )
                    TapRing(tapStash, Modifier.size(46.dp))
                }
            }
        }
    }
}

/** On a computer the choice is made once, for every link: the real setting, right here. */
@Composable
private fun ChooseNow() {
    val scroll = rememberScrollState()
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 460.dp).fillMaxWidth().edgeFade(scroll, Orientation.Vertical).verticalScroll(scroll)) {
            FormatDefaults()
        }
    }
}

/** The stash: two downloads resolve out of characters, one lands with a munch. */
@Composable
private fun ArriveScene(active: Boolean, desktop: Boolean, modifier: Modifier) {
    val reduce = LocalReduceMotion.current
    val moods = LocalFx.current.moods
    val mark = rememberMarkState()
    val first = remember { Animatable(if (reduce) 0.64f else 0f) }
    val second = remember { Animatable(if (reduce) 0.31f else 0.08f) }
    var landed by remember { mutableStateOf(false) }
    LaunchedEffect(active, reduce) {
        if (!active || reduce) return@LaunchedEffect
        while (true) {
            landed = false
            first.snapTo(0f)
            second.snapTo(0.08f)
            delay(500)
            coroutineScope {
                launch { first.animateTo(1f, tween(3400, easing = FastOutSlowInEasing)) }
                launch { second.animateTo(0.58f, tween(5600, easing = LinearEasing)) }
                launch {
                    delay(3550)
                    landed = true
                    if (moods) mark.munch()
                }
            }
            delay(2400)
        }
    }
    val northern = rememberPoster(152f, 3)
    val train = rememberPoster(28f, 11)
    val rainy = rememberPoster(256f, 7)
    val pasta = rememberPoster(8f, 5)
    val total = 1_503_238_553L + if (landed) 431 * Sample.MB else 0
    val files = if (landed) 7 else 6
    val content: @Composable BoxScope.() -> Unit = {
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
            StashHeader(mark)
            Column(Modifier.padding(start = 6.dp, top = 12.dp)) {
                Txt(formatBytes(total), style = Hamstr.type.hero)
                Spacer(Modifier.height(2.dp))
                Txt(pluralStringResource(Res.plurals.stash_files, files, files), color = Hamstr.colors.muted)
            }
            SectionHeader(stringResource(Res.string.stash_downloading), trailing = stringResource(Res.string.stash_running, if (landed) 1 else 2))
            Group {
                AnimatedVisibility(!landed, enter = expandVertically(Specs.enter()) + fadeIn(Specs.listIn()), exit = shrinkVertically(Specs.leave()) + fadeOut(Specs.listOut())) {
                    MockRow(Sample.VIDEO, "1080p", speedLine(first.value), northern, first.value, done = false, highlighted = true)
                }
                MockRow(Sample.TRAIN, "720p", speedLine(second.value, slow = true), train, second.value, done = false)
            }
            SectionHeader(stringResource(Res.string.stash_stashed), trailing = "$files")
            Group {
                AnimatedVisibility(landed, enter = expandVertically(Specs.enter()) + fadeIn(Specs.listIn()), exit = shrinkVertically(Specs.leave()) + fadeOut(Specs.listOut())) {
                    MockRow(Sample.VIDEO, "1080p", doneLine(Sample.CHANNEL, 431 * Sample.MB, 0), northern, 1f, done = true)
                }
                MockRow(Sample.SONG, "m4a", doneLine(Sample.ARTIST, 8 * Sample.MB, 2), rainy, 1f, done = true)
                MockRow(Sample.PASTA, "720p", doneLine(Sample.PASTA_BY, 475 * Sample.MB, 26), pasta, 1f, done = true)
            }
        }
    }
    if (desktop) WindowFrame(modifier, content = content) else PhoneFrame(modifier, content = content)
}

/** A song's page: the cover, the heart, time running on, and the player's modes. */
@Composable
private fun PlayScene(active: Boolean, modifier: Modifier) {
    val c = Hamstr.colors
    val reduce = LocalReduceMotion.current
    val length = 222
    var position by remember { mutableIntStateOf(71) }
    var fav by remember { mutableStateOf(reduce) }
    LaunchedEffect(active, reduce) {
        if (!active || reduce) return@LaunchedEffect
        fav = false
        launch { delay(1600); fav = true }
        while (true) {
            delay(1000)
            position = (position + 1) % length
        }
    }
    val cover = rememberPoster(256f, 7, square = true)
    val tint = Color.hsv(256f, 0.55f, 0.5f)
    PhoneFrame(modifier) {
        // Tinted by its cover, like the real song page.
        Box(Modifier.fillMaxWidth().height(320.dp).background(Brush.verticalGradient(listOf(tint.copy(alpha = 0.45f), Color.Transparent))))
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
            Row(Modifier.fillMaxWidth().height(38.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Res.drawable.ic_expand_more, tint = c.muted, size = 22.dp)
                Spacer(Modifier.weight(1f))
                Txt(stringResource(Res.string.player_now_playing), style = Hamstr.type.small, color = c.muted)
                Spacer(Modifier.weight(1f))
                Icon(Res.drawable.ic_more_vert, tint = c.muted, size = 20.dp)
            }
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(18.dp))) {
                Image(cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Txt(Sample.SONG, style = Hamstr.type.section, maxLines = 1)
                    Spacer(Modifier.height(2.dp))
                    Txt(Sample.ARTIST, style = Hamstr.type.small, color = c.muted)
                }
                val pop by animateFloatAsState(if (fav) 1f else 0.85f, Specs.pop(), label = "heart")
                Icon(
                    if (fav) Res.drawable.ic_favorite else Res.drawable.ic_favorite_border,
                    tint = if (fav) c.accent else c.muted, size = 22.dp,
                    modifier = Modifier.graphicsLayer { scaleX = pop; scaleY = pop },
                )
            }
            Spacer(Modifier.height(14.dp))
            val at by animateFloatAsState(position / length.toFloat(), Specs.glide(), label = "seek")
            BoxWithConstraints(Modifier.fillMaxWidth().height(14.dp), contentAlignment = Alignment.CenterStart) {
                ProgressLine(at)
                Box(Modifier.offset(x = (maxWidth - 12.dp) * at).size(12.dp).clip(CircleShape).background(c.ink))
            }
            Spacer(Modifier.height(4.dp))
            Row {
                Txt(clock(position), style = Hamstr.type.mono, color = c.ghost)
                Spacer(Modifier.weight(1f))
                Txt(clock(length), style = Hamstr.type.mono, color = c.ghost)
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Icon(Res.drawable.ic_shuffle, tint = c.muted, size = 22.dp)
                Icon(Res.drawable.ic_skip_previous, size = 30.dp)
                Box(Modifier.size(58.dp).clip(CircleShape).background(c.accent), contentAlignment = Alignment.Center) {
                    Icon(Res.drawable.ic_pause, tint = c.onAccent, size = 30.dp)
                }
                Icon(Res.drawable.ic_skip_next, size = 30.dp)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Res.drawable.ic_repeat, tint = c.accent, size = 22.dp)
                    Spacer(Modifier.height(3.dp))
                    Box(Modifier.size(4.dp).clip(CircleShape).background(c.accent))
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
                ModeChip(Res.drawable.ic_lyrics, stringResource(Res.string.lyrics), on = true)
                ModeChip(Res.drawable.ic_queue_music, stringResource(Res.string.queue_title))
                ModeChip(Res.drawable.ic_timer, null)
            }
        }
    }
}

/**
 * The two devices find each other (rings go out, the dots between them join up), the link flies
 * over in an arc and lights the way, the phone asks once, and the download runs to the end there.
 */
@Composable
private fun SendScene(active: Boolean, modifier: Modifier) {
    val c = Hamstr.colors
    val reduce = LocalReduceMotion.current
    val seek = remember { Animatable(if (reduce) 1f else 0f) } // the rings going out
    val found = remember { Animatable(if (reduce) 1f else 0f) } // the dots joining up
    val trip = remember { Animatable(if (reduce) 1f else 0f) } // the link on its way
    val glow = remember { Animatable(0f) } // the way it lit up
    val land = remember { Animatable(if (reduce) 1f else 0f) } // the ring where it landed
    val fetch = remember { Animatable(if (reduce) 0.62f else 0f) } // the download on the phone
    var sending by remember { mutableStateOf(false) }
    var asked by remember { mutableStateOf(false) }
    var tapAllow by remember { mutableStateOf(false) }
    var landed by remember { mutableStateOf(reduce) }
    var row by remember { mutableStateOf(reduce) }
    LaunchedEffect(active, reduce) {
        if (!active || reduce) return@LaunchedEffect
        while (true) {
            seek.snapTo(0f); found.snapTo(0f); trip.snapTo(0f); glow.snapTo(0f); land.snapTo(0f); fetch.snapTo(0f)
            sending = false; asked = false; tapAllow = false; landed = false; row = false
            delay(250)
            coroutineScope {
                launch { seek.animateTo(1f, tween(1500, easing = LinearOutSlowInEasing)) }
                launch { delay(350); found.animateTo(1f, tween(900, easing = FastOutSlowInEasing)) }
            }
            sending = true
            delay(250)
            glow.snapTo(1f)
            coroutineScope {
                launch { trip.animateTo(1f, tween(1300, easing = FastOutSlowInEasing)) }
                launch { delay(450); sending = false }
            }
            landed = true
            land.animateTo(1f, tween(700, easing = LinearOutSlowInEasing))
            asked = true
            delay(1100)
            tapAllow = true
            delay(620)
            asked = false
            tapAllow = false
            row = true
            coroutineScope {
                launch { glow.animateTo(0f, tween(900)) }
                fetch.animateTo(1f, tween(3200, easing = LinearEasing))
            }
            delay(1900)
            landed = false
            row = false
            delay(450)
        }
    }
    val poster = rememberPoster(152f, 3)
    Fit(380.dp, 340.dp, modifier) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().height(170.dp)) {
                // Where the two icons sit, and the arc from the top of one tile to the top of the other.
                val tileTop = 46.dp
                val from = Offset(96f, 46f)
                val over = Offset(190f, -34f)
                val to = Offset(284f, 46f)
                fun along(t: Float): Offset {
                    val u = 1f - t
                    return from * (u * u) + over * (2f * u * t) + to * (t * t)
                }
                // The way: dots that join up from both ends, then light up behind the link.
                Canvas(Modifier.fillMaxSize()) {
                    val dots = 17
                    for (i in 0 until dots) {
                        val t = (i + 0.5f) / dots
                        val fromEnd = min(t, 1f - t) * 1.7f
                        val shown = ((found.value - fromEnd) / 0.15f).coerceIn(0f, 1f)
                        if (shown <= 0f) continue
                        val lit = if (t <= trip.value) glow.value else 0f
                        val p = along(t)
                        drawCircle(
                            lerp(c.line, c.accent, lit).copy(alpha = shown),
                            radius = (1.8f + 1.2f * lit).dp.toPx(),
                            center = Offset(p.x.dp.toPx(), p.y.dp.toPx()),
                        )
                    }
                }
                DeviceTile(Res.drawable.ic_computer, Sample.COMPUTER, stringResource(Res.string.kind_desktop), lit = sending, modifier = Modifier.padding(top = tileTop).align(Alignment.TopStart))
                DeviceTile(Res.drawable.ic_smartphone, Sample.PHONE, stringResource(Res.string.kind_phone), lit = landed, modifier = Modifier.padding(top = tileTop).align(Alignment.TopEnd))
                // Rings: the two looking for each other, then the phone where the link lands.
                Canvas(Modifier.fillMaxSize()) {
                    val icons = listOf(Offset(14f + 20f, 46f + 14f + 20f), Offset(254f + 14f + 20f, 46f + 14f + 20f))
                    fun ring(at: Offset, v: Float, strength: Float) {
                        if (v <= 0f || v >= 1f) return
                        drawCircle(
                            c.accent.copy(alpha = (1f - v) * strength),
                            radius = (22f + 70f * v).dp.toPx(),
                            center = Offset(at.x.dp.toPx(), at.y.dp.toPx()),
                            style = Stroke(1.5.dp.toPx()),
                        )
                    }
                    for (at in icons) {
                        ring(at, seek.value, 0.55f)
                        ring(at, (seek.value - 0.3f) / 0.7f, 0.35f)
                    }
                    ring(icons[1], land.value, 0.8f)
                    // A short tail behind the link.
                    val t = trip.value
                    if (t in 0.02f..0.98f) for (k in 1..4) {
                        val p = along((t - k * 0.025f).coerceAtLeast(0f))
                        drawCircle(c.accent.copy(alpha = 0.32f - k * 0.07f), radius = (7f - k).dp.toPx(), center = Offset(p.x.dp.toPx(), p.y.dp.toPx()))
                    }
                }
                // The link itself.
                Box(
                    Modifier
                        .offset {
                            val p = along(trip.value)
                            IntOffset((p.x.dp - 15.dp).roundToPx(), (p.y.dp - 15.dp).roundToPx())
                        }
                        .graphicsLayer {
                            val t = trip.value
                            val inOut = min(t / 0.1f, (1f - t) / 0.1f).coerceIn(0f, 1f)
                            alpha = if (reduce) 0f else inOut
                            val s = 0.55f + 0.45f * inOut + 0.12f * sin(PI.toFloat() * t)
                            scaleX = s; scaleY = s
                            rotationZ = -18f + 36f * t
                        }
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(c.accent),
                    contentAlignment = Alignment.Center,
                ) { Icon(Res.drawable.ic_link, tint = c.onAccent, size = 16.dp) }
            }
            Box(Modifier.fillMaxWidth().height(138.dp)) {
                CardSwap(asked) {
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(Radii.panel)).background(c.overlay).padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(c.surface), contentAlignment = Alignment.Center) {
                                Icon(Res.drawable.ic_computer, tint = c.accent, size = 18.dp)
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Txt(stringResource(Res.string.nearby_ask_title, Sample.COMPUTER), style = Hamstr.type.bodyMedium, maxLines = 2)
                                Txt(Sample.LINK, style = Hamstr.type.mono, color = c.ghost, maxLines = 1)
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.weight(1.3f), contentAlignment = Alignment.Center) {
                                HButton(stringResource(Res.string.nearby_always), {}, Modifier.fillMaxWidth(), kind = ButtonKind.Primary)
                                TapRing(tapAllow, Modifier.size(44.dp))
                            }
                            HButton(stringResource(Res.string.nearby_once), {}, Modifier.weight(1f))
                        }
                    }
                }
                CardSwap(row) {
                    Group { MockRow(Sample.VIDEO, "1080p", stringResource(Res.string.nearby_received, Sample.COMPUTER), poster, fetch.value, done = fetch.value >= 1f, highlighted = true) }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.align(Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                Icon(Res.drawable.ic_wifi, tint = c.ghost, size = 14.dp)
                Spacer(Modifier.width(6.dp))
                Txt(stringResource(Res.string.tour_send_note), style = Hamstr.type.small, color = c.ghost)
            }
        }
    }
}

/** The hamster wears the colour that is picked below it, and winks at every change. */
@Composable
private fun LookScene(modifier: Modifier) {
    val s by Prefs.settings.collectAsState()
    val reduce = LocalReduceMotion.current
    val mark = rememberMarkState()
    var first by remember { mutableStateOf(true) }
    LaunchedEffect(s.accent, s.base) {
        if (first) first = false else if (!reduce) mark.wink()
    }
    Fit(300.dp, 250.dp, modifier) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            HamstrMark(112.dp, state = mark)
            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HButton(stringResource(Res.string.share_stash), {}, kind = ButtonKind.Primary, icon = Res.drawable.ic_download)
                Box(Modifier.size(46.dp).clip(CircleShape).background(Hamstr.colors.accent), contentAlignment = Alignment.Center) {
                    Icon(Res.drawable.ic_pause, tint = Hamstr.colors.onAccent, size = 24.dp)
                }
            }
            Spacer(Modifier.height(18.dp))
            ProgressLine(0.62f, Modifier.width(220.dp))
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Frames and pieces

/**
 * Lays [content] out at a fixed size in dp and scales it to fit: a picture keeps its
 * proportions on any screen and is still drawn sharp, at the real resolution.
 */
@Composable
private fun Fit(width: Dp, height: Dp, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    BoxWithConstraints(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // No room at all (a page squeezed by its words): nothing to draw, and no zero density.
        if (maxWidth < 24.dp || maxHeight < 24.dp) return@BoxWithConstraints
        // Never above its own size: with more room (smaller text, a small UI size) the picture
        // would otherwise grow while everything around it shrinks.
        val scale = minOf(maxWidth / width, maxHeight / height, 1f)
        val outer = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(outer.density * scale, 1f)) {
            Box(Modifier.size(width, height), content = content)
        }
    }
}

/**
 * A phone showing Hamstr: the bezel one step above the page, the screen the canvas with its
 * raster. [fade] fades the bottom edge, where the screen would scroll on.
 */
@Composable
private fun PhoneFrame(modifier: Modifier, fade: Boolean = true, content: @Composable BoxScope.() -> Unit) =
    Fit(300.dp, 580.dp, modifier) {
        val c = Hamstr.colors
        Box(
            Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(40.dp))
                .background(c.overlay)
                .padding(7.dp)
                .clip(RoundedCornerShape(33.dp))
                .background(c.canvas),
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().height(30.dp).padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                    Txt("9:41", style = Hamstr.type.small.copy(fontWeight = FontWeight.SemiBold))
                    Spacer(Modifier.weight(1f))
                    Icon(Res.drawable.ic_wifi, size = 13.dp)
                    Spacer(Modifier.width(5.dp))
                    Box(Modifier.width(17.dp).height(9.dp).clip(RoundedCornerShape(2.5.dp)).background(c.ink))
                }
                Box(Modifier.weight(1f).fillMaxWidth(), content = content)
            }
            // The camera.
            Box(Modifier.align(Alignment.TopCenter).padding(top = 10.dp).size(11.dp).clip(CircleShape).background(c.black))
            if (fade) BottomFade(Modifier.align(Alignment.BottomCenter))
        }
    }

/** Hamstr's window on a computer: its title bar with the hamster in solid cells, then the page. */
@Composable
private fun WindowFrame(modifier: Modifier, content: @Composable BoxScope.() -> Unit) =
    Fit(560.dp, 420.dp, modifier) {
        val c = Hamstr.colors
        Box(
            Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(16.dp))
                .background(c.overlay)
                .padding(5.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(c.canvas),
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().height(30.dp).padding(start = 12.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    CellMark(Modifier.size(14.dp))
                    Spacer(Modifier.width(7.dp))
                    Txt("Hamstr", style = Hamstr.type.small, color = c.muted)
                    Spacer(Modifier.weight(1f))
                    WindowButtons()
                }
                Box(Modifier.weight(1f).fillMaxWidth(), content = content)
            }
            BottomFade(Modifier.align(Alignment.BottomCenter))
        }
    }

@Composable
private fun BottomFade(modifier: Modifier) {
    val canvas = Hamstr.colors.canvas
    Box(modifier.fillMaxWidth().height(44.dp).background(Brush.verticalGradient(listOf(Color.Transparent, canvas))))
}

/** The hamster as solid cells, like the desktop title bar draws it: glyphs blur that small. */
@Composable
private fun CellMark(modifier: Modifier) {
    val c = Hamstr.colors
    Canvas(modifier) {
        val cell = size.width / MARK[0].length
        val top = (size.height - cell * MARK.size) / 2
        MARK.forEachIndexed { r, line ->
            line.forEachIndexed { col, ch ->
                val color = when (ch) {
                    '#' -> c.ink
                    '*' -> c.accent
                    else -> return@forEachIndexed
                }
                drawRect(color, Offset(col * cell, top + r * cell), Size(cell + 0.5f, cell + 0.5f))
            }
        }
    }
}

/** Minimize, maximize and close, as the desktop's own 10 dp line glyphs. */
@Composable
private fun WindowButtons() {
    val c = Hamstr.colors
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        repeat(3) { i ->
            Canvas(Modifier.size(30.dp, 22.dp)) {
                val s = 9.dp.toPx()
                val o = Offset((size.width - s) / 2, (size.height - s) / 2)
                val stroke = 1.dp.toPx()
                when (i) {
                    0 -> drawLine(c.muted, Offset(o.x, o.y + s / 2), Offset(o.x + s, o.y + s / 2), stroke)
                    1 -> drawRect(c.muted, o, Size(s, s), style = Stroke(stroke))
                    else -> {
                        drawLine(c.muted, o, Offset(o.x + s, o.y + s), stroke, cap = StrokeCap.Round)
                        drawLine(c.muted, Offset(o.x + s, o.y), Offset(o.x, o.y + s), stroke, cap = StrokeCap.Round)
                    }
                }
            }
        }
    }
}

/** A card that rises into its place and fades away from it. */
@Composable
private fun CardSwap(visible: Boolean, content: @Composable () -> Unit) =
    AnimatedVisibility(
        visible,
        enter = slideInVertically(spring(dampingRatio = 0.78f, stiffness = 360f)) { it / 3 } + fadeIn(Specs.appear()),
        exit = fadeOut(Specs.disappear()),
    ) { content() }

/** A sheet inside a picture: it comes up like a real one and drops away. */
@Composable
private fun SceneSheet(visible: Boolean, modifier: Modifier, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible,
        modifier = modifier,
        enter = slideInVertically(spring(dampingRatio = 0.78f, stiffness = 360f)) { it } + fadeIn(Specs.appear()),
        exit = slideOutVertically(Specs.leave()) { it } + fadeOut(Specs.listOut()),
    ) { content() }
}

/** Android's own share sheet, a little different from Hamstr's: Hamstr is one of the apps in it. */
@Composable
private fun SystemShareSheet(poster: ImageBitmap, tapHamstr: Boolean) {
    val c = Hamstr.colors
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
            .background(c.raised)
            .padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 22.dp),
    ) {
        Handle()
        Spacer(Modifier.height(14.dp))
        Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp))) {
                Image(poster, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            Spacer(Modifier.width(10.dp))
            Column {
                Txt(Sample.VIDEO, style = Hamstr.type.small, maxLines = 1)
                Txt(Sample.LINK, style = Hamstr.type.mono, color = c.ghost, maxLines = 1)
            }
        }
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            ShareTarget(stringResource(Res.string.tour_demo_messages)) { Icon(Res.drawable.ic_send, size = 20.dp) }
            ShareTarget(stringResource(Res.string.tour_demo_copy)) { Icon(Res.drawable.ic_content_paste, size = 20.dp) }
            ShareTarget("Hamstr", tap = tapHamstr) { HamstrMark(48.dp, interactive = false) }
            ShareTarget(stringResource(Res.string.tour_demo_notes)) { Icon(Res.drawable.ic_description, size = 20.dp) }
        }
    }
}

@Composable
private fun ShareTarget(label: String, tap: Boolean = false, icon: @Composable () -> Unit) {
    val c = Hamstr.colors
    val press by animateFloatAsState(if (tap) 0.9f else 1f, Specs.press(), label = "targetPress")
    Column(Modifier.width(62.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.Center) {
            Box(
                Modifier.size(48.dp).graphicsLayer { scaleX = press; scaleY = press }.clip(CircleShape).background(c.surface),
                contentAlignment = Alignment.Center,
            ) { icon() }
            TapRing(tap, Modifier.size(48.dp))
        }
        Spacer(Modifier.height(6.dp))
        Txt(label, style = Hamstr.type.small, color = if (tap) c.ink else c.muted, maxLines = 1)
    }
}

/** Hamstr's sheet coming up after the share: the link's picture, video or sound, Stash it. */
@Composable
private fun HamstrSheetPeek(poster: ImageBitmap) {
    val c = Hamstr.colors
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
            .background(c.overlay)
            .padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 18.dp),
    ) {
        Handle()
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(64.dp, 36.dp).clip(RoundedCornerShape(8.dp))) {
                Image(poster, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                DecodeText(Sample.VIDEO, style = Hamstr.type.bodyMedium)
                Spacer(Modifier.height(3.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Badge("youtube")
                    Badge("12:34")
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        MockSegmented(listOf(stringResource(Res.string.mode_video), stringResource(Res.string.mode_audio), stringResource(Res.string.mode_custom)), 0)
        Spacer(Modifier.height(12.dp))
        HButton(stringResource(Res.string.share_stash_sized, "~431 MB"), {}, Modifier.fillMaxWidth(), kind = ButtonKind.Primary, icon = Res.drawable.ic_download)
    }
}

/** A button in some other app (likes, Share, Save). */
@Composable
private fun AppPill(icon: DrawableResource, label: String, tap: Boolean = false) {
    val c = Hamstr.colors
    val press by animateFloatAsState(if (tap) 0.92f else 1f, Specs.press(), label = "pillPress")
    Box(contentAlignment = Alignment.Center) {
        Row(
            Modifier.graphicsLayer { scaleX = press; scaleY = press }.clip(RoundedCornerShape(18.dp)).background(c.surface).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, size = 16.dp)
            Spacer(Modifier.width(6.dp))
            Txt(label, style = Hamstr.type.small, maxLines = 1)
        }
        TapRing(tap, Modifier.size(42.dp))
    }
}

/**
 * A fingertip landing on something: a soft dot that presses in and lifts, and an accent ring
 * spreading from it. With reduced motion it stays as a still ring.
 */
@Composable
private fun TapRing(on: Boolean, modifier: Modifier) {
    val c = Hamstr.colors
    val reduce = LocalReduceMotion.current
    val t = remember { Animatable(0f) }
    LaunchedEffect(on, reduce) {
        if (on && !reduce) {
            t.snapTo(0f)
            t.animateTo(1f, tween(680, easing = LinearOutSlowInEasing))
        } else t.snapTo(if (on) 0.35f else 0f)
    }
    if (!on) return
    Canvas(modifier) {
        val r = size.minDimension / 2
        val v = t.value
        val press = if (v < 0.3f) v / 0.3f else 1f - (v - 0.3f) / 0.7f * 0.7f
        drawCircle(c.ink.copy(alpha = 0.24f * press), radius = r * (0.92f - 0.12f * press))
        drawCircle(c.accent.copy(alpha = (1f - v).coerceIn(0f, 1f)), radius = r * (0.9f + 0.6f * v), style = Stroke(2.dp.toPx()))
    }
}

/** A key on the keyboard that goes down and lights up. */
@Composable
private fun Keycap(label: String, pressed: Boolean) {
    val c = Hamstr.colors
    val p = animateSelection(pressed)
    val dy by animateDpAsState(if (pressed) 2.dp else 0.dp, tween(90), label = "key")
    Box(
        Modifier
            .offset(y = dy)
            .heightIn(min = 42.dp)
            .widthIn(min = 46.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(lerp(c.surface, c.accent, p))
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Txt(label, style = Hamstr.type.mono.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold), color = lerp(c.ink, c.onAccent, p))
    }
}

/** The stash's header: the hamster, the name and the + button. */
@Composable
private fun StashHeader(mark: MarkState) {
    val c = Hamstr.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        HamstrMark(30.dp, state = mark, interactive = false)
        Spacer(Modifier.width(10.dp))
        Txt("Hamstr", style = Hamstr.type.title, modifier = Modifier.weight(1f))
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(12.dp)).background(c.surface), contentAlignment = Alignment.Center) {
            Icon(Res.drawable.ic_add, size = 20.dp)
        }
    }
}

/** A download as the stash lists it: the thumbnail resolving out of characters as it comes in. */
@Composable
private fun MockRow(title: String, badge: String, meta: String, poster: ImageBitmap, progress: Float, done: Boolean, highlighted: Boolean = false) {
    val c = Hamstr.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radii.row))
            .background(if (highlighted) c.surface else c.overlay)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(52.dp).clip(RoundedCornerShape(Radii.thumb))) {
            AsciiImage(poster, reveal = if (done) 1f else progress, cell = 5.dp, modifier = Modifier.size(52.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Txt(title, style = Hamstr.type.bodyMedium, maxLines = 1)
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Badge(badge)
                Txt(meta, style = Hamstr.type.small, color = c.ghost, maxLines = 1)
            }
            if (!done) {
                Spacer(Modifier.height(7.dp))
                ProgressLine(progress)
            }
        }
        Spacer(Modifier.width(8.dp))
        if (done) Icon(Res.drawable.ic_check, tint = c.green, size = 20.dp, modifier = Modifier.padding(7.dp))
        else Txt("${(progress * 100).toInt()}%", style = Hamstr.type.mono, modifier = Modifier.padding(end = 6.dp))
    }
}

/** The segmented control's look, moved by the picture instead of a finger. */
@Composable
private fun MockSegmented(options: List<String>, selected: Int) {
    val c = Hamstr.colors
    val at by animateFloatAsState(selected.toFloat(), Specs.arrive(), label = "segment")
    BoxWithConstraints(Modifier.fillMaxWidth().clip(RoundedCornerShape(Radii.control)).background(c.canvas).padding(4.dp)) {
        val w = maxWidth / options.size
        Box(Modifier.offset(x = w * at).width(w).height(36.dp).clip(RoundedCornerShape(10.dp)).background(c.hover))
        Row {
            options.forEachIndexed { i, o ->
                val near = (1f - kotlin.math.abs(at - i)).coerceIn(0f, 1f)
                Box(Modifier.width(w).height(36.dp), contentAlignment = Alignment.Center) {
                    Txt(o, style = Hamstr.type.bodyMedium, color = lerp(c.muted, c.ink, near), maxLines = 1)
                }
            }
        }
    }
}

/** A row in the share sheet: what it is, and what is picked, with the chevron that opens it. */
@Composable
private fun ValueRow(label: String, value: String) {
    val c = Hamstr.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        Txt(label, style = Hamstr.type.body, modifier = Modifier.weight(1f))
        Crossfade(value, animationSpec = Specs.blend(), label = "value") { Txt(it, style = Hamstr.type.small, color = c.muted, maxLines = 1) }
        Spacer(Modifier.width(4.dp))
        Icon(Res.drawable.ic_expand_more, tint = c.ghost, size = 16.dp)
    }
}

/** One of the player's modes; a lit one is tinted with the accent, like ToggleChip. */
@Composable
private fun ModeChip(icon: DrawableResource, label: String?, on: Boolean = false) {
    val c = Hamstr.colors
    Row(
        Modifier
            .height(34.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (on) c.accent.copy(alpha = 0.18f) else c.surface)
            .padding(horizontal = if (label == null) 9.dp else 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, tint = if (on) c.accent else c.muted, size = 16.dp)
        if (label != null) {
            Spacer(Modifier.width(6.dp))
            Txt(label, style = Hamstr.type.small, color = if (on) c.accent else c.ink, maxLines = 1)
        }
    }
}

/** A device in the Nearby picture; it lights up when a link lands on it. */
@Composable
private fun DeviceTile(icon: DrawableResource, name: String, kind: String, lit: Boolean, modifier: Modifier) {
    val c = Hamstr.colors
    val p = animateSelection(lit)
    val pop by animateFloatAsState(if (lit) 1.05f else 1f, Specs.pop(), label = "tile")
    Column(
        modifier
            .width(126.dp)
            .graphicsLayer { scaleX = pop; scaleY = pop }
            .clip(RoundedCornerShape(Radii.panel))
            .background(lerp(c.overlay, c.surface, p))
            .padding(14.dp),
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(lerp(c.surface, c.raised, p)), contentAlignment = Alignment.Center) {
            Icon(icon, tint = c.accent, size = 22.dp)
        }
        Spacer(Modifier.height(10.dp))
        Txt(name, style = Hamstr.type.bodyMedium, maxLines = 1)
        Txt(kind, style = Hamstr.type.small, color = c.ghost, maxLines = 1)
    }
}

/** A sheet's handle. */
@Composable
private fun Handle() {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(Modifier.width(36.dp).height(4.dp).clip(RoundedCornerShape(4.dp)).background(Hamstr.colors.line))
    }
}

/** How fast and how long, for a made-up download at [progress]. */
private fun speedLine(progress: Float, slow: Boolean = false): String {
    val left = ((1f - progress) * if (slow) 48f else 14f).roundToInt().toLong()
    return listOf(if (slow) "1.9 MB/s" else "6.4 MB/s", eta(left)).filter { it.isNotEmpty() }.joinToString(" · ")
}

/** Who, how big and how long ago, for a made-up finished download. */
@Composable
private fun doneLine(by: String, bytes: Long, hoursAgo: Int): String =
    listOf(by, formatBytes(bytes), ago(System.currentTimeMillis() - hoursAgo * 3_600_000L - 30_000L))
        .filter { it.isNotEmpty() }.joinToString(" · ")

/** 1:23 for a number of seconds. */
private fun clock(sec: Int) = "%d:%02d".format(sec / 60, sec % 60)

/**
 * A made-up video frame in Hamstr's look, like the demo's posters: a tinted sky, a glow and
 * a dark horizon of hills. Drawn once, small; thumbnails and covers scale it.
 */
@Composable
private fun rememberPoster(hue: Float, seed: Int, square: Boolean = false): ImageBitmap =
    remember(hue, seed, square) { poster(hue, seed, if (square) 200 else 320, if (square) 200 else 180) }

private fun poster(hue: Float, seed: Int, w: Int, h: Int): ImageBitmap {
    val image = ImageBitmap(w, h)
    val rnd = Random(seed)
    CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, androidx.compose.ui.graphics.Canvas(image), Size(w.toFloat(), h.toFloat())) {
        val deep = Color.hsv(hue, 0.6f, 0.26f)
        val bright = Color.hsv(hue, 0.7f, 0.95f)
        drawRect(Brush.linearGradient(listOf(deep, Color(0xFF0E0E0E)), Offset.Zero, Offset(w.toFloat(), h.toFloat())))
        val glow = Offset(w * (0.2f + 0.6f * rnd.nextFloat()), h * (0.22f + 0.25f * rnd.nextFloat()))
        val r = h * 0.9f
        drawCircle(Brush.radialGradient(listOf(bright.copy(alpha = 0.7f), Color.Transparent), glow, r), radius = r, center = glow)
        // A few faint stars or sparks.
        repeat(26) {
            drawCircle(Color.White.copy(alpha = 0.15f + 0.4f * rnd.nextFloat()), radius = 0.6f + rnd.nextFloat(), center = Offset(w * rnd.nextFloat(), h * 0.55f * rnd.nextFloat()))
        }
        val phase = rnd.nextFloat() * 6f
        val hills = Path().apply {
            moveTo(0f, h.toFloat())
            var x = 0f
            while (x <= w) {
                lineTo(x, h * 0.7f + sin(x * 0.021f + phase) * h * 0.08f + sin(x * 0.063f + phase * 2f) * h * 0.03f)
                x += 4f
            }
            lineTo(w.toFloat(), h.toFloat())
            close()
        }
        drawPath(hills, Color.hsv(hue, 0.45f, 0.09f))
    }
    return image
}
