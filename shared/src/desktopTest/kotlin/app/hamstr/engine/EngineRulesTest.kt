package app.hamstr.engine

import app.hamstr.data.Choice
import app.hamstr.data.DownloadTask
import app.hamstr.data.FileNames
import app.hamstr.data.Mode
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EngineRulesTest {
    // Signed with `openssl dgst -sha256 -sign`, as release.yml does, by the two release keys.
    private val sums = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855  Hamstr-test.apk\n".toByteArray()
    private val hamstrSig = Base64.getDecoder().decode("MEUCID273+/BQ2XVwYxuff+Q6lDtl1flL+CrPmmyApIJnBEBAiEA+6jbPw1indWiDpEil9EEO8I2TDGOhD35i7dE3zW2XD4=")
    private val cheekSig = Base64.getDecoder().decode("MEQCIBU15qFMBW/0qCj463mIUSzDQWxnD8mnmZ0+cNWicPOiAiBToTdD2zZVY7XavpsRnygxVPdkbtKsu26keDdu3Vy0YA==")

    @Test
    fun releaseSignaturesHoldOnlyForTheirOwnKeyAndText() {
        assertTrue(ReleaseKeys.verify(sums, hamstrSig, ReleaseKeys.HAMSTR))
        assertTrue(ReleaseKeys.verify(sums, cheekSig, ReleaseKeys.CHEEK))
        assertFalse(ReleaseKeys.verify(sums, hamstrSig, ReleaseKeys.CHEEK))
        assertFalse(ReleaseKeys.verify(sums + " ".toByteArray(), hamstrSig, ReleaseKeys.HAMSTR))
        assertFalse(ReleaseKeys.verify(sums, ByteArray(8), ReleaseKeys.HAMSTR))
        assertEquals(mapOf("Hamstr-test.apk" to "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"), ReleaseKeys.sums(String(sums)))
        assertEquals(ReleaseKeys.sums(String(sums))["Hamstr-test.apk"], ReleaseKeys.sha256(ByteArray(0)))
    }

    @Test
    fun versions() {
        assertTrue(Versions.newer("1.10.0", "1.9.2"))
        assertTrue(Versions.newer("v0.8.2", "0.8.1"))
        assertFalse(Versions.newer("0.8.1", "0.8.1"))
        assertFalse(Versions.newer("0.8.1-beta", "0.8.1"))
        assertFalse(Versions.newer("0.7.9", "0.8.0"))
        assertTrue(Versions.newer("1.0", "0.99.99"))
        assertTrue(Versions.sameCheekLine("0.3.9", "0.3.1"))
        assertFalse(Versions.sameCheekLine("0.4.0", "0.3.1"))
        assertTrue(Versions.sameCheekLine("1.9.0", "1.2.0"))
        assertFalse(Versions.sameCheekLine("2.0.0", "1.2.0"))
        assertFalse(Versions.sameCheekLine("0.3", "0.3.1"))
    }

    @Test
    fun failuresInPlainWords() {
        assertEquals(Fix.UpdateYtDlp, Failures.explain("ERROR: [youtube] abc: nsig extraction failed").fix)
        assertEquals(Fix.None, Failures.explain("ERROR: Unsupported URL: https://example.com").fix)
        assertTrue(Failures.isNetwork("error sending request for url (https://x): connection closed before message completed"))
        assertTrue(Failures.isNetwork("server said 503 Service Unavailable"))
        assertFalse(Failures.isNetwork("No space left on device"))
        assertFalse(Failures.isNetwork("ERROR: Private video. Sign in if you've been granted access"))
        assertEquals(Fix.Retry, Failures.explain(null).fix)
    }

    @Test
    fun formatsAndNames() {
        val video = Choice(Mode.Video, 720, "mp4", "opus", false)
        assertEquals("bv*[height<=720]+ba/b[height<=720]/bv*+ba/b", Formats.formatSelector(video))
        assertEquals("bv*+ba/b", Formats.formatSelector(video.copy(height = null)))
        assertEquals("ba[acodec^=opus]/ba[ext=opus]/ba[ext=ogg]/ba/b", Formats.formatSelector(video.copy(mode = Mode.Audio)))
        assertEquals("bv*+ba/b", Formats.formatSelector(video.copy(mode = Mode.Custom)))
        assertEquals("best[height<=480]", Formats.formatSelector(video.copy(mode = Mode.Custom, customFormat = "best[height<=480]")))
        val task = DownloadTask(url = "https://example.com", title = "t", thumbnail = null, source = null, durationSec = 0, choice = video)
        assertEquals("%(title).150B [%(id)s].%(ext)s", Formats.outputTemplate(task))
        assertEquals("%(title).150B [%(id)s] 720p.%(ext)s", Formats.outputTemplate(task.copy(versionOf = "first")))
        assertEquals("%(title).150B [%(id)s] audio.%(ext)s", Formats.outputTemplate(task.copy(versionOf = "first", choice = video.copy(mode = Mode.Audio))))

        assertEquals("Title 720p", FileNames.cleanName("Title [dQw4w9WgXcQ] 720p"))
        assertEquals("Title", FileNames.cleanName("Title [dQw4w9WgXcQ]"))
        assertEquals("Hamstr", FileNames.subfolder(""))
        assertEquals("Videos/YouTube", FileNames.subfolder(" Videos / YouTube/"))
        assertEquals("evil", FileNames.subfolder("../../evil"))
        assertEquals("a_b", FileNames.subfolder("a:b"))
    }
}
