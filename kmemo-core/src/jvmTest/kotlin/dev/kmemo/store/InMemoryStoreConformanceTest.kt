package dev.kmemo.store

import dev.kmemo.CacheStore
import dev.kmemo.tck.CacheStoreContract
import kotlin.time.Duration

/**
 * Holds [InMemoryStore] to the shared [CacheStoreContract] — the same suite every store adapter must
 * pass. InMemory-specific behaviour (capacity eviction, stats, dimension-mismatch rejection) stays in
 * [InMemoryStoreTest]; this class only asserts the seam.
 */
class InMemoryStoreConformanceTest : CacheStoreContract() {
    override fun createStore(ttl: Duration?): CacheStore = InMemoryStore(ttl = ttl, clock = clock)
}
