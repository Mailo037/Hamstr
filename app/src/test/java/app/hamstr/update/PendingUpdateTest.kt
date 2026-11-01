package app.hamstr.update

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class PendingUpdateTest {
    private val release = Release("0.8.1", "notes", "https://example.com/update.apk", "Hamstr.apk", 4, "https://example.com/release")

    @Test fun completeDownloadSurvivesAStoreRecreationButPartialOrMissingFilesDoNotBecomeReady() {
        val dir = Files.createTempDirectory("hamstr-pending-update").toFile()
        try {
            val store = PendingUpdate(dir)
            store.save(release, ready = false)
            store.partial.writeText("part")
            assertEquals(release to false, PendingUpdate(dir).read())
            store.apk.writeText("done")
            store.save(release, ready = true)
            assertEquals(release to true, PendingUpdate(dir).read())
            store.apk.writeText("bad")
            assertEquals(release to false, PendingUpdate(dir).read())
            store.apk.delete()
            assertEquals(release to false, PendingUpdate(dir).read())
            store.clear()
            assertNull(PendingUpdate(dir).read())
        } finally { dir.deleteRecursively() }
    }
}
