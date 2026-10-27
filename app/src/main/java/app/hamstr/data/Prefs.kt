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
    // Subtitle language codes; empty means the phone's language plus English.
    val subLangs: List<String> = emptyList(),
    val parallel: Int = 2,
    val wifiOnly: Boolean = false,
    // "Title.mp4" instead of yt-dlp's "Title [id].mp4".
    val cleanNames: Boolean = true,
    // Hamstr's own switch; Android's permission is a separate gate.
    val notifications: Boolean = true,
    val askedNotifications: Boolean = false,
    val librarySort: LibrarySort = LibrarySort.Newest,
    val libraryPlaylists: PlaylistPlacement = PlaylistPlacement.Row,
    val quickStash: Boolean = false, // share -> download immediately, no sheet
    val playlists: Boolean = false,
    // Bring Hamstr up with the file playing once the last download is in.
    val openWhenDone: Boolean = false,
    val autoUpdate: Boolean = true,
    val lastUpdateCheck: Long = 0,
    val engine: DownloadEngine = DownloadEngine.Cheek,
    val cheekAutoUpdate: Boolean = true,
    val lastCheekCheck: Long = 0,
    // Hamstr's own updates from GitHub Releases
    val appUpdates: Boolean = true,
    val lastAppCheck: Long = 0,
    val seenRelease: String = "",
    // Appearance
    val accent: Int = ACCENTS.first().argb,
    val base: Base = Base.Graphite,
    val size: UiSize = UiSize.Normal,
    val miniStyle: MiniStyle = MiniStyle.Floating,
    val iconStyle: IconStyle = IconStyle.Dark,
    // Motion & effects
    val intro: Boolean = true,
    val raster: Boolean = true,
    val rain: Boolean = true,
    val decode: Boolean = true,
    val asciiThumbs: Boolean = true,
    val moods: Boolean = true,
    val stretch: Boolean = true,
    val haptics: Boolean = true,
    // Playback
    val resumeAfterVideos: Boolean = true,
    val iconFollowsAccent: Boolean = true,
) {
    fun defaultChoice() = Choice(mode, height, container, audioFormat, subtitles)

    /** The languages subtitles are fetched in, e.g. [de, en]. */
    fun subtitleLanguages(): List<String> = subLangs.ifEmpty {
        listOf(java.util.Locale.getDefault().language.ifEmpty { "en" }, "en").distinct()
    }
}

enum class LibrarySort { Newest, Oldest, Name, Size }

