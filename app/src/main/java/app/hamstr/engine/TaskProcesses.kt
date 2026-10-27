package app.hamstr.engine

import android.os.Process
import android.system.Os
import android.system.OsConstants
import java.io.File

/** Stop only this download's processes, including FFmpeg children holding output pipes open. */
internal object TaskProcesses {
    private data class Child(val pid: Int, val parent: Int, val command: List<String>)

    fun stop(work: File) {
        val directory = work.absolutePath
        val children = File("/proc").listFiles().orEmpty().mapNotNull { entry ->
            val pid = entry.name.toIntOrNull() ?: return@mapNotNull null
            if (pid == Process.myPid()) return@mapNotNull null
            runCatching {
                // /proc is shared with other apps on some Android versions.
                if (Os.stat(entry.path).st_uid != Process.myUid()) return@mapNotNull null
                val parent = File(entry, "status").useLines { lines ->
                    lines.first { it.startsWith("PPid:") }.substringAfter(':').trim().toInt()
                }
                Child(pid, parent, File(entry, "cmdline").readText().split('\u0000'))
            }.getOrNull()
        }
        val roots = children.filter { child -> child.command.any { arg ->
            val path = arg.removePrefix("temp:")
            path == directory || path.startsWith("$directory/")
        } }.map { it.pid }.toSet()
        val owned = roots.toMutableSet()
        do {
            val added = children.filter { it.parent in owned }.map { it.pid }.filter { owned.add(it) }
        } while (added.isNotEmpty())
        // Kill children too: destroying just Cheek/Python leaves FFmpeg's inherited pipes open.
        for (pid in owned - roots + roots) runCatching { Os.kill(pid, OsConstants.SIGKILL) }
    }
}
