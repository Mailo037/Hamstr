package app.hamstr.demo

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import app.hamstr.MainActivity
import app.hamstr.data.Choice
import app.hamstr.data.DownloadStore
import app.hamstr.data.Playlists
import app.hamstr.data.UserList
import app.hamstr.data.DownloadTask
import app.hamstr.data.Mode
import app.hamstr.data.Status
import app.hamstr.engine.Cheek
import app.hamstr.engine.Engine
import app.hamstr.engine.MediaInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import app.hamstr.nearby.DeviceKind
import app.hamstr.nearby.Nearby
import app.hamstr.nearby.Via
import java.io.File
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Debug builds only: fills Hamstr with demo downloads for screenshots and demos, and puts
 * the real list back afterwards.
 *
 *     adb shell am start -n app.hamstr/.demo.DemoActivity --es seed default
 *     adb shell am start -n app.hamstr/.demo.DemoActivity --es seed /sdcard/Download/demo.json
 *     adb shell am start -n app.hamstr/.demo.DemoActivity --es seed off
 *     adb shell am start -n app.hamstr/.demo.DemoActivity --es nearby on
 *
 * "nearby on" makes up a few Hamstr devices around for a minute, for the send sheet's picture.
 *
 * A seed is a JSON array, see assets/demo/default.json. An entry with "status": "share"
 * stays off the list; sharing its link opens the share sheet with its details, offline. Thumbnails are drawn and media
 * files are made with the bundled FFmpeg, so playback, File info and Quality all work
 * without the network. Nothing goes into the shared Download folder.
 */
