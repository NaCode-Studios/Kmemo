package dev.kmemo

import dev.kmemo.fixtures.ConceptEmbedder
import dev.kmemo.fixtures.CountingEmbedder
import dev.kmemo.fixtures.HashingEmbedder
import kotlinx.coroutines.test.runTest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** M20 — shadow mode and per-scope thresholds. M19 part 2 — conversation-aware keys. */
class ShadowAndConversationTest {

    // --- Conversation-aware keys ---------------------------------------------------------------

    @Test
    fun `without context the cache keys on the last turn alone, as it always did`() = runTest {
        val cache = SemanticCache(HashingEmbedder())
        cache.getOrPut("what about the second one") { "the second is blue" }

        // The defect: a different exchange, same last turn, served the earlier answer.
        assertEquals("the second is blue", cache.get("what about the second one"))
    }

    @Test
    fun `the same turn after a different exchange is a different question`() = runTest {
        val cache = SemanticCache(HashingEmbedder())
        cache.getOrPut(
            "what about the second one",
            listOf("list the primary colours", "red, yellow, blue"),
        ) { "the second is yellow" }

        val other = cache.getOrPut(
            "what about the second one",
            listOf("list the planets", "Mercury, Venus, Earth"),
        ) { "the second is Venus" }

        assertEquals("the second is Venus", other, "context must not be silently ignored")
    }

    @Test
    fun `the same turn after the same exchange is a hit`() = runTest {
        val history = listOf("list the primary colours", "red, yellow, blue")
        val cache = SemanticCache(HashingEmbedder())
        cache.getOrPut("what about the second one", context = history) { "the second is yellow" }

        val again = cache.getOrPut("what about the second one", context = history) { "unreachable" }

        assertEquals("the second is yellow", again)
    }

    @Test
    fun `compute receives the bare prompt, not the folded conversation`() = runTest {
        val cache = SemanticCache(HashingEmbedder())
        var seen: String? = null

        cache.getOrPut("the last turn", context = listOf("earlier", "turns")) { seen = it; "answer" }

        assertEquals("the last turn", seen, "the context is the cache's business, not the model's")
    }

    @Test
    fun `a conversation written with context is readable with the same context`() = runTest {
        val history = listOf("set up a project", "done")
        val cache = SemanticCache(HashingEmbedder())
        cache.getOrPut("and now", context = history) { "now add the tests" }

        assertEquals("now add the tests", cache.get("and now", context = history))
        assertNull(cache.get("and now"), "reading without the context must not find it")
    }

    // --- Per-scope thresholds ------------------------------------------------------------------

    @Test
    fun `a scope override replaces the global threshold for that scope only`() = runTest {
        val cache = SemanticCache(
            ConceptEmbedder(),
            threshold = 0.99,
            thresholds = mapOf("lenient" to 0.1),
        )
        cache.put("how do I reverse a list", "use reversed()", scope = "lenient")
        cache.put("how do I reverse a list", "use reversed()", scope = "strict")

        // The same near-ish prompt clears 0.1 but not 0.99.
        assertIs<CacheLookup.Hit>(cache.lookup("how do I invert a list", scope = "lenient"))
        assertIs<CacheLookup.Miss>(cache.lookup("how do I invert a list", scope = "strict"))
    }

    @Test
    fun `explain reports the threshold that would actually apply`() = runTest {
        val cache = SemanticCache(HashingEmbedder(), threshold = 0.95, thresholds = mapOf("a" to 0.5))

        assertEquals(0.5, cache.explain("prompt", scope = "a").threshold)
        assertEquals(0.95, cache.explain("prompt", scope = "b").threshold)
    }

    @Test
    fun `an out-of-range override is rejected at construction`() {
        assertTrue(
            runCatching { SemanticCache(HashingEmbedder(), thresholds = mapOf("a" to 1.5)) }.isFailure,
        )
    }

    // --- Shadow mode ---------------------------------------------------------------------------

