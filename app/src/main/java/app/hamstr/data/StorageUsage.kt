package app.hamstr.data

import android.app.usage.StorageStatsManager
import android.content.Context
import android.os.Environment
import android.os.Process
import android.os.StatFs
import android.os.storage.StorageManager
import app.hamstr.ui.StorageUse
import java.io.File

/** What Hamstr takes up on the phone, for Settings › Data & backup. */
object StorageUsage {
    // Still in use while something downloads or is sent: never cleared with the cache.
    private val KEEP = setOf("parts", "nearby-out")

    fun measure(context: Context): StorageUse {
        val parts = size(File(context.cacheDir, "parts"))
        val files = DownloadStore.tasks.value.filter { it.status == Status.Done }.mapNotNull { it.filePath }.distinct()
            .sumOf { File(it).takeIf(File::isFile)?.length() ?: 0L }
        // yt-dlp's Python, FFmpeg and Cheek, as the engine unpacked or updated them.
        val engine = size(File(context.noBackupFilesDir, "youtubedl-android")) + size(File(context.filesDir, "cheek"))
        val cache = clearable(context).sumOf(::size)
        // Android's own count, which includes the APK and its libraries; walking the folders where it won't say.
        val stats = runCatching {
            context.getSystemService(StorageStatsManager::class.java).queryStatsForPackage(StorageManager.UUID_DEFAULT, context.packageName, Process.myUserHandle())
        }.getOrNull()
        val app = stats?.appBytes ?: run {
            val info = context.applicationInfo
            (listOf(info.sourceDir) + info.splitSourceDirs.orEmpty()).sumOf { File(it).length() } + size(File(info.nativeLibraryDir))
        }
        val data = ((stats?.dataBytes ?: (size(context.dataDir) + size(context.getExternalFilesDir(null)))) - engine - cache - parts).coerceAtLeast(0)
        val disk = StatFs(Environment.getDataDirectory().path)
        return StorageUse(
            listOf(
                StorageUse.Part(StorageUse.Kind.Downloads, files + parts),
                StorageUse.Part(StorageUse.Kind.App, app),
                StorageUse.Part(StorageUse.Kind.Engine, engine),
                StorageUse.Part(StorageUse.Kind.Data, data),
                StorageUse.Part(StorageUse.Kind.Cache, cache),
            ),
            free = disk.availableBytes, total = disk.totalBytes,
        )
    }

    /** Empties the cache, except what a running download or a send still reads; returns how much went. */
    fun clearCache(context: Context): Long = clearable(context).sumOf { f -> size(f).also { f.deleteRecursively() } }

    private fun clearable(context: Context): List<File> =
        context.cacheDir.listFiles().orEmpty().filter { it.name !in KEEP } + context.externalCacheDir?.listFiles().orEmpty()

    private fun size(f: File?): Long = when {
        f == null || !f.exists() -> 0
        f.isFile -> f.length()
        else -> f.walkTopDown().onFail { _, _ -> }.filter { it.isFile }.sumOf { it.length() }
    }
}
