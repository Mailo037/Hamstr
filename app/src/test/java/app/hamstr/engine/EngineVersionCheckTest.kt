package app.hamstr.engine

import org.junit.Assert.*
import org.junit.Test

class EngineVersionCheckTest {
    @Test fun recognizesNewerDateAndBuildVersions() {
        assertTrue(newerYtDlpVersion("2026.11.1", "2026.10.26"))
        assertTrue(newerYtDlpVersion("v2027.01.01", "2026.12.31"))
        assertTrue(newerYtDlpVersion("2026.10.26.1", "2026.10.26"))
    }

    @Test fun currentAndNewerInstalledVersionsDoNotOfferAnUpdate() {
        assertFalse(newerYtDlpVersion("2026.10.26", "2026.10.26"))
        assertFalse(newerYtDlpVersion("2026.10.26", "2026.10.26.0"))
        assertFalse(newerYtDlpVersion("2026.10.26", "2026.10.26.232810"))
        assertFalse(newerYtDlpVersion("2026.10.26", "2027.01.01"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun unknownInstalledVersionIsNotMistakenForAnAvailableUpdate() {
        newerYtDlpVersion("2026.10.26", "?")
    }
}
