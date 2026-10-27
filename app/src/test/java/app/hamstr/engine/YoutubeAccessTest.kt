package app.hamstr.engine

import com.yausername.youtubedl_android.YoutubeDLException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class YoutubeAccessTest {
    private val url = "https://www.youtube.com/watch?v=3uURd82kFd4"
    private val refusal = "Sign in to confirm you're not a bot. Use --cookies for authentication."

    @Test fun defaultSuccessDoesNotMakeExtraRequests() = runBlocking {
        var calls = 0
        assertEquals("formats", YoutubeAccess.run(url) { client ->
            calls++
            assertNull(client)
            "formats"
        })
        assertEquals(1, calls)
    }

    @Test fun guestRefusalRecoversFormatsWithAnAnonymousClient() = runBlocking {
        val calls = mutableListOf<String?>()
        val formats = YoutubeAccess.run(url) { client ->
            calls += client
            if (client == null) throw YoutubeDLException(refusal)
            listOf(360, 720, 1080)
        }
        assertEquals(listOf(360, 720, 1080), formats)
        assertEquals(listOf(null, "visionos"), calls)
    }

    @Test fun persistentRefusalStopsAfterTwoFallbacks() = runBlocking {
        val calls = mutableListOf<String?>()
        val failure = YoutubeDLException(refusal)
        try {
            YoutubeAccess.run<Unit>(url) { client -> calls += client; throw failure }
            fail("Expected refusal")
        } catch (e: YoutubeDLException) { assertSame(failure, e) }
        assertEquals(listOf(null, "visionos", "android_vr"), calls)
    }

    @Test fun unrelatedHostsAndRestrictedContentAreNotRetried() = runBlocking {
        val cases = listOf(
            "https://example.com/watch" to refusal,
            "https://youtube.com.example.com/watch" to refusal,
            "https://youtube.com@example.com/watch" to refusal,
            url to "Private video. Sign in if you've been granted access.",
            url to "Sign in to confirm your age",
            url to "Sign in. This video is members-only",
            url to "HTTP Error 403: Forbidden",
            url to "Requested format is not available",
        )
        for ((link, message) in cases) {
            var calls = 0
            try {
                YoutubeAccess.run<Unit>(link) { calls++; throw YoutubeDLException(message) }
                fail("Expected failure")
            } catch (_: YoutubeDLException) { }
            assertEquals("$link: $message", 1, calls)
        }
    }

    @Test fun doesNotRepeatAPlaylistAfterAFileWasCompleted() = runBlocking {
        var calls = 0
        try {
            YoutubeAccess.run<Unit>(url, mayRetry = { false }) { calls++; throw YoutubeDLException(refusal) }
            fail("Expected failure")
        } catch (_: YoutubeDLException) { }
        assertEquals(1, calls)
    }

    @Test fun cancellationIsPropagatedImmediately() = runBlocking {
        var calls = 0
        val canceled = CancellationException("Canceled")
        try {
            YoutubeAccess.run<Unit>(url) { calls++; throw canceled }
            fail("Expected cancellation")
        } catch (e: CancellationException) { assertSame(canceled, e) }
        assertEquals(1, calls)
    }

    @Test fun fallbackFailureDoesNotHideANewNonLoginError() = runBlocking {
        var calls = 0
        val offline = YoutubeDLException("Network is unreachable")
        try {
            YoutubeAccess.run<Unit>("https://youtu.be/3uURd82kFd4") { client ->
                calls++
                throw if (client == null) YoutubeDLException(refusal) else offline
            }
            fail("Expected network error")
        } catch (e: YoutubeDLException) { assertSame(offline, e) }
        assertEquals(2, calls)
    }

    @Test fun missingFormatsOnAFallbackClientStillTryTheLastClient() = runBlocking {
        val calls = mutableListOf<String?>()
        val formats = YoutubeAccess.run(url) { client ->
            calls += client
            when (client) {
                null -> throw YoutubeDLException(refusal)
                "visionos" -> throw YoutubeDLException("Requested format is not available")
                else -> listOf(360)
            }
        }
        assertEquals(listOf(360), formats)
        assertEquals(listOf(null, "visionos", "android_vr"), calls)
    }
}
