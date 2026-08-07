@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.kmemo.store.file

import kotlinx.cinterop.toKString
import platform.posix.getenv

internal actual fun temporaryDirectory(): String =
    sequenceOf("TMPDIR", "TEMP", "TMP")
        .mapNotNull { getenv(it)?.toKString() }
        .firstOrNull { it.isNotBlank() }
        ?.trimEnd('/', '\\')
        ?: "/tmp"

internal actual fun platformTag(): String = "native"
