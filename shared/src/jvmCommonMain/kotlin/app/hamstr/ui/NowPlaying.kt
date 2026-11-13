package app.hamstr.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The download the player has open, so the lists can mark it; each app's player keeps it current. */
object NowPlaying {
    val id = MutableStateFlow<String?>(null)

    private val _played = MutableStateFlow<Set<String>>(emptySet())

    /**
     * Everything the player opened since the app started. A new file's check in the stash goes
     * once it was played (Positions only knows the longer ones that were left halfway).
     */
    val played: StateFlow<Set<String>> = _played.asStateFlow()

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            id.collect { now -> if (now != null) _played.update { it + now } }
        }
    }
}
