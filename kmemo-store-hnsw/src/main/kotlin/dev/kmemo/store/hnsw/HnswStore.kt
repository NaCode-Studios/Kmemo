package dev.kmemo.store.hnsw

import dev.kmemo.CacheEntry
import dev.kmemo.CacheStore
import dev.kmemo.ScoredEntry
import dev.kmemo.Vectors
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Instant
import kotlin.time.Duration

/**
 * An in-process [CacheStore] that scales past the exact-scan [dev.kmemo.store.InMemoryStore] with an
 * approximate-nearest-neighbour (HNSW) index, while staying pure Kotlin with no extra dependency.
 *
 * **Correctness is not approximate.** The HNSW graph only *proposes* candidates; this store then
 * rescores them exactly with [Vectors.dot] and applies scope and TTL from an authoritative map, so
 * liveness, expiry, `size`, `remove` and replacement are exact. Approximation costs only recall — a
 * true nearest neighbour is occasionally missed — which is why the exact store stays the reference and
 * why this one is opt-in, for caches large enough that an O(n) scan per lookup hurts.
 *
 * Stale graph nodes (from removals and replacements) are filtered on read and periodically compacted
 * by rebuilding a scope's index once they dominate it.
 *
 * Safe across coroutines and threads: every operation takes a mutex, as in [dev.kmemo.store.InMemoryStore].
 *
 * @param m HNSW neighbours per node (see [HnswIndex]).
 * @param efConstruction build-time candidate width (see [HnswIndex]).
 * @param efSearch query-time candidate width; the recall/latency dial.
 * @param ttl how long an entry stays valid, or `null` to keep it until removed.
 * @param clock time source; substitute a fixed clock in tests instead of sleeping.
 */
public class HnswStore(
    private val m: Int = 16,
    private val efConstruction: Int = 200,
    private val efSearch: Int = 64,
    private val ttl: Duration? = null,
    private val clock: Clock = Clock.systemUTC(),
) : CacheStore {

    init {
        require(m > 0) { "m must be positive, was $m" }
        require(efConstruction > 0) { "efConstruction must be positive, was $efConstruction" }
        require(efSearch > 0) { "efSearch must be positive, was $efSearch" }
        require(ttl == null || ttl.isPositive()) { "ttl must be positive, was $ttl" }
    }

    private val mutex = Mutex()

    /** Source of truth: id -> the live entry and its expiry. Scope, TTL and size are read from here. */
    private val entries = HashMap<String, Held>()

    /** scope -> approximate index; each stores its scope's ids and vectors for candidate generation. */
    private val indexes = HashMap<String, HnswIndex>()

    private var dimensions = -1

    override suspend fun put(entry: CacheEntry): Unit = mutex.withLock {
        if (dimensions == -1) {
            dimensions = entry.dimensions
        } else {
            require(entry.dimensions == dimensions) {
                "embedding dimension mismatch: store holds $dimensions-dimensional vectors, entry " +
                    "'${entry.id}' has ${entry.dimensions}. One embedding model per store."
            }
        }
        val expiresAt = ttl?.let { clock.instant().plusMillis(it.inWholeMilliseconds) }
        entries[entry.id] = Held(entry, expiresAt)
        indexes.getOrPut(entry.scope) { newIndex() }.add(entry.id, entry.embedding)
        maybeCompact(entry.scope)
    }

    override suspend fun search(scope: String, embedding: FloatArray, limit: Int): List<ScoredEntry> {
        require(limit > 0) { "limit must be positive, was $limit" }
        return mutex.withLock {
            val index = indexes[scope] ?: return@withLock emptyList()
            val now = clock.instant()
            val candidateIds = index.search(embedding, maxOf(limit * OVERFETCH, efSearch))

            val seen = HashSet<String>(candidateIds.size)
            val scored = ArrayList<ScoredEntry>(candidateIds.size)
            for (id in candidateIds) {
                if (!seen.add(id)) continue // a replaced entry can appear under two graph nodes
                val held = entries[id] ?: continue
                if (held.entry.scope != scope || isExpired(held, now)) continue
                scored += ScoredEntry(held.entry, Vectors.dot(embedding, held.entry.embedding))
            }

            scored.sortByDescending { it.similarity }
            if (scored.size > limit) scored.subList(0, limit).toList() else scored
        }
    }

    override suspend fun remove(id: String): Boolean = mutex.withLock {
        // The graph node is left as a stale waypoint and filtered on read; compaction reclaims it.
        entries.remove(id) != null
    }

    override suspend fun clear(scope: String?): Unit = mutex.withLock {
        if (scope == null) {
            entries.clear()
            indexes.clear()
            dimensions = -1
        } else {
            entries.entries.removeAll { it.value.entry.scope == scope }
            indexes.remove(scope)
            if (entries.isEmpty()) dimensions = -1
        }
    }

    override suspend fun size(scope: String?): Int = mutex.withLock {
        val now = clock.instant()
        entries.values.count { !isExpired(it, now) && (scope == null || it.entry.scope == scope) }
    }

    private fun maybeCompact(scope: String) {
        val index = indexes[scope] ?: return
        val liveInScope = entries.values.count { it.entry.scope == scope }
        if (index.size() >= COMPACT_MIN_NODES && index.size() > liveInScope * COMPACT_STALE_FACTOR) {
            val fresh = newIndex()
            for ((id, held) in entries) {
                if (held.entry.scope == scope) fresh.add(id, held.entry.embedding)
            }
            indexes[scope] = fresh
        }
    }

    private fun newIndex(): HnswIndex = HnswIndex(m, efConstruction, efSearch)

    private fun isExpired(held: Held, now: Instant): Boolean {
        val expiresAt = held.expiresAt ?: return false
        return !expiresAt.isAfter(now)
    }

    private class Held(val entry: CacheEntry, val expiresAt: Instant?)

    private companion object {
        /** Fetch several times the requested `k` so exact rescoring has a real chance at the true top-k. */
        private const val OVERFETCH = 4

        private const val COMPACT_MIN_NODES = 64
        private const val COMPACT_STALE_FACTOR = 2
    }
}
