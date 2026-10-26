package app.hamstr.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class Settings(
    val mode: Mode = Mode.Video,
    val height: Int? = 1080,
    val container: String = "mp4",
    val audioFormat: String = "opus",
    val subtitles: Boolean = false,
    val parallel: Int = 2,
    val quickStash: Boolean = false, // share -> download immediately, no sheet
    val playlists: Boolean = false,
    val autoUpdate: Boolean = true,
    val lastUpdateCheck: Long = 0,
    // Hamstr's own updates from GitHub Releases
    val appUpdates: Boolean = true,
    val lastAppCheck: Long = 0,
    val seenRelease: String = "",
    // Appearance
    val accent: Int = ACCENTS.first().argb,
    val base: Base = Base.Graphite,
    // Motion & effects
    val intro: Boolean = true,
    val raster: Boolean = true,
    val rain: Boolean = true,
    val decode: Boolean = true,
    val asciiThumbs: Boolean = true,
    val moods: Boolean = true,
    val stretch: Boolean = true,
    val haptics: Boolean = true,
) {
    fun defaultChoice() = Choice(mode, height, container, audioFormat, subtitles)
}

object Prefs {
    private lateinit var sp: SharedPreferences
    private val _settings = MutableStateFlow(Settings())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    val heights = listOf(2160, 1440, 1080, 720, 480, null)
    val containers = listOf("mp4", "mkv", "webm")
    val audioFormats = listOf("opus", "m4a", "mp3")

    fun init(context: Context) {
        sp = context.getSharedPreferences("hamstr", Context.MODE_PRIVATE)
        _settings.value = Settings(
            mode = runCatching { Mode.valueOf(sp.getString("mode", null)!!) }.getOrDefault(Mode.Video),
            height = sp.getInt("height", 1080).takeIf { it > 0 },
            container = sp.getString("container", "mp4")!!,
            audioFormat = sp.getString("audioFormat", "opus")!!,
            subtitles = sp.getBoolean("subtitles", false),
            parallel = sp.getInt("parallel", 2),
            quickStash = sp.getBoolean("quickStash", false),
            playlists = sp.getBoolean("playlists", false),
            autoUpdate = sp.getBoolean("autoUpdate", true),
            lastUpdateCheck = sp.getLong("lastUpdateCheck", 0),
            appUpdates = sp.getBoolean("appUpdates", true),
            lastAppCheck = sp.getLong("lastAppCheck", 0),
            seenRelease = sp.getString("seenRelease", "")!!,
            accent = sp.getInt("accent", ACCENTS.first().argb),
            base = runCatching { Base.valueOf(sp.getString("base", null)!!) }.getOrDefault(Base.Graphite),
            intro = sp.getBoolean("fx.intro", true),
            raster = sp.getBoolean("fx.raster", true),
            rain = sp.getBoolean("fx.rain", true),
            decode = sp.getBoolean("fx.decode", true),
            asciiThumbs = sp.getBoolean("fx.asciiThumbs", true),
            moods = sp.getBoolean("fx.moods", true),
            stretch = sp.getBoolean("fx.stretch", true),
            haptics = sp.getBoolean("fx.haptics", true),
        )
    }

    fun edit(block: (Settings) -> Settings) {
        val s = block(_settings.value)
        _settings.value = s
        sp.edit()
            .putString("mode", s.mode.name)
            .putInt("height", s.height ?: 0)
            .putString("container", s.container)
            .putString("audioFormat", s.audioFormat)
            .putBoolean("subtitles", s.subtitles)
            .putInt("parallel", s.parallel)
            .putBoolean("quickStash", s.quickStash)
            .putBoolean("playlists", s.playlists)
            .putBoolean("autoUpdate", s.autoUpdate)
            .putLong("lastUpdateCheck", s.lastUpdateCheck)
            .putBoolean("appUpdates", s.appUpdates)
            .putLong("lastAppCheck", s.lastAppCheck)
            .putString("seenRelease", s.seenRelease)
            .putInt("accent", s.accent)
            .putString("base", s.base.name)
            .putBoolean("fx.intro", s.intro)
            .putBoolean("fx.raster", s.raster)
            .putBoolean("fx.rain", s.rain)
            .putBoolean("fx.decode", s.decode)
            .putBoolean("fx.asciiThumbs", s.asciiThumbs)
            .putBoolean("fx.moods", s.moods)
            .putBoolean("fx.stretch", s.stretch)
            .putBoolean("fx.haptics", s.haptics)
            .apply()
    }
}

fun heightLabel(h: Int?) = when (h) {
    null -> "Best"
    2160 -> "4K"
    else -> "${h}p"
}

/** Dark bases; all keep Slate's neutral steps, just shifted. */
enum class Base(val label: String) { Graphite("Graphite"), Oled("OLED black"), Cocoa("Cocoa") }

data class AccentPreset(val name: String, val argb: Int)

val ACCENTS = listOf(
    AccentPreset("Cheek", 0xFFFF9A3C.toInt()),
    AccentPreset("Glyph pink", 0xFFFF5FD2.toInt()),
    AccentPreset("Lemon", 0xFFF2D541.toInt()),
    AccentPreset("Mint", 0xFF3DDC97.toInt()),
    AccentPreset("Sky", 0xFF5AB4FF.toInt()),
    AccentPreset("Violet", 0xFFA98BFF.toInt()),
    AccentPreset("Coral", 0xFFFF6B6B.toInt()),
)
