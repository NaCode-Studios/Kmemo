package dev.kmemo.store.file

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal actual class JournalFile actual constructor(path: String) {

    private val file = File(path)

    actual fun readTextOrNull(): String? = if (file.isFile) file.readText() else null

    actual fun append(text: String) {
        file.parentFile?.mkdirs()
        file.appendText(text)
    }

    actual fun replace(text: String) {
        file.parentFile?.mkdirs()
        val temporary = File(file.path + ".compacting")
        temporary.writeText(text)
        // ATOMIC_MOVE where the filesystem supports it: a process that dies during a compaction then
        // opens onto the journal it had before rather than onto half of a rewritten one.
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    actual fun delete() {
        file.delete()
    }
}
