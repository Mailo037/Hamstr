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

    /** [pause] for many (a playlist's Pause). */
    fun pauseAll(ids: List<String>) = ids.forEach(::pause)

    /** [resume] for many (a playlist, "Retry all"): the queues override it to look at the list once, not once each. */
    fun resumeAll(ids: List<String>) = ids.forEach(::resume)

    /** Starts downloads the night or the charger holds back (Schedule), or that wait for Wi-Fi, right away. */
    fun startNow(ids: List<String>) {
        val picked = ids.toHashSet()
        DownloadStore.updateAll(which = { it.id in picked }) {
            it.copy(anytime = true, tonight = false, mobileData = it.mobileData || it.stage == Stage.WaitingForWifi)
        }
        resumeAll(ids)
    }

    /** Puts downloads off to the night hours (Settings › Queue), running ones too; "Start now" undoes it. */
    fun tonight(ids: List<String>) {
        val picked = ids.toHashSet()
        val running = DownloadStore.tasks.value.filter { it.id in picked && it.status == Status.Running }.map { it.id }
        DownloadStore.updateAll(which = { it.id in picked }) { it.copy(tonight = true, anytime = false) }
        // A running one stops where it is and picks up from there at night; one by one, so each
        // resume comes after its pause.
        running.forEach { pause(it); resume(it) }
        resumeAll(ids.filter { it !in running })
    }

    /** Reads a channel or playlist without its formats, for Subscriptions. */
    suspend fun feed(url: String): MediaInfo

    /** Looks at every followed channel now ([Subscriptions.check] with force) and stashes what is new. */
    fun checkFeeds()

    /** The channel or artist page of a download, asking yt-dlp when the download doesn't know it. */
    suspend fun channelOf(t: DownloadTask): String?
}

object Downloads {
    lateinit var control: DownloadControl

    /** yt-dlp is updating itself before failed downloads are tried again ([DownloadControl.retry]). */
    val updating = MutableStateFlow(false)
}
