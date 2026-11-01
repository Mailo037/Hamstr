package app.hamstr.update

import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.hamstr.BuildConfig
import app.hamstr.MainActivity
import app.hamstr.data.Prefs
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import org.junit.Assert.*
import org.junit.Test
import org.junit.After
import org.junit.runner.RunWith
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/** Uses the installed debug APK as a real, correctly signed download; never commits an install. */
@RunWith(AndroidJUnit4::class)
class BackgroundUpdateTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val notifications get() = context.getSystemService(NotificationManager::class.java)
    private val apk get() = File(context.applicationInfo.sourceDir)
    private val nextVersion get() = BuildConfig.VERSION_NAME.substringBeforeLast('.') + "." + (BuildConfig.VERSION_NAME.substringAfterLast('.').toInt() + 1)

    private class Fixture(private val serve: (Socket) -> Unit) : AutoCloseable {
        private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val url = "http://127.0.0.1:${server.localPort}/update.apk"
        private var client: Socket? = null
        init { thread(isDaemon = true, name = "UpdateFixture") {
            runCatching { server.accept().use { socket ->
                client = socket
                val input = socket.getInputStream().bufferedReader()
                while (!input.readLine().isNullOrEmpty()) { }
                serve(socket)
            } }
        } }
        override fun close() { client?.close(); server.close() }
    }

    private fun release(url: String, size: Long = apk.length()) = Release(nextVersion,
        "Keep using Hamstr while the update downloads.\n\nInstall when you’re ready.", url, "Hamstr.apk", size, "https://example.com/update")

    private fun shell(command: String) {
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
    }
    private fun capture(name: String) {
        val dir = File(context.getExternalFilesDir(null), "update-shots").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot().useBitmap { bitmap ->
            File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
    private inline fun <T> Bitmap.useBitmap(block: (Bitmap) -> T): T = try { block(this) } finally { recycle() }
    private suspend fun until(test: () -> Boolean) {
        try { withTimeout(90_000) { while (!test()) delay(50) } }
        catch (e: TimeoutCancellationException) { throw AssertionError("Update did not reach the expected state: ${Updater.state.value}", e) }
    }
    private fun textNode(text: String, node: AccessibilityNodeInfo? = instrumentation.uiAutomation.rootInActiveWindow): AccessibilityNodeInfo? {
        node ?: return null
        if (node.text?.toString() == text) return node
        for (i in 0 until node.childCount) textNode(text, node.getChild(i))?.let { return it }
        return null
    }
    private fun clickText(text: String) {
        var button = textNode(text) ?: error("Missing action: $text")
        while (!button.isClickable) button = button.parent ?: error("Action has no clickable parent: $text")
        assertTrue(button.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
    private fun reset() {
        Prefs.edit { it.copy(appUpdates = false) }
        Updater.pending(context).clear()
        UpdateNotifications.cancelResult(context)
        Updater.init(context)
    }

    @After fun cleanup(): Unit = runBlocking {
        shell("cmd statusbar collapse")
        shell("input keyevent KEYCODE_WAKEUP")
        context.stopService(Intent(context, UpdateDownloadService::class.java))
        delay(250)
        reset()
    }

    @Test fun keepsDownloadingWithTheScreenOffThenNotifiesWithoutInstallingAndRestoresTheReadyApk(): Unit = runBlocking {
        reset()
        val sessions = context.packageManager.packageInstaller.mySessions.map { it.sessionId }
        Fixture { socket ->
            val output = socket.getOutputStream()
            output.write("HTTP/1.1 200 OK\r\nContent-Length: ${apk.length()}\r\nConnection: close\r\n\r\n".toByteArray())
            apk.inputStream().use { input ->
                val buffer = ByteArray(128 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    output.write(buffer, 0, n); output.flush(); Thread.sleep(35)
                }
            }
        }.use { server ->
            ActivityScenario.launch(MainActivity::class.java).use { activity ->
                activity.onActivity {
                    Updater.available(release(server.url))
                    it.startActivity(Intent(it, MainActivity::class.java).setAction(MainActivity.ACTION_UPDATE))
                }
                until { textNode("Download update") != null }
                clickText("Download update")
                until { (Updater.state.value as? UpdateState.Downloading)?.progress?.let { it > 0.02f } == true }
                until { textNode("Download update") == null }
                assertNull("The update sheet closes after starting", textNode("Download update"))
                // The activity remains usable while the service owns the download.
                activity.onActivity { assertTrue(it.window.decorView.isAttachedToWindow) }
                capture("update_app")
                activity.moveToState(Lifecycle.State.CREATED)
                shell("input keyevent KEYCODE_HOME")
                val progress = notifications.activeNotifications.first { it.id == UpdateNotifications.PROGRESS }.notification
                assertEquals(Notification.FLAG_ONGOING_EVENT, progress.flags and Notification.FLAG_ONGOING_EVENT)
                shell("cmd statusbar expand-notifications"); delay(1200); capture("update_progress")
                shell("cmd statusbar collapse"); shell("input keyevent KEYCODE_SLEEP")
                until { Updater.state.value is UpdateState.Ready }
                until { notifications.activeNotifications.any { it.notification.extras.getString(Notification.EXTRA_TITLE)?.contains("ready to install") == true } }
                assertEquals(sessions, context.packageManager.packageInstaller.mySessions.map { it.sessionId })
                assertEquals(apk.length(), Updater.pending(context).apk.length())
                Updater.init(context)
                assertTrue("Verified download survives restarting the updater", Updater.state.value is UpdateState.Ready)
                shell("input keyevent KEYCODE_WAKEUP"); shell("wm dismiss-keyguard")
                shell("cmd statusbar expand-notifications"); delay(1200); capture("update_notification_ready")
                val readyTitle = notifications.activeNotifications.first { it.notification.extras.getString(Notification.EXTRA_TITLE)?.contains("ready to install") == true }
                    .notification.extras.getString(Notification.EXTRA_TITLE)!!
                until { textNode(readyTitle) != null }
                clickText(readyTitle)
                until { textNode("Install update") != null }
                delay(3500); capture("update_ready")
                assertTrue(Updater.state.value is UpdateState.Ready)
            }
        }
        reset()
    }

    @Test fun cancellationStopsTheTransferAndDoesNotOfferAPartialApk(): Unit = runBlocking {
        reset()
        Fixture { socket ->
            val output = socket.getOutputStream()
            output.write("HTTP/1.1 200 OK\r\nContent-Length: 10000000\r\n\r\n".toByteArray())
            repeat(100) { output.write(ByteArray(100_000)); output.flush(); Thread.sleep(200) }
        }.use { server ->
            ActivityScenario.launch(MainActivity::class.java).use { activity ->
                activity.onActivity { Updater.install(it, release(server.url, 10_000_000)) }
                until { (Updater.state.value as? UpdateState.Downloading)?.progress?.let { it > 0f } == true }
                context.startService(Intent(context, UpdateDownloadService::class.java).setAction(UpdateDownloadService.CANCEL))
                until { Updater.state.value is UpdateState.Available }
                until { notifications.activeNotifications.none { it.id == UpdateNotifications.PROGRESS } }
                assertFalse(Updater.pending(context).apk.exists())
                assertFalse(Updater.pending(context).partial.exists())
            }
        }
        reset()
    }

    @Test fun truncatedDownloadFailsWithoutLeavingAReadyApk(): Unit = runBlocking {
        reset()
        Fixture { socket -> socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 100000\r\nConnection: close\r\n\r\nbroken".toByteArray()) }.use { server ->
            ActivityScenario.launch(MainActivity::class.java).use { activity ->
                activity.onActivity { Updater.install(it, release(server.url, 100_000)) }
                until { Updater.state.value is UpdateState.Failed }
                until { notifications.activeNotifications.none { it.id == UpdateNotifications.PROGRESS } }
                assertFalse(Updater.pending(context).apk.exists())
                assertFalse(Updater.pending(context).partial.exists())
            }
        }
        reset()
    }
}
