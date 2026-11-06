package app.hamstr.ui

import kotlinx.coroutines.flow.MutableStateFlow

/** The download the player has open, so the lists can mark it; each app's player keeps it current. */
object NowPlaying {
    val id = MutableStateFlow<String?>(null)
}
