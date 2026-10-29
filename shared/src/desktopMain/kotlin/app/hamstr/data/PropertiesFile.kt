package app.hamstr.data

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties

/** [KeyValues] in a properties file, written whole and swapped in on every change. */
class PropertiesFile(private val file: File) : KeyValues {
    private val props = Properties().apply {
        if (file.isFile) file.inputStream().use(::load)
    }

    override fun getString(key: String, default: String?): String? = props.getProperty(key) ?: default
    override fun getInt(key: String, default: Int) = props.getProperty(key)?.toIntOrNull() ?: default
    override fun getLong(key: String, default: Long) = props.getProperty(key)?.toLongOrNull() ?: default
    override fun getBoolean(key: String, default: Boolean) = props.getProperty(key)?.toBooleanStrictOrNull() ?: default
    override fun all(): Map<String, Any?> = props.stringPropertyNames().associateWith { props.getProperty(it) }

    override fun edit(): KeyValues.Editor = object : KeyValues.Editor {
        private val changes = LinkedHashMap<String, String?>()
        override fun putString(key: String, value: String?) = apply { changes[key] = value }
        override fun putInt(key: String, value: Int) = apply { changes[key] = value.toString() }
        override fun putLong(key: String, value: Long) = apply { changes[key] = value.toString() }
        override fun putBoolean(key: String, value: Boolean) = apply { changes[key] = value.toString() }
        override fun remove(key: String) = apply { changes[key] = null }
        override fun apply() = save(changes)
    }

    @Synchronized
    private fun save(changes: Map<String, String?>) {
        changes.forEach { (k, v) -> if (v == null) props.remove(k) else props.setProperty(k, v) }
        file.parentFile?.mkdirs()
        val next = File(file.path + ".new")
        next.outputStream().use { props.store(it, "Hamstr") }
        Files.move(next.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
