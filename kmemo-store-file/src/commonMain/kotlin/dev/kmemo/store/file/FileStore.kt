package dev.kmemo.store.file

import dev.kmemo.CacheEntry
import dev.kmemo.CacheListener
import dev.kmemo.CacheStore
import dev.kmemo.Quantization
import dev.kmemo.ScoredEntry
import dev.kmemo.store.InMemoryStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Duration

/**
 * A [CacheStore] that survives the process, on every target `kmemo-core` publishes.
 *
 * ```kotlin
 * val cache = semanticCache(embedder) {
 *     store = FileStore(path = "$appSupportDirectory/kmemo.journal", maxEntries = 5_000)
 * }
 * ```
 *
 * `kmemo-core` has built for iOS, macOS, Linux, Windows, JS and WasmJS since `2.0.0`, and the argument
 * for that release was a good one: a phone pays for every call over a mobile network, a browser has no
 * server to cache on, and an edge deployment may have no reliable uplink. Those are the deployments
 * where a cache pays for itself fastest, and until this they were also the deployments that lost the
 * entire cache every time the process ended, because `InMemoryStore` was the only store that built off
 * the JVM. An iOS app cached for the length of one session and started cold on every launch, so a user
 * who asked the same question on Monday and on Tuesday paid twice, on the connection that costs most.
 *
 * ### Why it is a journal over memory, and not a database
 *
 * The obvious answers were a multiplatform SQLite driver, which brings decades of somebody else's work
 * on durability and crash safety, and a hand-written file-backed index, which keeps the dependency count
 * where the README is proud of it. The first does not reach `wasmJs` at all, so it cannot satisfy a
 * store that has to follow `kmemo-core` everywhere, and that settles it on availability rather than on
 * taste. The second is the wrong shape for a cache: an on-disk *index* exists so the working set can
 * exceed memory, and a cache's working set is bounded by [maxEntries] by construction, so it already
 * fits. What was missing was never the index. It was durability across a restart.
 *
 * So the index stays in memory, where the exact scan already passes the store contract on every target,
 * and every mutation is appended to a log. Opening replays the log. That puts four operations on the
 * platform seam, read, append, replace and delete, instead of a driver.
 *
 * ### What it costs
 *
 * **Memory holds everything.** This is [InMemoryStore] with a log beside it, so [maxEntries] and
 * [maxBytes] bound the store exactly as they do there, and a journal larger than memory is not a
 * situation this store can be in. Reach for Postgres, Redis or Qdrant when the cache has to be bigger
 * than one process, or shared between several.
 *
 * **A write is a file append.** Ordinary buffered IO on the calling coroutine, not an fsync per write,
 * so a power cut can lose the last writes. For a cache that is the right trade in both directions: a
 * lost write is a future miss, and an fsync per write would cost more than the model call the entry was
 * saving.
 *
 * **One writer.** A journal is a single file with an append-only tail, so two processes pointed at one
 * path will interleave records and neither will be able to read the other's. Give each process its own
 * path, or use a store that is a server.
 *
 * ### Restart, truncation and compaction
 *
 * On the first operation the journal is read and replayed into the index, in order, which reproduces
 * evictions and expiry as they happened. A tail truncated by a process that died mid-append is dropped
 * rather than refused: the records before the tear are intact and describe a cache that was real, and
 * turning one lost write into a lost cache would be the wrong direction for a cache to fail in.
 *
 * The log is compacted when it grows past [compactAfter] records, by rewriting it as one `put` per live
 * entry. The rewrite goes to a temporary file and is moved into place, so a process that dies during a
 * compaction still opens onto the journal it had before.
 *
 * @param path where the journal lives. Its directory is created if it does not exist.
 * @param maxEntries and @param maxBytes bound the resident index, exactly as on [InMemoryStore].
 * @param ttl how long an entry may be served, applied on replay as well as at runtime.
 * @param compactAfter journal records tolerated before the log is rewritten from the live entries.
 * @param clock the time source, for entry expiry.
 * @param listener sees the store's own events, chiefly eviction. See [InMemoryStore].
 * @param quantization compresses the resident vectors, exactly as on [InMemoryStore]. The journal
 *   always holds the full-precision vector, so turning quantization on or off does not require the
 *   cache to be rebuilt.
 */