/** Where the library shows playlists: a row on top, as tiles among the files, or not at all. */
enum class PlaylistPlacement { Row, Grid, Hidden }

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
            subLangs = sp.getString("subLangs", "")!!.split(',').filter { it.isNotBlank() },
            parallel = sp.getInt("parallel", 2),
            wifiOnly = sp.getBoolean("wifiOnly", false),
            cleanNames = sp.getBoolean("cleanNames", true),
            notifications = sp.getBoolean("notifications", true),
            askedNotifications = sp.getBoolean("askedNotifications", false),
            librarySort = runCatching { LibrarySort.valueOf(sp.getString("librarySort", null)!!) }.getOrDefault(LibrarySort.Newest),
            libraryPlaylists = runCatching { PlaylistPlacement.valueOf(sp.getString("libraryPlaylists", null)!!) }.getOrDefault(PlaylistPlacement.Row),
            quickStash = sp.getBoolean("quickStash", false),
            playlists = sp.getBoolean("playlists", false),
            openWhenDone = sp.getBoolean("openWhenDone", false),
            autoUpdate = sp.getBoolean("autoUpdate", true),
            lastUpdateCheck = sp.getLong("lastUpdateCheck", 0),
            engine = runCatching { DownloadEngine.valueOf(sp.getString("engine", null)!!) }.getOrDefault(DownloadEngine.Cheek),
            cheekAutoUpdate = sp.getBoolean("cheekAutoUpdate", true),
            lastCheekCheck = sp.getLong("lastCheekCheck", 0),
            appUpdates = sp.getBoolean("appUpdates", true),
            lastAppCheck = sp.getLong("lastAppCheck", 0),
            seenRelease = sp.getString("seenRelease", "")!!,
            accent = sp.getInt("accent", ACCENTS.first().argb),
            base = runCatching { Base.valueOf(sp.getString("base", null)!!) }.getOrDefault(Base.Graphite),
            size = runCatching { UiSize.valueOf(sp.getString("size", null)!!) }.getOrDefault(UiSize.Normal),
            miniStyle = runCatching { MiniStyle.valueOf(sp.getString("miniStyle", null)!!) }.getOrDefault(MiniStyle.Floating),
            iconStyle = runCatching { IconStyle.valueOf(sp.getString("iconStyle", null)!!) }.getOrDefault(IconStyle.Dark),
            intro = sp.getBoolean("fx.intro", true),
            raster = sp.getBoolean("fx.raster", true),
            rain = sp.getBoolean("fx.rain", true),
            decode = sp.getBoolean("fx.decode", true),
            asciiThumbs = sp.getBoolean("fx.asciiThumbs", true),
            moods = sp.getBoolean("fx.moods", true),
            stretch = sp.getBoolean("fx.stretch", true),
            haptics = sp.getBoolean("fx.haptics", true),
            resumeAfterVideos = sp.getBoolean("resumeAfterVideos", true),
            iconFollowsAccent = sp.getBoolean("iconFollowsAccent", true),
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
            .putString("subLangs", s.subLangs.joinToString(","))
            .putInt("parallel", s.parallel)
            .putBoolean("wifiOnly", s.wifiOnly)
            .putBoolean("cleanNames", s.cleanNames)
            .putBoolean("notifications", s.notifications)
            .putBoolean("askedNotifications", s.askedNotifications)
            .putString("librarySort", s.librarySort.name)
            .putString("libraryPlaylists", s.libraryPlaylists.name)
            .putBoolean("quickStash", s.quickStash)
            .putBoolean("playlists", s.playlists)
            .putBoolean("openWhenDone", s.openWhenDone)
            .putBoolean("autoUpdate", s.autoUpdate)
            .putLong("lastUpdateCheck", s.lastUpdateCheck)
            .putString("engine", s.engine.name)
            .putBoolean("cheekAutoUpdate", s.cheekAutoUpdate)
            .putLong("lastCheekCheck", s.lastCheekCheck)
            .putBoolean("appUpdates", s.appUpdates)
            .putLong("lastAppCheck", s.lastAppCheck)
            .putString("seenRelease", s.seenRelease)
            .putInt("accent", s.accent)
            .putString("base", s.base.name)
            .putString("size", s.size.name)
            .putString("miniStyle", s.miniStyle.name)
            .putString("iconStyle", s.iconStyle.name)
            .putBoolean("fx.intro", s.intro)
            .putBoolean("fx.raster", s.raster)
            .putBoolean("fx.rain", s.rain)
            .putBoolean("fx.decode", s.decode)
            .putBoolean("fx.asciiThumbs", s.asciiThumbs)
            .putBoolean("fx.moods", s.moods)
            .putBoolean("fx.stretch", s.stretch)
            .putBoolean("fx.haptics", s.haptics)
            .putBoolean("resumeAfterVideos", s.resumeAfterVideos)
            .putBoolean("iconFollowsAccent", s.iconFollowsAccent)
            .apply()
    }
}

fun heightLabel(h: Int?) = when (h) {
    null -> "Best"
    2160 -> "4K"
    else -> "${h}p"
}

/** Who fetches the bytes. yt-dlp always works out the streams. */
enum class DownloadEngine(val label: String) { YtDlp("yt-dlp"), Cheek("Cheek") }

/** Dark bases; all keep Slate's neutral steps, just shifted. */
enum class Base(val label: String) {
    Graphite("Graphite"), Oled("OLED black"), Cocoa("Cocoa"), Midnight("Midnight"), Moss("Moss"), Plum("Plum"),
}

/** How big everything is drawn: text, controls and spacing scale together. */
enum class UiSize(val scale: Float) { Small(0.9f), Normal(1f), Large(1.1f) }

/** The mini player: floating above the tabs, docked onto them, or a slimmer bar. */
enum class MiniStyle { Floating, Docked, Compact }

/** The launcher icon: the hamster on black, on a light tile, or on the accent. */
enum class IconStyle { Dark, Light, Accent }

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
