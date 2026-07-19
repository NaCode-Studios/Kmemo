package dev.kmemo

import dev.kmemo.fixtures.ConceptEmbedder
import dev.kmemo.fixtures.CountingEmbedder
import dev.kmemo.fixtures.FixedEmbedder
import dev.kmemo.fixtures.HashingEmbedder
import dev.kmemo.guard.MatchGuards
import dev.kmemo.store.InMemoryStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SemanticCacheTest {

    @Test
    fun `a cached response comes back`() = runTest {
        val cache = SemanticCache(HashingEmbedder())
        cache.put("How do I reverse a list in Python?", "Use reversed() or list.reverse().")

        assertEquals(
            "Use reversed() or list.reverse().",
            cache.get("How do I reverse a list in Python?"),
        )
    }

    @Test
    fun `an empty cache reports why it could not help`() = runTest {
        val result = SemanticCache(HashingEmbedder()).lookup("anything")

        val miss = assertIs<CacheLookup.Miss>(result)
        assertEquals(MissReason.EMPTY_SCOPE, miss.reason)
        assertNull(miss.bestSimilarity)
    }

    /**
     * The reason this library exists.
     *
     * Two prompts that need different answers, embedded far above any usable threshold. The same
     * cache is built twice — once the way a hand-rolled semantic cache works, once with kmemo's
     * defaults — and only the difference in guards decides whether a user is told that 250 dollars
     * is 92 euros.
     */
    @Test
    fun `a near miss no threshold could catch`() = runTest {
        val cached = "Convert 100 USD to EUR"
        val asked = "Convert 250 USD to EUR"
        val answer = "100 USD is about 92 EUR."

        val naive = SemanticCache(ConceptEmbedder(), guards = MatchGuards.none())
        naive.put(cached, answer)
        val servedAnyway = assertIs<CacheLookup.Hit>(naive.lookup(asked))
        assertTrue(
            servedAnyway.similarity > SemanticCache.DEFAULT_THRESHOLD,
            "the near miss scored ${servedAnyway.similarity}, so no threshold would have stopped it",
        )
        assertEquals(answer, servedAnyway.response)

        val guarded = SemanticCache(ConceptEmbedder())
        guarded.put(cached, answer)
        val refused = assertIs<CacheLookup.Miss>(guarded.lookup(asked))
        assertEquals(MissReason.REJECTED_BY_GUARD, refused.reason)
        assertTrue(refused.detail.orEmpty().startsWith("numeric"), "expected the numeric guard, got ${refused.detail}")
        assertTrue(
            refused.bestSimilarity!! > SemanticCache.DEFAULT_THRESHOLD,
            "the guard, not the threshold, is what refused this",
        )
    }

    @Test
    fun `a candidate below the threshold is reported as such`() = runTest {
        val cache = SemanticCache(
            embedder = FixedEmbedder(
                mapOf(
                    "cached" to unit(1.0),
                    "asked" to unit(0.80),
                ),
            ),
            threshold = 0.95,
        )
        cache.put("cached", "answer")

        val miss = assertIs<CacheLookup.Miss>(cache.lookup("asked"))
        assertEquals(MissReason.BELOW_THRESHOLD, miss.reason)
        assertEquals("cached", miss.bestPrompt)
    }

    @Test
    fun `a guard rejection falls through to the next candidate`() = runTest {
        // The closest entry names a different language, the next closest is the same question in
        // lower case. Looking only at the nearest neighbour would turn a usable hit into a miss.
        val cache = SemanticCache(
            embedder = FixedEmbedder(
                mapOf(
                    "How do I reverse a list in Ruby?" to unit(0.99),
                    "how do i reverse a list in python" to unit(0.97),
                    "How do I reverse a list in Python?" to unit(1.0),
                ),
            ),
            threshold = 0.95,
        )
        cache.put("How do I reverse a list in Ruby?", "ruby answer")
        cache.put("how do i reverse a list in python", "python answer")

        val hit = assertIs<CacheLookup.Hit>(cache.lookup("How do I reverse a list in Python?"))
        assertEquals("python answer", hit.response)
    }

    @Test
    fun `a verifier gets the last word`() = runTest {
        val cache = SemanticCache(
            embedder = HashingEmbedder(),
            verifier = Verifier { _, _, _ -> false },
        )
        cache.put("How do I reverse a list?", "answer")

        val miss = assertIs<CacheLookup.Miss>(cache.lookup("How do I reverse a list?"))
        assertEquals(MissReason.REJECTED_BY_VERIFIER, miss.reason)
        assertEquals(1, cache.stats().verifierRejections)
    }

    @Test
    fun `scopes do not see each other`() = runTest {
        val cache = SemanticCache(HashingEmbedder())
        cache.put("What is 2 plus 2?", "four", scope = "gpt-4o")

        assertEquals("four", cache.get("What is 2 plus 2?", scope = "gpt-4o"))
        assertNull(cache.get("What is 2 plus 2?", scope = "haiku"))
        assertEquals(1, cache.size("gpt-4o"))
        assertEquals(0, cache.size("haiku"))
    }

    @Test
    fun `getOrPut computes once, caches, and then stops computing`() = runTest {
        val cache = SemanticCache(HashingEmbedder())
        var computed = 0

        val first = cache.getOrPut("How do I reverse a list?") {
            computed++
            "answer"
        }
        val second = cache.getOrPut("How do I reverse a list?") {
            computed++
            "should not run"
        }

        assertEquals("answer", first)
        assertEquals("answer", second)
        assertEquals(1, computed)
    }

    @Test
    fun `getOrPut embeds the prompt once, not once per operation`() = runTest {
        // Embedding calls cost money. Doing lookup-then-put by hand pays for two on every miss.
        val embedder = CountingEmbedder(HashingEmbedder())
        val cache = SemanticCache(embedder)

        cache.getOrPut("How do I reverse a list?") { "answer" }

        assertEquals(1, embedder.calls)
    }

    @Test
    fun `stats break misses down by cause`() = runTest {
        val cache = SemanticCache(ConceptEmbedder())
        cache.put("Convert 100 USD to EUR", "about 92 EUR")

        cache.lookup("Convert 100 USD to EUR")
        cache.lookup("Convert 250 USD to EUR")
        cache.lookup("How do I bake sourdough bread at home?")

        val stats = cache.stats()
        assertEquals(3, stats.lookups)
        assertEquals(1, stats.hits)
        assertEquals(2, stats.misses)
        assertEquals(1, stats.guardRejections)
        assertEquals(1, stats.belowThreshold)
        assertEquals(1, stats.writes)
        assertEquals(1.0 / 3.0, stats.hitRate, 1e-9)
    }

    @Test
    fun `an entry can be invalidated by the id its hit reported`() = runTest {
        val cache = SemanticCache(HashingEmbedder())
        cache.put("How do I reverse a list?", "answer")
        val hit = assertIs<CacheLookup.Hit>(cache.lookup("How do I reverse a list?"))

        assertTrue(cache.invalidate(hit.entryId))
        assertNull(cache.get("How do I reverse a list?"))
    }

    @Test
    fun `clear empties a single scope or everything`() = runTest {
        val cache = SemanticCache(HashingEmbedder())
        cache.put("first prompt", "a", scope = "one")
        cache.put("second prompt", "b", scope = "two")

        cache.clear("one")
        assertEquals(0, cache.size("one"))
        assertEquals(1, cache.size("two"))

        cache.clear()
        assertEquals(0, cache.size())
    }

    @Test
    fun `a hit carries the metadata stored with the entry`() = runTest {
        val cache = SemanticCache(HashingEmbedder())
        cache.put("prompt goes here", "answer", metadata = mapOf("model" to "gpt-4o", "tokens" to "412"))

        val hit = assertIs<CacheLookup.Hit>(cache.lookup("prompt goes here"))
        assertEquals("gpt-4o", hit.metadata["model"])
        assertEquals("412", hit.metadata["tokens"])
    }

    @Test
    fun `entries evicted by the store stop being served`() = runTest {
        val cache = SemanticCache(HashingEmbedder(), store = InMemoryStore(maxEntries = 1))
        cache.put("the first question about lists", "first")
        cache.put("a completely separate question about maps", "second")

        assertEquals(1, cache.size())
        assertNull(cache.get("the first question about lists"))
    }

    @Test
    fun `concurrent misses on the same prompt call the model once`() = runTest {
        // The case that makes a cold cache worse than no cache: a burst of identical requests,
        // all missing, all paying. The first computes; the rest wait and are served its answer.
        val cache = SemanticCache(HashingEmbedder())
        val started = CompletableDeferred<Unit>()
        var calls = 0

        val answers = (1..20).map {
            async {
                cache.getOrPut("what is the capital of France?") {
                    calls++
                    started.complete(Unit)
                    "Paris"
                }
            }
        }.awaitAll()

        assertEquals(1, calls)
        assertTrue(answers.all { it == "Paris" })
        assertEquals(1, cache.size())
    }

    @Test
    fun `different prompts are not serialized behind each other`() = runTest {
        val cache = SemanticCache(HashingEmbedder())
        var calls = 0

        (1..10).map { index ->
            async { cache.getOrPut("a distinct question number $index about lists") { calls++; "answer $index" } }
        }.awaitAll()

        assertEquals(10, calls)
        assertEquals(10, cache.size())
    }

    @Test
    fun `coalescing can be turned off`() = runTest {
        // Counting calls would prove nothing here — a single-threaded test dispatcher may well run
        // the callers one after another and fill the cache before the second reaches compute. So
        // every caller parks on a barrier that only opens once all five have arrived: reaching it
        // is only possible if all five are inside compute at the same time.
        val cache = SemanticCache(HashingEmbedder(), coalesceConcurrentMisses = false)
        val allInside = CompletableDeferred<Unit>()
        var inside = 0

        (1..5).map {
            async {
                cache.getOrPut("the very same prompt about lists") {
                    if (++inside == 5) allInside.complete(Unit)
                    allInside.await()
                    "answer"
                }
            }
        }.awaitAll()

        assertEquals(5, inside)
    }

    /** A unit vector in the plane whose dot product with `unit(1.0)` is exactly [similarity]. */
    private fun unit(similarity: Double): FloatArray =
        floatArrayOf(similarity.toFloat(), sqrt(1.0 - similarity * similarity).toFloat())
}
