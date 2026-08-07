package dev.kmemo.store.file

internal actual fun temporaryDirectory(): String = nodeTemporaryDirectory()

internal actual fun platformTag(): String = "js"

private fun nodeTemporaryDirectory(): String = js("require('os').tmpdir()")
