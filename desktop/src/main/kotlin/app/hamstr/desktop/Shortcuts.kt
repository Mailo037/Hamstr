package app.hamstr.desktop

import androidx.compose.ui.input.key.KeyEvent
import app.hamstr.cast.Cast
import app.hamstr.cast.CastSession

/**
 * The window's keys: DesktopApp puts its handler here, and the window hands it every key that
 * nothing focused took (a text field keeps its Space and arrows).
 */
internal object Shortcuts {
    var handle: (KeyEvent) -> Boolean = { false }
}

/**
 * What plays now, steered from outside the player: the keys anywhere in the window and the wheel
 * over the mini player. What another Hamstr plays here comes first, then the window's own player,
 * whose commands go to the TV or speaker while it plays there.
 */
internal object PlayingNow {
    private var beforeMute: Float? = null

    private fun local(): FfPlayer? = DesktopStage.now.value?.player ?: DesktopPlayback.player.value

    private fun remote(): CastSession? {
        if (DesktopStage.now.value != null) return null
        val t = DesktopPlayback.current.value ?: return null
        return Cast.session.value?.takeIf { it.media.value?.id == t.id }
    }

    val active: Boolean get() = local() != null

    fun toggle(): Boolean {
        remote()?.let { it.toggle(); return true }
        val p = local() ?: return false
        p.toggle()
        return true
    }

    fun seekBy(seconds: Double): Boolean {
        remote()?.let { it.seek(it.status.value.positionNow() + seconds); return true }
        val p = local() ?: return false
        p.seek(p.position.value + seconds)
        return true
    }

    fun next(): Boolean {
        if (!active) return false
        if (DesktopStage.now.value != null) DesktopStage.next() else DesktopPlayback.next()
        return true
    }

    fun previous(): Boolean {
        if (!active) return false
        if (DesktopStage.now.value != null) DesktopStage.previous() else DesktopPlayback.previous()
        return true
    }

    private fun volume(): Float? = remote()?.let { it.status.value.volume ?: 1f } ?: local()?.loudness?.value

    private fun setVolume(v: Float): Boolean {
        val level = v.coerceIn(0f, 1f)
        remote()?.let { it.setVolume(level); return true }
        val p = local() ?: return false
        p.setVolume(level)
        return true
    }

    /** Louder or quieter by [delta] (0.05 is a step of the keys and the wheel). */
    fun volumeBy(delta: Float): Boolean {
        val v = volume() ?: return false
        if (delta > 0) beforeMute = null
        return setVolume(v + delta)
    }

    /** Silent, and back to the level it had. */
    fun toggleMute(): Boolean {
        val v = volume() ?: return false
        return if (v > 0f) {
            beforeMute = v
            setVolume(0f)
        } else {
            setVolume(beforeMute ?: 1f).also { beforeMute = null }
        }
    }
}
