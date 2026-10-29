package app.hamstr.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import app.hamstr.ui.components.MARK
import app.hamstr.ui.components.Txt
import app.hamstr.ui.components.animateSelection
import app.hamstr.Log
import app.hamstr.ui.theme.Hamstr
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import java.awt.Cursor
import java.awt.Frame
import java.awt.MouseInfo
import java.awt.Point
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent

/**
 * The window without the system's frame: Hamstr draws its own title bar on the canvas, the way
 * the app is flat everywhere else. Maximising fills the screen without covering the taskbar.
 * On macOS the system's traffic lights stay and the bar only leaves room for them.
 */
class WindowFrame(val window: ComposeWindow) {
    var maximized by mutableStateOf(false)
        private set
    /**
     * Where the pointer is over the window, in dp from its top left; null when it is elsewhere.
     * Read straight from AWT: Compose's own hover loses track when the window jumps (minimised,
     * maximised, restored) under a resting pointer and leaves a button lit.
     */
    var pointer by mutableStateOf<DpOffset?>(null)
        private set
    private var normal: Rectangle? = null

    init {
        val track = object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) = moved(e)
            override fun mouseMoved(e: MouseEvent) = moved(e)
            override fun mouseDragged(e: MouseEvent) = moved(e)
            override fun mouseExited(e: MouseEvent) { pointer = null }
            private fun moved(e: MouseEvent) { pointer = DpOffset(e.x.dp, e.y.dp) }
        }
        window.addMouseListener(track)
        window.addMouseMotionListener(track)
        // A jump moves the window, not the pointer; the next real move says where it is.
        window.addWindowStateListener { pointer = null }
    }

    /** Rounded like every Windows 11 window; square while it fills the screen. */
    fun round(on: Boolean = !maximized) {
        if (!Paths.windows) return
        runCatching {
            val hwnd = Pointer(window.windowHandle)
            Dwm.lib.DwmSetWindowAttribute(hwnd, DWMWA_WINDOW_CORNER_PREFERENCE, IntByReference(if (on) DWMWCP_ROUND else DWMWCP_DONOTROUND), 4)
            // No light system border around the dark window.
            Dwm.lib.DwmSetWindowAttribute(hwnd, DWMWA_BORDER_COLOR, IntByReference(DWMWA_COLOR_NONE), 4)
        }.onFailure { Log.d("HamstrWindow", "No rounded corners", it) }
    }

    fun toggleMaximized() = if (maximized) restore() else maximize()

    fun maximize() {
        normal = window.bounds
        val gc = window.graphicsConfiguration
        val screen = gc.bounds
        val insets = Toolkit.getDefaultToolkit().getScreenInsets(gc)
        window.bounds = Rectangle(screen.x + insets.left, screen.y + insets.top, screen.width - insets.left - insets.right, screen.height - insets.top - insets.bottom)
        maximized = true
        pointer = null
        round(false)
    }

    /** Back to the size it had; dragged out of maximised, it comes along under the pointer. */
    fun restore(under: Point? = null) {
        normal?.let { n -> window.bounds = if (under == null) n else Rectangle(under.x - n.width / 2, under.y - 18, n.width, n.height) }
        maximized = false
        pointer = null
        round(true)
    }

    fun minimize() {
        window.extendedState = window.extendedState or Frame.ICONIFIED
    }

    private interface Dwm : Library {
        fun DwmSetWindowAttribute(hwnd: Pointer, attribute: Int, value: IntByReference, size: Int): Int

        companion object {
            val lib: Dwm by lazy { Native.load("dwmapi", Dwm::class.java) }
        }
    }

    private companion object {
        const val DWMWA_WINDOW_CORNER_PREFERENCE = 33
        const val DWMWA_BORDER_COLOR = 34
        const val DWMWCP_DONOTROUND = 1
        const val DWMWCP_ROUND = 2
        const val DWMWA_COLOR_NONE = 0xFFFFFFFE.toInt()
    }
}

/** How big the title bar and its buttons are: small rounded keys in the bar, not full-height slabs. */
private val BAR = 34.dp
private val BUTTON = DpSize(30.dp, 24.dp)
private val GLYPH = 8.dp

