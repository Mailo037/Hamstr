package app.hamstr.data

import app.hamstr.Platform
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Everything Hamstr knows, in one file to keep or to take to another device: the settings, the
 * download list (titles, links and where the files were), resume positions and what only one
 * platform has (the phone's playlists, as [extras]). Never the files themselves, and never
 * Nearby's key: a device's key is who it is, and it stays on the device.
 */
object Backup {
    private const val KIND = "hamstr-backup"
    private const val FORMAT = 1

    /** Settings that belong to this device; a backup coming in leaves them as they are. */
    private val LOCAL = setOf(
        "lastUpdateCheck", "lastCheekCheck", "lastAppCheck", "seenRelease", "askedNotifications", "notifications",
        "deviceName", "toured", "language", "trayOnClose", "startWithSystem", "transferJobs",
    )

    class Restored(val downloads: Int, val positions: Int, val extras: JSONObject)

    /** "hamstr-backup-2026-11-03.json". */
    fun fileName(now: Long = System.currentTimeMillis()) = "hamstr-backup-" + SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date(now)) + ".json"

    fun export(version: String, extras: Map<String, Any> = emptyMap()): String = JSONObject().apply {
        put("kind", KIND)
        put("format", FORMAT)
        put("version", version)
        put("platform", platform())
        put("created", System.currentTimeMillis())
        put("settings", JSONObject(Prefs.encode()))
        put("downloads", JSONArray(DownloadStore.encode()))
        put("positions", JSONObject().apply { Positions.all.value.forEach { (id, r) -> put(id, JSONArray(listOf(r.position, r.duration))) } })
        put("extras", JSONObject(extras))
    }.toString(2)

    /**
     * Takes a backup in: its settings replace this device's (except [LOCAL] ones, and the download
     * folder when it comes from the other platform), downloads and positions are added to the ones
     * here. Throws [IllegalArgumentException] for a file that isn't a Hamstr backup.
     */
    fun restore(text: String): Restored {
        val o = runCatching { JSONObject(text) }.getOrNull()
        require(o != null && o.optString("kind") == KIND) { "Not a Hamstr backup" }
        require(o.optInt("format") in 1..FORMAT) { "This backup is from a newer Hamstr" }
        o.optJSONObject("settings")?.let { saved ->
            val samePlatform = o.optString("platform") == platform()
            val incoming = saved.keys().asSequence()
                .filter { it !in LOCAL && (samePlatform || it != "downloadFolder") }
                .associateWith { saved.opt(it).takeIf { v -> v != JSONObject.NULL } }
            Prefs.edit { Prefs.decode(Prefs.encode(it) + incoming) }
        }
        val downloads = o.optJSONArray("downloads")?.let { DownloadStore.merge(DownloadStore.decode(it.toString())) } ?: 0
        val positions = o.optJSONObject("positions")?.let { p ->
            p.keys().asSequence().mapNotNull { id ->
                val pair = p.optJSONArray(id) ?: return@mapNotNull null
                id to Positions.Resume(pair.optLong(0), pair.optLong(1))
            }.toMap()
        }.orEmpty()
        val before = Positions.all.value.size
        Positions.restore(positions)
        return Restored(downloads, Positions.all.value.size - before, o.optJSONObject("extras") ?: JSONObject())
    }

    private fun platform() = if (Platform.desktop) "desktop" else "android"
}
