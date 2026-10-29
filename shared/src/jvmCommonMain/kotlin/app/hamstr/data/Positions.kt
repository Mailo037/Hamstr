package app.hamstr.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Where each longer video or song was left, so it carries on from there next time. */
object Positions {
    data class Resume(val position: Long, val duration: Long) {
        val fraction get() = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    }

    // Short clips just start over; the last seconds count as finished.
    private const val MIN_LENGTH = 60_000L
    private const val MIN_POSITION = 10_000L
    private const val END_MARGIN = 15_000L

    private lateinit var sp: KeyValues
    private val _all = MutableStateFlow<Map<String, Resume>>(emptyMap())
    val all: StateFlow<Map<String, Resume>> = _all.asStateFlow()

    fun init(store: KeyValues) {
        sp = store
        _all.value = sp.all().mapNotNull { (id, v) ->
            val (pos, dur) = (v as? String)?.split(',')?.mapNotNull { it.toLongOrNull() }?.takeIf { it.size == 2 } ?: return@mapNotNull null
            id to Resume(pos, dur)
        }.toMap()
    }

    fun save(id: String, position: Long, duration: Long) {
        if (!::sp.isInitialized) return
        if (duration < MIN_LENGTH || position < MIN_POSITION || position > duration - END_MARGIN) return clear(id)
        _all.update { it + (id to Resume(position, duration)) }
        sp.edit().putString(id, "$position,$duration").apply()
    }

    fun clear(id: String) {
        if (!::sp.isInitialized || id !in _all.value) return
        _all.update { it - id }
        sp.edit().remove(id).apply()
    }

    fun resumeAt(id: String): Long = _all.value[id]?.position ?: 0
}
