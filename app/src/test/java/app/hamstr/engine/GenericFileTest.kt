package app.hamstr.engine

import org.junit.Assert.*
import org.junit.Test

class GenericFileTest {
    @Test fun installerUsesResponseFilename() {
        assertEquals("opencode-desktop-win-x64.exe", GenericFile.fileName(
            "https://opencode.ai/files/bin/2.2.0/opencode-desktop-win-x64.exe",
            "attachment; filename=\"opencode-desktop-win-x64.exe\"", "application/octet-stream",
        ))
    }

    @Test fun mediaAndManifestsKeepTheirProcessing() {
        for ((name, mime) in listOf("clip.mp4" to "application/octet-stream", "music" to "audio/mpeg", "stream" to "application/vnd.apple.mpegurl", "stream" to "application/dash+xml")) {
            assertNull(name, GenericFile.fileName("https://example.com/$name", "attachment", mime))
        }
    }

    @Test fun htmlAndApiResponsesStillNeedExtraction() {
        assertNull(GenericFile.fileName("https://example.com/file.exe", null, "text/html; charset=utf-8"))
        assertNull(GenericFile.fileName("https://example.com/api", null, "application/json"))
    }

    @Test fun encodedFilenameCannotEscapeOutputDirectory() {
        assertEquals("setup+new.exe", GenericFile.fileName("https://example.com/download", "attachment; filename*=UTF-8''..%2Fsetup%2Bnew.exe", "application/octet-stream"))
    }

    @Test fun extensionlessBinaryGetsOriginalFileType() {
        assertEquals("manual.pdf", GenericFile.fileName("https://example.com/manual", null, "application/pdf"))
        assertEquals("file.bin", GenericFile.fileName("https://example.com/file", null, "application/octet-stream"))
    }
}
