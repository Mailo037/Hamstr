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
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
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

    /** Native file clipboard data: paste into a chat, mail composer or file manager. */
    fun copyFiles(files: List<File>) {
        val present = files.filter { it.isFile }
        if (present.isEmpty()) return
        Toolkit.getDefaultToolkit().systemClipboard.setContents(object : Transferable {
            override fun getTransferDataFlavors() = arrayOf(DataFlavor.javaFileListFlavor)
            override fun isDataFlavorSupported(flavor: DataFlavor) = flavor == DataFlavor.javaFileListFlavor
            override fun getTransferData(flavor: DataFlavor): Any {
                if (!isDataFlavorSupported(flavor)) throw UnsupportedFlavorException(flavor)
                return present
            }
        }, null)
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
