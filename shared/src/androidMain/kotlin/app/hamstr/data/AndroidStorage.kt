package app.hamstr.data

import android.content.Context
import android.content.SharedPreferences

private class SharedPrefs(private val sp: SharedPreferences) : KeyValues {
    override fun getString(key: String, default: String?) = sp.getString(key, default)
    override fun getInt(key: String, default: Int) = sp.getInt(key, default)
    override fun getLong(key: String, default: Long) = sp.getLong(key, default)
    override fun getBoolean(key: String, default: Boolean) = sp.getBoolean(key, default)
    override fun all(): Map<String, Any?> = sp.all
    override fun edit(): KeyValues.Editor = object : KeyValues.Editor {
        private val e = sp.edit()
        override fun putString(key: String, value: String?) = apply { e.putString(key, value) }
        override fun putInt(key: String, value: Int) = apply { e.putInt(key, value) }
        override fun putLong(key: String, value: Long) = apply { e.putLong(key, value) }
        override fun putBoolean(key: String, value: Boolean) = apply { e.putBoolean(key, value) }
        override fun remove(key: String) = apply { e.remove(key) }
        override fun apply() = e.apply()
    }
}

fun Prefs.init(context: Context) = init(SharedPrefs(context.getSharedPreferences("hamstr", Context.MODE_PRIVATE)))

fun Positions.init(context: Context) = init(SharedPrefs(context.getSharedPreferences("positions", Context.MODE_PRIVATE)))

fun DownloadStore.init(context: Context) = init(context.filesDir)

/** Another small store in SharedPreferences, e.g. Nearby's keys. */
fun keyValues(context: Context, name: String): KeyValues = SharedPrefs(context.getSharedPreferences(name, Context.MODE_PRIVATE))
