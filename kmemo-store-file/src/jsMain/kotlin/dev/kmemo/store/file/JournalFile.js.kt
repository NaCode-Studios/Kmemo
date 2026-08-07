package dev.kmemo.store.file

/**
 * Node's own filesystem, reached the same way the Wasm implementation reaches it.
 *
 * `require` rather than an `external object`, because an external declaration binds to a global symbol
 * and `fs` is not one: it is a CommonJS module, and the module kind a Kotlin/JS build emits is a build
 * setting rather than something this file can rely on. Calling `require` is what Node itself does and
 * it reads the same on both JS targets.
 *
 * There is no filesystem in a browser, so this store is for Node, for an Electron or Capacitor main
 * process, and for the Kotlin/JS side of a desktop application. A browser build compiles and fails on
 * the first operation with Node's own error, which is a clearer report than a wrapper pretending to
 * have written something.
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
