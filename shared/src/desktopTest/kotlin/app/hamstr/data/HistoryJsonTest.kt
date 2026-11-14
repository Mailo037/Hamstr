package app.hamstr.data

import org.json.JSONArray
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HistoryJsonTest {
    private val odd = "Quote \" back \\ slash / new\nline\ttab \u0001 ctl \u2028 sep 🐹 emoji ü é 漢字"

    private val full = DownloadTask(
        id = "a", url = "https://example.com/watch?v=1&t=2", title = odd, thumbnail = "https://i.example.com/1.jpg",
        source = "youtube", durationSec = 245, choice = Choice(Mode.Audio, 720, "mkv", "m4a", true, "bv*+ba", open = true),
        status = Status.Done, progress = 0.3f, filePath = "C:\\Music\\$odd.m4a", contentUri = "content://media/1",
        sizeBytes = 5_000_000_000L, error = "403: Forbidden", createdAt = 1_731_571_200_123L, finishedAt = 1_731_571_260_456L,
        group = "g", groupTitle = "Album \"x\"", versionOf = "b", replaces = "c", videoHeight = 1080, uploader = "Ärtist",
        mediaKey = "Youtube:abc", hidden = true, sha256 = "f".repeat(64), hashedSize = 5_000_000_000L, hashedModified = 1_731_571_260_000L,
        anytime = true, mobileData = true, tonight = true, channelUrl = "https://www.youtube.com/@x",
    )
    private val bare = DownloadTask(id = "b", url = "https://example.com/2", title = "", thumbnail = null, source = null, durationSec = 0,
        choice = Choice(Mode.Video, null, "mp4", "opus", false), createdAt = 0)

    /** What DownloadStore wrote with org.json before, field for field. */
    private fun legacy(t: DownloadTask) = JSONObject().apply {
        put("id", t.id); put("url", t.url); put("title", t.title)
        put("thumbnail", t.thumbnail); put("source", t.source); put("duration", t.durationSec)
        put("mode", t.choice.mode.name); put("height", t.choice.height ?: 0); put("container", t.choice.container)
        put("audioFormat", t.choice.audioFormat); put("subtitles", t.choice.subtitles); put("customFormat", t.choice.customFormat); put("open", t.choice.open)
        put("status", t.status.name); put("progress", t.progress.toDouble())
        put("filePath", t.filePath); put("contentUri", t.contentUri); put("size", t.sizeBytes)
        put("error", t.error); put("createdAt", t.createdAt); put("finishedAt", t.finishedAt)
        put("group", t.group); put("groupTitle", t.groupTitle)
        put("versionOf", t.versionOf); put("replaces", t.replaces); put("videoHeight", t.videoHeight)
        put("uploader", t.uploader); put("mediaKey", t.mediaKey); put("hidden", t.hidden)
        put("sha256", t.sha256); put("hashedSize", t.hashedSize); put("hashedModified", t.hashedModified)
        put("anytime", t.anytime); put("mobileData", t.mobileData); put("tonight", t.tonight)
        put("channelUrl", t.channelUrl)
    }

    @Test
    fun whatItWritesItReadsBack() {
        val read = HistoryJson.read(HistoryJson.write(listOf(full, bare)))
        assertEquals(listOf(full, bare), read.tasks)
        assertEquals(0, read.skipped)
        assertEquals(0, HistoryJson.read(HistoryJson.write(emptyList())).tasks.size)
    }

    @Test
    fun historiesOrgJsonWroteReadTheSame() {
        val text = JSONArray().put(legacy(full)).put(legacy(bare)).toString()
        assertEquals(listOf(full, bare), HistoryJson.read(text).tasks)
        // Pretty-printed too (a backup someone opened and saved again).
        assertEquals(listOf(full, bare), HistoryJson.read(JSONArray(text).toString(2)).tasks)
    }

    @Test
    fun orgJsonReadsWhatItWrites() {
        // Backups put the history inside their own JSON, and an older Hamstr reads it with org.json.
        val a = JSONArray(HistoryJson.write(listOf(full, bare)))
        val expected = legacy(full)
        val o = a.getJSONObject(0)
        assertEquals(expected.keySet(), o.keySet())
        for (k in expected.keySet() - "progress") assertEquals(expected.get(k).toString(), o.get(k).toString(), k)
        assertEquals(full.progress, o.getDouble("progress").toFloat())
        assertEquals(legacy(bare).keySet(), a.getJSONObject(1).keySet())
    }

    @Test
    fun aLoneSurrogateSurvivesTheFile() {
        val t = bare.copy(title = "half \uD83D of an emoji")
        val text = HistoryJson.write(listOf(t))
        val again = String(text.toByteArray(Charsets.UTF_8), Charsets.UTF_8)
        assertEquals(t.title, HistoryJson.read(again).tasks.single().title)
    }

    @Test
    fun valuesOfAnotherTypeReadLikeOrgJsonReadThem() {
        val text = """
            [ {"id":"a","url":"u","title":null,"duration":"245","height":720.0,"size":"12","subtitles":"TRUE","hidden":1,
               "progress":"0.5","status":"Scheduled","mode":"Karaoke","thumbnail":"","future":{"x":[1,{"y":"]}"}]},"more":[1,2]},
              {"url":"no id"}, 5, "text", null,
              {"id":7,"url":"u2","createdAt":1.731571200123E12} ]
        """.trimIndent()
        val read = HistoryJson.read(text)
        assertEquals(listOf("a", "7"), read.tasks.map { it.id })
        assertEquals(4, read.skipped)
        val a = read.tasks[0]
        assertEquals("", a.title)
        assertEquals(245, a.durationSec)
        assertEquals(720, a.choice.height)
        assertEquals(12L, a.sizeBytes)
        assertTrue(a.choice.subtitles)
        assertEquals(false, a.hidden)
        assertEquals(0.5f, a.progress)
        // Unknown states and kinds from a newer Hamstr: waiting, and a video.
        assertEquals(Status.Paused, a.status)
        assertEquals(Mode.Video, a.choice.mode)
        assertEquals(null, a.thumbnail)
        assertEquals(1_731_571_200_123L, read.tasks[1].createdAt)
    }

    @Test
    fun aDamagedFileIsNotReadAsAShorterList() {
        val good = HistoryJson.write(listOf(full, bare))
        for (broken in listOf(good.dropLast(1), good.dropLast(40), good.replaceFirst(",", ";"), "", "{}", "[1,]", "[{\"id\":\"a\" \"url\":\"u\"}]", good + "x", "[\"\\x\"]"))
            assertFailsWith<IllegalArgumentException>(broken.take(30)) { HistoryJson.read(broken) }
    }

    @Test
    fun notANumberDoesNotLoseTheSave() {
        val t = bare.copy(progress = Float.NaN)
        assertEquals(0f, HistoryJson.read(HistoryJson.write(listOf(t))).tasks.single().progress)
    }
}
