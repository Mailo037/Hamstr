package app.hamstr.engine

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder

/** Recognises ordinary HTTP files before either engine adds media post-processors. */
internal object GenericFile {
    data class Info(val name: String, val size: Long)

    fun probe(url: String): Info? {
        if (!url.startsWith("https://") && !url.startsWith("http://")) return null
        fun connect(method: String) = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = 10_000
            if (method == "GET") setRequestProperty("Range", "bytes=0-0")
        }
        var connection = connect("HEAD")
        try {
            if (connection.responseCode in listOf(405, 501)) {
                connection.disconnect()
                connection = connect("GET")
            }
            if (connection.responseCode !in 200..299) return null
            val name = fileName(connection.url.toString(), connection.getHeaderField("Content-Disposition"), connection.contentType)
                ?: return null
            val size = connection.getHeaderField("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
                ?: connection.getHeaderFieldLong("Content-Length", 0)
            return Info(name, size.coerceAtLeast(0))
        } finally {
            connection.disconnect()
        }
    }

    internal fun fileName(url: String, disposition: String?, contentType: String?): String? {
        val mime = contentType.orEmpty().substringBefore(';').trim().lowercase()
        // Pages and manifests still need extraction, even when their URL looks like a file.
        if (mime.contains("html") || mime.contains("mpegurl") || mime == "application/dash+xml") return null
        val encoded = Regex("""filename\*\s*=\s*UTF-8''([^;]+)""", RegexOption.IGNORE_CASE)
            .find(disposition.orEmpty())?.groupValues?.get(1)?.trim()?.trim('"')
        val plain = Regex("""filename\s*=\s*(?:"([^"]+)"|([^;]+))""", RegexOption.IGNORE_CASE)
            .find(disposition.orEmpty())?.let { it.groupValues[1].ifEmpty { it.groupValues[2].trim() } }
        fun decode(value: String) = runCatching { URLDecoder.decode(value.replace("+", "%2B"), "UTF-8") }.getOrDefault(value)
        val name = (encoded?.let(::decode) ?: plain ?: decode(URL(url).path.substringAfterLast('/')))
            .substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("""[\p{Cntrl}:*?"<>|]"""), "_").trim().trim('.')
        val ext = name.substringAfterLast('.', "").lowercase()
        if (mime.startsWith("audio/") || mime.startsWith("video/") || ext in audiovisual) return null
        val binary = mime == "application/octet-stream" || mime in setOf(
            "application/x-msdownload", "application/vnd.microsoft.portable-executable",
            "application/zip", "application/x-7z-compressed", "application/x-rar-compressed",
            "application/pdf", "application/gzip", "application/x-android-package-archive",
        )
        val attachment = disposition.orEmpty().trimStart().startsWith("attachment", ignoreCase = true)
        if (!binary && !attachment && ext !in files && !mime.startsWith("image/")) return null
        val fallbackExt = when (mime) {
            "application/pdf" -> "pdf"
            "application/zip" -> "zip"
            "application/gzip" -> "gz"
            "image/jpeg" -> "jpg"
            "image/png" -> "png"
            else -> "bin"
        }
        return if (ext.isEmpty()) "${name.ifEmpty { "download" }}.$fallbackExt" else name
    }

    private val audiovisual = setOf(
        "mp4", "m4v", "webm", "mkv", "mov", "avi", "ts", "mts", "m2ts", "flv", "wmv", "mpg", "mpeg",
        "3gp", "3g2", "ogv", "vob", "mxf", "asf", "m2v",
        "mp3", "mp2", "m4a", "m4b", "mka", "weba", "aac", "opus", "ogg", "oga", "flac", "wav",
        "aif", "aiff", "alac", "ape", "amr", "ac3", "eac3", "dts", "au", "wma", "m3u8", "mpd",
    )
    private val files = setOf("exe", "msi", "msix", "dmg", "pkg", "apk", "aab", "zip", "7z", "rar", "tar", "gz", "bz2", "xz", "pdf", "txt", "csv", "docx", "xlsx", "pptx", "iso")
}
