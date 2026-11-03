package app.hamstr.desktop

import androidx.compose.ui.graphics.toArgb
import app.hamstr.Log
import app.hamstr.data.Prefs
import app.hamstr.ui.theme.paletteFor
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GraphicsDevice
import java.awt.GraphicsEnvironment
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import javax.imageio.ImageIO
import javax.swing.JPanel
import javax.swing.JWindow
import javax.swing.SwingUtilities
import javax.swing.Timer
import kotlin.math.PI
import kotlin.math.sin
import androidx.compose.ui.graphics.Color as ComposeColor

/**
 * A small window that shows the moment Hamstr starts, until the real one has drawn: loading
 * Compose and Skia takes a few seconds, and without it a click on the icon looks like nothing
 * happened. Plain Swing, because it has to be up before any of that loads. It takes the colours
 * from Settings › Appearance and needs no words, so no language either.
 */
object Splash {
    private const val W = 300
    private const val H = 220
    private var window: JWindow? = null
    private var timer: Timer? = null

    fun show() = SwingUtilities.invokeLater {
        runCatching {
            val s = Prefs.settings.value
            val palette = paletteFor(s.base, ComposeColor(s.accent))
            val canvas = Color(palette.canvas.toArgb())
            val line = Color(palette.line.toArgb())
            val ink = Color(palette.ink.toArgb())
            val accent = Color(palette.accent.toArgb())
            val track = Color(palette.surface.toArgb())
            val loader = Thread.currentThread().contextClassLoader
            val icon = loader.getResourceAsStream("hamstr_window.png")?.use(ImageIO::read)
            val font = runCatching {
                loader.getResourceAsStream("composeResources/app.hamstr.res/font/inter_600.ttf")!!.use { Font.createFont(Font.TRUETYPE_FONT, it) }
            }.getOrElse { Font(Font.SANS_SERIF, Font.BOLD, 1) }.deriveFont(22f)
            // Rounded corners where the system can draw a see-through window; square elsewhere.
            val round = GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice
                .isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT)
            val started = System.nanoTime()

            val panel = object : JPanel() {
                init { isOpaque = !round }
                override fun paintComponent(g: Graphics) {
                    val g2 = g.create() as Graphics2D
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                    g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                    g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
                    if (round) g2.composite = AlphaComposite.Src
                    val shape = RoundRectangle2D.Float(0.5f, 0.5f, W - 1f, H - 1f, 40f, 40f)
                    g2.color = canvas
                    if (round) g2.fill(shape) else g2.fillRect(0, 0, W, H)
                    g2.composite = AlphaComposite.SrcOver
                    g2.color = line
                    g2.stroke = BasicStroke(1f)
                    if (round) g2.draw(shape) else g2.drawRect(0, 0, W - 1, H - 1)

                    val size = 72
                    icon?.let { g2.drawImage(it, (W - size) / 2, 40, size, size, null) }
                    g2.font = font
                    g2.color = ink
                    val fm = g2.fontMetrics
                    g2.drawString("Hamstr", (W - fm.stringWidth("Hamstr")) / 2, 40 + size + 20 + fm.ascent)

                    // A short accent bar gliding along a track: Hamstr is busy, not stuck.
                    val trackW = 120
                    val tx = (W - trackW) / 2
                    val ty = H - 34
                    g2.color = track
                    g2.fillRoundRect(tx, ty, trackW, 4, 4, 4)
                    val t = (System.nanoTime() - started) / 1e9
                    val phase = (sin(t * PI * 1.2) + 1) / 2
                    val barW = 36
                    g2.color = accent
                    g2.clipRect(tx, ty, trackW, 4)
                    g2.fillRoundRect(tx + (phase * (trackW - barW)).toInt(), ty, barW, 4, 4, 4)
                    g2.dispose()
                }
            }

            window = JWindow().apply {
                if (round) background = Color(0, 0, 0, 0)
                contentPane = panel
                setSize(W, H)
                setLocationRelativeTo(null)
                isAlwaysOnTop = false
                isVisible = true
            }
            timer = Timer(16) { panel.repaint() }.apply { start() }
        }.onFailure { Log.w("HamstrSplash", "Couldn't show the start window", it) }
    }

    /** The real window is up: the small one goes. */
    fun close() = SwingUtilities.invokeLater {
        timer?.stop()
        timer = null
        window?.dispose()
        window = null
    }
}
