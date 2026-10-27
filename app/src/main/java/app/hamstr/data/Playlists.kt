package app.hamstr.data

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** A playlist the user made: a name and files in the order they were added. */
data class UserList(val id: String, val name: String, val ids: List<String>, val createdAt: Long = System.currentTimeMillis())

/**
 * A playlist as the app shows it. Files that arrived together (a downloaded playlist) form
 * one on their own; the user's own lists sit beside them. Both can be renamed, added to,
 * taken from and deleted; deleting a list never touches its files.
 */
data class Playlist(val id: String, val name: String, val items: List<DownloadTask>, val downloaded: Boolean) {
    val favorites get() = id == Playlists.FAVORITES
    /** Every file in it is a song: the library shows its covers square. */
    val songs get() = items.isNotEmpty() && items.all { it.choice.mode == Mode.Audio }
}

object Playlists {
    private const val GROUP = "group:"
    private const val LIST = "list:"
    private const val FAVORITES_ID = "favorites"
    /** The list a swipe in the library fills; it exists from the first heart on. */
    const val FAVORITES = LIST + FAVORITES_ID

    private val _lists = MutableStateFlow<List<UserList>>(emptyList())
    val lists: StateFlow<List<UserList>> = _lists.asStateFlow()
    private lateinit var file: File
    private val saves = Channel<Unit>(Channel.CONFLATED)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Synchronized
    fun init(context: Context) {
        if (::file.isInitialized) return
        file = File(context.filesDir, "playlists.json")
        val atomic = AtomicFile(file)
        _lists.value = runCatching { decode(atomic.openRead().bufferedReader().use { it.readText() }) }.getOrDefault(emptyList())
        scope.launch {
            for (ignored in saves) {
                try {
                    val arr = JSONArray()
                    _lists.value.forEach { l ->
                        arr.put(JSONObject().apply {
                            put("id", l.id); put("name", l.name); put("createdAt", l.createdAt)
                            put("ids", JSONArray(l.ids))
                        })
                    }
                    val output = atomic.startWrite()
                    try {
                        output.write(arr.toString().toByteArray(Charsets.UTF_8))
                        atomic.finishWrite(output)
                    } catch (e: Exception) {
                        atomic.failWrite(output)
                        throw e
                    }
                } catch (e: Exception) {
                    Log.e("HamstrPlaylists", "Could not save playlists", e)
                }
            }
        }
    }

    internal fun decode(text: String): List<UserList> = JSONArray(text).let { a ->
        List(a.length()) { i ->
            val o = a.getJSONObject(i)
            val ids = o.optJSONArray("ids") ?: JSONArray()
            UserList(o.getString("id"), o.optString("name"), List(ids.length()) { ids.getString(it) }, o.optLong("createdAt"))
        }
    }

    /** Every playlist with at least one finished file: the user's own first (newest on top), then downloaded ones. */
    fun all(tasks: List<DownloadTask>, lists: List<UserList>): List<Playlist> {
        val done = tasks.filter { it.status == Status.Done && !it.hidden }
        val byId = done.associateBy { it.id }
        // Favorites lead, and only once something is in them.
        val own = lists.sortedWith(compareByDescending<UserList> { it.id == FAVORITES_ID }.thenByDescending { it.createdAt })
            .filter { it.id != FAVORITES_ID || it.ids.any(byId::containsKey) }.map { l -> Playlist(LIST + l.id, l.name, l.ids.mapNotNull(byId::get), downloaded = false) }
        val groups = done.filter { it.group != null }.groupBy { it.group!! }.map { (g, items) ->
            val sorted = items.sortedBy { it.createdAt }
            Playlist(GROUP + g, sorted.firstNotNullOfOrNull { it.groupTitle } ?: "", sorted, downloaded = true)
        }.sortedByDescending { p -> p.items.maxOf { it.createdAt } }
        // A new list is shown while still empty, so there is somewhere to add to; a downloaded
        // one down to its last file is just that file again, as in the library grid.
        return own + groups.filter { it.items.size > 1 }
    }

    fun find(id: String, tasks: List<DownloadTask>, lists: List<UserList>) = all(tasks, lists).firstOrNull { it.id == id }

