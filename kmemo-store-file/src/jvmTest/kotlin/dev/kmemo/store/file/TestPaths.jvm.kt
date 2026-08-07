package dev.kmemo.store.file

internal actual fun temporaryDirectory(): String = System.getProperty("java.io.tmpdir").trimEnd('/', '\\')

internal actual fun platformTag(): String = "jvm"
