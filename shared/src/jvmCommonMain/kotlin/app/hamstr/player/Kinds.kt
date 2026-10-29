package app.hamstr.player

import app.hamstr.data.DownloadTask
import app.hamstr.data.Mode
import java.io.File
import java.net.URLConnection

enum class Kind { Video, Audio, Image, Other }

/** What a stashed file is, by its extension. */
fun kindOf(file: File): Kind {
    val mime = mimeOf(file)
    return when {
        mime.startsWith("video/") -> Kind.Video
        mime.startsWith("audio/") -> Kind.Audio
        mime.startsWith("image/") -> Kind.Image
        else -> Kind.Other
    }
}

fun mimeOf(file: File): String = when (val ext = file.extension.lowercase()) {
    "opus", "ogg", "oga" -> "audio/ogg"
    "m4a" -> "audio/mp4"
    "mkv" -> "video/x-matroska"
    "webm" -> "video/webm"
    else -> URLConnection.guessContentTypeFromName("x.$ext") ?: "application/octet-stream"
}

/**
 * What a download is: by its extension, except that a song is a song even in an .mp4 or
 * .webm (SoundCloud and other audio sites hand those out when asked for a video).
 */
fun kindOf(t: DownloadTask): Kind {
    val file = t.filePath?.let(::File) ?: return Kind.Other
    val kind = kindOf(file)
    return if (kind == Kind.Video && t.choice.mode == Mode.Audio) Kind.Audio else kind
}
