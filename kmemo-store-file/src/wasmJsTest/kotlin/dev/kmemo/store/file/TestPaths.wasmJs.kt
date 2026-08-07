@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.kmemo.store.file

internal actual fun temporaryDirectory(): String = nodeTemporaryDirectory()

internal actual fun platformTag(): String = "wasm"

private fun nodeTemporaryDirectory(): String = js("require('os').tmpdir()")
