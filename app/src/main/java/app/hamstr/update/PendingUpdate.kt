package app.hamstr.update

import app.hamstr.data.AtomicJson
import org.json.JSONObject
import java.io.File

/** Kept in private files, so a verified APK survives closing or restarting Hamstr. */
internal class PendingUpdate(private val directory: File) {
    val apk get() = File(directory, "update.apk")
    val partial get() = File(directory, "update.apk.part")
    private val metadata get() = AtomicJson(File(directory, "state.json"))

    fun save(release: Release, ready: Boolean) = metadata.write(
        release.toJson().put("ready", ready).toString(),
    )

    fun read(): Pair<Release, Boolean>? = runCatching {
        val json = JSONObject(metadata.read())
        val release = Release.fromJson(json)
        release to (json.optBoolean("ready") && apk.isFile && apk.length() > 0 && (release.size <= 0 || apk.length() == release.size))
    }.getOrNull()

    fun clear() { directory.listFiles()?.forEach { it.delete() } }
}

internal fun Release.toJson(): JSONObject = JSONObject()
    .put("version", version).put("notes", notes).put("apkUrl", apkUrl)
    .put("apkName", apkName).put("size", size).put("page", page)

internal fun Release.Companion.fromJson(json: JSONObject) = Release(
    json.getString("version"), json.optString("notes"), json.getString("apkUrl"),
    json.getString("apkName"), json.optLong("size"), json.getString("page"),
)
