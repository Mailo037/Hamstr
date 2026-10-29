package app.hamstr.data

import java.io.File

/** How downloaded files are named, the same on every platform. */
object FileNames {
    /** yt-dlp's "Title [id] 720p" becomes "Title 720p"; the ID only kept two downloads apart. */
    fun cleanName(stem: String): String =
        Regex("""^(.*\S) \[[^\[\]]+\]((?: [\w-]+)?)$""").find(stem)?.let { it.groupValues[1] + it.groupValues[2] } ?: stem

    /** [name] in [dir], or "name (1).ext" and so on when it is taken. */
    fun freeName(dir: File, name: String): File {
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        var f = File(dir, name)
        var n = 1
        while (f.exists()) f = File(dir, "$base ($n)$ext").also { n++ }
        return f
    }

    /** Renames a finished file to its clean name, next to any file that already has it. */
    fun tidy(file: File): File {
        val clean = cleanName(file.nameWithoutExtension)
        if (clean == file.nameWithoutExtension) return file
        val target = freeName(file.parentFile ?: return file, if (file.extension.isEmpty()) clean else "$clean.${file.extension}")
        return if (file.renameTo(target)) target else file
    }
}
