package dev.kmemo.store.file

/**
 * A directory that exists on every target the tests run on.
 *
 * [JournalFile] deliberately does not create directories on the native targets, because `mkdir` takes a
 * mode on POSIX and none on Windows and a cache is the wrong place to grow a portable filesystem layer.
 * The tests therefore have to name somewhere that is already there, and the system temporary directory
 * is the one place every platform agrees on.
 */
internal expect fun temporaryDirectory(): String

private var counter = 0

/**
 * A journal path no other test is using, in this run or in any earlier one.
 *
 * The run token is the part that matters. A file outlives the process that wrote it, so a path built
 * from a counter alone would hand the second run of the suite the first run's journal, and the store
 * would open onto entries a test never wrote.
 */
private val run = kotlin.random.Random.nextInt(Int.MAX_VALUE).toString(RADIX)

internal fun journalPath(name: String): String =
    "${temporaryDirectory()}/kmemo-$name-${platformTag()}-$run-${++counter}.journal"

private const val RADIX = 36

internal expect fun platformTag(): String
