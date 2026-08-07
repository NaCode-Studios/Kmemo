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

/** iOS, macOS and Linux, where `off_t` and `size_t` are both 64 bits. See the Windows copy. */
internal actual object RawFile {

    actual fun readAll(path: String): ByteArray? {
        val file = fopen(path, "rb") ?: return null
        try {
            fseek(file, 0, SEEK_END)
            val length = ftell(file).toInt()
            if (length <= 0) return ByteArray(0)
            rewind(file)
            val bytes = ByteArray(length)
            val read = bytes.usePinned { fread(it.addressOf(0), 1u, length.convert(), file) }
            return bytes.copyOf(read.toInt())
        } finally {
            fclose(file)
        }
    }

    actual fun write(path: String, bytes: ByteArray, append: Boolean) {
        val file = fopen(path, if (append) "ab" else "wb") ?: error("cannot open $path for writing")
        try {
            if (bytes.isEmpty()) return
            val written = bytes.usePinned { fwrite(it.addressOf(0), 1u, bytes.size.convert(), file) }
            check(written.toInt() == bytes.size) { "wrote $written of ${bytes.size} bytes to $path" }
        } finally {
            fclose(file)
        }
    }

    actual fun move(from: String, to: String) {
        rename(from, to)
    }

    actual fun delete(path: String) {
        remove(path)
    }
}
