package app.hamstr.cast

import java.io.File

/**
 * How Hamstr talks to a device: Google Cast (Chromecast, Google TV, Nest speakers), DLNA/UPnP
 * (most smart TVs, receivers, Sonos, Kodi), MPD (music servers), AirPlay (without pairing) or
 * another Hamstr app on the same network ([HamstrCast]).
 */
enum class CastProtocol(val label: String) { Chromecast("Chromecast"), Dlna("DLNA"), Mpd("MPD"), AirPlay("AirPlay"), Hamstr("Hamstr") }

/** A TV or speaker on the same network that can play what Hamstr sends it. */
data class CastDevice(
    /** Stable while the device stays: the UPnP UDN or the Cast id. */
    val id: String,
    val name: String,
    val protocol: CastProtocol,
    val host: String,
    val port: Int,
    /** "Samsung TV", "Chromecast", … when the device says. */
    val model: String? = null,
    /** False for a speaker, which only gets the sound of a video. */
    val video: Boolean = true,
    /** DLNA: where AVTransport and RenderingControl take their commands. */
    val transport: String? = null,
    val rendering: String? = null,
    /** Another Hamstr: a phone or a computer. */
    val hamstr: app.hamstr.nearby.DeviceKind? = null,
)

/** One file as the device gets it. */
data class CastMedia(
    val id: String,
    val file: File,
    val title: String,
    val artist: String?,
    val mime: String,
    val video: Boolean,
    /** An http(s) link or a local file. */
    val cover: String? = null,
)

/** What the device says it does, in seconds. */
data class CastStatus(
    val state: State = State.Idle,
    val position: Double = 0.0,
    val duration: Double = 0.0,
    /** 0..1, or null when the device doesn't say. */
    val volume: Float? = null,
    val at: Long = System.currentTimeMillis(),
) {
    enum class State { Idle, Loading, Playing, Paused, Finished, Failed }

    /** The position now, counting on from the last report while it plays. */
    fun positionNow(now: Long = System.currentTimeMillis()): Double =
        if (state == State.Playing) (position + (now - at) / 1000.0).let { if (duration > 0) it.coerceAtMost(duration) else it } else position
}

/** The protocol's side of a session: what one device understands. */
interface CastLink {
    suspend fun connect()
    suspend fun load(url: String, coverUrl: String?, media: CastMedia, at: Double, play: Boolean)
    suspend fun play()
    suspend fun pause()
    suspend fun seek(seconds: Double)
    suspend fun setVolume(level: Float)
    /** Asks the device how it is doing. */
    suspend fun status(): CastStatus
    /** Stops what plays there and lets go of the device. */
    suspend fun stop()
    fun close()

    /** The device plays something else now (its user picked another song there): the session ends. */
    class Released : java.io.IOException("released")
}
