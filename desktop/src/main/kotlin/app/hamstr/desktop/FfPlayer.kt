package app.hamstr.desktop

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import app.hamstr.Log
import app.hamstr.data.Prefs
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
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine
import kotlin.math.max

/**
 * Plays a file with the FFmpeg Hamstr already brings, so every format it downloads plays, the
 * same on every system: one FFmpeg decodes the sound into the system's audio line, another the
 * pictures into frames for Compose, scaled to at most 1280×720 and 30 a second. The sound passes
 * the equalizer on its way to the line. It is the clock; pictures wait for it or are dropped
 * when late. A seek starts both again there. [source] is what FFmpeg opens: the file, or a link
 * to a stream another Hamstr serves ("Play on"); [file]'s name still tells the kind.
 */
class FfPlayer internal constructor(
    private val file: File,
    start: Double = 0.0,
    private val source: String = file.path,
    knownInfo: Info? = null,
    private val openAudio: (AudioFormat) -> SourceDataLine = { format ->
        AudioSystem.getSourceDataLine(format).apply { open(format, RATE / 10 * 4) }
    },
) {
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

    val info: Info? = knownInfo ?: if (source == file.path && file.isFile) infoCache.get(file, ::probe) else probe()
    private val _volume = MutableStateFlow(Prefs.settings.value.desktopVolume)
    val loudness: StateFlow<Float> = _volume.asStateFlow()
    private val commands = Executors.newSingleThreadExecutor { task -> Thread(task, "hamstr-player-control").apply { isDaemon = true } }
    private val revision = AtomicLong()
    @Volatile private var closed = false
    private var session: Session? = null
    // Reuse the system's audio device when seeking; only the decoders need restarting.
    private val outputLock = Any()
    private var output: SourceDataLine? = null

    init {
        if (info == null) _error.value = "FFmpeg can't read this file"
    }

    @Synchronized
    fun play() {
        if (closed || info == null) return
        if (_ended.value) {
            _ended.value = false
            _position.value = 0.0
            revision.incrementAndGet()
        }
        _playing.value = true
        commands.execute { updatePlayback() }
    }

    @Synchronized
    fun pause() {
        if (closed) return
        _playing.value = false
        commands.execute { updatePlayback() }
    }

    fun toggle() = if (_playing.value) pause() else play()

    @Synchronized
    fun seek(seconds: Double) {
        if (closed || !seconds.isFinite()) return
        val total = info?.duration?.takeIf { it.isFinite() && it > 0 } ?: return
        val at = seconds.coerceIn(0.0, max(0.0, total - 0.5))
        val version = revision.incrementAndGet()
        _ended.value = false
        _position.value = at
        commands.execute {
            // Key repeats and loop jumps may arrive faster than a decoder starts.
            if (!closed && revision.get() == version && session?.version != version) restart(at, version)
        }
    }

    @Synchronized
    fun setVolume(v: Float) {
        if (closed || !v.isFinite()) return
        _volume.value = v.coerceIn(0f, 1f)
        // PropertiesFile publishes in memory now and queues its disk write itself. Do not
        // put this behind audio opening: a replacement player must see the new level at once.
        runCatching { Prefs.edit { it.copy(desktopVolume = _volume.value) } }
            .onFailure { Log.w(TAG, "Couldn't save playback volume", it) }
    }

    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        revision.incrementAndGet()
        _playing.value = false
        commands.execute {
            session?.stop()
            session = null
            synchronized(outputLock) {
                runCatching { output?.close() }
                output = null
            }
        }
        commands.shutdown()
    }

    private fun updatePlayback() {
        // Position and revision form one request; never pair a new revision with an old target.
        val (version, at, playing) = synchronized(this) {
            if (closed) return
            Triple(revision.get(), _position.value, _playing.value)
        }
        val s = session
        if (playing) {
            if (s == null || s.version != version) restart(at, version) else s.resume()
        } else s?.pause()
    }

    private fun restart(at: Double, version: Long) {
        session?.stop()
        if (closed || revision.get() != version) return
        synchronized(this) { if (!closed && revision.get() == version) _error.value = null }
        session = info?.let { Session(it, at, version).also { s -> s.start(paused = !_playing.value) } }
    }

    // ---- one run of the decoders, from one point in the file --------------------------------

    private inner class Session(val info: Info, val from: Double, val version: Long) {
        @Volatile private var stopped = false
        @Volatile private var paused = false
        private val lock = Object()
        private var audio: Process? = null
        private var video: Process? = null
        private var line: SourceDataLine? = null
        private var firstFrame = 0L
        private fun active() = !stopped && !closed && revision.get() == version
        // The wall clock, for files without sound.
        @Volatile private var wallStart = 0L
        @Volatile private var pausedAt = 0L
        @Volatile private var pausedFor = 0L
        private val threads = mutableListOf<Thread>()

        /** Seconds into the file the listener is at now. */
        fun clock(): Double {
            val l = line
            if (info.audio && l != null) return from + (l.longFramePosition - firstFrame) / RATE.toDouble()
            val now = if (paused) pausedAt else System.nanoTime()
            return from + (now - wallStart - pausedFor) / 1e9
        }

        fun start(paused: Boolean) {
            this.paused = paused
            wallStart = System.nanoTime()
            pausedAt = wallStart
            if (info.audio) {
                try {
                    val format = AudioFormat(RATE.toFloat(), 16, 2, true, false)
                    synchronized(outputLock) {
                        line = output ?: openAudio(format).also { output = it }
                        firstFrame = line!!.longFramePosition
                        if (!paused && active()) line!!.start()
                    }
                } catch (e: Exception) {
                    if (!info.video) { fail(e); return }
                    Log.w(TAG, "No audio output; playing video silently", e)
                    line = null
                }
                if (!active()) return
                if (line != null) threads += Thread(::sound, "hamstr-player-sound").apply { isDaemon = true; start() }
            }
            // Opening a missing/slow audio device must not move a silent video's clock ahead.
            wallStart = System.nanoTime()
            pausedAt = wallStart
            if (active() && info.video) threads += Thread(::pictures, "hamstr-player-pictures").apply { isDaemon = true; start() }
            if (!info.audio && !info.video) finished()
        }

        fun pause() {
            synchronized(lock) {
                if (paused) return
                pausedAt = System.nanoTime()
                paused = true
                synchronized(outputLock) { line?.stop() }
            }
        }

        fun resume() {
            synchronized(lock) {
                if (!paused || !active()) return
                pausedFor += System.nanoTime() - pausedAt
                paused = false
                synchronized(outputLock) { line?.start() }
                lock.notifyAll()
            }
        }

        fun stop() {
            synchronized(lock) {
                stopped = true
                lock.notifyAll()
                listOfNotNull(audio, video).forEach { it.destroyForcibly() }
            }
            synchronized(outputLock) { runCatching { line?.stop(); line?.flush() } }
            threads.forEach { it.interrupt() }
        }

        private fun decoder(output: List<String>, sound: Boolean): Process? = synchronized(lock) {
            if (!active()) return@synchronized null
            ffmpeg(output).also { if (sound) audio = it else video = it }
        }

        private fun waitWhilePaused() {
            synchronized(lock) { while (paused && active()) lock.wait(200) }
        }

        private fun sound() {
            runCatching {
            val p = decoder(listOf("-vn", "-ac", "2", "-ar", RATE.toString(), "-f", "s16le", "pipe:1"), sound = true) ?: return
            val l = line ?: return
            val buf = ByteArray(8192)
            val eq = app.hamstr.audio.SoundChain(RATE, 2)
            p.inputStream.use { input ->
                while (active()) {
                    waitWhilePaused()
                    if (!active()) break
                    val n = input.readNBytes(buf, 0, buf.size)
                    if (n == 0) break
                    val count = n - n % 4
                    eq.process16(buf, 0, count)
                    scalePcm16(buf, count, _volume.value)
                    var written = 0
                    while (written < count && active()) {
                        waitWhilePaused()
                        // Never hold the device lock in a blocking write: pause/seek must get it.
                        val sent = synchronized(outputLock) {
                            if (!active() || paused) 0 else {
                                val available = minOf(count - written, l.available()).let { it - it % 4 }
                                if (available > 0) l.write(buf, written, available) else 0
                            }
                        }
                        written += sent
                        publish { _position.value = clock() }
                        if (sent == 0) Thread.sleep(5)
                    }
                }
            }
            // drain() blocks indefinitely on a paused line, preventing a seek from retiring it.
            while (active() && l.available() < l.bufferSize) {
                waitWhilePaused()
                publish { _position.value = clock() }
                Thread.sleep(10)
            }
            finished()
            }.onFailure { if (active()) fail(it) }
        }

        private fun pictures() {
            runCatching {
            val (w, h) = size(info)
            val p = decoder(listOf("-an", "-vf", "fps=$FPS,scale=$w:$h:flags=bilinear", "-pix_fmt", "bgra", "-f", "rawvideo", "pipe:1"), sound = false) ?: return
            val frame = ByteArray(w * h * 4)
            val imageInfo = ImageInfo(w, h, ColorType.BGRA_8888, ColorAlphaType.PREMUL)
            var index = 0L
            DataInputStream(p.inputStream.buffered(frame.size * 2)).use { input ->
                while (active()) {
                    try {
                        input.readFully(frame)
                    } catch (_: EOFException) {
                        break
                    }
                    val at = from + index++ / FPS.toDouble()
                    waitWhilePaused()
                    // Too early: wait for the sound. Far too late: drop it and catch up.
                    var ahead = at - clock()
                    while (ahead > 0.004 && active()) {
                        Thread.sleep((ahead * 1000).toLong().coerceIn(1, 100))
                        waitWhilePaused()
                        ahead = at - clock()
                    }
                    if (!active()) break
                    if (ahead < -0.25) continue
                    val picture = Image.makeRaster(imageInfo, frame, w * 4).toComposeImageBitmap()
                    publish {
                        _frame.value = picture
                        if (line == null) _position.value = at
                    }
                }
            }
            if (line == null) finished()
            }.onFailure { if (active()) fail(it) }
        }

        private fun ffmpeg(output: List<String>): Process =
            ProcessBuilder(listOf(Tools.ffmpeg.path, "-hide_banner", "-loglevel", "error", "-nostdin", "-ss", "%.3f".format(java.util.Locale.ROOT, from), "-i", source) + output)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()

        private fun publish(block: () -> Unit) = synchronized(this@FfPlayer) {
            if (active()) block()
        }

        private fun finished() = publish {
            _playing.value = false
            _ended.value = true
        }

        private fun fail(e: Throwable) = publish {
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
                "format=duration:stream=codec_type,width,height", "-of", "json", source)
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
        private val infoCache = InfoCache()

        /** Gain in PCM works even on audio devices without a MASTER_GAIN control. */
        internal fun scalePcm16(bytes: ByteArray, count: Int, volume: Float) {
            if (volume == 1f) return
            for (i in 0 until count step 2) {
                val sample = ((bytes[i].toInt() and 0xff) or (bytes[i + 1].toInt() shl 8)).toShort()
                val scaled = (sample * volume).toInt()
                bytes[i] = scaled.toByte()
                bytes[i + 1] = (scaled shr 8).toByte()
            }
        }

        /** The picture's size scaled into 1280×720, both even, as scalers want. */
        fun size(info: Info): Pair<Int, Int> {
            if (info.width <= 0 || info.height <= 0) return 1280 to 720
            val scale = minOf(1.0, 1280.0 / info.width, 720.0 / info.height)
            fun even(v: Double) = max(2, (v.toInt() / 2) * 2)
            return even(info.width * scale) to even(info.height * scale)
        }
    }
}
