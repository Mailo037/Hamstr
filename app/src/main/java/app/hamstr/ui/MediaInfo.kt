package app.hamstr.ui

import android.media.MediaMetadataRetriever
import app.hamstr.res.*
import org.jetbrains.compose.resources.StringResource
import java.io.File

/** What the container itself reports, for the file info; empty for files Android can't read. */
fun readMediaInfo(file: File): List<Pair<StringResource, String>> = runCatching {
    val r = MediaMetadataRetriever()
    try {
        r.setDataSource(file.absolutePath)
        fun key(k: Int) = r.extractMetadata(k)?.trim()?.takeIf { it.isNotEmpty() }
        val w = key(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
        val h = key(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
        val tracks = listOfNotNull(
            key(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)?.let { "video" },
            key(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)?.let { "audio" },
        )
        listOfNotNull(
            key(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)?.let { Res.string.info_type to it },
            if (w != null && h != null) Res.string.info_resolution to "$w × $h" else null,
            key(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.takeIf { it != "0" }?.let { Res.string.info_rotation to "$it°" },
            key(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull()?.let { Res.string.info_bitrate to "${it / 1000} kbit/s" },
            tracks.takeIf { it.isNotEmpty() }?.let { Res.string.info_tracks to it.joinToString(" + ") },
            key(MediaMetadataRetriever.METADATA_KEY_TITLE)?.let { Res.string.info_tag_title to it },
            key(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.let { Res.string.info_tag_artist to it },
            key(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.let { Res.string.info_tag_album to it },
            key(MediaMetadataRetriever.METADATA_KEY_DATE)?.let { Res.string.info_tag_date to it },
            key(MediaMetadataRetriever.METADATA_KEY_GENRE)?.let { Res.string.info_tag_genre to it },
        )
    } finally {
        r.release()
    }
}.getOrDefault(emptyList())
