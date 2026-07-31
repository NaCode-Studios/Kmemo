package dev.kmemo

import dev.kmemo.fixtures.CountingEmbedder
import dev.kmemo.fixtures.HashingEmbedder
import dev.kmemo.fixtures.MutableClock
import dev.kmemo.store.InMemoryStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/** M19 — the exact-match layer: the repeat that costs no embedding and no search. */
class ExactCacheTest {

    @Test
    fun `off by default so nothing changes for anyone who does not ask for it`() = runTest {
        val embedder = CountingEmbedder(HashingEmbedder())
        val cache = SemanticCache(embedder)

        cache.getOrPut("what is the capital of France") { "Paris" }
        cache.getOrPut("what is the capital of France") { "should not be recomputed" }

        // Two lookups, two embed calls: the old behaviour, unchanged.
        assertEquals(2, embedder.calls)
        assertEquals(0, cache.stats().exactHits)
    }

    @Test
    fun `an identical prompt costs no second embed call`() = runTest {
        val embedder = CountingEmbedder(HashingEmbedder())
        val cache = SemanticCache(embedder, exactCacheSize = 100)

        val first = cache.getOrPut("what is the capital of France") { "Paris" }
        val second = cache.getOrPut("what is the capital of France") { "should not be recomputed" }

        assertEquals("Paris", first)
        assertEquals("Paris", second)
        // One embed for the first lookup. The repeat never reached the embedder.
        assertEquals(1, embedder.calls)
        assertEquals(1, cache.stats().exactHits)
    }

    @Test
    fun `an exact hit is a hit in the stats and exactHits is a subset of hits`() = runTest {
        val cache = SemanticCache(HashingEmbedder(), exactCacheSize = 100)
        cache.getOrPut("prompt") { "answer" }

        cache.getOrPut("prompt") { "unreachable" }

        val stats = cache.stats()
        assertEquals(2, stats.lookups)
        assertEquals(1, stats.hits)
        assertEquals(1, stats.exactHits)
        assertTrue(stats.exactHits <= stats.hits, "exactHits must never exceed hits")
    }

    @Test
    fun `a near miss is never served by the fast path`() = runTest {
        val cache = SemanticCache(HashingEmbedder(), exactCacheSize = 100)
        cache.getOrPut("Convert 100 USD to EUR") { "92 EUR" }

        // One character different is a different key, so this cannot come from the exact layer.
        val result = cache.lookup("Convert 250 USD to EUR")

        assertIs<CacheLookup.Miss>(result)
        assertEquals(0, cache.stats().exactHits)
    }

    @Test
    fun `the same prompt in another scope is not the same question`() = runTest {
        val cache = SemanticCache(HashingEmbedder(), exactCacheSize = 100)
        cache.getOrPut("prompt", scope = "tenant-a") { "answer for A" }

        val other = cache.getOrPut("prompt", scope = "tenant-b") { "answer for B" }

        assertEquals("answer for B", other)
        assertEquals(0, cache.stats().exactHits, "a scope boundary must not be crossed by the fast path")
    }

    @Test
    fun `an exact hit emits a Hit event with similarity 1 and no timings`() = runTest {
        val listener = CapturingListener()
        val cache = SemanticCache(HashingEmbedder(), listeners = listOf(listener), exactCacheSize = 100)
        cache.getOrPut("prompt") { "answer" }
        listener.events.clear()

        cache.getOrPut("prompt") { "unreachable" }

        val hit = assertIs<CacheEvent.Hit>(listener.events.single())
        assertEquals(1.0, hit.similarity)
        // Nothing was embedded and nothing was searched, and the timings say exactly that.
        assertEquals(0L, hit.timings.embedNanos)
        assertEquals(0L, hit.timings.searchNanos)
    }

    @Test
    fun `invalidate drops the entry from the fast path too`() = runTest {
        val cache = SemanticCache(HashingEmbedder(), exactCacheSize = 100)
        cache.getOrPut("prompt") { "stale answer" }
        val id = assertIs<CacheLookup.Hit>(cache.lookup("prompt")).entryId

        cache.invalidate(id)

        // Without the purge the fast path would keep serving the answer just retracted.
        assertNull(cache.get("prompt"))
    }

    @Test
    fun `clear drops the fast path for that scope only`() = runTest {
        val cache = SemanticCache(HashingEmbedder(), exactCacheSize = 100)
        cache.getOrPut("prompt", scope = "a") { "answer a" }
        cache.getOrPut("prompt", scope = "b") { "answer b" }

        cache.clear("a")

        assertNull(cache.get("prompt", scope = "a"))
        assertEquals("answer b", cache.get("prompt", scope = "b"))
    }

    @Test
    fun `past its TTL nothing stale is served but the embedding is still reused`() = runTest {
        val clock = MutableClock()
        val embedder = CountingEmbedder(HashingEmbedder())
        val cache = SemanticCache(
            embedder,
            store = InMemoryStore(),
            exactCacheSize = 100,
            exactCacheTtl = 5.minutes,
            clock = clock,
        )
        cache.getOrPut("prompt") { "answer" }
        val embedsAfterWrite = embedder.calls

        clock.advance(10.minutes)
        val result = cache.getOrPut("prompt") { "unreachable, the store still has it" }

        assertEquals("answer", result, "the answer comes from the store, not from a stale fast path")
        // The point of the graceful degradation: expired for *serving*, still good for the vector.
        assertEquals(embedsAfterWrite, embedder.calls, "a stale recall must still save the embed call")
        assertEquals(0, cache.stats().exactHits, "a stale recall is not an exact hit")
    }

    @Test
    fun `the layer is bounded and evicts least-recently-used`() = runTest {
        val embedder = CountingEmbedder(HashingEmbedder())
        val cache = SemanticCache(embedder, exactCacheSize = 2)
        cache.getOrPut("a") { "answer a" }
        cache.getOrPut("b") { "answer b" }
        cache.getOrPut("c") { "answer c" }   // evicts "a"
        val before = embedder.calls

        cache.getOrPut("a") { "answer a" }

        assertTrue(embedder.calls > before, "the evicted prompt must go back through the normal path")
    }

    @Test
    fun `the builder DSL wires the layer through`() = runTest {
        val embedder = CountingEmbedder(HashingEmbedder())
        val cache = semanticCache(embedder) { exactCacheSize = 50 }
        cache.getOrPut("prompt") { "answer" }

        cache.getOrPut("prompt") { "unreachable" }

        assertEquals(1, cache.stats().exactHits)
    }

    @Test
    fun `a negative size is rejected and a non-positive TTL too`() {
        assertTrue(runCatching { SemanticCache(HashingEmbedder(), exactCacheSize = -1) }.isFailure)
        assertTrue(
            runCatching {
                SemanticCache(HashingEmbedder(), exactCacheSize = 1, exactCacheTtl = 0.minutes)
            }.isFailure,
        )
    }

    private class CapturingListener : CacheListener {
        val events: MutableList<CacheEvent> = mutableListOf()

        override fun onEvent(event: CacheEvent) {
            events += event
        }
    }
}