    /** Makes a list and returns its playlist id. */
    fun create(name: String, items: List<DownloadTask> = emptyList()): String {
        val l = UserList(UUID.randomUUID().toString(), name, items.map { it.id }.distinct())
        _lists.update { it + l }
        save()
        return LIST + l.id
    }

    fun isFavorite(task: DownloadTask) = _lists.value.any { it.id == FAVORITES_ID && task.id in it.ids }

    /** Hearts [task] or takes the heart back; returns whether it is a favorite now. */
    fun toggleFavorite(task: DownloadTask, name: String): Boolean {
        val now = !isFavorite(task)
        _lists.update { all ->
            val fav = all.firstOrNull { it.id == FAVORITES_ID }
            when {
                fav == null -> all + UserList(FAVORITES_ID, name, listOf(task.id))
                now -> all.map { if (it.id == FAVORITES_ID) it.copy(ids = it.ids + task.id) else it }
                else -> all.map { if (it.id == FAVORITES_ID) it.copy(ids = it.ids - task.id) else it }
            }
        }
        save()
        return now
    }

    fun add(id: String, items: List<DownloadTask>) {
        when {
            id.startsWith(LIST) -> edit(id.removePrefix(LIST)) { l -> l.copy(ids = (l.ids + items.map { it.id }).distinct()) }
            id.startsWith(GROUP) -> {
                val g = id.removePrefix(GROUP)
                val title = DownloadStore.tasks.value.firstNotNullOfOrNull { t -> t.groupTitle?.takeIf { t.group == g } }
                items.forEach { t -> DownloadStore.update(t.id) { it.copy(group = g, groupTitle = title) } }
            }
        }
    }

    /** Moves a file to another place in the list (a downloaded list keeps its order by arrival times). */
    fun move(id: String, items: List<DownloadTask>, from: Int, to: Int) {
        if (from == to || from !in items.indices || to !in items.indices) return
        val order = items.toMutableList().apply { add(to, removeAt(from)) }
        when {
            id.startsWith(LIST) -> edit(id.removePrefix(LIST)) { l ->
                // Files that are gone stay where they were, after the ones shown.
                val shown = order.map { it.id }
                l.copy(ids = shown + l.ids.filterNot { it in shown })
            }
            id.startsWith(GROUP) -> {
                val times = items.map { it.createdAt }.sorted()
                order.forEachIndexed { i, t -> if (t.createdAt != times[i]) DownloadStore.update(t.id) { it.copy(createdAt = times[i]) } }
            }
        }
    }

    /** Takes a file off the list; the file itself stays. */
    fun remove(id: String, item: DownloadTask) {
        when {
            id.startsWith(LIST) -> edit(id.removePrefix(LIST)) { l -> l.copy(ids = l.ids - item.id) }
            id.startsWith(GROUP) -> DownloadStore.update(item.id) { it.copy(group = null, groupTitle = null) }
        }
    }

    fun rename(id: String, name: String) {
        when {
            id.startsWith(LIST) -> edit(id.removePrefix(LIST)) { it.copy(name = name) }
            id.startsWith(GROUP) -> {
                val g = id.removePrefix(GROUP)
                DownloadStore.tasks.value.filter { it.group == g }.forEach { t -> DownloadStore.update(t.id) { it.copy(groupTitle = name) } }
            }
        }
    }

    /** Removes the list; a downloaded one falls apart into single files. */
    fun delete(id: String) {
        when {
            id.startsWith(LIST) -> { _lists.update { all -> all.filterNot { it.id == id.removePrefix(LIST) } }; save() }
            id.startsWith(GROUP) -> {
                val g = id.removePrefix(GROUP)
                DownloadStore.tasks.value.filter { it.group == g }.forEach { t -> DownloadStore.update(t.id) { it.copy(group = null, groupTitle = null) } }
            }
        }
    }

    /** Swaps all lists at once; the debug build's demo mode uses it to stage and restore data. */
    fun replace(lists: List<UserList>) {
        _lists.value = lists
        save()
    }

    private fun edit(listId: String, block: (UserList) -> UserList) {
        _lists.update { all -> all.map { if (it.id == listId) block(it) else it } }
        save()
    }

    private fun save() {
        if (!::file.isInitialized) return
        saves.trySend(Unit)
    }
}
