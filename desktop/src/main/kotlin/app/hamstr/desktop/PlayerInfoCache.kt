package app.hamstr.desktop

import java.io.File

/** Reopening unchanged local media need not launch ffprobe again. Streams are never cached. */
internal class InfoCache(private val capacity: Int = 32) {
    private data class Key(val path: String, val size: Long, val modified: Long)
    private val entries = LinkedHashMap<Key, FfPlayer.Info>(16, 0.75f, true)

    fun get(file: File, load: () -> FfPlayer.Info?): FfPlayer.Info? {
        val key = Key(file.absoluteFile.normalize().path, file.length(), file.lastModified())
        synchronized(entries) { entries[key]?.let { return it } }
        val info = load() ?: return null
        synchronized(entries) {
            entries[key] = info
            while (entries.size > capacity) entries.remove(entries.keys.first())
        }
        return info
    }
}
