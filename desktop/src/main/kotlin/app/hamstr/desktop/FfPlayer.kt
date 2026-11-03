package app.hamstr.desktop

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import app.hamstr.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.json.JSONObject
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.FloatControl
import javax.sound.sampled.SourceDataLine
import kotlin.math.log10
import kotlin.math.max

/**
 * Plays a file with the FFmpeg Hamstr already brings, so every format it downloads plays, the
 * same on every system: one FFmpeg decodes the sound into the system's audio line, another the
 * pictures into frames for Compose, scaled to at most 1280×720 and 30 a second. The sound passes
 * the equalizer on its way to the line. It is the clock; pictures wait for it or are dropped
 * when late. A seek starts both again there.
 */
class FfPlayer(private val file: File, start: Double = 0.0) {
    data class Info(val duration: Double, val width: Int, val height: Int, val audio: Boolean, val video: Boolean)

    private val _frame = MutableStateFlow<ImageBitmap?>(null)
    val frame: StateFlow<ImageBitmap?> = _frame.asStateFlow()
    private val _position = MutableStateFlow(start)
    /** Seconds into the file. */
    val position: StateFlow<Double> = _position.asStateFlow()
    private val _playing = MutableStateFlow(false)
    val playing: StateFlow<Boolean> = _playing.asStateFlow()
    private val _ended = MutableStateFlow(false)
    val ended: StateFlow<Boolean> = _ended.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    val info: Info? = probe()
    @Volatile private var volume = 1f
    @Volatile private var session: Session? = null

    init {
        if (info == null) _error.value = "FFmpeg can't read this file"
    }

    fun play() {
        if (info == null) return
        if (_ended.value) {
            _ended.value = false
            restart(0.0)
        }
        _playing.value = true
        val s = session
        if (s == null) restart(_position.value) else s.resume()
    }

    fun pause() {
        _playing.value = false
        session?.pause()
    }

    fun toggle() = if (_playing.value) pause() else play()

    fun seek(seconds: Double) {
        val total = info?.duration?.takeIf { it > 0 } ?: return
        _ended.value = false
        restart(seconds.coerceIn(0.0, max(0.0, total - 0.5)))
    }

    fun setVolume(v: Float) {
        volume = v.coerceIn(0f, 1f)
        session?.applyVolume()
    }

    fun close() {
        session?.stop()
        session = null
        _playing.value = false
    }

    private fun restart(at: Double) {
        session?.stop()
        _position.value = at
        session = if (info != null) Session(info, at).also { it.start(paused = !_playing.value) } else null
    }

    // ---- one run of the decoders, from one point in the file --------------------------------

