package app.hamstr.desktop

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPosition
import app.hamstr.data.PropertiesFile
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.io.File
import javax.swing.Timer
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Where the window was: a new start opens it there, at that size, and fills the screen again if
 * it did. Only where a screen still shows at least half of it; a monitor may be gone since.
 */
internal object WindowPlace {
    private val file by lazy { PropertiesFile(File(Paths.data, "window.properties")) }

    class Place(val position: WindowPosition, val size: DpSize, val maximized: Boolean)

    fun load(): Place {
        val f = file
        val w = f.getInt("w", 0)
        val h = f.getInt("h", 0)
        val saved = Rectangle(f.getInt("x", 0), f.getInt("y", 0), w, h)
        if (w > 0 && h > 0 && onScreen(saved)) {
            return Place(WindowPosition(saved.x.dp, saved.y.dp), DpSize(w.dp, h.dp), f.getBoolean("maximized", false))
        }
        // The first start: the usual size, smaller on a small screen.
        val work = runCatching { GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds }.getOrNull()
        val size = if (work == null) DpSize(1120.dp, 760.dp)
            else DpSize(min(1120, (work.width * 0.85).roundToInt()).dp, min(760, (work.height * 0.85).roundToInt()).dp)
        return Place(WindowPosition(androidx.compose.ui.Alignment.Center), size, false)
    }

    /** Saves half a second after the window last moved or changed size, and once more at the end. */
    fun watch(frame: WindowFrame) {
        val later = Timer(500) { save(frame) }.apply { isRepeats = false }
        frame.window.addComponentListener(object : ComponentAdapter() {
            override fun componentMoved(e: ComponentEvent) = later.restart()
            override fun componentResized(e: ComponentEvent) = later.restart()
        })
    }

    fun save(frame: WindowFrame) {
        if (!frame.window.isDisplayable) return
        val (r, maximized) = frame.place()
        if (r.width <= 0 || r.height <= 0) return
        file.edit().putInt("x", r.x).putInt("y", r.y).putInt("w", r.width).putInt("h", r.height).putBoolean("maximized", maximized).apply()
    }

    private fun onScreen(r: Rectangle): Boolean {
        val screens = runCatching { GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map { it.defaultConfiguration.bounds } }.getOrDefault(emptyList())
        val seen = screens.sumOf { s -> s.intersection(r).let { if (it.isEmpty) 0L else it.width.toLong() * it.height } }
        return seen * 2 >= r.width.toLong() * r.height
    }
}
