package app.hamstr.player

/** Resume only a positively identified video interruption. Unknown audio must not take
 * playback back from a music/podcast app after it stops, even if a video plays later. */
internal class PlaybackInterruption {
    enum class Source { Video, MusicOrUnknown }
    private var heardVideo = false
    private var handedOver = false
    val mayResume: Boolean get() = heardVideo && !handedOver

    fun observe(sources: List<Source>) {
        if (Source.Video in sources) heardVideo = true
        if (Source.MusicOrUnknown in sources) handedOver = true
    }

    fun reset() {
        heardVideo = false
        handedOver = false
    }
}
