package dev.kmemo.store.file

/**
 * The C standard library, and nothing above it.
 *
 * `fopen`, `fread`, `fwrite`, `rename` and `remove` are the same five calls everywhere Kotlin/Native
 * goes, and a wrapper library would be a dependency in a module whose argument is that the core has one,
 * for functions that have not changed since 1989.
 *
 * The calls themselves are one level down, in [RawFile], because their **types** are not the same
 * everywhere: `ftell` and `fread` are declared with different integer widths on Windows than on POSIX,
 * and the compiler refuses to compile a source set shared across both when a platform-varying width
 * reaches an expression in it. [RawFile]'s signature is `Int`, `String` and `ByteArray`, which every
 * platform agrees on, so all the logic stays here and only the four calls are written twice.
 *
 * Directory creation is deliberately absent: `mkdir` takes a mode argument on POSIX and none on Windows,
 * so the platform difference is real rather than incidental, and a cache is not the right place to grow
 * a portable filesystem layer. Point the store at a directory that exists, which on a phone is the one
 * the platform already gave the application.
 */
internal actual class JournalFile actual constructor(private val path: String) {

    actual fun readTextOrNull(): String? = RawFile.readAll(path)?.decodeToString()

    actual fun append(text: String) {
        RawFile.write(path, text.encodeToByteArray(), append = true)
    }

    actual fun replace(text: String) {
        val temporary = "$path.compacting"
        RawFile.write(temporary, text.encodeToByteArray(), append = false)
        // rename over an existing file is atomic on POSIX. On Windows it is not, and the C runtime
        // refuses it outright, so the old file is removed first and the window between the two is the
        // price of the platform.
        RawFile.delete(path)
        RawFile.move(temporary, path)
    }

    actual fun delete() {
        RawFile.delete(path)
    }
}

/**
 * The four raw file operations, with a signature no platform disagrees about.
 *
 * Everything in it is written twice, once for POSIX and once for Windows, and the two bodies are the
 * same code. That is not an oversight: what differs is the width of the integers the C library declares,
 * which is exactly what cannot be shared, and hiding the difference behind a common signature is the
 * only way to keep the rest of this module written once.
 */
internal expect object RawFile {

    /** The whole file, or `null` when there is none. */
    fun readAll(path: String): ByteArray?

    /** Writes [bytes], appending to the file or replacing it. */
    fun write(path: String, bytes: ByteArray, append: Boolean)

    /** Renames [from] onto [to]. */
    fun move(from: String, to: String)

    /** Removes [path]. A no-op when there is nothing there. */
    fun delete(path: String)
}
