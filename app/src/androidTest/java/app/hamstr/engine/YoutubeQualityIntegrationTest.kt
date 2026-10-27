package app.hamstr.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Live check of the same metadata path used by Get another quality, without signing in. */
@RunWith(AndroidJUnit4::class)
class YoutubeQualityIntegrationTest {
    @Test fun publicVideoOffersMultipleQualities(): Unit = runBlocking {
        val info = Engine.info("https://www.youtube.com/watch?v=3uURd82kFd4")
        assertTrue("No title returned", info.title.isNotBlank())
        assertTrue("Expected multiple qualities, got ${info.heights}", info.heights.size > 1)
        assertTrue("Expected HD video, got ${info.heights}", info.heights.any { it >= 720 })
    }

    @Test fun anonymousFallbackStillOffersHd(): Unit = runBlocking {
        Engine.awaitReady()
        val url = "https://www.youtube.com/watch?v=3uURd82kFd4"
        val response = YoutubeAccess.run(url) { client ->
            // Exercise the recovery path even when this network's default client works.
            if (client == null) throw YoutubeDLException("Sign in to confirm you're not a bot")
            val request = YoutubeDLRequest(url).apply {
                addOption("--ignore-config")
                addOption("--no-cookies")
                addOption("--no-cookies-from-browser")
                addOption("--no-playlist")
                addOption("--socket-timeout", 15)
                addOption("--dump-single-json")
                addOption("--extractor-args", "youtube:player_client=$client")
            }
            WarmYtDlp.execute(request)!!
        }
        val formats = JSONObject(response.out).getJSONArray("formats")
        val heights = (0 until formats.length()).map { formats.getJSONObject(it).optInt("height") }.filter { it > 0 }.distinct()
        assertTrue("Anonymous fallback should include HD, got $heights", heights.any { it >= 720 })
    }
}
