package app.hamstr.desktop

import app.hamstr.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.StandardOpenOption

/**
 * One Hamstr per data folder, like any installed app: starting it again brings the running
 * window to the front instead of opening a second Hamstr, which would share its download list
 * and show up on Nearby as a device of its own.
 */
object Instance {
    private const val TAG = "HamstrInstance"
    private val lockFile get() = File(Paths.data, "instance.lock")
    private val portFile get() = File(Paths.data, "instance.port")
    private var lock: FileLock? = null

    private val _show = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Another start asked for the window. */
    val show: SharedFlow<Unit> = _show

    /**
     * True when this is the only Hamstr; false when one runs already. Then it is told to show
     * itself, unless [quiet] (a start at sign-in finding Hamstr already there).
     */
    fun claim(quiet: Boolean): Boolean {
        val channel = FileChannel.open(lockFile.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        lock = runCatching { channel.tryLock() }.getOrNull()
        if (lock == null) {
            channel.close()
            if (!quiet) runCatching { Socket(InetAddress.getLoopbackAddress(), portFile.readText().trim().toInt()).close() }
                .onFailure { Log.w(TAG, "Couldn't reach the running Hamstr", it) }
            return false
        }
        // The running one listens on the loopback only; a connection is the whole message.
        val server = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
        portFile.writeText(server.localPort.toString())
        Thread({
            while (true) runCatching { server.accept().close(); _show.tryEmit(Unit) }
        }, "hamstr-instance").apply { isDaemon = true }.start()
        return true
    }
}
