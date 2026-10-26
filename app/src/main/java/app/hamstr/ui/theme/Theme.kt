package app.hamstr.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import app.hamstr.R
import app.hamstr.data.Base
import app.hamstr.data.Prefs
import app.hamstr.ui.components.LocalReduceMotion
import app.hamstr.ui.components.systemReducesMotion

/**
 * Raster: Slate's neutral dark surfaces with one accent (cheek orange).
 * ASCII is an accent only (background raster, [ BADGES ], thumbnails, decoding text).
 */
@Immutable
data class Palette(
    val canvas: Color = Color(0xFF141414),
    val overlay: Color = Color(0xFF1E1E1E),
    val surface: Color = Color(0xFF262626),
    val raised: Color = Color(0xFF2D2D2D),
    val hover: Color = Color(0xFF343434),
    val ink: Color = Color(0xFFEEEEEE),
    val muted: Color = Color(0xFFAAAAAA),
    val ghost: Color = Color(0xFF8A8A8A),
    val line: Color = Color(0xFF363636),
    val accent: Color = Color(0xFFFF9A3C),
    val onAccent: Color = Color(0xFF1A0F04),
    val green: Color = Color(0xFF10B981),
    val red: Color = Color(0xFFF08080),
    val black: Color = Color(0xFF0A0A09),
)

val Inter = FontFamily(
    Font(R.font.inter_400, FontWeight.Normal),
    Font(R.font.inter_500, FontWeight.Medium),
    Font(R.font.inter_600, FontWeight.SemiBold),
)

val Mono = FontFamily(
    Font(R.font.jetbrains_mono_400, FontWeight.Normal),
    Font(R.font.jetbrains_mono_600, FontWeight.SemiBold),
)

@Immutable
data class Type(
    val body: TextStyle = TextStyle(fontFamily = Inter, fontSize = 14.sp, lineHeight = 19.sp),
    val bodyMedium: TextStyle = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 19.sp),
    val small: TextStyle = TextStyle(fontFamily = Inter, fontSize = 12.sp, lineHeight = 16.sp),
    val section: TextStyle = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp),
    val title: TextStyle = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 21.sp, lineHeight = 26.sp),
    val hero: TextStyle = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 36.sp, lineHeight = 40.sp, letterSpacing = (-0.5).sp),
    val badge: TextStyle = TextStyle(fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 10.5.sp, lineHeight = 14.sp, letterSpacing = 0.3.sp),
    val mono: TextStyle = TextStyle(fontFamily = Mono, fontSize = 12.sp, lineHeight = 16.sp),
    val button: TextStyle = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp),
)

/** Slate radii: rows 10/16, controls 12/14, panels 20, sheets 28. */
object Radii {
    val row = 16.dp
    val control = 14.dp
    val thumb = 10.dp
    val panel = 20.dp
    val sheet = 28.dp
}

/** Slate's travel easing (190 ms pill, 220 ms surfaces). */
val TravelEasing = CubicBezierEasing(0.22f, 0.61f, 0.36f, 1f)

val LocalPalette = staticCompositionLocalOf { Palette() }
val LocalType = staticCompositionLocalOf { Type() }

object Hamstr {
    val colors: Palette @Composable get() = LocalPalette.current
    val type: Type @Composable get() = LocalType.current
}

/** Which decorative effects are on (Settings > Motion & effects, and the system's reduce-motion). */
@Immutable
data class Fx(
    val intro: Boolean = true,
    val raster: Boolean = true,
    val rain: Boolean = true,
    val decode: Boolean = true,
    val asciiThumbs: Boolean = true,
    val moods: Boolean = true,
    val stretch: Boolean = true,
)

val LocalFx = staticCompositionLocalOf { Fx() }

fun paletteFor(base: Base, accent: Color): Palette {
    val onAccent = if (accent.luminance() > 0.45f) Color(0xFF14100A) else Color.White
    return when (base) {
        Base.Graphite -> Palette(accent = accent, onAccent = onAccent)
        Base.Oled -> Palette(
            canvas = Color(0xFF000000), overlay = Color(0xFF121212), surface = Color(0xFF1B1B1B),
            raised = Color(0xFF232323), hover = Color(0xFF2B2B2B), line = Color(0xFF2A2A2A),
            black = Color(0xFF000000), accent = accent, onAccent = onAccent,
        )
        Base.Cocoa -> Palette(
            canvas = Color(0xFF15120F), overlay = Color(0xFF1E1A16), surface = Color(0xFF27221D),
            raised = Color(0xFF2E2822), hover = Color(0xFF372F28), line = Color(0xFF3A322A),
            ink = Color(0xFFF1EBE2), muted = Color(0xFFADA396), ghost = Color(0xFF8E8476),
            black = Color(0xFF0D0B09), accent = accent, onAccent = onAccent,
        )
    }
}

private object NoHaptics : HapticFeedback {
    override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {}
}

@Composable
fun HamstrTheme(content: @Composable () -> Unit) {
    val s by Prefs.settings.collectAsState()
    val reduce = systemReducesMotion()
    val target = paletteFor(s.base, Color(s.accent))
    // Colour changes from Settings ease over instead of snapping.
    val accent by animateColorAsState(target.accent, tween(260), label = "accent")
    val canvas by animateColorAsState(target.canvas, tween(260), label = "canvas")
    val palette = target.copy(accent = accent, canvas = canvas)
    val fx = Fx(
        intro = s.intro && !reduce,
        raster = s.raster,
        rain = s.rain && !reduce,
        decode = s.decode && !reduce,
        asciiThumbs = s.asciiThumbs,
        moods = s.moods && !reduce,
        stretch = s.stretch && !reduce,
    )
    CompositionLocalProvider(
        LocalPalette provides palette,
        LocalType provides Type(),
        LocalTextSelectionColors provides TextSelectionColors(palette.accent, palette.accent.copy(alpha = 0.35f)),
        LocalReduceMotion provides reduce,
        LocalFx provides fx,
        LocalHapticFeedback provides if (s.haptics) LocalHapticFeedback.current else NoHaptics,
        content = content,
    )
}
