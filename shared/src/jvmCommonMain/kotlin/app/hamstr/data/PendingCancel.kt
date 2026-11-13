package app.hamstr.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Cancel with Undo, for both apps. A canceled download is paused first, so its parts stay, and
 * hidden from the lists; it is really canceled ([DownloadControl.cancel], which deletes the parts)
 * only once Undo is no longer offered. Undo brings it back as it was. If the app dies in between,
 * it simply stays paused.
 */
object PendingCancel {
    /** How long Undo is offered; the snack stays as long. */
    const val UNDO_MS = 8_000L

    private val _pending = MutableStateFlow<Set<String>>(emptySet())

    /** Downloads hidden from the lists while Undo is possible. */
    val pending: StateFlow<Set<String>> = _pending.asStateFlow()

    private val _requests = MutableSharedFlow<DownloadTask>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Once per request, for the snack ("Download canceled · Undo"). */
    val requests: SharedFlow<DownloadTask> = _requests.asSharedFlow()

    /** [resume]: it was running or waiting, so Undo sets it going again. */
    private class Entry(val resume: Boolean, val timer: Job)

    private val entries = HashMap<String, Entry>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Pauses [task] (its parts are kept), hides it and cancels it for real after [UNDO_MS]. */
    fun request(task: DownloadTask) {
        val now = DownloadStore.get(task.id) ?: task
        // Only what is still on its way can be canceled; a second request for the same one changes nothing.
        if (!now.status.active && now.status != Status.Paused) return
        synchronized(this) {
            if (now.id in entries) return
            val timer = scope.launch {
                delay(UNDO_MS)
                expire(now.id)
            }
            entries[now.id] = Entry(resume = now.status.active, timer = timer)
            _pending.update { it + now.id }
        }
        if (now.status.active) runCatching { Downloads.control.pause(now.id) }
        _requests.tryEmit(now)
    }

    /** Back as it was: what ran or waited goes on, what was paused stays paused. Too late: nothing. */
    fun undo(id: String) {
        val entry = synchronized(this) {
            entries.remove(id)?.also {
                it.timer.cancel()
                _pending.update { s -> s - id }
            }
        } ?: return
        // Finished or failed meanwhile (the pause came too late): it just shows again as it is.
        if (entry.resume && DownloadStore.get(id)?.status == Status.Paused) runCatching { Downloads.control.resume(id) }
    }

    private suspend fun expire(id: String) {
        synchronized(this) { entries.remove(id) } ?: return
        val t = DownloadStore.get(id)
        // Finished meanwhile: nothing left to cancel, the file stays.
        if (t != null && (t.status.active || t.status == Status.Paused)) {
            runCatching { Downloads.control.cancel(id) }
            // Hidden until the queue has taken it off (the phone's service answers a moment later),
            // so it doesn't flash back as paused in between.
            withTimeoutOrNull(5_000) {
                DownloadStore.tasks.first { list -> list.none { it.id == id && (it.status.active || it.status == Status.Paused) } }
            }
        }
        _pending.update { it - id }
    }
}
