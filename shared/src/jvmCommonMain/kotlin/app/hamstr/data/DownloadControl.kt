package app.hamstr.data

import app.hamstr.engine.MediaInfo
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * What screens can ask of the download queue. On the phone DownloadService answers, on the
 * desktop the app's own queue; each sets [Downloads.control] when it starts.
 */
interface DownloadControl {
    fun pause(id: String)
    fun resume(id: String)
    fun cancel(id: String)

    /**
     * Tries failed downloads again; with [updateFirst] after yt-dlp updated itself, for sites that
     * changed (Fix.UpdateYtDlp). [Downloads.updating] is true meanwhile.
     */
    fun retry(ids: List<String>, updateFirst: Boolean)

    /** Starts downloads the night or the charger holds back (Schedule) right away. */
    fun startNow(ids: List<String>) {
        ids.forEach { id ->
            DownloadStore.update(id) { it.copy(anytime = true) }
            resume(id)
        }
    }

    /** Reads a channel or playlist without its formats, for Subscriptions. */
    suspend fun feed(url: String): MediaInfo

    /** Looks at every followed channel now ([Subscriptions.check] with force) and stashes what is new. */
    fun checkFeeds()
}

object Downloads {
    lateinit var control: DownloadControl

    /** yt-dlp is updating itself before failed downloads are tried again ([DownloadControl.retry]). */
    val updating = MutableStateFlow(false)
}
