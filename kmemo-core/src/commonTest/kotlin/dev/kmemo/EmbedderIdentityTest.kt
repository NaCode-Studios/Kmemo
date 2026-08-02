package dev.kmemo

import dev.kmemo.fixtures.DeclaredEmbedder
import dev.kmemo.fixtures.HashingEmbedder
import dev.kmemo.store.InMemoryStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * M25 — the embedder is part of the key.
 *
 * Every test here shares one store between two caches whose embedders produce **identical vectors**
 * and differ only in what they declare. That is deliberate: the failure this guards against is the
 * quiet one, where the dimension count still matches and the similarities still look like
 * similarities, so a test that also changed the numbers would be proving something easier than the
 * real case.
 */
class EmbedderIdentityTest {

    private val prompt = "How do I reverse a list in Kotlin?"

    @Test
    fun `a store written by one embedder is not served to another`() = runTest {
        val store = InMemoryStore()
        SemanticCache(DeclaredEmbedder("openai:text-embedding-3-small"), store)
            .put(prompt, "use reversed()")

        val afterSwap = SemanticCache(DeclaredEmbedder("openai:text-embedding-3-large"), store)
        val result = afterSwap.lookup(prompt)

        val miss = assertIs<CacheLookup.Miss>(result)
        assertEquals(MissReason.EMBEDDER_MISMATCH, miss.reason)
        assertTrue(
            miss.detail.orEmpty().contains("text-embedding-3-small") &&
                miss.detail.orEmpty().contains("text-embedding-3-large"),
            "the detail should name both identities, was: ${miss.detail}",
        )
    }

    @Test
    fun `the refusal emits an event naming both identities and the entry`() = runTest {
        val store = InMemoryStore()
        val written = SemanticCache(DeclaredEmbedder("v1"), store).put(prompt, "use reversed()")

        val listener = CapturingListener()
        SemanticCache(DeclaredEmbedder("v2"), store, listeners = listOf(listener)).lookup(prompt)

        val event = assertIs<CacheEvent.EmbedderMismatch>(
            listener.events.single { it is CacheEvent.EmbedderMismatch },
        )
        assertEquals("v2", event.expected)
        assertEquals("v1", event.found)
        assertEquals(written, event.entryId)
    }

    @Test
    fun `getOrPut recomputes rather than serving an entry from another embedder`() = runTest {
        val store = InMemoryStore()
        SemanticCache(DeclaredEmbedder("v1"), store).put(prompt, "the stale answer")

        var computed = false
        val answer = SemanticCache(DeclaredEmbedder("v2"), store).getOrPut(prompt) {
            computed = true
            "the fresh answer"
        }

        assertEquals("the fresh answer", answer)
        assertTrue(computed, "the model must be called; the cached answer was not comparable")
    }

    /**
     * The compatibility promise the whole design turns on. A caller who declares nothing is not
     * opting out of the check — `undeclared` is an identity like any other — they are simply on both
     * sides of it, which is where every existing deployment already is.
     */
    @Test
    fun `two undeclared embedders share a store exactly as they always did`() = runTest {
        val store = InMemoryStore()
        SemanticCache(HashingEmbedder(), store).put(prompt, "use reversed()")

        val result = SemanticCache(HashingEmbedder(), store).lookup(prompt)

        assertIs<CacheLookup.Hit>(result)
        assertEquals("use reversed()", result.response)
    }

    /**
     * Declaring an identity for the first time, against a store that was filled before identities
     * existed. Those entries are refused, and that is the honest outcome rather than a regression:
     * nothing recorded what produced them, so nothing can vouch for them.
     */
    @Test
    fun `declaring an identity does not adopt entries written before identities existed`() = runTest {
        val store = InMemoryStore()
        SemanticCache(HashingEmbedder(), store).put(prompt, "written by nobody in particular")

        val declared = SemanticCache(DeclaredEmbedder("openai:text-embedding-3-small"), store)

        assertEquals(MissReason.EMBEDDER_MISMATCH, assertIs<CacheLookup.Miss>(declared.lookup(prompt)).reason)
    }