    @Test
    fun `shadow mode never serves, even for a prompt it has already seen`() = runTest {
        val cache = SemanticCache(HashingEmbedder(), shadowThresholds = listOf(0.5))
        var computes = 0

        cache.getOrPut("prompt") { computes++; "first" }
        val second = cache.getOrPut("prompt") { computes++; "second" }

        assertEquals("second", second, "shadow mode must never serve a cached answer")
        assertEquals(2, computes)
    }

    @Test
    fun `shadow mode still writes, or it would measure nothing`() = runTest {
        val cache = SemanticCache(HashingEmbedder(), shadowThresholds = listOf(0.5))

        cache.getOrPut("prompt") { "answer" }

        assertEquals(1, cache.size(), "a shadow cache that never fills reports a miss for everything")
    }

    @Test
    fun `the report carries one decision per configured threshold, in order`() = runTest {
        val listener = CapturingListener()
        val cache = SemanticCache(
            HashingEmbedder(),
            listeners = listOf(listener),
            shadowThresholds = listOf(0.99, 0.9, 0.5),
        )
        cache.getOrPut("how do I reverse a list") { "use reversed()" }
        listener.events.clear()

        cache.getOrPut("how do I reverse a list") { "use reversed()" }

        val shadow = assertIs<CacheEvent.Shadow>(listener.events.single { it is CacheEvent.Shadow })
        assertEquals(listOf(0.99, 0.9, 0.5), shadow.report.decisions.map { it.threshold })
    }

    @Test
    fun `a low threshold would hit where a high one would not`() = runTest {
        val listener = CapturingListener()
        val cache = SemanticCache(
            ConceptEmbedder(),
            listeners = listOf(listener),
            shadowThresholds = listOf(0.999, 0.1),
        )
        cache.getOrPut("how do I reverse a list") { "use reversed()" }
        listener.events.clear()

        cache.getOrPut("how do I invert a list") { "use reversed()" }

        val decisions = assertIs<CacheEvent.Shadow>(listener.events.single { it is CacheEvent.Shadow })
            .report.decisions.associateBy { it.threshold }
        assertEquals(false, decisions[0.999]?.wouldHit, "a near-1.0 threshold should refuse")
        assertEquals(true, decisions[0.1]?.wouldHit, "a permissive threshold should accept")
        assertEquals(MissReason.BELOW_THRESHOLD, decisions[0.999]?.reason)
    }

    @Test
    fun `an empty scope reports EMPTY_SCOPE at every threshold`() = runTest {
        val listener = CapturingListener()
        val cache = SemanticCache(
            HashingEmbedder(),
            listeners = listOf(listener),
            shadowThresholds = listOf(0.9, 0.5),
        )

        cache.getOrPut("first prompt ever") { "answer" }

        val shadow = assertIs<CacheEvent.Shadow>(listener.events.single { it is CacheEvent.Shadow })
        assertTrue(shadow.report.decisions.all { it.reason == MissReason.EMPTY_SCOPE && !it.wouldHit })
    }

    @Test
    fun `shadow mode moves no hit or miss counter`() = runTest {
        val cache = SemanticCache(HashingEmbedder(), shadowThresholds = listOf(0.5))
        cache.getOrPut("prompt") { "answer" }

        cache.getOrPut("prompt") { "answer" }

        val stats = cache.stats()
        assertEquals(0, stats.hits, "a mode that moved the numbers you are measuring would defeat itself")
        assertEquals(0, stats.lookups)
    }

    @Test
    fun `shadow mode embeds once per call and nothing more`() = runTest {
        val embedder = CountingEmbedder(HashingEmbedder())
        val cache = SemanticCache(embedder, shadowThresholds = listOf(0.9, 0.8, 0.7, 0.6, 0.5))

        cache.getOrPut("prompt") { "answer" }

        // Five thresholds are read off one search, not five searches and five embeds.
        assertEquals(1, embedder.calls)
    }

    private class CapturingListener : CacheListener {
        val events: MutableList<CacheEvent> = CopyOnWriteArrayList()

        override fun onEvent(event: CacheEvent) {
            events += event
        }
    }
}
