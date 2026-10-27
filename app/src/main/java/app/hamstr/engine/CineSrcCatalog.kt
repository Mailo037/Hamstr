package app.hamstr.engine

import android.content.Context
import app.hamstr.data.Choice
import app.hamstr.data.DownloadStore
import app.hamstr.data.DownloadTask
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

internal data class CineSrcSeasonSummary(val number: Int, val name: String, val count: Int)
internal data class CineSrcSeries(val id: Long, val title: String, val seasons: List<CineSrcSeasonSummary>)
internal data class CineSrcEpisode(val number: Int, val title: String, val durationSec: Int, val thumbnail: String?)
internal data class CineSrcSeason(val seriesId: Long, val number: Int, val episodes: List<CineSrcEpisode>) {
    fun target(episode: CineSrcEpisode) = CineSrcTarget(seriesId, number, episode.number)
}

/** The public metadata endpoints used by CineSrc's own episode selector. No API key. */
internal object CineSrcCatalog {
    suspend fun series(id: Long): CineSrcSeries {
        require(id > 0)
        return parseSeries(id, json("tv/$id"))
    }

    suspend fun season(id: Long, season: Int): CineSrcSeason {
        require(id > 0 && season >= 0)
        return parseSeason(id, season, json("tv/$id/season/$season"))
    }

    internal fun parseSeries(id: Long, json: JSONObject): CineSrcSeries {
        check(json.getLong("id") == id) { "The series response did not match the requested ID" }
        val seasons = json.getJSONArray("seasons")
        return CineSrcSeries(id, json.getString("name"), (0 until seasons.length()).mapNotNull {
            val s = seasons.getJSONObject(it)
            val number = s.optInt("season_number", -1)
            if (number < 0 || s.optInt("episode_count") <= 0) null
            else CineSrcSeasonSummary(number, s.optString("name"), s.getInt("episode_count"))
        }.distinctBy { it.number }.sortedBy { it.number })
    }

    internal fun parseSeason(id: Long, season: Int, json: JSONObject): CineSrcSeason {
        check(json.getInt("season_number") == season) { "The episode response did not match the selected season" }
        val episodes = json.getJSONArray("episodes")
        return CineSrcSeason(id, season, (0 until episodes.length()).mapNotNull {
            val e = episodes.getJSONObject(it)
            val number = e.optInt("episode_number", -1)
            if (number <= 0 || e.optInt("season_number", season) != season || e.optLong("show_id", id) != id) null
            else CineSrcEpisode(number, e.optString("name"), e.optInt("runtime").coerceAtLeast(0) * 60,
                e.optString("still_path").takeIf { path -> path.startsWith('/') && !path.startsWith("//") }?.let { path -> "https://image.tmdb.org/t/p/w500$path" })
        }.distinctBy { it.number }.sortedBy { it.number })
    }

    private suspend fun json(path: String): JSONObject = runInterruptible(Dispatchers.IO) {
        val connection = URL("https://cinesrc.st/api/tmdb/$path").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Accept", "application/json")
            check(connection.responseCode in 200..299) { "CineSrc metadata: HTTP ${connection.responseCode}" }
            val text = connection.inputStream.bufferedReader().use { reader ->
                val out = StringBuilder()
                val buffer = CharArray(8192)
                while (true) {
                    val n = reader.read(buffer)
                    if (n < 0) break
                    check(out.length + n <= 4 * 1024 * 1024) { "CineSrc metadata is too large" }
                    out.append(buffer, 0, n)
                }
                out.toString()
            }
            JSONObject(text)
        } finally { connection.disconnect() }
    }

    /** Each selected episode is an independent resumable queue item in one season group. */
    fun enqueue(context: Context, series: CineSrcSeries, season: CineSrcSeason, selected: Set<Int>, choice: Choice) {
        val groupTitle = series.title + " · " + context.getString(app.hamstr.R.string.cinesrc_season, season.number)
        tasksFor(series, season, selected, choice, groupTitle).forEach(DownloadStore::add)
        DownloadService.send(context, DownloadService.ACTION_START)
    }

    internal fun tasksFor(series: CineSrcSeries, season: CineSrcSeason, selected: Set<Int>, choice: Choice, groupTitle: String): List<DownloadTask> {
        val episodes = season.episodes.filter { it.number in selected }
        require(episodes.isNotEmpty() && episodes.size == selected.size && series.id == season.seriesId)
        val group = UUID.randomUUID().toString()
        return episodes.map { episode ->
            val target = season.target(episode)
            DownloadTask(url = target.embed,
                title = "${series.title} S${season.number.toString().padStart(2, '0')}E${episode.number.toString().padStart(2, '0')} · ${episode.title}",
                thumbnail = episode.thumbnail, source = "CineSrc", durationSec = episode.durationSec, choice = choice,
                mediaKey = "CineSrc:${target.id}", group = if (episodes.size > 1) group else null, groupTitle = if (episodes.size > 1) groupTitle else null)
        }
    }
}