    @Test
    fun `the same identity across two cache instances still hits`() = runTest {
        val store = InMemoryStore()
        SemanticCache(DeclaredEmbedder("openai:text-embedding-3-small"), store).put(prompt, "use reversed()")

        val restarted = SemanticCache(DeclaredEmbedder("openai:text-embedding-3-small"), store)

        assertEquals("use reversed()", assertIs<CacheLookup.Hit>(restarted.lookup(prompt)).response)
    }

    /**
     * A store part-way through a re-embedding holds both, and the lookup has to walk past the stale
     * entry to the fresh one instead of stopping at the first refusal. Otherwise the migration path
     * documented in `docs/MIGRATION.md` would only work once every last entry had been rewritten.
     */
    @Test
    fun `a usable entry behind a mismatched one is still served`() = runTest {
        val store = InMemoryStore()
        // Same prompt, so the stale entry scores 1.0 and is considered first.
        SemanticCache(DeclaredEmbedder("v1"), store).put(prompt, "the stale answer")
        SemanticCache(DeclaredEmbedder("v2"), store).put(prompt, "the re-embedded answer")

        val result = SemanticCache(DeclaredEmbedder("v2"), store).lookup(prompt)

        assertEquals("the re-embedded answer", assertIs<CacheLookup.Hit>(result).response)
    }

    @Test
    fun `a mismatch is counted once per lookup even when the lookup goes on to hit`() = runTest {
        val store = InMemoryStore()
        SemanticCache(DeclaredEmbedder("v1"), store).put(prompt, "the stale answer")
        val cache = SemanticCache(DeclaredEmbedder("v2"), store)
        cache.put(prompt, "the re-embedded answer")

        cache.lookup(prompt)

        val stats = cache.stats()
        assertEquals(1, stats.hits, "the fresh entry was served")
        assertEquals(1, stats.embedderMismatches, "and the stale one was met exactly once")
    }

    @Test
    fun `an undeclared cache never counts a mismatch`() = runTest {
        val cache = SemanticCache(HashingEmbedder())
        cache.put(prompt, "use reversed()")

        cache.lookup(prompt)

        assertEquals(0, cache.stats().embedderMismatches)
    }

    @Test
    fun `explain names the mismatch instead of blaming a guard`() = runTest {
        val store = InMemoryStore()
        SemanticCache(DeclaredEmbedder("v1"), store).put(prompt, "use reversed()")

        val explanation = SemanticCache(DeclaredEmbedder("v2"), store).explain(prompt)

        assertEquals(MissReason.EMBEDDER_MISMATCH, explanation.decision)
        val candidate = explanation.candidates.single()
        assertTrue(candidate.aboveThreshold, "the vectors are identical; only the declaration differs")
        assertTrue(candidate.rejectingGuards.isEmpty(), "no guard objects to this pair")
        assertTrue(!candidate.embedderMatches && !candidate.wouldServe)
    }

    @Test
    fun `an entry carries its embedder through withResponse`() = runTest {
        val store = InMemoryStore()
        SemanticCache(DeclaredEmbedder("v1"), store).put(prompt, "first")

        val original = store.search(SemanticCache.DEFAULT_SCOPE, HashingEmbedder().embed(prompt), 1)
            .single().entry

        assertEquals("v1", original.withResponse("second").embedder)
    }

    /**
     * Deduplication runs the same similarity that the read path refuses to trust across embedders, so
     * it has to refuse it in the same place. Merging here would delete a live answer on the strength
     * of a number about two different vector spaces.
     */
    @Test
    fun `deduplicateWrites never merges across embedders`() = runTest {
        val store = InMemoryStore()
        SemanticCache(DeclaredEmbedder("v1"), store, deduplicateWrites = 0.99).put(prompt, "old model")

        SemanticCache(DeclaredEmbedder("v2"), store, deduplicateWrites = 0.99).put(prompt, "new model")

        assertEquals(2, store.size(), "both entries survive; only one embedder wrote each")
    }

    @Test
    fun `an embedder that declares nothing reports the undeclared identity`() {
        assertEquals(Embedder.UNDECLARED, HashingEmbedder().identity)
    }

    private class CapturingListener : CacheListener {
        val events = mutableListOf<CacheEvent>()
        override fun onEvent(event: CacheEvent) {
            events += event
        }
    }
}
