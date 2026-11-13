package app.hamstr.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.hamstr.data.PendingCancel
import app.hamstr.ui.components.LocalReduceMotion
import app.hamstr.ui.components.Specs
import app.hamstr.ui.components.Txt
import app.hamstr.ui.theme.Hamstr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.PluralStringResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString

/**
 * The desktop's answers, like the phone's snack: one short line and at most one action (Undo)
 * in accent, at the foot of the content column. A new one replaces the one showing. It stays
 * 2.6 s, or as long as an Undo is possible, and while the pointer rests on it.
 */
object Snacks {
    internal class Snack(val text: String, val action: String?, val onAction: (() -> Unit)?) {
        val id = System.nanoTime()

        // Undo is offered as long as a canceled download waits; everything else says its line and goes.
        val hold = if (action != null) PendingCancel.UNDO_MS else 2_600L
    }

    private val _current = MutableStateFlow<Snack?>(null)
    internal val current: StateFlow<Snack?> = _current.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun show(text: String, action: String? = null, onAction: (() -> Unit)? = null) {
        _current.value = Snack(text, action?.takeIf { onAction != null }, onAction)
    }

    /** [show] with the words from the strings, for callbacks and flows that aren't composing. */
    fun show(text: StringResource, vararg args: Any, action: StringResource? = null, onAction: (() -> Unit)? = null) {
        scope.launch { show(getString(text, *args), action?.let { getString(it) }, onAction) }
    }

    /** [show] with words that change with [count]. */
    fun show(text: PluralStringResource, count: Int, vararg args: Any) {
        scope.launch { show(getPluralString(text, count, *args)) }
    }

    internal fun dismiss(id: Long) = _current.update { if (it?.id == id) null else it }
}

/**
 * Where the snacks show: at the bottom centre of [BoxScope], 12 dp up; with a modal open ([top]),
 * at its top instead, clear of the modal's buttons. A screen reader hears each one as it comes.
 */
@Composable
internal fun BoxScope.SnackHost(top: Boolean) {
    val snack by Snacks.current.collectAsState()
    val reduce = LocalReduceMotion.current
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    LaunchedEffect(snack?.id, hovered) {
        val s = snack ?: return@LaunchedEffect
        if (hovered) return@LaunchedEffect
        delay(s.hold)
        Snacks.dismiss(s.id)
    }
    // Reduced motion: it only fades.
    fun enter(from: Int): EnterTransition = fadeIn(Specs.appear()) + if (reduce) EnterTransition.None else slideInVertically(Specs.enter()) { from * it / 2 }
    fun exit(to: Int): ExitTransition = fadeOut(Specs.disappear()) + if (reduce) ExitTransition.None else slideOutVertically(Specs.leave()) { to * it / 2 }
    AnimatedVisibility(
        snack != null && !top, Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
        enter = enter(1), exit = exit(1),
    ) { SnackPill(snack, source) }
    AnimatedVisibility(
        snack != null && top, Modifier.align(Alignment.TopCenter).padding(top = 12.dp),
        enter = enter(-1), exit = exit(-1),
    ) { SnackPill(snack, source) }
}

/** The pill: `raised`, 14 dp corners, `small` text and the action in accent. It keeps the last line while it leaves. */
@Composable
private fun SnackPill(snack: Snacks.Snack?, source: MutableInteractionSource) {
    val c = Hamstr.colors
    var shown by remember { mutableStateOf(snack) }
    snack?.let { shown = it }
    val s = shown ?: return
    Row(
        Modifier
            .semantics { liveRegion = LiveRegionMode.Polite }
            .widthIn(max = 520.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(c.raised)
            .hoverable(source)
            .padding(start = 16.dp, end = 6.dp, top = 4.dp, bottom = 4.dp)
            .heightIn(min = 40.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Txt(s.text, style = Hamstr.type.small, maxLines = 2, modifier = Modifier.weight(1f, fill = false).padding(end = 10.dp, top = 6.dp, bottom = 6.dp))
        if (s.action != null) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { s.onAction?.invoke(); Snacks.dismiss(s.id) }
                    .heightIn(min = 40.dp)
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) { Txt(s.action, style = Hamstr.type.button, color = c.accent, maxLines = 1) }
        } else Spacer(Modifier.width(10.dp))
    }
}
