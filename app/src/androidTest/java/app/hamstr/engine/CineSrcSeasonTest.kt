package app.hamstr.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.accessibilityservice.AccessibilityServiceInfo
import app.hamstr.ShareActivity
import app.hamstr.data.Prefs
import app.hamstr.data.DownloadStore
import app.hamstr.data.Choice
import app.hamstr.data.Mode
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CineSrcSeasonTest {
    @Test fun instantStashStillOffersEpisodesAndThenTheExistingQualitySheet(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("cineSrcLive") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val old = Prefs.settings.value
        try {
            Prefs.edit { it.copy(quickStash = true) }
            context.startActivity(Intent(context, ShareActivity::class.java).setAction(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, "https://cineby.rocks/tv/40075?season=2").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val label = context.getString(app.hamstr.R.string.cinesrc_episode, 1, "Scary-oke")
            val node = waitForText(label)
            assertTrue(DownloadStore.tasks.value.none { it.url == "https://cineby.rocks/tv/40075?season=2" })
            screenshot("cinesrc-season-picker.png")
            click(node)
            instrumentation.waitForIdleSync()
            click(waitForText(context.getString(app.hamstr.R.string.cinesrc_continue)))
            val url = "https://cinesrc.st/embed/tv/40075?s=2&e=1"
            val until = SystemClock.elapsedRealtime() + 90_000
            while (Engine.cachedInfo(url) == null && SystemClock.elapsedRealtime() < until) SystemClock.sleep(100)
            assertTrue(Engine.cachedInfo(url)?.heights?.isNotEmpty() == true)
            assertEquals(url, Engine.cachedInfo(url)?.url)
        } finally {
            screenshot("cinesrc-season-last-state.png")
            instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            Prefs.edit { old }
        }
    }

    private fun screenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            java.io.File(instrumentation.targetContext.cacheDir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    private fun waitForText(text: String): AccessibilityNodeInfo {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val until = SystemClock.elapsedRealtime() + 25_000
        while (SystemClock.elapsedRealtime() < until) {
            val roots = listOfNotNull(automation.rootInActiveWindow) + automation.windows.mapNotNull { it.root }
            roots.firstNotNullOfOrNull { findText(it, text) }?.let { return it }
            SystemClock.sleep(100)
        }
        error("Did not find episode-picker text: $text")
    }

    private fun findText(node: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        if (node.text?.toString()?.contains(text) == true || node.contentDescription?.toString()?.contains(text) == true) return node
        for (i in 0 until node.childCount) node.getChild(i)?.let { child -> findText(child, text)?.let { return it } }
        return null
    }

    private fun click(node: AccessibilityNodeInfo) {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable) {
                assertTrue(current.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                return
            }
            current = current.parent
        }
        error("Expected a clickable episode-picker control")
    }

    @Test fun metadataFiltersWrongSeasonAndQueuesOnlyChosenEpisodes() {
        val show = CineSrcCatalog.parseSeries(40075, JSONObject("""{"id":40075,"name":"Gravity Falls","seasons":[{"season_number":2,"episode_count":3,"name":"Season 2"}]}"""))
        val data = CineSrcCatalog.parseSeason(40075, 2, JSONObject("""{"season_number":2,"episodes":[
            {"episode_number":3,"season_number":2,"show_id":40075,"name":"Third","runtime":22},
            {"episode_number":1,"season_number":2,"show_id":40075,"name":"First","still_path":"/first.jpg"},
            {"episode_number":2,"season_number":1,"show_id":40075,"name":"Wrong season"},
            {"episode_number":4,"season_number":2,"show_id":999,"name":"Wrong series"}]}"""))
        assertEquals(listOf(1, 3), data.episodes.map { it.number })
        val choice = Choice(Mode.Video, 720, "mp4", "m4a", false)
        val tasks = CineSrcCatalog.tasksFor(show, data, setOf(1, 3), choice, "Gravity Falls · Season 2")
        assertEquals(listOf("https://cinesrc.st/embed/tv/40075?s=2&e=1", "https://cinesrc.st/embed/tv/40075?s=2&e=3"), tasks.map { it.url })
        assertEquals(1, tasks.map { it.group }.distinct().size)
        assertNotNull(tasks.first().group)
        assertEquals(choice, tasks.last().choice)
        assertThrows(IllegalArgumentException::class.java) { CineSrcCatalog.tasksFor(show, data, setOf(2), choice, "Season 2") }
        assertThrows(IllegalStateException::class.java) { CineSrcCatalog.parseSeason(40075, 1, JSONObject("""{"season_number":2,"episodes":[]}""")) }
    }

    @Test fun suppliedSeasonLoadsAllTwentyEpisodesAndResolvesTheChosenOne(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("cineSrcLive") == "true")
        val target = CineSrcTarget.parse("https://cineby.rocks/tv/40075?season=2")!!
        val show = CineSrcCatalog.series(target.tmdbId)
        val season = CineSrcCatalog.season(show.id, target.season!!)
        assertEquals("Gravity Falls", show.title)
        assertEquals(20, season.episodes.size)
        assertEquals("Scary-oke", season.episodes.first().title)
        Engine.awaitReady()
        val info = Engine.info(season.target(season.episodes[1]).embed)
        assertEquals("CineSrc", info.source)
        assertTrue(info.heights.isNotEmpty())
        assertEquals("https://cinesrc.st/embed/tv/40075?s=2&e=2", info.url)
    }
}
