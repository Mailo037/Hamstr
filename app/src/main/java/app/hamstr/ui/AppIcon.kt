package app.hamstr.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.IconCompat
import app.hamstr.MainActivity
import app.hamstr.R
import app.hamstr.data.ACCENTS
import app.hamstr.data.IconStyle
import app.hamstr.data.Prefs
import app.hamstr.ui.components.MARK
import kotlin.math.abs
import kotlin.math.min

/**
 * The hamster outside the app wears the accent too. The launcher icon can't be recoloured
 * at runtime, so there is one launcher entry (an activity-alias with its own icon) per
 * preset, and the nearest one to the accent is switched on. Shortcuts and the widgets'
 * hamster are drawn on the fly.
 */
object AppIcon {
    private val COLOURS = listOf("", "Pink", "Lemon", "Mint", "Sky", "Violet", "Coral")
    /** Alias class names in the manifest: per [IconStyle], one per preset in the order of [ACCENTS]. */
    private val ALIASES = listOf("", "Light", "OnAccent").flatMap { style -> COLOURS.map { "Launcher$style$it" } }

    /** The preset closest in hue to [accent]: a custom colour gets the nearest icon. */
    fun nearest(accent: Int): Int {
        ACCENTS.indexOfFirst { it.argb == accent }.takeIf { it >= 0 }?.let { return it }
        fun hue(c: Int) = FloatArray(3).also { android.graphics.Color.colorToHSV(c, it) }[0]
        val h = hue(accent)
        return ACCENTS.indices.minBy { i -> abs(hue(ACCENTS[i].argb) - h).let { d -> min(d, 360f - d) } }
    }

    /** Switches the launcher entry and redraws the shortcuts; cheap when nothing changed. */
    fun sync(context: Context) {
        val s = Prefs.settings.value
        val want = s.iconStyle.ordinal * COLOURS.size + if (s.iconFollowsAccent) nearest(s.accent) else 0
        val pm = context.packageManager
        val names = ALIASES.map { ComponentName(context.packageName, "${context.packageName}.$it") }
        // The first alias is on by default in the manifest, the others off.
        fun on(i: Int) = pm.getComponentEnabledSetting(names[i]).let {
            it == PackageManager.COMPONENT_ENABLED_STATE_ENABLED || (i == 0 && it == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)
        }
        fun set(i: Int, enabled: Boolean) = pm.setComponentEnabledSetting(
            names[i],
            if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
        runCatching {
            // The new entry goes on before the old one goes off, so there is always a launcher icon.
            if (!on(want)) set(want, true)
            names.indices.filter { it != want && on(it) }.forEach { set(it, false) }
        }
        shortcuts(context, s.accent)
    }

    private var shortcutAccent: Int? = null

    /** Long-press shortcuts with their glyph in the accent. */
    fun shortcuts(context: Context, accent: Int) {
        if (shortcutAccent == accent) return
        shortcutAccent = accent
        fun shortcut(id: String, action: String, short: Int, long: Int, icon: Int, rank: Int) =
            ShortcutInfoCompat.Builder(context, id)
                .setShortLabel(context.getString(short))
                .setLongLabel(context.getString(long))
                .setIcon(IconCompat.createWithAdaptiveBitmap(glyph(context, icon, accent)))
                .setIntent(Intent(action).setClassName(context, MainActivity::class.java.name))
                .setRank(rank)
                .build()
        runCatching {
            ShortcutManagerCompat.setDynamicShortcuts(
                context,
                listOf(
                    shortcut("paste", MainActivity.ACTION_PASTE, R.string.sc_paste_short, R.string.sc_paste_long, R.drawable.ic_content_paste, 0),
                    shortcut("add", MainActivity.ACTION_ADD, R.string.sc_add_short, R.string.sc_add_long, R.drawable.ic_add, 1),
                    shortcut("library", MainActivity.ACTION_LIBRARY, R.string.sc_library_short, R.string.sc_library_long, R.drawable.ic_grid_view, 2),
                ),
            )
        }
    }

    /** An adaptive-icon bitmap: the glyph in the accent on the shortcut background, in the safe zone. */
    private fun glyph(context: Context, icon: Int, accent: Int): Bitmap {
        val size = 432 // 108 dp at 4x
        val bmp = createBitmap(size, size)
        val canvas = Canvas(bmp)
        canvas.drawColor(ContextCompat.getColor(context, R.color.shortcut_background))
        val d = ContextCompat.getDrawable(context, icon)!!.mutate()
        val g = (size * 44f / 108f).toInt()
        val o = (size - g) / 2
        d.setBounds(o, o, o + g, o + g)
        d.colorFilter = PorterDuffColorFilter(accent, PorterDuff.Mode.SRC_IN)
        d.draw(canvas)
        return bmp
    }

    /** The hamster mark as a bitmap with cheeks in [accent], for the widgets (same grid as the app's mark). */
    fun mark(context: Context, sizePx: Int, accent: Int, ink: Int = 0xFFEEEEEE.toInt()): Bitmap {
        val bmp = createBitmap(sizePx, sizePx)
        val canvas = Canvas(bmp)
        val cols = MARK[0].length
        val cell = sizePx * 0.92f / cols
        val ox = (sizePx - cell * cols) / 2f
        val oy = (sizePx - cell * MARK.size * 1.1f) / 2f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = ResourcesCompat.getFont(context, R.font.jetbrains_mono_600)
            textSize = cell * 1.15f
            textAlign = Paint.Align.CENTER
        }
        val baseline = -(paint.ascent() + paint.descent()) / 2f
        MARK.forEachIndexed { r, line ->
            line.forEachIndexed { col, ch ->
                if (ch == '.') return@forEachIndexed
                paint.color = if (ch == '*') accent else ink
                canvas.drawText(ch.toString(), ox + col * cell + cell / 2f, oy + r * cell * 1.1f + cell * 0.55f + baseline, paint)
            }
        }
        return bmp
    }
}
