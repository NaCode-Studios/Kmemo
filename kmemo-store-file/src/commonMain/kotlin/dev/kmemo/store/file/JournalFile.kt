package dev.kmemo.store.file

/**
 * The whole platform-specific surface of this module: read a file, append to it, replace it, delete it.
 *
 * Four operations, and that is the argument for building the store this way. A multiplatform SQLite
 * driver would bring decades of somebody else's work on durability and crash safety, and it does not
 * reach `wasmJs` at all, so it cannot satisfy a store that has to follow `kmemo-core` everywhere. A
 * hand-written *index* on disk would put B-trees and page management in a cache library, which is a lot
 * of surface for something whose working set is bounded by `maxEntries` and therefore already fits in
 * memory. What was actually missing was durability across a restart, and durability across a restart is
 * an append-only log. That log needs exactly these four operations, on every platform, and nothing else.
 *
 * [replace] must be atomic where the platform can make it so: compaction rewrites the journal, and a
 * process that dies halfway through a non-atomic rewrite loses the cache rather than a write.
 */
internal expect class JournalFile(path: String) {

    /** The file's whole contents, or `null` when there is no file yet. */
    fun readTextOrNull(): String?

    /** Appends [text] to the end, creating the file and its directory if needed. */
    fun append(text: String)

    /** Replaces the whole file with [text], atomically where the platform allows it. */
    fun replace(text: String)

    /** Removes the file. A no-op when there is nothing there. */
    fun delete()
}
