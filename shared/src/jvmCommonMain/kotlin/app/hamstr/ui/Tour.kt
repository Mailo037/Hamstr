package app.hamstr.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import app.hamstr.ui.components.edgeFade
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.hamstr.Platform
import app.hamstr.data.DownloadStore
import app.hamstr.data.Prefs
import app.hamstr.res.*
import app.hamstr.ui.components.Badge
import app.hamstr.ui.components.ButtonKind
import app.hamstr.ui.components.DecodeText
import app.hamstr.ui.components.Group
import app.hamstr.ui.components.HButton
import app.hamstr.ui.components.LocalReduceMotion
import app.hamstr.ui.components.RasterBackground
import app.hamstr.ui.components.Specs
import app.hamstr.ui.components.Txt
import app.hamstr.ui.theme.Hamstr
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs

/**
 * The tour's pages, in order. Each names its badge, title and text; [desktopText] (and
 * [desktopTitle]) replace them on the computer, and [phoneOnly] pages don't show there.
 */
internal enum class TourStep(
    val badge: StringResource,
    val title: StringResource,
    val text: StringResource,
    val desktopTitle: StringResource? = null,
    val desktopText: StringResource? = null,
    val phoneOnly: Boolean = false,
) {
    Hello(Res.string.tour_hello_badge, Res.string.tour_hello_title, Res.string.tour_hello_text, desktopText = Res.string.tour_hello_text_desktop),
    Share(Res.string.tour_step_1, Res.string.tour_share_title, Res.string.tour_share_text, Res.string.tour_share_title_desktop, Res.string.tour_share_text_desktop),
    Choose(Res.string.tour_step_2, Res.string.tour_choose_title, Res.string.tour_choose_text, desktopText = Res.string.tour_choose_text_desktop),
    Arrive(Res.string.tour_step_3, Res.string.tour_arrive_title, Res.string.tour_arrive_text, desktopText = Res.string.tour_arrive_text_desktop),
    Play(Res.string.tour_step_4, Res.string.tour_play_title, Res.string.tour_play_text, phoneOnly = true),
    Send(Res.string.tour_send_badge, Res.string.tour_send_title, Res.string.tour_send_text, desktopText = Res.string.tour_send_text_desktop),
    Look(Res.string.tour_look_badge, Res.string.tour_look_title, Res.string.tour_look_text);

    companion object {
        fun on(desktop: Boolean) = entries.filter { !(desktop && it.phoneOnly) }
    }
}

/** When the tour shows by itself: once, on a first start. */
object TourGate {
    /**
     * True on a first start. Someone who already has downloads (an update from a version
     * without the tour) knows the way around, so for them it counts as seen.
     */
    fun due(): Boolean {
        if (Prefs.settings.value.toured) return false
        if (DownloadStore.tasks.value.isNotEmpty()) {
            done()
            return false
        }
        return true
    }

    /** Seen or skipped: it doesn't come back by itself; Settings › About plays it again. */
    fun done() {
        if (!Prefs.settings.value.toured) Prefs.edit { it.copy(toured = true) }
    }
}

/** The modifier key for pasting on this computer: ⌘ on a Mac, Ctrl elsewhere. */
internal val pasteKeys = if (System.getProperty("os.name").orEmpty().startsWith("Mac")) "⌘V" else "Ctrl+V"

/**
 * What Hamstr is and how it works, in a few pages: each shows the part of the app it talks
 * about, drawn from the app's own pieces and moving the way the app does, over a sentence or
 * two. Swipe or use the buttons (arrow keys, Enter and Esc on a computer); [onDone] runs after
 * the last page or on Skip. [desktop] picks the computer's pages and words.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun Tour(onDone: () -> Unit, modifier: Modifier = Modifier, desktop: Boolean = Platform.desktop, startAt: Int = 0) {
    val c = Hamstr.colors
    val steps = remember(desktop) { TourStep.on(desktop) }
    val pager = rememberPagerState(startAt.coerceIn(0, steps.lastIndex)) { steps.size }
    val scope = rememberCoroutineScope()
    val reduce = LocalReduceMotion.current
    val haptics = LocalHapticFeedback.current
    val finish by rememberUpdatedState(onDone)
    val page = pager.currentPage
    val last = page == steps.lastIndex

    fun go(to: Int) {
        if (to !in steps.indices || to == pager.targetPage) return
        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
        scope.launch { if (reduce) pager.scrollToPage(to) else pager.animateScrollToPage(to, animationSpec = Specs.enter()) }
    }
    val next = { if (pager.targetPage < steps.lastIndex) go(pager.targetPage + 1) else finish() }
    val back = { go(pager.targetPage - 1) }
    BackHandler(enabled = page > 0) { back() }

    // Arrow keys, Enter and Esc work as soon as the tour is up.
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .background(c.canvas)
            // Taps on the tour never reach the app underneath.
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
            .focusRequester(focus)
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (e.key) {
                    Key.DirectionRight, Key.Enter, Key.NumPadEnter -> { next(); true }
                    Key.DirectionLeft -> { back(); true }
                    Key.Escape -> { finish(); true }
                    else -> false
                }
            }
            .focusable(),
    ) {
        // Sideways (a computer, a phone held sideways) the picture sits beside the words.
        val wide = maxWidth > maxHeight * 1.15f && maxWidth >= 560.dp
        RasterBackground(Modifier.fillMaxWidth().height(maxHeight * 0.5f))
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 24.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Txt(
                    "%02d / %02d".format(page + 1, steps.size),
                    style = Hamstr.type.mono,
                    color = c.ghost,
                    // The dots below say where the reader is.
                    modifier = Modifier.weight(1f).clearAndSetSemantics { },
                )
                AnimatedVisibility(!last, enter = fadeIn(Specs.appear()), exit = fadeOut(Specs.disappear())) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(onClick = finish)
                            .heightIn(min = 48.dp)
                            .padding(horizontal = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) { Txt(stringResource(Res.string.tour_skip), style = Hamstr.type.button, color = c.muted) }
                }
            }
            HorizontalPager(
                pager,
                Modifier.weight(1f).fillMaxWidth(),
                key = { steps[it] },
            ) { i ->
                TourPage(steps[i], wide = wide, active = pager.settledPage == i && !pager.isScrollInProgress, desktop = desktop)
            }
            Column(
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 16.dp),
            ) {
                Dots(pager, steps.size, Modifier.align(Alignment.CenterHorizontally))
                Spacer(Modifier.height(18.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Back slides in from the second page; the first one has only the way forward.
                    AnimatedVisibility(
                        page > 0,
                        enter = expandHorizontally(Specs.enter()) + fadeIn(Specs.appear()),
                        exit = shrinkHorizontally(Specs.leave()) + fadeOut(Specs.disappear()),
                    ) {
                        Row {
                            HButton(stringResource(Res.string.tour_back), back, Modifier.width(112.dp))
                            Spacer(Modifier.width(8.dp))
                        }
                    }
                    HButton(
                        stringResource(if (last) Res.string.tour_start else Res.string.tour_next),
                        next,
                        Modifier.weight(1f),
                        kind = ButtonKind.Primary,
                        icon = if (last) Res.drawable.ic_download else Res.drawable.ic_chevron_right,
                    )
                }
            }
        }
    }
}

/**
 * The tour over the app while [visible]: it fades in, and on its way out it fades and grows a
 * little, as if the app came forward through it.
 */
