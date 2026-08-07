@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.kmemo.store.file

/**
 * Node's filesystem through Wasm's JS interop.
 *
 * Kotlin/Wasm cannot declare an `external object` for a CommonJS module the way Kotlin/JS can, so each
 * call is a `js(...)` function instead. They are one-liners on purpose: everything that can be decided
 * in Kotlin is decided in Kotlin, so the JS here stays small enough to read in one go.
 *
 * There is no filesystem in a browser. See the JS implementation for what that means.
 */
internal actual class JournalFile actual constructor(private val path: String) {

    actual fun readTextOrNull(): String? = if (nodeExists(path)) nodeRead(path) else null

    actual fun append(text: String) {
        makeParentDirectory()
        nodeAppend(path, text)
    }

    actual fun replace(text: String) {
        makeParentDirectory()
        val temporary = "$path.compacting"
        nodeWrite(temporary, text)
        nodeRename(temporary, path)
    }

    actual fun delete() {
        if (nodeExists(path)) nodeUnlink(path)
    }

    private fun makeParentDirectory() {
        val separator = maxOf(path.lastIndexOf('/'), path.lastIndexOf('\\'))
        if (separator <= 0) return
        val parent = path.substring(0, separator)
        if (!nodeExists(parent)) nodeMkdirs(parent)
    }
}

private fun nodeExists(path: String): Boolean = js("require('fs').existsSync(path)")

private fun nodeRead(path: String): String = js("require('fs').readFileSync(path, 'utf8')")

private fun nodeAppend(path: String, data: String) { js("require('fs').appendFileSync(path, data)") }

private fun nodeWrite(path: String, data: String) { js("require('fs').writeFileSync(path, data)") }

private fun nodeRename(from: String, to: String) { js("require('fs').renameSync(from, to)") }

private fun nodeUnlink(path: String) { js("require('fs').unlinkSync(path)") }

private fun nodeMkdirs(path: String) { js("require('fs').mkdirSync(path, { recursive: true })") }
