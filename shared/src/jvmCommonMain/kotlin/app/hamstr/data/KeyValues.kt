package app.hamstr.data

/**
 * Small saved settings, shaped like Android's SharedPreferences: SharedPreferences
 * itself on the phone, a properties file on the desktop.
 */
interface KeyValues {
    fun getString(key: String, default: String?): String?
    fun getInt(key: String, default: Int): Int
    fun getLong(key: String, default: Long): Long
    fun getBoolean(key: String, default: Boolean): Boolean
    fun all(): Map<String, Any?>
    fun edit(): Editor

    interface Editor {
        fun putString(key: String, value: String?): Editor
        fun putInt(key: String, value: Int): Editor
        fun putLong(key: String, value: Long): Editor
        fun putBoolean(key: String, value: Boolean): Editor
        fun remove(key: String): Editor
        fun apply()
    }
}
