package dev.kmemo.store

import dev.kmemo.CacheEntry
import dev.kmemo.CacheEvent
import dev.kmemo.CacheListener
import dev.kmemo.CacheStore
import dev.kmemo.EvictionCause
import dev.kmemo.Quantization
import dev.kmemo.ScoredEntry
import dev.kmemo.Vectors
import dev.kmemo.internal.LruMap
import dev.kmemo.internal.VectorCode
import dev.kmemo.internal.VectorCodes
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.time.Duration

/** Point-in-time view of an [InMemoryStore]. */
public data class InMemoryStoreStats(
    /** Live entries, expired ones excluded. */
    public val size: Int,
    /** Entries dropped to stay within `maxEntries` or `maxBytes`, since construction. */
    public val evictions: Long,
    /** Entries dropped for being past their TTL, since construction. */
    public val expirations: Long,
    /** Estimated resident bytes of the entries held (embeddings dominate: `dimensions * 4` each). */
    public val bytes: Long = 0,
)

/**
 * Default [CacheStore]: entries in a map, search by scanning them.
 *
 * Good enough to start with and honest about its ceiling. Search is a linear scan of every entry in
 * the scope, so cost grows with cache size — at the default 10,000 entries and 1,536-dimensional
 * vectors a lookup is well under a millisecond, which is nothing next to the network call it
 * replaces. Past roughly 100,000 entries, or as soon as you want the cache shared between instances
 * or to survive a restart, move to a real vector store.
 *
 * Eviction is least-recently-*used*, not least-recently-written, and "used" means served: only a
 * confirmed hit calls [touch]. An entry that keeps scoring second place never gets credit for it.
 *
 * Every method takes a mutex, so the store is safe to share across coroutines and threads.
 *
 * @param maxEntries hard cap across all scopes; the least recently used entry goes first.
 * @param ttl how long an entry stays valid, or `null` to keep entries until they are evicted.
 *   Expired entries are never returned by [search] and are dropped as they are encountered.
 * @param clock time source; substitute a fixed clock in tests instead of sleeping.
 * @param maxBytes optional cap on estimated resident bytes (embeddings dominate: `dimensions * 4`
 *   each), evicted least-recently-used just like `maxEntries`, so a cache in a memory-constrained
 *   service cannot grow without bound. `null` (the default) bounds by count only.
 * @param quantization compresses vectors **for the scan only**, trading a little recall for a cheaper
 *   pass over the scope. Survivors are rescored against the full-precision vectors, so every similarity
 *   this store returns is exact and a quantization error can only ever cost a candidate, never change a
 *   decision. [Quantization.NONE] by default: at ten thousand entries the exact scan is already well
 *   under a millisecond, and a knob that quietly lowers recall should be reached for on evidence.
 * @param listener optional sink notified with a [CacheEvent.Eviction] whenever an entry is evicted
 *   (for capacity or memory) or dropped for being expired — the store owns eviction, so the store is
 *   what reports it. Called inline while the store's lock is held, so it must be fast and non-blocking
 *   (see [CacheListener]); pass the same listener you give [dev.kmemo.SemanticCache] to see the whole
 *   event stream in one place. `null` (the default) emits nothing.
 */