@Composable
fun TourOverlay(visible: Boolean, onDone: () -> Unit, startAt: Int = 0) {
    AnimatedVisibility(
        visible,
        enter = fadeIn(Specs.appear()),
        exit = fadeOut(Specs.leave(260)) + scaleOut(Specs.leave(260), targetScale = 1.03f),
    ) { Tour(onDone, startAt = startAt) }
}

/** One page: the picture, then (or beside it) the badge, the title and a sentence or two. */
@Composable
private fun TourPage(step: TourStep, wide: Boolean, active: Boolean, desktop: Boolean) {
    // On a short screen the picture gives way first; the words scroll only once it can't.
    val scroll = rememberScrollState()
    val words = Modifier.edgeFade(scroll, Orientation.Vertical).verticalScroll(scroll)
    if (wide) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 32.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(40.dp),
        ) {
            Box(Modifier.weight(1.1f).fillMaxHeight().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                TourScene(step, active, desktop)
            }
            Column(Modifier.weight(1f).widthIn(max = 460.dp).then(words).padding(vertical = 16.dp)) { Words(step, desktop) }
        }
    } else {
        BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
            val room = maxHeight
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                    TourScene(step, active, desktop)
                }
                Spacer(Modifier.height(12.dp))
                Column(Modifier.heightIn(max = (room - 140.dp).coerceAtLeast(120.dp)).then(words)) { Words(step, desktop) }
            }
        }
    }
}

/** Takes what a sideways scroll inside leaves over, so the pager around never sees it. */
private val KeepSideways = object : NestedScrollConnection {
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource) = Offset(available.x, 0f)
    override suspend fun onPostFling(consumed: Velocity, available: Velocity) = Velocity(available.x, 0f)
}

@Composable
private fun Words(step: TourStep, desktop: Boolean) {
    val c = Hamstr.colors
    val title = stringResource(if (desktop) step.desktopTitle ?: step.title else step.title)
    val text = if (desktop && step.desktopText != null) {
        if (step == TourStep.Share) stringResource(step.desktopText, pasteKeys) else stringResource(step.desktopText)
    } else stringResource(step.text)
    Column(Modifier.fillMaxWidth().padding(horizontal = 2.dp)) {
        Badge(stringResource(step.badge), accent = true)
        Spacer(Modifier.height(10.dp))
        DecodeText(title, style = Hamstr.type.title, maxLines = 2, durationMs = 420)
        Spacer(Modifier.height(8.dp))
        Txt(text, style = Hamstr.type.body.copy(lineHeight = Hamstr.type.body.lineHeight * 1.08f), color = c.muted)
        if (step == TourStep.Look) {
            Spacer(Modifier.height(16.dp))
            // The real settings: the tour recolours as you pick. A row of chips scrolled to its end
            // keeps the rest of the swipe, so it doesn't turn the page as well.
            Box(Modifier.nestedScroll(KeepSideways)) { Group { LookBlocks() } }
        }
    }
}

/**
 * Where the reader is: one dot per page, the current one stretched into an accent pill. It
 * follows the pager while it is dragged.
 */
@Composable
private fun Dots(pager: PagerState, count: Int, modifier: Modifier = Modifier) {
    val c = Hamstr.colors
    val label = stringResource(Res.string.tour_progress, pager.currentPage + 1, count)
    Row(modifier.semantics { contentDescription = label }, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        val at = pager.currentPage + pager.currentPageOffsetFraction
        repeat(count) { i ->
            val near = (1f - abs(at - i)).coerceIn(0f, 1f)
            Box(
                Modifier
                    .width(6.dp + 18.dp * near)
                    .height(6.dp)
                    .clip(CircleShape)
                    .background(lerp(c.line, c.accent, near)),
            )
        }
    }
}
