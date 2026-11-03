package app.hamstr.desktop

import app.hamstr.data.Backup
import app.hamstr.data.DownloadStore
import app.hamstr.data.Status
import app.hamstr.ui.BackupResult
import app.hamstr.ui.StorageUse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/** Settings › Data & backup on the computer: its folders, and the system's file dialogs. */
object DesktopData {
    /** Where the installed app lives (the folder, or the .app on macOS); null when run from the build. */
    private val installed: File? = System.getProperty("jpackage.app-path")?.let(::File)?.takeIf { it.exists() }?.let { launcher ->
        when {
            Paths.windows -> launcher.parentFile
            Paths.mac -> launcher.parentFile?.parentFile?.parentFile // Hamstr.app/Contents/MacOS/Hamstr
            else -> launcher.parentFile?.parentFile // /opt/hamstr/bin/Hamstr
        }
    }

    fun measure(): StorageUse {
        val files = DownloadStore.tasks.value.filter { it.status == Status.Done }.mapNotNull { it.filePath }.distinct()
            .sumOf { File(it).takeIf(File::isFile)?.length() ?: 0L }
        val parts = size(Paths.parts)
        val tools = size(Tools.dir)
        val cache = size(Paths.cache)
        val data = (size(Paths.data) - parts - tools - cache).coerceAtLeast(0)
        val drive = Paths.downloads.let { d -> generateSequence(d) { it.parentFile }.firstOrNull(File::exists) } ?: Paths.data
        return StorageUse(
            listOf(
                StorageUse.Part(StorageUse.Kind.Downloads, files + parts),
                StorageUse.Part(StorageUse.Kind.App, size(installed)),
                StorageUse.Part(StorageUse.Kind.Engine, tools),
                StorageUse.Part(StorageUse.Kind.Data, data),
                StorageUse.Part(StorageUse.Kind.Cache, cache),
            ),
            free = drive.usableSpace, total = drive.totalSpace,
        )
    }

    /** yt-dlp's cache only: everything else in the data folder is the list, the settings or the tools. */
    fun clearCache(): Long = size(Paths.cache).also { Paths.cache.deleteRecursively() }

    suspend fun export(): BackupResult? = withContext(Dispatchers.IO) {
        val target = ask(FileDialog.SAVE, Backup.fileName()) ?: return@withContext null
        val file = if (target.extension.equals("json", true)) target else File(target.path + ".json")
        runCatching {
            file.writeText(Backup.export(Updater.version))
            BackupResult.Saved
        }.getOrElse { BackupResult.Failed(it.message ?: it.javaClass.simpleName, reading = false) }
    }

    suspend fun import(): BackupResult? = withContext(Dispatchers.IO) {
        val file = ask(FileDialog.LOAD, null) ?: return@withContext null
        runCatching { BackupResult.Restored(Backup.restore(file.readText()).downloads) }
            .getOrElse { BackupResult.Failed(it.message ?: it.javaClass.simpleName, reading = true) }
    }

    /** The system's dialog for one file; it blocks, so only off the window's thread. */
    private fun ask(mode: Int, name: String?): File? {
        val d = FileDialog(null as Frame?, "Hamstr", mode).apply {
            directory = File(System.getProperty("user.home")).path
            if (name != null) file = name
            if (mode == FileDialog.LOAD) setFilenameFilter { _, n -> n.endsWith(".json", ignoreCase = true) }
            isVisible = true
        }
        return d.file?.let { File(d.directory, it) }
    }

    private fun size(f: File?): Long = when {
        f == null || !f.exists() -> 0
        f.isFile -> f.length()
        else -> f.walkTopDown().onFail { _, _ -> }.filter { it.isFile }.sumOf { it.length() }
    }
}
