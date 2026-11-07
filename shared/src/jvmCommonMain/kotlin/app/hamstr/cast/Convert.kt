package app.hamstr.cast

import app.hamstr.Log
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Turns a file into one the device plays, when it doesn't play it as it is: FFmpeg looks at
 * what is inside ([probe]), [plan] decides with what each kind of device takes, and [prepare]
 * makes the copy into [Cast.cache] once (the next time it is there at once). A whole file,
 * not a stream, so the TV knows its length and seeking works as with the original.
 */
object Convert {
    private const val TAG = "Cast"

    /** What is inside a file: its container (by extension) and the codecs of its first streams. */
    data class Probe(val container: String, val video: String?, val audio: String?, val duration: Double)

    sealed interface Plan {
        /** Plays as it is. */
        data object Direct : Plan
        /** Only the sound, as [ext]; [copy] keeps the codec, otherwise AAC. */
        data class Sound(val copy: Boolean, val ext: String) : Plan
        /** The picture as it is in an MP4, the sound copied or made AAC. */
        data class Remux(val copyAudio: Boolean) : Plan
        /** Picture and sound made again, H.264 and AAC in an MP4. */
        data object Encode : Plan
    }

    // What each protocol plays without help. MPD plays anything FFmpeg reads.
    private val AUDIO = mapOf(
        CastProtocol.Chromecast to setOf("mp3", "aac", "flac", "vorbis", "opus", "pcm"),
        CastProtocol.Dlna to setOf("mp3", "aac", "flac", "pcm"),
        CastProtocol.AirPlay to setOf("mp3", "aac"),
    )
    private val AUDIO_CONTAINERS = mapOf(
        CastProtocol.Chromecast to setOf("mp3", "m4a", "mp4", "aac", "flac", "ogg", "oga", "opus", "webm", "wav"),
        CastProtocol.Dlna to setOf("mp3", "m4a", "mp4", "aac", "flac", "wav"),
        CastProtocol.AirPlay to setOf("mp3", "m4a", "mp4", "aac"),
    )
    private val VIDEO = mapOf(
        CastProtocol.Chromecast to setOf("h264", "vp8", "vp9"),
        CastProtocol.Dlna to setOf("h264", "hevc"),
        CastProtocol.AirPlay to setOf("h264"),
    )
    private val VIDEO_CONTAINERS = mapOf(
        CastProtocol.Chromecast to setOf("mp4", "m4v", "webm"),
        CastProtocol.Dlna to setOf("mp4", "m4v"),
        CastProtocol.AirPlay to setOf("mp4", "m4v", "mov"),
    )
    // Sound inside a video that the video's container takes along.
    private val VIDEO_AUDIO = mapOf(
        CastProtocol.Chromecast to setOf("aac", "mp3", "opus", "vorbis"),
        CastProtocol.Dlna to setOf("aac", "mp3", "ac3"),
        CastProtocol.AirPlay to setOf("aac"),
    )

    /** What to do with [p] for [protocol]; [picture] false when only the sound should go (a speaker, a song). */
    fun plan(protocol: CastProtocol, p: Probe, picture: Boolean): Plan {
        // Another Hamstr plays whatever Hamstr downloads.
        if (protocol == CastProtocol.Hamstr) return Plan.Direct
        if (protocol == CastProtocol.Mpd) return if (p.video == null) Plan.Direct else Plan.Sound(copy = false, ext = "m4a")
        val audioOk = p.audio == null || p.audio in AUDIO.getValue(protocol)
        if (!picture || p.video == null) {
            if (p.video == null && audioOk && p.container in AUDIO_CONTAINERS.getValue(protocol)) return Plan.Direct
            return when (p.audio) {
                "aac" -> Plan.Sound(copy = true, ext = "m4a")
                "mp3" -> Plan.Sound(copy = true, ext = "mp3")
                else -> Plan.Sound(copy = false, ext = "m4a")
            }
        }
        val videoOk = p.video in VIDEO.getValue(protocol)
        val soundOk = p.audio == null || p.audio in VIDEO_AUDIO.getValue(protocol)
        if (videoOk && soundOk && p.container in VIDEO_CONTAINERS.getValue(protocol)) return Plan.Direct
        // An MP4 takes H.264, HEVC and VP9 along; a Chromecast's WebM would have needed none of this.
        if (videoOk) return Plan.Remux(copyAudio = p.audio in setOf("aac", "mp3", "ac3"))
        return Plan.Encode
    }

    /** Reads the streams from FFmpeg's description of [file] (it has no ffprobe on the phone). */
    fun probe(file: File): Probe? {
        val ff = Cast.ffmpeg ?: return null
        val proc = runCatching { ff(listOf("-hide_banner", "-nostdin", "-i", file.absolutePath)).redirectErrorStream(true).start() }.getOrNull() ?: return null
        val text = proc.inputStream.bufferedReader().readText()
        proc.waitFor(10, TimeUnit.SECONDS)
        return parse(file.extension.lowercase(), text)
    }

