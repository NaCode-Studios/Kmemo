package dev.kmemo

import dev.kmemo.fixtures.HashingEmbedder
import dev.kmemo.store.InMemoryStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** M21 — invalidating on knowing a fact changed, rather than guessing with a TTL. */
class TagInvalidationTest {

    @Test
    fun `tags survive a write and come back on the hit`() = runTest {
        val cache = SemanticCache(HashingEmbedder())
        cache.put("what does the widget cost", "12 EUR", tags = setOf("price-list"))

        val hit = cache.lookup("what does the widget cost")

        assertTrue(hit is CacheLookup.Hit)
    }

    @Test
    fun `invalidateByTag drops exactly the entries that depend on the changed fact`() = runTest {
        val cache = SemanticCache(HashingEmbedder())
        cache.put("what does the widget cost", "12 EUR", tags = setOf("price-list"))
        cache.put("what does the gadget cost", "30 EUR", tags = setOf("price-list"))
        cache.put("what are your opening hours", "nine to five", tags = setOf("policy"))

        val removed = cache.invalidateByTag("price-list")

        assertEquals(2, removed)
        assertNull(cache.get("what does the widget cost"))
        assertNull(cache.get("what does the gadget cost"))
        assertEquals("nine to five", cache.get("what are your opening hours"))
    }

    @Test
    fun `an untagged entry is never caught by a tag invalidation`() = runTest {
        val cache = SemanticCache(HashingEmbedder())
        cache.put("untagged", "answer")

        assertEquals(0, cache.invalidateByTag("anything"))
        assertEquals("answer", cache.get("untagged"))
    }

    @Test
    fun `invalidation can be narrowed to one scope`() = runTest {
        val cache = SemanticCache(HashingEmbedder())
        cache.put("prompt", "answer a", scope = "a", tags = setOf("shared"))
        cache.put("prompt", "answer b", scope = "b", tags = setOf("shared"))

        assertEquals(1, cache.invalidateByTag("shared", scope = "a"))
        assertNull(cache.get("prompt", scope = "a"))
        assertEquals("answer b", cache.get("prompt", scope = "b"))
    }

    @Test
    fun `the exact-match layer cannot keep serving an invalidated answer`() = runTest {
        val cache = SemanticCache(HashingEmbedder(), exactCacheSize = 100)
        cache.put("what does the widget cost", "12 EUR", tags = setOf("price-list"))
        // Warm the fast path, so the answer is now held in two places.
        assertEquals("12 EUR", cache.get("what does the widget cost"))

        cache.invalidateByTag("price-list")

        assertNull(cache.get("what does the widget cost"), "the fast path must not outlive the entry")
    }

    @Test
    fun `getOrPut can tag what it writes`() = runTest {
        val cache = SemanticCache(HashingEmbedder())
        cache.getOrPut("what does the widget cost", emptyList(), tags = setOf("price-list")) { "12 EUR" }

        assertEquals(1, cache.invalidateByTag("price-list"))
    }

    @Test
    fun `a store that does not index tags fails loudly instead of reporting zero`() = runTest {
        val cache = SemanticCache(HashingEmbedder(), store = TaglessStore())

        // Reporting 0 would tell a caller that stale answers were dropped when nothing was.
        assertFailsWith<UnsupportedOperationException> { cache.invalidateByTag("price-list") }
    }

    /** A minimal store that takes the [CacheStore.invalidateByTag] default. */
    private class TaglessStore(private val delegate: CacheStore = InMemoryStore()) : CacheStore {
        override suspend fun put(entry: CacheEntry) = delegate.put(entry)

        override suspend fun search(scope: String, embedding: FloatArray, limit: Int): List<ScoredEntry> =
            delegate.search(scope, embedding, limit)

        override suspend fun remove(id: String): Boolean = delegate.remove(id)

        override suspend fun clear(scope: String?) = delegate.clear(scope)

        override suspend fun size(scope: String?): Int = delegate.size(scope)
    }
}
