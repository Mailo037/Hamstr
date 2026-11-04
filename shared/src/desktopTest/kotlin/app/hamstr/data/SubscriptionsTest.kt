package app.hamstr.data

import app.hamstr.engine.MediaInfo
import app.hamstr.engine.PlaylistEntry
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SubscriptionsTest {
    private fun video(id: String) = PlaylistEntry(url = "https://www.youtube.com/watch?v=$id", title = "Video $id", thumbnail = null, durationSec = 60, uploader = "Someone", key = "Youtube:$id")

    private fun feed(vararg ids: String) = MediaInfo(
        url = "https://www.youtube.com/@someone/videos", title = "Someone", thumbnail = null, source = "Youtube", durationSec = 0,
        uploader = "Someone", heights = emptyList(), approxBytes = 0, entries = ids.map(::video),
    )

    @Test
    fun aChannelIsReadAsItsVideos() {
        assertEquals("https://www.youtube.com/@someone/videos", Subscriptions.feedUrl("https://youtube.com/@someone"))
        assertEquals("https://www.youtube.com/channel/UC123/videos", Subscriptions.feedUrl("https://m.youtube.com/channel/UC123/"))
        // A tab or a playlist stays as it is.
        assertEquals("https://www.youtube.com/@someone/shorts", Subscriptions.feedUrl("https://www.youtube.com/@someone/shorts"))
        assertEquals("https://www.youtube.com/playlist?list=PL1", Subscriptions.feedUrl("https://www.youtube.com/playlist?list=PL1"))
        // A playlist is read whole, its new videos come at the end.
        assertEquals(null, Subscriptions.listed("https://www.youtube.com/playlist?list=PL1"))
        assertEquals(50, Subscriptions.listed("https://www.youtube.com/@someone/videos"))
    }

    @Test
    fun onlyWhatIsNewIsStashed() {
        val sub = Subscription(url = "https://www.youtube.com/@someone/videos", title = "Someone", seen = setOf("Youtube:a", "Youtube:b"))
        val fresh = Subscriptions.fresh(sub, feed("c", "a", "b").entries!!)
        assertEquals(listOf("Youtube:c"), fresh.map { it.key })
        val tasks = Subscriptions.tasksFor(sub, fresh)
        assertEquals(1, tasks.size)
        assertEquals(null, tasks.single().group)
        assertEquals(Mode.Video, tasks.single().choice.mode)
    }

    @Test
    fun aLongAbsenceBringsTheNewestOnly() {
        val sub = Subscription(url = "u", title = "Someone", seen = setOf("Youtube:old"))
        // A channel lists its newest first: the first ten.
        val channel = (1..15).map { "n$it" } + "old"
        assertEquals((1..10).map { "Youtube:n$it" }, Subscriptions.fresh(sub, channel.map(::video)).map { it.key })
        // A playlist grows at its end: the last ten.
        val playlist = listOf("old") + (1..15).map { "n$it" }
        assertEquals((6..15).map { "Youtube:n$it" }, Subscriptions.fresh(sub, playlist.map(::video)).map { it.key })
    }

    @Test
    fun severalAtOnceArriveTogetherInTheChosenFormat() {
        val sub = Subscription(url = "u", title = "Someone", mode = Mode.Audio)
        val tasks = Subscriptions.tasksFor(sub, listOf(video("a"), video("b")))
        assertEquals(1, tasks.mapNotNull { it.group }.distinct().size)
        assertTrue(tasks.all { it.groupTitle == "Someone" && it.choice.mode == Mode.Audio })
    }

    @Test
    fun aLookQueuesTheNewVideosAndRemembersThem() = runBlocking {
        val sub = Subscription(url = "https://www.youtube.com/@someone/videos", title = "Someone", seen = setOf("Youtube:a"))
        Subscriptions.replace(listOf(sub))
        try {
            val queued = mutableListOf<DownloadTask>()
            val n = Subscriptions.check({ feed("b", "a") }, force = true) { queued += it }
            assertEquals(1, n)
            assertEquals(listOf("https://www.youtube.com/watch?v=b"), queued.map { it.url })
            val after = Subscriptions.all.value.single()
            assertTrue("Youtube:b" in after.seen)
            assertEquals(1, after.stashed)
            // Nothing new the second time.
            assertEquals(0, Subscriptions.check({ feed("b", "a") }, force = true) { queued += it })
        } finally {
            Subscriptions.replace(emptyList())
        }
    }

    @Test
    fun linksToChannelsAndPlaylistsAreRecognised() {
        val channel = app.hamstr.engine.MediaLinks.Feed.Channel
        val playlist = app.hamstr.engine.MediaLinks.Feed.Playlist
        assertEquals(channel, app.hamstr.engine.MediaLinks.feed("https://www.youtube.com/@agony"))
        assertEquals(channel, app.hamstr.engine.MediaLinks.feed("https://m.youtube.com/@agony/videos"))
        assertEquals(channel, app.hamstr.engine.MediaLinks.feed("https://music.youtube.com/channel/UC123"))
        assertEquals(playlist, app.hamstr.engine.MediaLinks.feed("https://www.youtube.com/playlist?list=PL1"))
        assertEquals(channel, app.hamstr.engine.MediaLinks.feed("https://soundcloud.com/someone"))
        assertEquals(playlist, app.hamstr.engine.MediaLinks.feed("https://soundcloud.com/someone/sets/summer"))
        assertEquals(channel, app.hamstr.engine.MediaLinks.feed("https://someone.bandcamp.com/"))
        assertEquals(channel, app.hamstr.engine.MediaLinks.feed("https://www.tiktok.com/@someone"))
        // One video or song is not a feed.
        assertEquals(null, app.hamstr.engine.MediaLinks.feed("https://www.youtube.com/watch?v=abc&list=PL1"))
        assertEquals(null, app.hamstr.engine.MediaLinks.feed("https://soundcloud.com/someone/a-song"))
        assertEquals(null, app.hamstr.engine.MediaLinks.feed("https://www.tiktok.com/@someone/video/123"))
        assertEquals(null, app.hamstr.engine.MediaLinks.feed("https://soundcloud.com/discover"))
    }

    @Test
    fun aChannelTabIsNamedAfterTheChannel() {
        assertEquals("AGONY", Subscriptions.cleanTitle("AGONY - Videos"))
        assertEquals("Lofi Garden", Subscriptions.cleanTitle("Lofi Garden - Shorts"))
        assertEquals("Up - Down", Subscriptions.cleanTitle("Up - Down"))
    }

    @Test
    fun followedChannelsSurviveABackupRoundTrip() {
        val sub = Subscription(url = "https://www.youtube.com/@someone/videos", title = "Someone", mode = Mode.Audio, seen = setOf("Youtube:a"), stashed = 3)
        val back = Subscriptions.decode(Subscriptions.encode(listOf(sub))).single()
        assertEquals(sub, back)
    }
}
