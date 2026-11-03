package app.hamstr.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import app.hamstr.res.Res
import app.hamstr.res.base_cocoa
import app.hamstr.res.base_graphite
import app.hamstr.res.base_midnight
import app.hamstr.res.base_moss
import app.hamstr.res.base_oled
import app.hamstr.res.base_plum
import org.jetbrains.compose.resources.StringResource
import kotlinx.coroutines.flow.updateAndGet

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
    val playbackShuffle: Boolean = false,
    val playbackRepeat: Int = 0, // Media3: off (0), one (1), all (2).
    val resumeAfterVideos: Boolean = true,
    val iconFollowsAccent: Boolean = true,
    // The equalizer (app.hamstr.audio.Equalizer): on or off, a preset's id, and the bands of "custom" in dB.
    val eqOn: Boolean = false,
    val eqPreset: String = "flat",
    val eqGains: List<Float> = List(10) { 0f },
    // Seconds one song fades into the next; 0 cuts as before.
    val crossfade: Int = 0,
    // Nearby: other Hamstr apps on the same Wi-Fi or in Bluetooth range. Empty name: the device's own.
    val nearby: Boolean = true,
    val nearbyBluetooth: Boolean = true,
    val deviceName: String = "",
    // Desktop: closing the window keeps Hamstr in the tray; start with the computer, in the tray.
    val trayOnClose: Boolean = true,
    val startWithSystem: Boolean = false,
    // The first-start tour was shown (or skipped); Settings › About plays it again.
    val toured: Boolean = false,
    // The app's language as a tag from AppLanguages; empty follows the device. Android 13+ keeps it in the system instead.
    val language: String = "",
    // Where finished files go. The desktop keeps a whole path, the phone a folder inside
    // Download; empty means Hamstr's own (Downloads/Hamstr, Download/Hamstr).
    val downloadFolder: String = "",
    // Android 14+: downloads started in Hamstr run as a user-initiated transfer job (no daily limit).
    val transferJobs: Boolean = true,
) {
    fun defaultChoice() = Choice(mode, height, container, audioFormat, subtitles, open = openWhenDone)

    /** The languages subtitles are fetched in, e.g. [de, en]. */
    fun subtitleLanguages(): List<String> = subLangs.ifEmpty {
        listOf(java.util.Locale.getDefault().language.ifEmpty { "en" }, "en").distinct()
    }
}

enum class LibrarySort { Newest, Oldest, Name, Size }

/** Where the library shows playlists: a row on top, as tiles among the files, or not at all. */
enum class PlaylistPlacement { Row, Grid, Hidden }

object Prefs {
    private lateinit var sp: KeyValues
    private val _settings = MutableStateFlow(Settings())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    val heights = listOf(2160, 1440, 1080, 720, 480, null)
    val containers = listOf("mp4", "mkv", "webm")
    val audioFormats = listOf("opus", "m4a", "mp3")

    fun init(store: KeyValues) {
        sp = store
        _settings.value = read(store)
    }

    /** Settings as [write] leaves them in [sp]; what's missing or can't be read keeps its default. */
    private fun read(sp: KeyValues) = Settings(
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
        playbackShuffle = sp.getBoolean("playbackShuffle", false),
        playbackRepeat = sp.getInt("playbackRepeat", 0).takeIf { it in 0..2 } ?: 0,
        resumeAfterVideos = sp.getBoolean("resumeAfterVideos", true),
        iconFollowsAccent = sp.getBoolean("iconFollowsAccent", true),
        eqOn = sp.getBoolean("eq.on", false),
        eqPreset = sp.getString("eq.preset", "flat")!!,
        eqGains = sp.getString("eq.gains", "")!!.split(',').mapNotNull { it.toFloatOrNull()?.coerceIn(-12f, 12f) }
            .takeIf { it.size == 10 } ?: List(10) { 0f },
        crossfade = sp.getInt("crossfade", 0).coerceIn(0, 12),
        nearby = sp.getBoolean("nearby", true),
        nearbyBluetooth = sp.getBoolean("nearbyBluetooth", true),
        deviceName = sp.getString("deviceName", "")!!,
        trayOnClose = sp.getBoolean("trayOnClose", true),
        startWithSystem = sp.getBoolean("startWithSystem", false),
        toured = sp.getBoolean("toured", false),
        language = sp.getString("language", "")!!,
        downloadFolder = sp.getString("downloadFolder", "")!!,
        transferJobs = sp.getBoolean("transferJobs", true),
    )