public class FileStore(
    private val path: String,
    maxEntries: Int = InMemoryStore.DEFAULT_MAX_ENTRIES,
    ttl: Duration? = null,
    maxBytes: Long? = null,
    private val compactAfter: Int = DEFAULT_COMPACT_AFTER,
    clock: Clock = Clock.System,
    listener: CacheListener? = null,
    quantization: Quantization = Quantization.NONE,
) : CacheStore {

    init {
        require(path.isNotBlank()) { "path must not be blank" }
        require(compactAfter > 0) { "compactAfter must be positive, was $compactAfter" }
    }

    private val index = InMemoryStore(
        maxEntries = maxEntries,
        ttl = ttl,
        clock = clock,
        maxBytes = maxBytes,
        listener = listener,
        quantization = quantization,
    )

    private val file = JournalFile(path)
    private val gate = Mutex()
    private var loaded = false
    private var records = 0

    override suspend fun put(entry: CacheEntry) {
        loaded()
        index.put(entry)
        // After the index, not before. A record for a write the index refused, on a dimension mismatch,
        // would replay into a store that then refuses it again on every open.
        write(Record.Put(entry))
    }

    override suspend fun search(scope: String, embedding: FloatArray, limit: Int): List<ScoredEntry> {
        loaded()
        return index.search(scope, embedding, limit)
    }

    override suspend fun touch(id: String) {
        loaded()
        // Not journaled. Recency is what the resident index uses to choose an eviction victim, and it is
        // rebuilt by the replay order on the next open. Writing a record per hit would make the journal
        // grow with reads rather than with writes, which is the wrong axis entirely.
        index.touch(id)
    }

    override suspend fun remove(id: String): Boolean {
        loaded()
        val removed = index.remove(id)
        if (removed) write(Record.Remove(id))
        return removed
    }

    override suspend fun invalidateByTag(tag: String, scope: String?): Int {
        loaded()
        val removed = index.invalidateByTag(tag, scope)
        if (removed > 0) write(Record.InvalidateTag(tag, scope))
        return removed
    }

    override suspend fun clear(scope: String?) {
        loaded()
        index.clear(scope)
        if (scope == null) {
            // Nothing that came before it can matter, so the log is replaced rather than appended to.
            // Appending a clear to a journal of a million dead writes would keep paying to replay them.
            gate.withLock {
                file.replace("")
                records = 0
            }
        } else {
            write(Record.Clear(scope))
        }
    }

    override suspend fun size(scope: String?): Int {
        loaded()
        return index.size(scope)
    }

    /** Removes the journal from disk and empties the index. The store stays usable and starts over. */
    public suspend fun destroy() {
        gate.withLock {
            index.clear(null)
            file.delete()
            records = 0
            loaded = true
        }
    }

    /**
     * Replays the journal into the index, once, on the first operation.
     *
     * Lazily rather than in the constructor, because reading a file is IO and a constructor cannot
     * suspend. The alternative was a suspending factory function, which would have meant this store
     * could not be built where every other one can, including inside the shared conformance suite.
     */
    private suspend fun loaded() {
        if (loaded) return
        gate.withLock {
            if (loaded) return
            val text = file.readTextOrNull()
            if (text != null) {
                val replayed = Journal.decode(text)
                for (record in replayed) apply(record)
                records = replayed.size
                // A journal that was already past the threshold when it was opened is compacted now
                // rather than on the next write, so a process that reads and never writes does not
                // carry a log that grows every time it starts.
                if (records > compactAfter) compact()
            }
            loaded = true
        }
    }

    private suspend fun apply(record: Record) {
        when (record) {
            is Record.Put -> index.put(record.entry)
            is Record.Remove -> index.remove(record.id)
            is Record.Clear -> index.clear(record.scope)
            is Record.InvalidateTag -> runCatching { index.invalidateByTag(record.tag, record.scope) }
        }
    }

    private suspend fun write(record: Record) {
        gate.withLock {
            file.append(Journal.encode(record))
            if (++records > compactAfter) compact()
        }
    }

    /** Must be called with [gate] held. Rewrites the log as one put per live entry. */
    private suspend fun compact() {
        val live = index.entries()
        file.replace(live.joinToString("") { Journal.encode(Record.Put(it)) })
        records = live.size
    }

    public companion object {
        /**
         * Journal records tolerated before the log is rewritten.
         *
         * Ten times the in-memory store's default capacity, so a cache running at capacity compacts
         * roughly once per ten thousand writes rather than on every eviction. Compaction is one pass
         * over the resident entries and one file write, so it is cheap and it should still not be on
         * the path of every write.
         */
        public const val DEFAULT_COMPACT_AFTER: Int = 100_000
    }
}
