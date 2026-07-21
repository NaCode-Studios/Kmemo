package dev.kmemo

import dev.kmemo.fixtures.ConceptEmbedder
import dev.kmemo.fixtures.HashingEmbedder
import dev.kmemo.fixtures.MutableClock
import dev.kmemo.store.InMemoryStore
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/** M9 — the CacheEvent stream: emission from the cache, evictions from the store, and the Flow bridge. */
class CacheEventTest {

    @Test
    fun `a hit emits a Hit event carrying the match`() = runTest {
        val listener = CapturingListener()
        val cache = SemanticCache(HashingEmbedder(), listeners = listOf(listener))
        cache.put("How do I reverse a list?", "use reversed()")

        cache.lookup("How do I reverse a list?")

        val hit = assertIs<CacheEvent.Hit>(listener.events.single { it is CacheEvent.Hit })
        assertEquals("How do I reverse a list?", hit.matchedPrompt)
        assertEquals(SemanticCache.DEFAULT_SCOPE, hit.scope)
        assertTrue(hit.entryId.isNotBlank())
    }

    @Test
    fun `a miss emits a Miss event with the same reason the lookup reports`() = runTest {
        val listener = CapturingListener()
        val cache = SemanticCache(ConceptEmbedder(), listeners = listOf(listener))
        cache.put("Convert 100 USD to EUR", "about 92 EUR")

        cache.lookup("Convert 250 USD to EUR") // the numeric guard refuses this

        val miss = assertIs<CacheEvent.Miss>(listener.events.single { it is CacheEvent.Miss })
        assertEquals(MissReason.REJECTED_BY_GUARD, miss.reason)
        assertTrue(miss.detail.orEmpty().startsWith("numeric"))
    }

    @Test
    fun `put and a computing getOrPut both emit Write`() = runTest {
        val listener = CapturingListener()
        val cache = SemanticCache(HashingEmbedder(), listeners = listOf(listener))

        cache.put("first question about lists", "a")
        cache.getOrPut("a different question about maps") { "b" }

        assertEquals(2, listener.events.count { it is CacheEvent.Write })
    }

    @Test
    fun `warm emits a Write for each preloaded entry`() = runTest {
        val listener = CapturingListener()
        val cache = SemanticCache(HashingEmbedder(), listeners = listOf(listener))

        cache.warm((1..3).map { WarmEntry("question $it", "answer $it") })

        assertEquals(3, listener.events.count { it is CacheEvent.Write })
    }

    @Test
    fun `timings are measured, and the verifier time is zero when none runs`() = runTest {
        val listener = CapturingListener()
        val cache = SemanticCache(HashingEmbedder(), listeners = listOf(listener))
        cache.put("How do I reverse a list?", "use reversed()")

        cache.lookup("How do I reverse a list?")

        val hit = assertIs<CacheEvent.Hit>(listener.events.single { it is CacheEvent.Hit })
        assertTrue(hit.timings.searchNanos >= 0)
        assertTrue(hit.timings.embedNanos >= 0)
        assertEquals(0L, hit.timings.verifierNanos, "no verifier ran, so its time must be reported as 0")
    }

    @Test
    fun `verifier time is captured when a verifier runs`() = runTest {
        val listener = CapturingListener()
        val cache = SemanticCache(
            HashingEmbedder(),
            verifier = { _, _, _ -> delay(10.milliseconds); true },
            listeners = listOf(listener),
        )
        cache.put("How do I reverse a list?", "use reversed()")

        cache.lookup("How do I reverse a list?")

        val hit = assertIs<CacheEvent.Hit>(listener.events.single { it is CacheEvent.Hit })
        assertTrue(hit.timings.verifierNanos > 0, "a verifier ran, so its time must be non-zero")
    }

    @Test
    fun `a listener that throws never breaks the lookup`() = runTest {
        val cache = SemanticCache(
            HashingEmbedder(),
            listeners = listOf(CacheListener { throw IllegalStateException("bad sink") }),
        )
        cache.put("How do I reverse a list?", "use reversed()")

        val hit = assertIs<CacheLookup.Hit>(cache.lookup("How do I reverse a list?"))
        assertEquals("use reversed()", hit.response)
    }

    @Test
    fun `coalesced concurrent misses emit one event per counted lookup`() = runTest {
        val listener = CapturingListener()
        val cache = SemanticCache(HashingEmbedder(), listeners = listOf(listener))

        (1..20).map {
            async { cache.getOrPut("the one question everyone asks at once") { "answer" } }
        }.awaitAll()

        // One event per counted lookup: the re-check inside the coalescing lock is uncounted and must
        // not emit. Lookups == hits + misses == 20; plus exactly one Write for the single compute.
        val hits = listener.events.count { it is CacheEvent.Hit }
        val misses = listener.events.count { it is CacheEvent.Miss }
        val writes = listener.events.count { it is CacheEvent.Write }
        assertEquals(20, hits + misses)
        assertEquals(1, writes)
        assertEquals(cache.stats().hits, hits.toLong())
        assertEquals(cache.stats().misses, misses.toLong())
    }

    @Test
    fun `the store reports a capacity eviction`() = runTest {
        val listener = CapturingListener()
        val store = InMemoryStore(maxEntries = 1, listener = listener)

        store.put(entry("first", "a"))
        store.put(entry("second", "b"))

        val eviction = assertIs<CacheEvent.Eviction>(listener.events.single { it is CacheEvent.Eviction })
        assertEquals(EvictionCause.CAPACITY, eviction.cause)
        assertEquals("first", eviction.prompt)
    }

    @Test
    fun `the store reports an expiry eviction`() = runTest {
        val listener = CapturingListener()
        val clock = MutableClock()
        val store = InMemoryStore(ttl = 1.minutes, clock = clock, listener = listener)
        store.put(entry("perishable", "a"))

        clock.advance(2.minutes)
        store.purgeExpired()

        val eviction = assertIs<CacheEvent.Eviction>(listener.events.single { it is CacheEvent.Eviction })
        assertEquals(EvictionCause.EXPIRED, eviction.cause)
    }

    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun `CacheEvents republishes the stream as a Flow`() = runTest {
        val events = CacheEvents()
        val cache = SemanticCache(HashingEmbedder(), listeners = listOf(events))
        val received = CopyOnWriteArrayList<CacheEvent>()

        backgroundScope.launch { events.events.collect { received += it } }
        runCurrent() // let the collector subscribe before anything is emitted (replay = 0)

        cache.put("How do I reverse a list?", "use reversed()")
        cache.lookup("How do I reverse a list?")
        runCurrent()

        assertTrue(received.any { it is CacheEvent.Write })
        assertTrue(received.any { it is CacheEvent.Hit })
    }

    @Test
    fun `with no listeners the cache still works and stays quiet`() = runTest {
        // The default path: nothing to assert about events, only that behaviour is unchanged.
        val cache = SemanticCache(HashingEmbedder())
        cache.put("How do I reverse a list?", "use reversed()")
        assertEquals("use reversed()", cache.get("How do I reverse a list?"))
    }

    private fun entry(prompt: String, response: String, scope: String = "s"): CacheEntry =
        CacheEntry(
            id = "id-$prompt",
            scope = scope,
            prompt = prompt,
            response = response,
            embedding = floatArrayOf(1.0f, 2.0f, 3.0f),
            createdAt = Instant.parse("2026-01-01T00:00:00Z"),
        )

    /** Collects every event, thread-safely, for assertions. */
    private class CapturingListener : CacheListener {
        val events: MutableList<CacheEvent> = CopyOnWriteArrayList()

        override fun onEvent(event: CacheEvent) {
            events += event
        }
    }
}
