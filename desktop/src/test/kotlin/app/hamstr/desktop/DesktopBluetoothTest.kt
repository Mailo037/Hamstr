package app.hamstr.desktop

import app.hamstr.data.Prefs
import app.hamstr.data.PropertiesFile
import app.hamstr.nearby.DeviceKind
import app.hamstr.nearby.Nearby
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class DesktopBluetoothTest {
    /** The helper buildBle left in build/ble, copied where the app looks for it; false without one. */
    private fun helper(): Boolean {
        val built = System.getProperty("hamstr.testBle")?.let(::File) ?: return false
        val exe = if (Paths.windows) ".exe" else ""
        val bin = listOf("release", "debug").map { File(built, "$it/hamstr-ble$exe") }.firstOrNull { it.isFile } ?: return false
        Tools.dir.mkdirs()
        bin.copyTo(Tools.bluetooth, overwrite = true).setExecutable(true)
        return true
    }

    @Test
    fun startsTheHelperAndServes() {
        if (!helper()) return
        Prefs.init(PropertiesFile(File(Paths.data, "settings.properties")))
        Prefs.edit { it.copy(nearby = false, nearbyBluetooth = true) }
        Nearby.init(PropertiesFile(File(Paths.data, "nearby.properties")), Paths.data, DeviceKind.Desktop, "Test", emptyList())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            DesktopBluetooth.start(scope)
            val deadline = System.currentTimeMillis() + 30_000
            val settled = setOf(DesktopBluetooth.State.On, DesktopBluetooth.State.NoAdapter)
            while (DesktopBluetooth.state.value !in settled && System.currentTimeMillis() < deadline) Thread.sleep(100)
            // A computer with Bluetooth serves and scans; one without says so instead of trying forever.
            assertTrue(DesktopBluetooth.state.value in settled, "Still ${DesktopBluetooth.state.value}")
        } finally {
            DesktopBluetooth.stop()
            scope.cancel()
        }
    }
}
