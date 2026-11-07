package app.hamstr.nearby

import app.hamstr.data.DownloadTask
import app.hamstr.data.Mode
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.Base64
import java.util.UUID

/** Only media details travel: never another device's paths, ids, download state or commands. */
class FileMetadata private constructor(private val data: JSONObject) {
    fun applyTo(task: DownloadTask): DownloadTask = task.copy(
        url = web(data.text("url")) ?: task.url,
        title = data.text("title") ?: task.title,
        source = data.text("source") ?: task.source,
        uploader = data.text("uploader") ?: task.uploader,
        // An embedded cover extracted by the importer works offline; a remote URL is a fallback.
        // A transferred cover, when present, is installed separately and takes priority over both.
        thumbnail = task.thumbnail ?: web(data.text("thumbnail")),
        durationSec = data.positiveInt("duration") ?: task.durationSec,
        videoHeight = data.positiveInt("videoHeight") ?: task.videoHeight,
        mediaKey = data.text("mediaKey") ?: task.mediaKey,
        channelUrl = web(data.text("channelUrl")) ?: task.channelUrl,
        choice = task.choice.copy(
            mode = runCatching { Mode.valueOf(data.optString("mode")) }.getOrDefault(task.choice.mode),
            height = data.positiveInt("height") ?: task.choice.height,
        ),
    )

    internal fun json() = data

    companion object {
        internal fun read(data: JSONObject) = FileMetadata(data)

        fun from(task: DownloadTask) = FileMetadata(JSONObject().apply {
            put("url", web(task.url)); put("title", task.title.take(4096))
            put("source", task.source?.take(4096)); put("uploader", task.uploader?.take(4096))
            put("thumbnail", web(task.thumbnail)); put("duration", task.durationSec)
            put("videoHeight", task.videoHeight); put("mediaKey", task.mediaKey?.take(4096))
            put("channelUrl", web(task.channelUrl)); put("mode", task.choice.mode.name)
            put("height", task.choice.height)
        })

        private fun JSONObject.text(key: String) = opt(key)?.let { it as? String }?.take(4096)?.takeIf { it.isNotBlank() }
        private fun JSONObject.positiveInt(key: String) = optLong(key).takeIf { it in 1..Int.MAX_VALUE.toLong() }?.toInt()
        private fun web(value: String?): String? = value?.takeIf {
            it.length <= 4096 && runCatching { URI(it).let { u -> u.scheme in listOf("http", "https") && !u.host.isNullOrBlank() } }.getOrDefault(false)
        }
    }
}

/** A complete received file and its optional, negotiated attachment. The importer owns both files. */
data class ReceivedFile(val file: File, val name: String, val metadata: FileMetadata? = null, val cover: File? = null) {
    /** Keep artwork in app-owned storage, under a fresh name, not a path supplied by the sender. */
    fun keepCover(dir: File): String? {
        val part = cover ?: return null
        val target = File(dir, "nearby-${UUID.randomUUID()}.jpg")
        return runCatching {
            check(dir.isDirectory || dir.mkdirs())
            if (!part.renameTo(target)) { part.copyTo(target); part.delete() }
            target.toURI().toString()
        }.onFailure { target.delete() }.getOrNull()
    }

    fun delete() { file.delete(); cover?.delete() }
}

/**
 * Metadata v1: a bounded JSON object with media details and optional base64 cover, sent as a
 * separate framed stream after each file. The signed offer's `metadata: 1` and receipt's
 * `x_metadata: 1` must both be present; old apps therefore keep exactly their file-only stream.
 */
internal object FileAttachment {
    const val MAX_COVER = 4 * 1024 * 1024
    const val MAX_BYTES = 6 * 1024 * 1024

    fun encode(task: DownloadTask?): ByteArray {
        val data = JSONObject()
        if (task != null) {
            data.put("media", FileMetadata.from(task).json())
            // Best effort: an unavailable/oversized cover must not prevent sharing the media.
            runCatching { cover(task.thumbnail) }.getOrNull()?.let { data.put("cover", Base64.getEncoder().encodeToString(it)) }
        }
        return data.toString().toByteArray(Charsets.UTF_8).also { require(it.size <= MAX_BYTES) }
    }

    fun decode(bytes: ByteArray, file: ReceivedFile, dir: File): ReceivedFile {
        require(bytes.size <= MAX_BYTES)
        val data = JSONObject(String(bytes, Charsets.UTF_8))
        val art = data.optString("cover").takeIf { it.isNotEmpty() }?.let {
            Base64.getDecoder().decode(it).also { bytes -> require(bytes.isNotEmpty() && bytes.size <= MAX_COVER) }
        }
        val cover = art?.let {
            File(dir, "${UUID.randomUUID()}.cover.part").also { target ->
                try { target.writeBytes(it) } catch (e: Exception) { target.delete(); throw e }
            }
        }
        return file.copy(metadata = data.optJSONObject("media")?.let(FileMetadata::read), cover = cover)
    }

    private fun cover(location: String?): ByteArray? {
        if (location.isNullOrBlank()) return null
        val local = if (location.startsWith("file:")) runCatching { File(URI(location)) }.getOrNull()
            else File(location).takeIf { it.isAbsolute }
        if (local != null) {
            if (!local.isFile || local.length() !in 1..MAX_COVER.toLong()) return null
            return local.inputStream().use { it.readNBytesBounded() }
        }
        val uri = URI(location)
        return when (uri.scheme) {
            "http", "https" -> (uri.toURL().openConnection() as HttpURLConnection).run {
                connectTimeout = 5_000; readTimeout = 5_000; instanceFollowRedirects = false
                try {
                    if (responseCode !in 200..299 || contentLengthLong > MAX_COVER) return null
                    inputStream.use { it.readNBytesBounded() }
                } finally { disconnect() }
            }

            else -> null
        }
    }

    private fun java.io.InputStream.readNBytesBounded(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = read(buf)
            if (n < 0) break
            require(out.size() + n <= MAX_COVER)
            out.write(buf, 0, n)
        }
        return out.toByteArray().also { require(it.isNotEmpty()) }
    }
}