    internal fun parse(container: String, text: String): Probe {
        fun codec(kind: String) = Regex("Stream #\\S+.*?: $kind: (\\w+)").find(text)?.groupValues?.get(1)?.lowercase()?.let { c ->
            when {
                c.startsWith("pcm_") -> "pcm"
                c == "mp3float" -> "mp3"
                else -> c
            }
        }
        val video = Regex("Stream #\\S+.*?: Video: (\\w+)[^\\n]*").findAll(text)
            // A cover picture in a song is a "video" stream too.
            .firstOrNull { !it.value.contains("attached pic") && !it.groupValues[1].equals("mjpeg", true) && !it.groupValues[1].equals("png", true) }
            ?.groupValues?.get(1)?.lowercase()
        val d = Regex("Duration: (\\d+):(\\d+):(\\d+(?:\\.\\d+)?)").find(text)?.groupValues
        val duration = d?.let { it[1].toDouble() * 3600 + it[2].toDouble() * 60 + it[3].toDouble() } ?: 0.0
        return Probe(container, video, codec("Audio"), duration)
    }

    /**
     * The file and type to send for [m] on [device]: the original, or a copy that plays there.
     * [progress] hears 0..1 while a copy is made; [wanted] going false stops it.
     */
    fun prepare(m: CastMedia, device: CastDevice, progress: (Float) -> Unit, wanted: () -> Boolean): Pair<File, String> {
        val p = probe(m.file) ?: return m.file to m.mime
        val plan = plan(device.protocol, p, picture = m.video && device.video)
        Log.d(TAG, "${m.file.name} on ${device.protocol}: $p → $plan")
        if (plan == Plan.Direct) return m.file to m.mime
        val ext = when (plan) { is Plan.Sound -> plan.ext; else -> "mp4" }
        val mime = when (ext) { "mp3" -> "audio/mpeg"; "m4a" -> "audio/mp4"; else -> "video/mp4" }
        val cache = Cast.cache ?: throw IOException("no place for the copy")
        cache.mkdirs()
        val key = MessageDigest.getInstance("SHA-1").digest("${m.file.absolutePath}|${m.file.length()}|${m.file.lastModified()}|$plan".toByteArray())
            .joinToString("") { "%02x".format(it) }.take(24)
        val out = File(cache, "$key.$ext")
        if (out.isFile && out.length() > 0) return out.also { it.setLastModified(System.currentTimeMillis()) } to mime
        tidy(cache)
        val part = File(cache, "$key.part.$ext")
        val codec = when (plan) {
            is Plan.Sound -> listOf("-vn", "-map", "0:a:0") + (if (plan.copy) listOf("-c:a", "copy") else listOf("-c:a", "aac", "-b:a", "256k"))
            is Plan.Remux -> listOf("-map", "0:v:0", "-map", "0:a:0?", "-c:v", "copy") + (if (plan.copyAudio) listOf("-c:a", "copy") else listOf("-c:a", "aac", "-b:a", "192k"))
            Plan.Encode -> encode(x264 = true)
            Plan.Direct -> emptyList()
        }
        val faststart = if (ext != "mp3") listOf("-movflags", "+faststart") else emptyList()
        try {
            run(listOf("-hide_banner", "-nostdin", "-y", "-i", m.file.absolutePath) + codec + faststart + listOf("-progress", "pipe:1", "-nostats", part.absolutePath), p.duration, progress, wanted)
        } catch (e: IOException) {
            // A phone's FFmpeg without x264: MPEG-4 Part 2, which TVs play too.
            if (plan != Plan.Encode || !wanted()) throw e
            run(listOf("-hide_banner", "-nostdin", "-y", "-i", m.file.absolutePath) + encode(x264 = false) + faststart + listOf("-progress", "pipe:1", "-nostats", part.absolutePath), p.duration, progress, wanted)
        }
        if (!part.renameTo(out)) throw IOException("couldn’t keep the copy")
        return out to mime
    }

    private fun encode(x264: Boolean): List<String> = listOf("-map", "0:v:0", "-map", "0:a:0?") +
        (if (x264) listOf("-c:v", "libx264", "-preset", "veryfast", "-crf", "21", "-profile:v", "high", "-level", "4.1") else listOf("-c:v", "mpeg4", "-q:v", "4")) +
        listOf("-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "192k")

    private fun run(args: List<String>, duration: Double, progress: (Float) -> Unit, wanted: () -> Boolean) {
        val ff = Cast.ffmpeg ?: throw IOException("no FFmpeg")
        val proc = ff(args).start()
        // FFmpeg's talk on stderr is read away, or it would stall once the pipe is full.
        Thread({ runCatching { proc.errorStream.use { e -> val buf = ByteArray(8192); while (e.read(buf) >= 0) Unit } } }, "hamstr-cast-ffmpeg").apply { isDaemon = true }.start()
        try {
            proc.inputStream.bufferedReader().useLines { lines: Sequence<String> ->
                for (line in lines) {
                    if (!wanted()) { proc.destroyForcibly(); throw IOException("not wanted any more") }
                    if (line.startsWith("out_time_us=") || line.startsWith("out_time_ms=")) {
                        val us = line.substringAfter('=').toLongOrNull() ?: continue
                        if (duration > 0) progress((us / 1_000_000.0 / duration).toFloat().coerceIn(0f, 1f))
                    }
                }
            }
            if (!proc.waitFor(1, TimeUnit.MINUTES) || proc.exitValue() != 0) throw IOException("FFmpeg couldn’t make a copy the device plays")
        } finally {
            if (proc.isAlive) proc.destroyForcibly()
        }
    }

    /** Keeps the cache to the last few copies and a few gigabytes. */
    private fun tidy(dir: File) {
        val files = dir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() } ?: return
        var total = 0L
        files.forEachIndexed { i, f ->
            total += f.length()
            if (f.name.contains(".part.") || i >= 12 || total > 3L shl 30) f.delete()
        }
    }
}
