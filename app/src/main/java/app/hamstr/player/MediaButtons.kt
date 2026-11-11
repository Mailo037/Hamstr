package app.hamstr.player

import android.view.KeyEvent

/**
 * Headphones like Sony's XM5 send Play or Pause by what they think the player does, not a
 * toggle. When they are wrong, a tap sends Play into a song that already plays, or Pause into
 * one that is paused, and nothing would happen. Those turn into the other one: Play while
 * playing pauses, Pause while paused plays (when there is something to play). Everything else
 * (play/pause, the headset button, next, previous) is left to Media3.
 */
internal object MediaButtons {
    fun flips(keyCode: Int, playing: Boolean, hasItems: Boolean, ended: Boolean): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_MEDIA_PLAY -> playing
        KeyEvent.KEYCODE_MEDIA_PAUSE -> !playing && hasItems && !ended
        else -> false
    }
}