    /** Changes settings in one step: edits from two threads at once don't undo each other. */
    @Synchronized
    fun edit(block: (Settings) -> Settings) {
        write(sp.edit(), _settings.updateAndGet(block)).apply()
    }

    /** [s] under the keys it is saved with, for a backup. */
    fun encode(s: Settings = settings.value): Map<String, Any?> = LinkedHashMap<String, Any?>().also { out ->
        write(object : KeyValues.Editor {
            override fun putString(key: String, value: String?) = apply { out[key] = value }
            override fun putInt(key: String, value: Int) = apply { out[key] = value }
            override fun putLong(key: String, value: Long) = apply { out[key] = value }
            override fun putBoolean(key: String, value: Boolean) = apply { out[key] = value }
            override fun remove(key: String) = apply { out.remove(key) }
            override fun apply() {}
        }, s)
    }

    /** Settings from [encode]'s keys, read back as loosely as a backup from another version needs. */
    fun decode(values: Map<String, Any?>): Settings = read(object : KeyValues {
        override fun getString(key: String, default: String?) = values[key]?.toString() ?: default
        override fun getInt(key: String, default: Int) = (values[key] as? Number)?.toInt() ?: values[key]?.toString()?.toIntOrNull() ?: default
        override fun getLong(key: String, default: Long) = (values[key] as? Number)?.toLong() ?: values[key]?.toString()?.toLongOrNull() ?: default
        override fun getBoolean(key: String, default: Boolean) = values[key] as? Boolean ?: values[key]?.toString()?.toBooleanStrictOrNull() ?: default
        override fun all() = values
        override fun edit(): KeyValues.Editor = throw UnsupportedOperationException()
    })

    private fun write(e: KeyValues.Editor, s: Settings): KeyValues.Editor = e
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
        .putBoolean("playbackShuffle", s.playbackShuffle)
        .putInt("playbackRepeat", s.playbackRepeat)
        .putBoolean("resumeAfterVideos", s.resumeAfterVideos)
        .putBoolean("iconFollowsAccent", s.iconFollowsAccent)
        .putBoolean("eq.on", s.eqOn)
        .putString("eq.preset", s.eqPreset)
        .putString("eq.gains", s.eqGains.joinToString(","))
        .putInt("crossfade", s.crossfade)
        .putBoolean("nearby", s.nearby)
        .putBoolean("nearbyBluetooth", s.nearbyBluetooth)
        .putString("deviceName", s.deviceName)
        .putBoolean("trayOnClose", s.trayOnClose)
        .putBoolean("startWithSystem", s.startWithSystem)
        .putBoolean("toured", s.toured)
        .putString("language", s.language)
        .putString("downloadFolder", s.downloadFolder)
        .putBoolean("transferJobs", s.transferJobs)
}

fun heightLabel(h: Int?) = when (h) {
    null -> "Best"
    2160 -> "4K"
    else -> "${h}p"
}

/** Who fetches the bytes. yt-dlp always works out the streams. */
enum class DownloadEngine(val label: String) { YtDlp("yt-dlp"), Cheek("Cheek") }

/** Dark bases; all keep Slate's neutral steps, just shifted. Their names are in each language's strings. */
enum class Base(val title: StringResource) {
    Graphite(Res.string.base_graphite), Oled(Res.string.base_oled), Cocoa(Res.string.base_cocoa),
    Midnight(Res.string.base_midnight), Moss(Res.string.base_moss), Plum(Res.string.base_plum),
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