class DemoActivity : Activity() {
    private val scope: CoroutineScope = MainScope()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getStringExtra("nearby") == "on") {
            nearby()
            finish()
            return
        }
        val seed = intent.getStringExtra("seed") ?: "default"
        scope.launch {
            val message = runCatching { withContext(Dispatchers.IO) { if (seed == "off") stop() else start(seed) } }
                .getOrElse { Log.e(TAG, "Demo mode failed", it); "Demo mode failed: ${it.message}" }
            Toast.makeText(this@DemoActivity, message, Toast.LENGTH_SHORT).show()
            startActivity(Intent(this@DemoActivity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            finish()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private val backup get() = File(filesDir, "downloads.real.json")
    private val listsBackup get() = File(filesDir, "playlists.real.json")
    private val media get() = File(getExternalFilesDir(null), "demo")

    private suspend fun start(seed: String): String {
        val items = JSONArray(
            if (seed == "default" || !seed.contains('/')) assets.open("demo/$seed.json").bufferedReader().use { it.readText() }
            else File(seed).readText(),
        )
        // The first start keeps the real list; later seeds replace only the demo.
        if (!backup.exists()) {
            val live = File(filesDir, "downloads.json")
            if (live.exists()) live.copyTo(backup) else backup.writeText("[]")
            val lists = File(filesDir, "playlists.json")
            if (lists.exists()) lists.copyTo(listsBackup, overwrite = true) else listsBackup.writeText("[]")
        }
        media.deleteRecursively()
        media.mkdirs()
        Engine.awaitReady()
        val now = System.currentTimeMillis()
        val tasks = (0 until items.length()).flatMap { i -> tasks(items.getJSONObject(i), i, now) }
        DownloadStore.replace(tasks)
        // Entries with a "playlist" name go into that list, in seed order. A demo started
        // before playlists existed has no copy of them, so they stay as they are.
        val lists = (0 until items.length()).mapNotNull { i -> items.getJSONObject(i).optString("playlist").ifEmpty { null }?.let { it to "demo-$i" } }
            .filter { (_, id) -> tasks.any { it.id == id } }
            .groupBy({ it.first }, { it.second })
            .entries.mapIndexed { i, (name, ids) -> UserList("demo-list-$i", name, ids, now - i) }
        if (listsBackup.exists()) Playlists.replace(lists)
        // tools/demo_screens.py waits for this before it starts taking pictures.
        File(media, ".ready").writeText(tasks.size.toString())
        return "Demo mode: ${tasks.size} entries"
    }

    /** Made-up devices, kept "around" for a minute; nothing is remembered, so they leave no trace. */
    private fun nearby() {
        fun key() = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair().public.encoded
        val devices = listOf(
            Triple("Studio PC", DeviceKind.Desktop, setOf(Via.Wifi)),
            Triple("Pixel 9", DeviceKind.Phone, setOf(Via.Wifi, Via.Bluetooth)),
            Triple("Old Galaxy", DeviceKind.Phone, setOf(Via.Bluetooth)),
        ).map { (name, kind, via) -> key().let { k -> Triple(Nearby.idOf(k) to Base64.getEncoder().encodeToString(k), name to kind, via) } }
        Thread {
            repeat(20) {
                devices.forEach { (who, what, via) -> via.forEach { v -> Nearby.seen(who.first, what.first, what.second, who.second, v) } }
                Thread.sleep(3_000)
            }
        }.start()
    }

    private fun stop(): String {
        if (!backup.exists()) return "Demo mode is off"
        DownloadStore.replace(DownloadStore.decode(backup.readText()))
        backup.delete()
        if (listsBackup.exists()) Playlists.replace(Playlists.decode(listsBackup.readText()))
        listsBackup.delete()
        media.deleteRecursively()
        return "Demo mode off, your list is back"
    }

    /** One seed entry: a file per quality in "heights" (the first is the original), or a download in progress. */
    private fun tasks(o: JSONObject, index: Int, now: Long): List<DownloadTask> {
        val audio = o.optString("kind") == "audio"
        val title = o.getString("title")
        val duration = o.optInt("duration", 60)
        val hue = o.optDouble("hue", Random(index).nextDouble(0.0, 360.0)).toFloat()
        val thumb = o.optString("thumbnail").ifEmpty { null }?.let { Uri.fromFile(File(it)).toString() }
            ?: Uri.fromFile(poster(File(media, "thumb-$index.png"), hue, index)).toString()
        val heights = if (audio) listOf(0) else o.optJSONArray("heights")?.let { a -> List(a.length()) { a.getInt(it) } } ?: listOf(1080)
        // Every link counts as resolved, so the share and Quality sheets work offline. Videos
        // offer the usual ladder; "share" entries stay off the list and only feed the share sheet.
        val offered = if (audio) emptyList() else (heights + listOf(2160, 1080, 720, 480)).distinct().sortedDescending()
        val bytes = offered.associateWith { h -> duration * 1000L * (if (h >= 2160) 2500 else if (h >= 1080) 600 else if (h >= 720) 330 else 160) }
        Engine.remember(MediaInfo(
            url = o.optString("url", "https://example.com/watch/$index"), title = title, thumbnail = thumb,
            source = o.optString("site", "Youtube"), durationSec = duration, uploader = o.optString("uploader").ifEmpty { null },
            heights = offered, approxBytes = bytes.values.maxOrNull() ?: (duration * 16_000L), videoBytes = bytes,
            audioBytes = duration * 16_000L, id = "demo$index",
        ))
        if (o.optString("status") == "share") return emptyList()
        val status = Status.valueOf(o.optString("status", "Done").replaceFirstChar { it.uppercase() })
        val created = now - o.optLong("ageMinutes", 30L * (index + 1)) * 60_000
        val id = "demo-$index"
        return heights.mapIndexed { v, height ->
            val choice = Choice(if (audio) Mode.Audio else Mode.Video, height.takeIf { it > 0 }, "mp4", "m4a", false)
            val base = DownloadTask(
                id = if (v == 0) id else "$id-$height",
                url = o.optString("url", "https://example.com/watch/$index"),
                title = title,
                thumbnail = thumb,
                source = o.optString("site", "Youtube"),
                durationSec = duration,
                choice = choice,
                uploader = o.optString("uploader").ifEmpty { null },
                mediaKey = "${o.optString("site", "Youtube")}:demo$index",
                createdAt = created - v * 60_000L,
                versionOf = if (v == 0) null else id,
            )
            when (status) {
                Status.Done -> {
                    val file = File(media, safe(title) + (if (audio) ".m4a" else " [${height}p].mp4"))
                    render(file, thumb, audio, height, duration)
                    base.copy(status = Status.Done, filePath = file.absolutePath, sizeBytes = file.length(), progress = 1f,
                        finishedAt = created, videoHeight = height)
                }
                else -> base.copy(
                    status = status, progress = o.optDouble("progress", 0.4).toFloat(),
                    error = o.optString("error").ifEmpty { null }, sizeBytes = o.optLong("size", 0),
                )
            }
        }
    }

    /** At most 30 seconds of the poster with a quiet tone, through the FFmpeg Hamstr ships; lists keep the seed's length. */
    private fun render(out: File, thumb: String, audio: Boolean, height: Int, duration: Int) {
        val seconds = duration.coerceIn(5, 30).toString()
        val image = Uri.parse(thumb).path!!
        val args = if (audio) listOf(
            "-f", "lavfi", "-i", "sine=frequency=220:sample_rate=44100:duration=$seconds",
            "-c:a", "aac", "-b:a", "64k", out.absolutePath,
        ) else listOf(
            "-loop", "1", "-framerate", "2", "-i", image,
            "-f", "lavfi", "-i", "sine=frequency=330:sample_rate=44100",
            "-t", seconds, "-vf", "scale=-2:$height,format=yuv420p",
            "-c:v", "mpeg4", "-q:v", "5", "-c:a", "aac", "-b:a", "64k", "-shortest", out.absolutePath,
        )
        val process = ProcessBuilder(listOf(Cheek.ffmpeg.absolutePath, "-y", "-loglevel", "error") + args)
            .redirectErrorStream(true)
            .apply { environment()["LD_LIBRARY_PATH"] = Cheek.libraryPath }
            .start()
        val log = process.inputStream.bufferedReader().use { it.readText() }
        check(process.waitFor(120, TimeUnit.SECONDS) && process.exitValue() == 0 && out.length() > 0) { "FFmpeg: $log" }
    }

    /** A 16:9 poster in Hamstr's raster look: a tinted glow under falling characters. */
    private fun poster(out: File, hue: Float, seed: Int): File {
        val w = 1280
        val h = 720
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val deep = Color.HSVToColor(floatArrayOf(hue, 0.55f, 0.22f))
        val bright = Color.HSVToColor(floatArrayOf(hue, 0.75f, 0.95f))
        canvas.drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), deep, Color.rgb(14, 14, 14), Shader.TileMode.CLAMP) })
        val rnd = Random(seed)
        val cx = w * rnd.nextFloat()
        val cy = h * (0.2f + 0.6f * rnd.nextFloat())
        canvas.drawCircle(cx, cy, 520f, Paint().apply {
            shader = RadialGradient(cx, cy, 520f, Color.argb(150, Color.red(bright), Color.green(bright), Color.blue(bright)), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        })
        val glyphs = " .:-=+*#"
        val cell = 30f
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE; textSize = 24f; textAlign = Paint.Align.CENTER }
        var y = cell
        while (y < h) {
            var x = cell / 2
            while (x < w) {
                val near = 1f - (kotlin.math.hypot(x - cx, y - cy) / 700f).coerceIn(0f, 1f)
                if (rnd.nextFloat() < 0.25f + 0.6f * near) {
                    text.color = if (rnd.nextFloat() < 0.06f) bright else Color.WHITE
                    text.alpha = (20 + 150 * near * rnd.nextFloat()).toInt()
                    canvas.drawText(glyphs[(1 + near * 6 * rnd.nextFloat()).toInt().coerceIn(1, 7)].toString(), x, y, text)
                }
                x += cell
            }
            y += cell
        }
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return out
    }

    private fun safe(name: String) = name.replace(Regex("""[\\/:*?"<>|]"""), "_").take(80)

    private companion object {
        const val TAG = "HamstrDemo"
    }
}