/** The title bar: the hamster and the name on the left, the window buttons on the right. */
@Composable
fun TitleBar(frame: WindowFrame, onClose: () -> Unit) {
    val c = Hamstr.colors
    Row(Modifier.fillMaxWidth().height(BAR), verticalAlignment = Alignment.CenterVertically) {
        // Only the bar moves the window: a click on a button that slips a pixel stays a click.
        Row(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .pointerInput(frame) { detectTapGestures(onDoubleTap = { if (!Paths.mac) frame.toggleMaximized() }) }
                .pointerInput(frame) {
                    var start = Point()
                    var origin = Point()
                    detectDragGestures(
                        onDragStart = {
                            if (frame.maximized) frame.restore(MouseInfo.getPointerInfo().location)
                            start = MouseInfo.getPointerInfo().location
                            origin = frame.window.location
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            val p = MouseInfo.getPointerInfo().location
                            frame.window.setLocation(origin.x + p.x - start.x, origin.y + p.y - start.y)
                        },
                    )
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // macOS keeps its own buttons on the left.
            Spacer(Modifier.width(if (Paths.mac) 78.dp else 14.dp))
            CellMark(14.dp)
            Spacer(Modifier.width(8.dp))
            Txt("Hamstr", style = Hamstr.type.small, color = c.muted)
        }
        if (!Paths.mac) {
            WindowButton("Minimize", frame, onClick = frame::minimize) {
                val s = GLYPH.toPx() / 2
                drawLine(it, Offset(size.width / 2 - s, size.height / 2), Offset(size.width / 2 + s, size.height / 2), 1.dp.toPx())
            }
            WindowButton(if (frame.maximized) "Restore" else "Maximize", frame, onClick = frame::toggleMaximized) {
                val s = GLYPH.toPx()
                val stroke = Stroke(1.dp.toPx())
                val o = Offset((size.width - s) / 2, (size.height - s) / 2)
                if (frame.maximized) {
                    // Two windows: the front one whole, of the back one only what shows above and right of it.
                    val d = 2.dp.toPx()
                    val w = 1.dp.toPx()
                    drawRect(it, o + Offset(0f, d), Size(s - d, s - d), style = stroke)
                    drawLine(it, o + Offset(d, 0f), o + Offset(s, 0f), w)
                    drawLine(it, o + Offset(s, 0f), o + Offset(s, s - d), w)
                } else {
                    drawRect(it, o, Size(s, s), style = stroke)
                }
            }
            WindowButton("Close", frame, danger = true, onClick = onClose) {
                val s = GLYPH.toPx() / 2
                val m = Offset(size.width / 2, size.height / 2)
                drawLine(it, m + Offset(-s, -s), m + Offset(s, s), 1.dp.toPx())
                drawLine(it, m + Offset(-s, s), m + Offset(s, -s), 1.dp.toPx())
            }
            Spacer(Modifier.width(6.dp))
        }
    }
}

/** The hamster as solid cells, like the window icon: glyphs would blur at this size. */
@Composable
private fun CellMark(size: Dp) {
    val c = Hamstr.colors
    Canvas(Modifier.size(size)) {
        val cols = MARK[0].length
        val cell = this.size.width / cols
        val top = (this.size.height - cell * MARK.size) / 2
        MARK.forEachIndexed { r, line ->
            line.forEachIndexed { col, ch ->
                if (ch == '#' || ch == '*') drawRect(if (ch == '*') c.accent else c.ink, Offset(col * cell, top + r * cell), Size(cell + 0.5f, cell + 0.5f))
            }
        }
    }
}

/** A window button: hovering lifts it a step, the close button turns red. */
@Composable
private fun WindowButton(label: String, frame: WindowFrame, danger: Boolean = false, onClick: () -> Unit, glyph: androidx.compose.ui.graphics.drawscope.DrawScope.(Color) -> Unit) {
    val c = Hamstr.colors
    val density = LocalDensity.current
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val hovered = frame.pointer?.let { p -> with(density) { bounds.contains(Offset(p.x.toPx(), p.y.toPx())) } } == true
    val p = animateSelection(hovered)
    val bg = if (danger) Color(0xFFC42B1C) else c.hover
    Box(
        Modifier
            .padding(horizontal = 1.dp)
            .size(BUTTON)
            .onGloballyPositioned { bounds = it.boundsInWindow() }
            .clip(RoundedCornerShape(8.dp))
            .background(bg.copy(alpha = bg.alpha * p))
            .clickable(remember { MutableInteractionSource() }, null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
    ) {
        Canvas(Modifier.size(BUTTON)) { glyph(if (danger && hovered) Color.White else c.muted) }
    }
}

/** Edges and corners that resize the frameless window, 6 dp wide, with the system's resize cursors. */
@Composable
fun BoxScope.ResizeEdges(frame: WindowFrame) {
    if (Paths.mac || frame.maximized) return
    val e = 6.dp
    Edge(frame, Modifier.align(Alignment.CenterStart).width(e).fillMaxHeight(), Cursor.W_RESIZE_CURSOR, -1, 0)
    Edge(frame, Modifier.align(Alignment.CenterEnd).width(e).fillMaxHeight(), Cursor.E_RESIZE_CURSOR, 1, 0)
    Edge(frame, Modifier.align(Alignment.TopCenter).height(e).fillMaxWidth(), Cursor.N_RESIZE_CURSOR, 0, -1)
    Edge(frame, Modifier.align(Alignment.BottomCenter).height(e).fillMaxWidth(), Cursor.S_RESIZE_CURSOR, 0, 1)
    Edge(frame, Modifier.align(Alignment.TopStart).size(e * 2), Cursor.NW_RESIZE_CURSOR, -1, -1)
    Edge(frame, Modifier.align(Alignment.TopEnd).size(e * 2), Cursor.NE_RESIZE_CURSOR, 1, -1)
    Edge(frame, Modifier.align(Alignment.BottomStart).size(e * 2), Cursor.SW_RESIZE_CURSOR, -1, 1)
    Edge(frame, Modifier.align(Alignment.BottomEnd).size(e * 2), Cursor.SE_RESIZE_CURSOR, 1, 1)
}

@Composable
private fun Edge(frame: WindowFrame, modifier: Modifier, cursor: Int, dx: Int, dy: Int) {
    Box(
        modifier
            .pointerHoverIcon(PointerIcon(Cursor(cursor)))
            .pointerInput(frame) {
                var start = Point()
                var from = Rectangle()
                detectDragGestures(
                    onDragStart = { start = MouseInfo.getPointerInfo().location; from = frame.window.bounds },
                    onDrag = { change, _ ->
                        change.consume()
                        val p = MouseInfo.getPointerInfo().location
                        val min = frame.window.minimumSize
                        var x = from.x
                        var y = from.y
                        var w = from.width + dx * (p.x - start.x)
                        var h = from.height + dy * (p.y - start.y)
                        w = w.coerceAtLeast(min.width)
                        h = h.coerceAtLeast(min.height)
                        // Pulling the left or top edge moves the window as it grows.
                        if (dx < 0) x = from.x + from.width - w
                        if (dy < 0) y = from.y + from.height - h
                        frame.window.setBounds(x, y, w, h)
                    },
                )
            },
    )
}
