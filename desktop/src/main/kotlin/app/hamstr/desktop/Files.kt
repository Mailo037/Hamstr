package app.hamstr.desktop

import app.hamstr.Log
import app.hamstr.data.Choice
import app.hamstr.data.DownloadStore
import app.hamstr.data.DownloadTask
import app.hamstr.data.FileChecks
import app.hamstr.data.FileNames
import app.hamstr.data.Mode
import app.hamstr.data.Status
import app.hamstr.player.Kind
import app.hamstr.player.kindOf
import java.util.UUID
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
        import(dialog.files.orEmpty().toList())
    }

    /** Copies [files] (picked or dropped on the window) into the downloads folder and onto the list. */
    fun import(files: List<File>) {
        val picked = files.filter { it.isFile }
        if (picked.isEmpty()) return
        Thread {
            val group = groupFor(picked.size)
            picked.forEachIndexed { i, src ->
                runCatching {
                    // A file that already is in the downloads folder is listed, not copied.
                    val target = if (src.parentFile?.canonicalFile == Paths.downloads.canonicalFile) src
                        else FileNames.freeName(Paths.downloads.apply { mkdirs() }, src.name).also { src.copyTo(it) }
                    if (DownloadStore.tasks.value.none { it.filePath == target.path }) register(target, target.toURI().toString(), group, i)
                }.onFailure { Log.w(TAG, "Couldn't import $src", it) }
            }
            FileChecks.refresh()
        }.start()
    }

    /** Files another device sent, each with the name it gave: they move into the downloads folder. */
    fun adopt(files: List<Pair<File, String>>, from: String): List<File> {
        val group = groupFor(files.size, from)
        return files.mapIndexedNotNull { i, (part, name) ->
            runCatching {
                val target = FileNames.freeName(Paths.downloads.apply { mkdirs() }, name)
                runCatching { java.nio.file.Files.move(part.toPath(), target.toPath()) }.getOrElse {
                    part.copyTo(target)
                    part.delete()
                }
                register(target, target.toURI().toString(), group, i, source = from)
                target
            }.onFailure { Log.w(TAG, "Couldn't keep $name", it); part.delete() }.getOrNull()
        }.also { FileChecks.refresh() }
    }

    private fun groupFor(count: Int, title: String? = null) =
        if (count > 1) UUID.randomUUID().toString() to (title ?: java.text.SimpleDateFormat("d MMM, HH:mm").format(java.util.Date())) else null

    private fun register(file: File, url: String, group: Pair<String, String>?, index: Int, source: String? = null) {
        val now = System.currentTimeMillis()
        val audio = kindOf(file) == Kind.Audio
        DownloadStore.add(DownloadTask(
            url = url, title = file.nameWithoutExtension, thumbnail = null, source = source, durationSec = 0,
            choice = Choice(if (audio) Mode.Audio else Mode.Video, null, file.extension, if (audio) file.extension else "", false),
            status = Status.Done, progress = 1f, filePath = file.path, sizeBytes = file.length(),
            createdAt = now + index, finishedAt = now, group = group?.first, groupTitle = group?.second,
        ))
    }

    /** The system's folder picker; null when it was closed without a choice. */
    fun pickFolder(start: File): File? {
        if (Paths.mac) {
            // AWT's dialog picks folders on macOS when asked to; Swing's would look foreign there.
            System.setProperty("apple.awt.fileDialogForDirectories", "true")
            try {
                val d = FileDialog(null as Frame?, "Hamstr", FileDialog.LOAD).apply { directory = start.path; isVisible = true }
                return d.file?.let { File(d.directory, it) }
            } finally {
                System.setProperty("apple.awt.fileDialogForDirectories", "false")
            }
        }
        runCatching { javax.swing.UIManager.setLookAndFeel(javax.swing.UIManager.getSystemLookAndFeelClassName()) }
        val chooser = javax.swing.JFileChooser(start.takeIf { it.isDirectory } ?: start.parentFile).apply {
            fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY
            dialogTitle = "Hamstr"
        }
        return if (chooser.showDialog(null, null) == javax.swing.JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
    }
}
