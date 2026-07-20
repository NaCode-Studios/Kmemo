package dev.kmemo.store.hnsw

import dev.kmemo.CacheStore
import dev.kmemo.tck.CacheStoreContract
import kotlin.time.Duration

/**
 * Holds [HnswStore] to the shared [CacheStoreContract]. It passes at the contract's small sizes because
 * the query width comfortably exceeds the entry count, so the approximate index returns every candidate
 * and the exact rescoring produces exact order — while [HnswRecallTest] covers the approximate regime.
 */
class HnswStoreConformanceTest : CacheStoreContract() {
    override fun createStore(ttl: Duration?): CacheStore = HnswStore(ttl = ttl, clock = clock)
}
