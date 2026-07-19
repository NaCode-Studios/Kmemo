package dev.kmemo.store

import dev.kmemo.CacheEntry
import dev.kmemo.CacheStore
import dev.kmemo.ScoredEntry
import dev.kmemo.Vectors
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Instant
import kotlin.time.Duration
import java.time.Duration as JavaDuration

/** Point-in-time view of an [InMemoryStore]. */
public data class InMemoryStoreStats(
    /** Live entries, expired ones excluded. */
    public val size: Int,
    /** Entries dropped to stay within `maxEntries`, since construction. */
    public val evictions: Long,
    /** Entries dropped for being past their TTL, since construction. */
    public val expirations: Long,
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
 */
public class InMemoryStore(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    ttl: Duration? = null,
    private val clock: Clock = Clock.systemUTC(),
) : CacheStore {

    init {
        require(maxEntries > 0) { "maxEntries must be positive, was $maxEntries" }
        require(ttl == null || ttl.isPositive()) { "ttl must be positive, was $ttl" }
    }

    private val ttlNanos: JavaDuration? = ttl?.let { JavaDuration.ofNanos(it.inWholeNanoseconds) }

    private val mutex = Mutex()

    /** Access-ordered, so the eldest key is always the least recently used one. */
    private val entries = LinkedHashMap<String, CacheEntry>(INITIAL_CAPACITY, LOAD_FACTOR, true)

    private var evictions = 0L
    private var expirations = 0L

    /** Dimension of everything stored so far, or `-1` while empty. Guards against model swaps. */
    private var dimensions = -1

    override suspend fun put(entry: CacheEntry) {
        mutex.withLock {
            // Drop anything already dead before judging the store's dimension. `dimensions` tracks
            // physical residency while `size()` counts live entries, and the two disagree exactly
            // when expired entries are still resident — which is when a caller sees "clear it" on a
            // store that already reports itself empty.
            dropExpired(clock.instant())

            if (dimensions == -1) {
                dimensions = entry.dimensions
            } else {
                require(entry.dimensions == dimensions) {
                    "embedding dimension mismatch: store holds $dimensions-dimensional vectors, " +
                        "entry '${entry.id}' has ${entry.dimensions}. Embedding models cannot be " +
                        "mixed in one store — clear it, or give the new model its own store."
                }
            }
            entries[entry.id] = entry
            evictOverflow()
        }
    }

    override suspend fun search(scope: String, embedding: FloatArray, limit: Int): List<ScoredEntry> {
        require(limit > 0) { "limit must be positive, was $limit" }
        return mutex.withLock {
            val now = clock.instant()
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
            val scored = mutableListOf<ScoredEntry>()
            // Iteration does not count as access in a LinkedHashMap, so scanning cannot disturb
            // the LRU order.
            for ((id, entry) in entries) {
                if (isExpired(entry, now)) {
                    expired += id
                    continue
                }
                if (entry.scope != scope) continue
                scored += ScoredEntry(entry, Vectors.dot(embedding, entry.embedding))
            }
            dropAll(expired)

            scored.sortByDescending { it.similarity }
            if (scored.size > limit) scored.subList(0, limit).toList() else scored
        }
    }

    override suspend fun touch(id: String) {
        mutex.withLock {
            // A get on an access-ordered map is the whole point: it moves the entry to the back
            // of the eviction queue.
            entries[id]
        }
    }

    override suspend fun remove(id: String): Boolean = mutex.withLock {
        val removed = entries.remove(id) != null
        forgetDimensionsIfEmpty()
        removed
    }

    override suspend fun clear(scope: String?) {
        mutex.withLock {
            if (scope == null) {
                entries.clear()
            } else {
                entries.entries.removeAll { it.value.scope == scope }
            }
            forgetDimensionsIfEmpty()
        }
    }

    override suspend fun size(scope: String?): Int = mutex.withLock {
        val now = clock.instant()
        entries.values.count { !isExpired(it, now) && (scope == null || it.scope == scope) }
    }

    /**
     * Drops every expired entry and returns how many were removed.
     *
     * [search] already discards expired entries as it meets them, so this is only needed to reclaim
     * memory in a cache that is written to far more often than it is read.
     */
    public suspend fun purgeExpired(): Int = mutex.withLock {
        dropExpired(clock.instant())
    }

    /** Current size and lifetime eviction counters. */
    public suspend fun stats(): InMemoryStoreStats = mutex.withLock {
        val now = clock.instant()
        InMemoryStoreStats(
            size = entries.values.count { !isExpired(it, now) },
            evictions = evictions,
            expirations = expirations,
        )
    }

    private fun isExpired(entry: CacheEntry, now: Instant): Boolean {
        val ttl = ttlNanos ?: return false
        return !entry.createdAt.plus(ttl).isAfter(now)
    }

    private fun dropAll(ids: List<String>) {
        for (id in ids) {
            if (entries.remove(id) != null) expirations++
        }
        forgetDimensionsIfEmpty()
    }

    /**
     * Removes every expired entry and returns how many went. Assumes the mutex is already held —
     * [kotlinx.coroutines.sync.Mutex] is not reentrant, so the locking entry point is
     * [purgeExpired] and everything internal calls this instead.
     */
    private fun dropExpired(now: Instant): Int {
        if (ttlNanos == null) return 0
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
        if (entries.size <= maxEntries) return

        dropExpired(clock.instant())

        while (entries.size > maxEntries) {
            val eldest = entries.keys.iterator().next()
            entries.remove(eldest)
            evictions++
        }

        // Purging can empty the map — a warm cache rehydrated with entries that are already past
        // their TTL does exactly that — and this is the one mutation path that would otherwise
        // leave the old dimension latched on an empty store.
        forgetDimensionsIfEmpty()
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

    public companion object {
        /**
         * Default cap. Chosen so a full linear scan stays fast enough to be invisible next to an
         * LLM call, rather than as a memory limit.
         */
        public const val DEFAULT_MAX_ENTRIES: Int = 10_000

        private const val INITIAL_CAPACITY = 64
        private const val LOAD_FACTOR = 0.75f
    }
}
