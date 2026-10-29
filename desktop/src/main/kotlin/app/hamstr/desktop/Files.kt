package app.hamstr.desktop

import app.hamstr.Log
import app.hamstr.data.Choice
import app.hamstr.data.DownloadStore
import app.hamstr.data.DownloadTask
import app.hamstr.data.FileNames
import app.hamstr.data.Mode
import app.hamstr.data.Status
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.io.File

/** The desktop's ways in and out: the clipboard, the file manager and the default apps. */
object Files {
    private const val TAG = "HamstrFiles"

    fun clipboardText(): String? = runCatching {
        Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as? String
    }.getOrNull()

    fun copy(text: String) {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
    }

    /** Opens a file with the app the system picks for it. */
    fun open(file: File) = launch { Desktop.getDesktop().open(file) }

    /** Shows the file in Explorer, Finder or the file manager, selected where the system can. */
    fun reveal(file: File) = launch {
        when {
            Paths.windows -> ProcessBuilder("explorer.exe", "/select,", file.absolutePath).start()
            Paths.mac -> ProcessBuilder("open", "-R", file.absolutePath).start()
            Desktop.getDesktop().isSupported(Desktop.Action.BROWSE_FILE_DIR) -> Desktop.getDesktop().browseFileDirectory(file)
            else -> Desktop.getDesktop().open(file.parentFile)
        }
    }

    fun browse(url: String) = launch { Desktop.getDesktop().browse(java.net.URI(url)) }

    private fun launch(block: () -> Unit) {
        // AWT's Desktop calls can block for a moment; the window shouldn't.
        Thread { runCatching(block).onFailure { Log.w(TAG, "Couldn't hand over to the system", it) } }.start()
    }

    /** Copies picked files into the downloads folder and puts them on the list, like the phone's import. */
    fun pickAndImport() {
        val dialog = FileDialog(null as Frame?, "Hamstr", FileDialog.LOAD).apply { isMultipleMode = true; isVisible = true }
        val picked = dialog.files.orEmpty()
        if (picked.isEmpty()) return
        Thread {
            Paths.downloads.mkdirs()
            picked.forEach { src ->
                runCatching {
                    val target = FileNames.freeName(Paths.downloads, src.name)
                    src.copyTo(target)
                    val now = System.currentTimeMillis()
                    DownloadStore.add(DownloadTask(
                        url = target.toURI().toString(), title = target.nameWithoutExtension, thumbnail = null, source = null, durationSec = 0,
                        choice = Choice(Mode.Video, null, target.extension, "", false), status = Status.Done, progress = 1f,
                        filePath = target.path, sizeBytes = target.length(), finishedAt = now,
                    ))
                }.onFailure { Log.w(TAG, "Couldn't import $src", it) }
            }
        }.start()
    }
}
