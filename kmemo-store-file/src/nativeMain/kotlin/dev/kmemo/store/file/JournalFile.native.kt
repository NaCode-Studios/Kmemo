@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.kmemo.store.file

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.posix.SEEK_END
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell
import platform.posix.fwrite
import platform.posix.remove
import platform.posix.rename
import platform.posix.rewind

/**
 * The C standard library, and nothing above it.
 *
 * One implementation covers iOS, macOS, Linux and Windows, because `fopen`, `fread`, `fwrite`,
 * `rename` and `remove` are the same five calls everywhere Kotlin/Native goes. A wrapper library would
 * be a dependency in a module whose argument is that the core has one, for five functions that have not
 * changed since 1989.
 *
 * Directory creation is the exception and is left to the caller: `mkdir` takes a mode argument on POSIX
 * and none on Windows, so the platform difference is real rather than incidental, and a cache is not
 * the right place to grow a portable filesystem layer. Point the store at a directory that exists,
 * which on a phone is the one the platform already gave the application.
 */
internal actual class JournalFile actual constructor(private val path: String) {

    actual fun readTextOrNull(): String? {
        val file = fopen(path, "rb") ?: return null
        try {
            fseek(file, 0.convert(), SEEK_END)
            val length = ftell(file)
            if (length <= 0) return ""
            rewind(file)
            val bytes = ByteArray(length.convert())
            val read = bytes.usePinned { fread(it.addressOf(0), 1.convert(), length.convert(), file) }
            return bytes.decodeToString(0, read.convert())
        } finally {
            fclose(file)
        }
    }

    actual fun append(text: String) = write(path, text, "ab")

    actual fun replace(text: String) {
        val temporary = "$path.compacting"
        write(temporary, text, "wb")
        // rename over an existing file is atomic on POSIX. On Windows it is not, and the C runtime
        // refuses it outright, so the old file is removed first and the window between the two is the
        // price of the platform.
        remove(path)
        rename(temporary, path)
    }

    actual fun delete() {
        remove(path)
    }

    private fun write(target: String, text: String, mode: String) {
        val file = fopen(target, mode) ?: error("cannot open $target for writing")
        try {
            val bytes = text.encodeToByteArray()
            if (bytes.isEmpty()) return
            bytes.usePinned { fwrite(it.addressOf(0), 1.convert(), bytes.size.convert(), file) }
        } finally {
            fclose(file)
        }
    }
}
