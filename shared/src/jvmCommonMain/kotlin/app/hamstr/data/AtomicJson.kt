package app.hamstr.data

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * A text file that is replaced whole: written next to itself and moved over in one step,
 * so a crash mid-write leaves the old contents. Reads the backup Android's AtomicFile
 * leaves behind when it was interrupted.
 */
class AtomicJson(private val file: File) {
    private val backup = File(file.path + ".bak")

    fun read(): String = (if (file.exists() || !backup.exists()) file else backup).readText()

    fun write(text: String) {
        file.parentFile?.mkdirs()
        val next = File(file.path + ".new")
        next.writeText(text)
        Files.move(next.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        backup.delete()
    }
}