public class InMemoryStore(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    ttl: Duration? = null,
    private val clock: Clock = Clock.System,
    private val maxBytes: Long? = null,
    private val listener: CacheListener? = null,
    private val quantization: Quantization = Quantization.NONE,
) : CacheStore {

    init {
        require(maxEntries > 0) { "maxEntries must be positive, was $maxEntries" }
        require(ttl == null || ttl.isPositive()) { "ttl must be positive, was $ttl" }
        require(maxBytes == null || maxBytes > 0) { "maxBytes must be positive, was $maxBytes" }
    }

    private val ttlDuration: Duration? = ttl

    private val mutex = Mutex()

    /** Access-ordered, so the eldest key is always the least recently used one. */
    private val entries = LruMap<String, CacheEntry>(INITIAL_CAPACITY)

    /**
     * Compressed vectors for the scan, by entry id. Empty when [quantization] is
     * [Quantization.NONE].
     *
     * Kept in step with [entries] by routing **every** removal through [drop]. A second map is the
     * cheap shape here; the price is that a removal which forgot it would leave a code behind for an
     * id that no longer exists, and that code would go on being scanned and then fail to rescore. So
     * there is one removal helper and nothing else touches this map.
     */
    private val codes = HashMap<String, VectorCode>(INITIAL_CAPACITY)

    private var evictions = 0L
    private var expirations = 0L

    /** Running estimate of resident bytes, kept in step with [entries] on every mutation. */
    private var currentBytes = 0L

    /** Dimension of everything stored so far, or `-1` while empty. Guards against model swaps. */
    private var dimensions = -1

    override suspend fun put(entry: CacheEntry) {
        mutex.withLock {
            // Drop anything already dead before judging the store's dimension. `dimensions` tracks
            // physical residency while `size()` counts live entries, and the two disagree exactly
            // when expired entries are still resident — which is when a caller sees "clear it" on a
            // store that already reports itself empty.
            dropExpired(clock.now())

            if (dimensions == -1) {
                dimensions = entry.dimensions
            } else {
                require(entry.dimensions == dimensions) {
                    "embedding dimension mismatch: store holds $dimensions-dimensional vectors, " +
                        "entry '${entry.id}' has ${entry.dimensions}. Embedding models cannot be " +
                        "mixed in one store — clear it, or give the new model its own store."
                }
            }
            val previous = entries.put(entry.id, entry)
            VectorCodes.encode(entry.embedding, quantization)?.let { codes[entry.id] = it }
            if (previous != null) currentBytes -= bytesOf(previous)
            currentBytes += bytesOf(entry)
            evictOverflow()
        }
    }

    override suspend fun search(scope: String, embedding: FloatArray, limit: Int): List<ScoredEntry> {
        require(limit > 0) { "limit must be positive, was $limit" }
        return mutex.withLock {
            val now = clock.now()
            // Same reason as in put: judge the dimension against what is actually alive, or a store
            // holding nothing but expired entries rejects a query from the model that replaced them.
            dropExpired(now)
            if (entries.isEmpty()) return@withLock emptyList()
            check(embedding.size == dimensions) {
                "query embedding has ${embedding.size} dimensions but the store holds " +
                    "$dimensions-dimensional vectors. This usually means the embedding model " +
                    "changed since these entries were written."
            }

            val expired = mutableListOf<String>()
            val live = mutableListOf<CacheEntry>()
            // Iteration does not count as access in a LinkedHashMap, so scanning cannot disturb
            // the LRU order.
            for ((id, entry) in entries.entries) {
                if (isExpired(entry, now)) {
                    expired += id
                    continue
                }
                if (entry.scope != scope) continue
                live += entry
            }
            dropAll(expired)

            val exact = shortlist(embedding, live, limit)
            val scored = exact.map { ScoredEntry(it, Vectors.dot(embedding, it.embedding)) }.toMutableList()

            // A primitive comparator, not sortByDescending { it.similarity }: the selector form boxes a
            // Double per element for the sort key, and this sort runs over every entry in the scope on
            // the lookup hot path. `Double.compareTo` here is the primitive `dcmpl`, so nothing is boxed.
            scored.sortWith { a, b -> b.similarity.compareTo(a.similarity) }
            if (scored.size > limit) scored.subList(0, limit).toList() else scored
        }
    }

    /**
     * The entries worth scoring exactly.
     *
     * With [Quantization.NONE] that is all of them and this is a no-op. Otherwise the compressed codes
     * rank every live entry cheaply and the best `limit * rescoreFactor` go through to the exact pass.
     * Nothing here produces a similarity anyone sees: the approximate score orders the shortlist and is
     * then discarded, and [search] recomputes every returned similarity from the full-precision
     * vectors. That is what confines a quantization error to a missed candidate.
     */
    private fun shortlist(embedding: FloatArray, live: List<CacheEntry>, limit: Int): List<CacheEntry> {
        val queryCode = VectorCodes.encode(embedding, quantization) ?: return live
        val wanted = limit * quantization.rescoreFactor
        if (live.size <= wanted) return live

        val ranked = live.mapNotNull { entry ->
            codes[entry.id]?.let { entry to it.score(queryCode) }
        }
        // An entry with no code is one written before the store was quantized, which cannot happen
        // through the public API. If it somehow did, scoring it exactly is the safe answer.
        if (ranked.size != live.size) return live
        return ranked.sortedByDescending { it.second }.take(wanted).map { it.first }
    }

    override suspend fun touch(id: String) {
        mutex.withLock {
            // A get on the LRU map is the whole point: it moves the entry to the back of the
            // eviction queue.
            entries.get(id)
        }
    }

    override suspend fun remove(id: String): Boolean = mutex.withLock {
        val removed = drop(id)
        if (removed != null) currentBytes -= bytesOf(removed)
        forgetDimensionsIfEmpty()
        removed != null
    }

    /**
     * The one way an entry leaves this store, so its compressed code leaves with it.
     *
     * Every other removal path calls this. A path that forgot would leave a code behind for an id that
     * no longer exists, and the shortlist would go on ranking a ghost.
     */
    private fun drop(id: String): CacheEntry? {
        codes.remove(id)
        return entries.remove(id)
    }

    /**
     * Drops every live entry carrying [tag], optionally narrowed to [scope].
     *
     * A scan, because this store is a scan: its search already walks the scope, so a tag index would
     * cost memory on every write to speed up an operation that happens when a fact changes. The
     * indexed stores are the ones where that trade goes the other way.
     */
    override suspend fun invalidateByTag(tag: String, scope: String?): Int = mutex.withLock {
        val doomed = entries.values.filter { tag in it.tags && (scope == null || it.scope == scope) }
        for (entry in doomed) {
            drop(entry.id)
            currentBytes -= bytesOf(entry)
        }
        forgetDimensionsIfEmpty()
        doomed.size
    }

    override suspend fun clear(scope: String?) {
        mutex.withLock {
            if (scope == null) {
                entries.clear()
                codes.clear()
                currentBytes = 0L
            } else {
                val doomed = entries.values.filter { it.scope == scope }.map { it.id }
                for (id in doomed) {
                    val removed = drop(id) ?: continue
                    currentBytes -= bytesOf(removed)
                }
            }
            forgetDimensionsIfEmpty()
        }
    }

    override suspend fun size(scope: String?): Int = mutex.withLock {
        val now = clock.now()
        entries.values.count { !isExpired(it, now) && (scope == null || it.scope == scope) }
    }

    /**
     * Drops every expired entry and returns how many were removed.
     *
     * [search] already discards expired entries as it meets them, so this is only needed to reclaim
     * memory in a cache that is written to far more often than it is read.
     */
    public suspend fun purgeExpired(): Int = mutex.withLock {
        dropExpired(clock.now())
    }

    /**
     * Every live entry, least recently used first.
     *
     * Exists for a store that keeps this one as its index and needs to write the live set out: the
     * file store compacts its journal by replacing it with one write per entry, and without this it
     * would have to keep a second copy of everything in order to know what to write.
     *
     * A snapshot, taken under the lock, so it is safe to iterate while the store carries on. Expired
     * entries are excluded, which makes a compaction a purge as well. The order is the eviction order,
     * so a replay in this order rebuilds the same recency the store had.
     */
    public suspend fun entries(): List<CacheEntry> = mutex.withLock {
        val now = clock.now()
        entries.values.filterNot { isExpired(it, now) }
    }

    /** Current size and lifetime eviction counters. */
    public suspend fun stats(): InMemoryStoreStats = mutex.withLock {
        val now = clock.now()
        InMemoryStoreStats(
            size = entries.values.count { !isExpired(it, now) },
            evictions = evictions,
            expirations = expirations,
            bytes = currentBytes,
        )
    }

    private fun isExpired(entry: CacheEntry, now: Instant): Boolean {
        val ttl = ttlDuration ?: return false
        return (entry.createdAt + ttl) <= now
    }

    private fun dropAll(ids: List<String>) {
        for (id in ids) {
            val removed = drop(id)
            if (removed != null) {
                expirations++
                currentBytes -= bytesOf(removed)
                emitEviction(removed, EvictionCause.EXPIRED)
            }
        }
        forgetDimensionsIfEmpty()
    }

    /**
     * Removes every expired entry and returns how many went. Assumes the mutex is already held —
     * [kotlinx.coroutines.sync.Mutex] is not reentrant, so the locking entry point is
     * [purgeExpired] and everything internal calls this instead.
     */
    private fun dropExpired(now: Instant): Int {
        if (ttlDuration == null) return 0
        val expired = entries.entries.filter { isExpired(it.value, now) }.map { it.key }
        dropAll(expired)
        return expired.size
    }

    /**
     * Makes room for a new entry, dropping anything already past its TTL before evicting anything
     * still alive.
     *
     * Counting matters here. Evicting on raw size would book a dead entry as an eviction, and — far
     * worse — would throw out a live, recently used entry while expired ones still occupy the map.
     */
    private fun evictOverflow() {
        val overCount = entries.size > maxEntries
        val overBytes = maxBytes != null && currentBytes > maxBytes
        if (!overCount && !overBytes) return

        dropExpired(clock.now())

        while (entries.size > maxEntries) {
            evictEldest(EvictionCause.CAPACITY)
        }
        // Then trim by memory, keeping at least the entry just written even if it alone is oversized.
        while (maxBytes != null && currentBytes > maxBytes && entries.size > 1) {
            evictEldest(EvictionCause.MEMORY)
        }

        // Purging can empty the map — a warm cache rehydrated with entries that are already past
        // their TTL does exactly that — and this is the one mutation path that would otherwise
        // leave the old dimension latched on an empty store.
        forgetDimensionsIfEmpty()
    }

    private fun evictEldest(cause: EvictionCause) {
        val eldest = entries.eldest() ?: return
        val removed = drop(eldest)
        if (removed != null) {
            currentBytes -= bytesOf(removed)
            emitEviction(removed, cause)
        }
        evictions++
    }

    /**
     * Reports one automatic removal to [listener], if there is one. Errors are swallowed — a telemetry
     * sink must never break a store mutation. Runs inline under the store lock, hence the fast-listener
     * requirement; nothing here suspends, so the coroutine [Mutex] is safe.
     */
    private fun emitEviction(entry: CacheEntry, cause: EvictionCause) {
        val target = listener ?: return
        try {
            target.onEvent(CacheEvent.Eviction(entry.scope, entry.prompt, entry.id, cause))
        } catch (_: Exception) {
            // A listener's failure is its own; see CacheListener's contract.
        }
    }

    /**
     * An empty store has no model attached to it, so the next writer may use any dimension.
     *
     * Without this, clearing a single scope until nothing is left — or removing the last entry —
     * leaves the old dimension latched, and the next `put` fails with a message telling the caller
     * to clear a store that is already empty.
     */
    private fun forgetDimensionsIfEmpty() {
        if (entries.isEmpty()) dimensions = -1
    }

    /**
     * Estimated resident bytes of [entry]. Embeddings dominate (`dimensions * 4`); the prompt and
     * response are counted too, plus a flat allowance for object and map-entry overhead. This is an
     * estimate for the memory bound, not an exact heap measurement.
     */
    private fun bytesOf(entry: CacheEntry): Long =
        entry.dimensions.toLong() * Float.SIZE_BYTES +
            entry.prompt.length + entry.response.length + ENTRY_OVERHEAD

    public companion object {
        /**
         * Default cap. Chosen so a full linear scan stays fast enough to be invisible next to an
         * LLM call, rather than as a memory limit.
         */
        public const val DEFAULT_MAX_ENTRIES: Int = 10_000

        private const val INITIAL_CAPACITY = 64
        private const val LOAD_FACTOR = 0.75f

        /** Flat per-entry allowance (object headers, map entry, id/scope strings) for the byte estimate. */
        private const val ENTRY_OVERHEAD = 128L
    }
}
