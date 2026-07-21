package dev.kmemo.micrometer

import dev.kmemo.Embedder
import dev.kmemo.SemanticCache
import dev.kmemo.store.InMemoryStore
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KmemoMetricsTest {

    /** Deterministic bag-of-words embedder; 64 dims keeps hash collisions from faking similarity. */
    private val embedder = Embedder { text ->
        val vector = FloatArray(64)
        val tokens = text.lowercase().split(Regex("\\W+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) vector[0] = 1.0f
        for (token in tokens) vector[Math.floorMod(token.hashCode(), 64)] += 1.0f
        vector
    }

    @Test
    fun `hits, misses and writes are counted`() = runTest {
        val registry = SimpleMeterRegistry()
        val metrics = KmemoMetrics()
        metrics.bindTo(registry)
        val cache = SemanticCache(embedder, threshold = 0.5, listeners = listOf(metrics))

        cache.put("How do I reverse a list?", "use reversed()")
        cache.lookup("How do I reverse a list?") // hit
        cache.lookup("zebra xylophone vortex nimbus") // miss (no shared words)

        assertEquals(2.0, registry.get("kmemo.cache.lookups").counter().count())
        assertEquals(1.0, registry.get("kmemo.cache.hits").counter().count())
        assertEquals(1.0, registry.get("kmemo.cache.writes").counter().count())
    }

    @Test
    fun `misses are split by reason`() = runTest {
        val registry = SimpleMeterRegistry()
        val metrics = KmemoMetrics()
        metrics.bindTo(registry)
        val cache = SemanticCache(embedder, threshold = 0.5, listeners = listOf(metrics))

        cache.lookup("a lonely query against an empty scope") // EMPTY_SCOPE

        assertEquals(
            1.0,
            registry.get("kmemo.cache.misses").tag("reason", "empty_scope").counter().count(),
        )
        // A reason that has not occurred still exists as a zero series (pre-registered).
        assertEquals(
            0.0,
            registry.get("kmemo.cache.misses").tag("reason", "below_threshold").counter().count(),
        )
    }

    @Test
    fun `guard rejections are tagged by the guard that fired`() = runTest {
        val registry = SimpleMeterRegistry()
        val metrics = KmemoMetrics()
        metrics.bindTo(registry)
        val cache = SemanticCache(embedder, threshold = 0.5, listeners = listOf(metrics))
        cache.put("Convert 100 USD to EUR", "about 92 EUR")

        cache.lookup("Convert 250 USD to EUR") // clears threshold, numeric guard refuses

        assertEquals(
            1.0,
            registry.get("kmemo.cache.guard.rejections").tag("guard", "numeric").counter().count(),
        )
        assertEquals(
            1.0,
            registry.get("kmemo.cache.misses").tag("reason", "rejected_by_guard").counter().count(),
        )
    }

    @Test
    fun `latency timers record a sample per lookup`() = runTest {
        val registry = SimpleMeterRegistry()
        val metrics = KmemoMetrics()
        metrics.bindTo(registry)
        val cache = SemanticCache(embedder, threshold = 0.5, listeners = listOf(metrics))
        cache.put("How do I reverse a list?", "use reversed()")

        cache.lookup("How do I reverse a list?")

        assertTrue(registry.get("kmemo.cache.embed").timer().count() >= 1)
        assertTrue(registry.get("kmemo.cache.search").timer().count() >= 1)
    }

    @Test
    fun `the hit ratio gauge tracks hits over lookups`() = runTest {
        val registry = SimpleMeterRegistry()
        val metrics = KmemoMetrics()
        metrics.bindTo(registry)
        val cache = SemanticCache(embedder, threshold = 0.5, listeners = listOf(metrics))
        cache.put("How do I reverse a list?", "use reversed()")

        cache.lookup("How do I reverse a list?") // hit
        cache.lookup("zebra xylophone vortex nimbus") // miss (no shared words)

        assertEquals(0.5, registry.get("kmemo.cache.hit.ratio").gauge().value(), 1e-9)
    }

    @Test
    fun `evictions are counted when the store shares the listener`() = runTest {
        val registry = SimpleMeterRegistry()
        val metrics = KmemoMetrics()
        metrics.bindTo(registry)
        val cache = SemanticCache(
            embedder,
            store = InMemoryStore(maxEntries = 1, listener = metrics),
            listeners = listOf(metrics),
        )

        cache.put("the first question, soon to be evicted", "a")
        cache.put("a second question that pushes the first out", "b")

        assertEquals(
            1.0,
            registry.get("kmemo.cache.evictions").tag("cause", "capacity").counter().count(),
        )
    }

    @Test
    fun `events before binding are ignored`() = runTest {
        val registry = SimpleMeterRegistry()
        val metrics = KmemoMetrics()
        val cache = SemanticCache(embedder, threshold = 0.5, listeners = listOf(metrics))

        cache.put("written before binding", "a")
        cache.lookup("written before binding") // hit, but no meters exist yet

        metrics.bindTo(registry)
        cache.lookup("written before binding") // hit, now counted

        assertEquals(1.0, registry.get("kmemo.cache.hits").counter().count())
    }
}
