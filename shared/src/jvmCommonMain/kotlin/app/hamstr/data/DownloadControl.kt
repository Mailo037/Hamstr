package app.hamstr.data

/**
 * What screens can ask of the download queue. On the phone DownloadService answers, on the
 * desktop the app's own queue; each sets [Downloads.control] when it starts.
 */
interface DownloadControl {
    fun pause(id: String)
    fun resume(id: String)
    fun cancel(id: String)
}

object Downloads {
    lateinit var control: DownloadControl
}
