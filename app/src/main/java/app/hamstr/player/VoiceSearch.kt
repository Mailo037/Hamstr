package app.hamstr.player

import android.os.Bundle
import android.provider.MediaStore
import app.hamstr.data.DownloadStore
import app.hamstr.data.DownloadTask
import app.hamstr.data.Playlists
import app.hamstr.data.Status
import java.io.File
import java.text.Normalizer

/**
 * What "Hey Google, play … on Hamstr" (or Bixby, or any assistant) means in the library:
 * a playlist, everything by an artist, or one song, whichever matches the words best. Nothing
 * but filler words ("play my music", "spiel meine Downloads") plays all songs shuffled.
 */
object VoiceSearch {
    /** What the assistant said it is looking for, when it says (MediaStore.EXTRA_MEDIA_FOCUS). */
    enum class Focus { Any, Artist, Album, Playlist, Title }

    data class Hit(val start: DownloadTask, val queue: List<DownloadTask>)

    private enum class Source { Playlist, Artist, Title }

    /**
     * [library]: the finished files that can play. [playlists]: names with their files, the
     * user's own and the downloaded ones. Null when nothing matches well enough.
     */
    fun find(
        query: String?,
        library: List<DownloadTask>,
        playlists: List<Pair<String, List<DownloadTask>>> = emptyList(),
        focus: Focus = Focus.Any,
        artist: String? = null,
        album: String? = null,
        title: String? = null,
        shuffle: (List<DownloadTask>) -> List<DownloadTask> = { it.shuffled() },
    ): Hit? {
        val songs = library.filter { kindOf(it) == Kind.Audio }
        // The assistant's own fields first, then the words as said.
        val asked = when (focus) {
            Focus.Artist -> artist
            Focus.Album, Focus.Playlist -> album
            Focus.Title -> title
            Focus.Any -> null
        }?.takeIf { it.isNotBlank() } ?: query.orEmpty()
        var words = tokens(asked)
        var kinds = when (focus) {
            Focus.Artist -> setOf(Source.Artist)
            Focus.Album, Focus.Playlist -> setOf(Source.Playlist, Source.Artist)
            Focus.Title -> setOf(Source.Title)
            Focus.Any -> Source.entries.toSet()
        }
        // "music by Queen", "Musik von Queen": the artist.
        val by = words.indexOfFirst { it in BY }
        if (focus == Focus.Any && by >= 0 && by < words.size - 1 && words.take(by).all { it in FILLER }) {
            words = words.drop(by + 1)
            kinds = setOf(Source.Artist)
        }
        if (words.all { it in FILLER }) {
            val pool = songs.ifEmpty { library }
            if (pool.isEmpty()) return null
            val order = shuffle(pool)
            return Hit(order.first(), order)
        }
        val said = words.filter { it !in LEAD }.ifEmpty { words }

        var best: Pair<Int, Hit>? = null
        fun offer(score: Int, hit: () -> Hit?) {
            if (score >= MIN && score > (best?.first ?: 0)) hit()?.let { best = score to it }
        }
        if (Source.Playlist in kinds) for ((name, items) in playlists) {
            val playable = items.filter { t -> library.any { it.id == t.id } }
            offer(score(said, name)) { playable.firstOrNull()?.let { Hit(it, playable) } }
        }
        if (Source.Artist in kinds) {
            for ((name, items) in library.filter { it.uploader != null }.groupBy { it.uploader!! }) {
                val mine = items.filter { kindOf(it) == Kind.Audio }.ifEmpty { items }.sortedBy { it.createdAt }
                offer(score(said, name)) { Hit(mine.first(), mine) }
            }
        }
        if (Source.Title in kinds) for (t in library) {
            offer(score(said, t.title)) { Hit(t, Playback.queueFor(t, library)) }
        }
        return best?.second
    }

    /** [find] over what Hamstr has now: finished files that are still there, and every playlist. */
    fun inLibrary(query: String?, extras: Bundle? = null): Hit? {
        val tasks = DownloadStore.tasks.value
        val library = tasks.filter { t ->
            t.status == Status.Done && !t.hidden && kindOf(t).let { it == Kind.Audio || it == Kind.Video } &&
                t.filePath?.let(::File)?.isFile == true
        }
        val playlists = Playlists.all(tasks, Playlists.lists.value).map { it.name to it.items }
        val focus = when (extras?.getString(MediaStore.EXTRA_MEDIA_FOCUS)) {
            MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE -> Focus.Artist
            MediaStore.Audio.Albums.ENTRY_CONTENT_TYPE -> Focus.Album
            MediaStore.Audio.Playlists.ENTRY_CONTENT_TYPE -> Focus.Playlist
            MediaStore.Audio.Media.ENTRY_CONTENT_TYPE -> Focus.Title
            else -> Focus.Any
        }
        return find(
            query, library, playlists, focus,
            artist = extras?.getString(MediaStore.EXTRA_MEDIA_ARTIST),
            album = extras?.getString(MediaStore.EXTRA_MEDIA_ALBUM) ?: extras?.getString("android.intent.extra.playlist"),
            title = extras?.getString(MediaStore.EXTRA_MEDIA_TITLE),
        )
    }

    /** 100 for the same words, less the fewer of the said words the name has. */
    internal fun score(said: List<String>, name: String): Int {
        val have = tokens(name)
        if (have.isEmpty() || said.isEmpty()) return 0
        if (have == said) return 100
        val text = " ${have.joinToString(" ")} "
        if (text.contains(" ${said.joinToString(" ")} ")) return 80 - (have.size - said.size).coerceAtMost(10)
        val found = said.count { w -> have.any { it == w || (w.length >= 4 && it.startsWith(w)) } }
        if (found == said.size) return 60
        return 40 * found / said.size
    }

    internal fun tokens(text: String): List<String> =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace('ß', 's')
            // "Don’t" is said as one word.
            .replace(Regex("['’`´]"), "")
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.isNotEmpty() }

    private const val MIN = 40

    private val BY = setOf("by", "von", "de", "di", "par", "door", "od")

    // Words that leave the request open: play whatever is in Hamstr.
    private val FILLER = setOf(
        "play", "spiel", "spiele", "abspielen", "please", "bitte", "something", "anything", "everything", "all",
        "my", "mine", "the", "some", "a", "music", "songs", "song", "tracks", "downloads", "download", "downloaded",
        "saved", "stash", "library", "hamstr", "hamster", "on", "in", "with", "from", "what", "i", "have", "ive",
        "meine", "meinen", "mein", "meiner", "die", "der", "das", "den", "etwas", "was", "irgendwas", "alles", "alle",
        "musik", "lieder", "lied", "titel", "heruntergeladen", "heruntergeladene", "heruntergeladenen", "gespeichert",
        "gespeicherte", "gespeicherten", "bibliothek", "auf", "mit", "aus", "ich", "habe", "hab", "von", "mir",
    )

    // Said before a name but not part of it.
    private val LEAD = setOf("play", "spiel", "spiele", "please", "bitte", "the", "die", "der", "das", "den", "song", "lied", "playlist", "album")
}
