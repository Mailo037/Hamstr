package app.hamstr.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/** Finished downloads whose file was deleted outside Hamstr. */
object FileChecks {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _missing = MutableStateFlow<Set<String>>(emptySet())
    val missing: StateFlow<Set<String>> = _missing.asStateFlow()

    /** Looks for finished downloads whose file is gone; cheap enough to run on every return to the app. */
    fun refresh() {
        scope.launch {
            _missing.value = DownloadStore.tasks.value
                .filter { it.status == Status.Done && it.filePath != null && !File(it.filePath).exists() }
                .map { it.id }.toSet()
        }
    }
}