    private inner class Session(val info: Info, val from: Double) {
        @Volatile private var stopped = false
        @Volatile private var paused = false
        private val lock = Object()
        private var audio: Process? = null
        private var video: Process? = null
        private var line: SourceDataLine? = null
        // The wall clock, for files without sound.
        private var wallStart = 0L
        private var pausedAt = 0L
        private var pausedFor = 0L
        private val threads = mutableListOf<Thread>()

        /** Seconds into the file the listener is at now. */
        fun clock(): Double {
            val l = line
            if (info.audio && l != null) return from + l.longFramePosition / RATE.toDouble()
            val now = if (paused) pausedAt else System.nanoTime()
            return from + (now - wallStart - pausedFor) / 1e9
        }

        fun start(paused: Boolean) {
            this.paused = paused
            wallStart = System.nanoTime()
            pausedAt = wallStart
            if (info.audio) threads += Thread(::sound, "hamstr-player-sound").apply { isDaemon = true; start() }
            if (info.video) threads += Thread(::pictures, "hamstr-player-pictures").apply { isDaemon = true; start() }
            if (!info.audio && !info.video) _ended.value = true
        }

        fun pause() {
            synchronized(lock) {
                if (paused) return
                paused = true
                pausedAt = System.nanoTime()
                line?.stop()
            }
        }

        fun resume() {
            synchronized(lock) {
                if (!paused) return
                pausedFor += System.nanoTime() - pausedAt
                paused = false
                line?.start()
                lock.notifyAll()
            }
        }

        fun applyVolume() {
            val gain = line?.takeIf { it.isControlSupported(FloatControl.Type.MASTER_GAIN) }?.getControl(FloatControl.Type.MASTER_GAIN) as? FloatControl ?: return
            val db = if (volume <= 0.001f) gain.minimum else (20 * log10(volume.toDouble())).toFloat()
            gain.value = db.coerceIn(gain.minimum, gain.maximum)
        }

        fun stop() {
            stopped = true
            synchronized(lock) { lock.notifyAll() }
            listOfNotNull(audio, video).forEach { it.destroyForcibly() }
            runCatching { line?.stop(); line?.flush(); line?.close() }
            threads.forEach { it.interrupt() }
        }

        private fun waitWhilePaused() {
            synchronized(lock) { while (paused && !stopped) lock.wait(200) }
        }

        private fun sound() {
            runCatching {
            val p = ffmpeg(listOf("-vn", "-ac", "2", "-ar", RATE.toString(), "-f", "s16le", "pipe:1")).also { audio = it }
            val format = AudioFormat(RATE.toFloat(), 16, 2, true, false)
            val l = AudioSystem.getSourceDataLine(format).apply { open(format, RATE / 4 * 4) }
            line = l
            applyVolume()
            if (!paused) l.start()
            val buf = ByteArray(8192)
            val eq = app.hamstr.audio.EqFilter(RATE, 2)
            p.inputStream.use { input ->
                while (!stopped) {
                    waitWhilePaused()
                    if (stopped) break
                    val n = input.read(buf)
                    if (n < 0) break
                    eq.process16(buf, 0, n - n % 4)
                    l.write(buf, 0, n - n % 4)
                    _position.value = clock()
                }
            }
            if (!stopped) {
                l.drain()
                finished()
            }
            }.onFailure { if (!stopped) fail(it) }
        }

        private fun pictures() {
            runCatching {
            val (w, h) = size(info)
            val p = ffmpeg(listOf("-an", "-vf", "fps=$FPS,scale=$w:$h:flags=bilinear", "-pix_fmt", "bgra", "-f", "rawvideo", "pipe:1")).also { video = it }
            val frame = ByteArray(w * h * 4)
            val imageInfo = ImageInfo(w, h, ColorType.BGRA_8888, ColorAlphaType.PREMUL)
            var index = 0L
            DataInputStream(p.inputStream.buffered(frame.size * 2)).use { input ->
                while (!stopped) {
                    try {
                        input.readFully(frame)
                    } catch (_: EOFException) {
                        break
                    }
                    val at = from + index++ / FPS.toDouble()
                    waitWhilePaused()
                    // Too early: wait for the sound. Far too late: drop it and catch up.
                    var ahead = at - clock()
                    while (ahead > 0.004 && !stopped) {
                        Thread.sleep((ahead * 1000).toLong().coerceIn(1, 100))
                        waitWhilePaused()
                        ahead = at - clock()
                    }
                    if (stopped) break
                    if (ahead < -0.25) continue
                    _frame.value = Image.makeRaster(imageInfo, frame, w * 4).toComposeImageBitmap()
                    // Without sound (or a sound card) the pictures move the clock.
                    if (!info.audio || line == null) _position.value = at
                }
            }
            if (!stopped && !info.audio) finished()
            }.onFailure { if (!stopped) fail(it) }
        }

        private fun ffmpeg(output: List<String>): Process =
            ProcessBuilder(listOf(Tools.ffmpeg.path, "-hide_banner", "-loglevel", "error", "-nostdin", "-ss", "%.3f".format(java.util.Locale.ROOT, from), "-i", file.path) + output)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()

        private fun finished() {
            _playing.value = false
            _ended.value = true
        }

        private fun fail(e: Throwable) {
            Log.w(TAG, "Playback failed", e)
            _error.value = e.message ?: e.javaClass.simpleName
            _playing.value = false
        }
    }

    /** Duration, picture size and which streams there are, from ffprobe. */
    private fun probe(): Info? = runCatching {
        val exe = if (Paths.windows) ".exe" else ""
        // A pipe read can block before the timeout, and waiting first can fill the pipe.
        val output = Files.createTempFile("hamstr-ffprobe-", ".json")
        val json = try {
            val p = ProcessBuilder(File(Tools.dir, "ffprobe$exe").path, "-v", "error", "-show_entries",
                "format=duration:stream=codec_type,width,height", "-of", "json", file.path)
                .redirectOutput(output.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            try {
                check(p.waitFor(15, TimeUnit.SECONDS)) { "ffprobe timed out" }
                check(p.exitValue() == 0) { "ffprobe exited with ${p.exitValue()}" }
                JSONObject(Files.readString(output))
            } finally {
                if (p.isAlive) p.destroyForcibly()
            }
        } finally {
            Files.deleteIfExists(output)
        }
        val streams = json.optJSONArray("streams")?.let { a -> List(a.length()) { a.getJSONObject(it) } }.orEmpty()
        val picture = streams.firstOrNull { it.optString("codec_type") == "video" && it.optInt("width") > 0 }
        Info(
            duration = json.optJSONObject("format")?.optString("duration")?.toDoubleOrNull() ?: 0.0,
            width = picture?.optInt("width") ?: 0,
            height = picture?.optInt("height") ?: 0,
            audio = streams.any { it.optString("codec_type") == "audio" },
            // A cover in an audio file is a "video" stream of one picture; the cover shows instead.
            video = picture != null && app.hamstr.player.kindOf(file) != app.hamstr.player.Kind.Audio,
        )
    }.onFailure { Log.w(TAG, "ffprobe failed for $file", it) }.getOrNull()

    companion object {
        private const val TAG = "HamstrPlayer"
        private const val RATE = 48_000
        private const val FPS = 30

        /** The picture's size scaled into 1280×720, both even, as scalers want. */
        fun size(info: Info): Pair<Int, Int> {
            if (info.width <= 0 || info.height <= 0) return 1280 to 720
            val scale = minOf(1.0, 1280.0 / info.width, 720.0 / info.height)
            fun even(v: Double) = max(2, (v.toInt() / 2) * 2)
            return even(info.width * scale) to even(info.height * scale)
        }
    }
}
