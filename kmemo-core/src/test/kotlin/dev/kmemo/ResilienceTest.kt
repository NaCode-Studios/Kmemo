package dev.kmemo

import dev.kmemo.fixtures.CountingEmbedder
import dev.kmemo.fixtures.HashingEmbedder
import dev.kmemo.fixtures.MutableClock
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/** M8 — resilience: embed-failure policy, retrying embedder, negative caching, and warm preload. */
class ResilienceTest {

    // --- EmbedFailurePolicy -------------------------------------------------------------------

    @Test
    fun `by default an embed failure propagates out of getOrPut`() = runTest {
        val cache = SemanticCache(AlwaysFailingEmbedder())

        assertFailsWith<IOException> {
            cache.getOrPut("anything") { "should never be reached" }
        }
    }

    @Test
    fun `fall-back policy degrades getOrPut to an uncached model call`() = runTest {
        val cache = SemanticCache(
            AlwaysFailingEmbedder(),
            embedFailurePolicy = EmbedFailurePolicy.FALL_BACK_TO_COMPUTE,
        )
        var computed = false

        val answer = cache.getOrPut("anything") { computed = true; "fresh from the model" }

        assertEquals("fresh from the model", answer)
        assertTrue(computed, "fall-back must call compute when the embedder is down")
        // There was no embedding to key it by, so nothing could be written back.
        assertEquals(0, cache.size())
    }

    @Test
    fun `cancellation always propagates, even under the fall-back policy`() = runTest {
        val cache = SemanticCache(
            CancellingEmbedder(),
            embedFailurePolicy = EmbedFailurePolicy.FALL_BACK_TO_COMPUTE,
        )

        assertFailsWith<CancellationException> {
            cache.getOrPut("anything") { "should never be reached" }
        }
    }

    @Test
    fun `lookup and put propagate an embed failure regardless of policy`() = runTest {
        val cache = SemanticCache(
            AlwaysFailingEmbedder(),
            embedFailurePolicy = EmbedFailurePolicy.FALL_BACK_TO_COMPUTE,
        )

        // The fall-back only covers getOrPut, the one entry point with a compute to fall back to.
        assertFailsWith<IOException> { cache.lookup("anything") }
        assertFailsWith<IOException> { cache.put("anything", "answer") }
    }

    // --- Degraded telemetry -------------------------------------------------------------------

    @Test
    fun `a fall-back is counted, so stepping aside is never silent`() = runTest {
        val cache = SemanticCache(
            AlwaysFailingEmbedder(),
            embedFailurePolicy = EmbedFailurePolicy.FALL_BACK_TO_COMPUTE,
        )

        cache.getOrPut("anything") { "fresh from the model" }

        val stats = cache.stats()
        assertEquals(1, stats.degradedLookups)
        // A fall-back is not a miss: no lookup ever happened, so the hit rate must not move.
        assertEquals(0, stats.lookups)
        assertEquals(0, stats.misses)
        assertEquals(0.0, stats.hitRate)
    }

    @Test
    fun `the counter moves with no listener attached`() = runTest {
        val cache = SemanticCache(
            AlwaysFailingEmbedder(),
            embedFailurePolicy = EmbedFailurePolicy.FALL_BACK_TO_COMPUTE,
        )

        repeat(3) { cache.getOrPut("anything") { "answer" } }

        // stats() is the floor of observability and must answer this on its own.
        assertEquals(3, cache.stats().degradedLookups)
    }

    @Test
    fun `a fall-back emits Degraded naming the operation, the policy and the cause`() = runTest {
        val listener = CollectingListener()
        val cache = SemanticCache(
            AlwaysFailingEmbedder(),
            embedFailurePolicy = EmbedFailurePolicy.FALL_BACK_TO_COMPUTE,
            listeners = listOf(listener),
        )

        cache.getOrPut("anything", scope = "tenant-a") { "answer" }

        val degraded = assertIs<CacheEvent.Degraded>(listener.events.single { it is CacheEvent.Degraded })
        assertEquals(DegradedOperation.GET_OR_PUT, degraded.operation)
        assertEquals(EmbedFailurePolicy.FALL_BACK_TO_COMPUTE, degraded.policy)
        assertEquals("tenant-a", degraded.scope)
        assertEquals(listOf("anything"), degraded.prompts)
        assertIs<IOException>(degraded.cause)
    }

    @Test
    fun `a degraded batch is one event carrying every prompt`() = runTest {
        val listener = CollectingListener()
        val cache = SemanticCache(
            AlwaysFailingEmbedder(),
            embedFailurePolicy = EmbedFailurePolicy.FALL_BACK_TO_COMPUTE,
            listeners = listOf(listener),
        )

        cache.getOrPutAll(listOf("a", "b", "c")) { "answer for $it" }

        val degraded = assertIs<CacheEvent.Degraded>(listener.events.single { it is CacheEvent.Degraded })
        assertEquals(DegradedOperation.GET_OR_PUT_ALL, degraded.operation)
        assertEquals(listOf("a", "b", "c"), degraded.prompts)
        // One failed embedAll call is one degraded call, not one per prompt.
        assertEquals(1, cache.stats().degradedLookups)
    }

    @Test
    fun `a degraded streaming call is reported as its own operation`() = runTest {
        val listener = CollectingListener()
        val cache = SemanticCache(
            AlwaysFailingEmbedder(),
            embedFailurePolicy = EmbedFailurePolicy.FALL_BACK_TO_COMPUTE,
            listeners = listOf(listener),
        )

        val chunks = cache.getOrPutStreaming("anything") { flowOf("one", "two") }.toList()

        assertEquals(listOf("one", "two"), chunks)
        val degraded = assertIs<CacheEvent.Degraded>(listener.events.single { it is CacheEvent.Degraded })
        assertEquals(DegradedOperation.GET_OR_PUT_STREAMING, degraded.operation)
    }

    @Test
    fun `nothing is reported under PROPAGATE, where the caller sees the exception instead`() = runTest {
        val listener = CollectingListener()
        val cache = SemanticCache(AlwaysFailingEmbedder(), listeners = listOf(listener))

        assertFailsWith<IOException> { cache.getOrPut("anything") { "unreachable" } }

        assertEquals(0, cache.stats().degradedLookups)
        assertTrue(listener.events.none { it is CacheEvent.Degraded })
    }

    @Test
    fun `a retrying embedder that exhausts its attempts arrives as the Degraded cause`() = runTest {
        val listener = CollectingListener()
        val cache = SemanticCache(
            AlwaysFailingEmbedder().retrying(maxAttempts = 2, initialDelay = 1.milliseconds),
            embedFailurePolicy = EmbedFailurePolicy.FALL_BACK_TO_COMPUTE,
            listeners = listOf(listener),
        )

        cache.getOrPut("anything") { "answer" }

        // Retrying is transparent to the cache, so there is no separate "retries exhausted" event:
        // the final throwable is what surfaces here.
        val degraded = assertIs<CacheEvent.Degraded>(listener.events.single { it is CacheEvent.Degraded })
        assertIs<IOException>(degraded.cause)
        assertEquals(1, cache.stats().degradedLookups)
    }

    private class CollectingListener : CacheListener {
        val events: MutableList<CacheEvent> = java.util.concurrent.CopyOnWriteArrayList()

        override fun onEvent(event: CacheEvent) {
            events += event
        }
    }

    // --- RetryingEmbedder ---------------------------------------------------------------------

    @Test
    fun `a retrying embedder rides out transient failures`() = runTest {
        val flaky = FlakyEmbedder(failures = 2)
        val embedder = flaky.retrying(maxAttempts = 3, initialDelay = 1.milliseconds)

        embedder.embed("hello") // first two attempts throw, the third succeeds

        assertEquals(3, flaky.attempts)
    }

    @Test
    fun `a retrying embedder gives up after maxAttempts and surfaces the failure`() = runTest {
        val flaky = FlakyEmbedder(failures = 99)
        val embedder = flaky.retrying(maxAttempts = 3, initialDelay = 1.milliseconds)

        assertFailsWith<IOException> { embedder.embed("hello") }
        assertEquals(3, flaky.attempts)
    }

    @Test
    fun `a retrying embedder never retries cancellation`() = runTest {
        val cancelling = CancellingEmbedder()
        val embedder = cancelling.retrying(maxAttempts = 5, initialDelay = 1.milliseconds)

        assertFailsWith<CancellationException> { embedder.embed("hello") }
        assertEquals(1, cancelling.calls, "cancellation must not be retried")
    }

    @Test
    fun `retryOn can rule a failure not worth retrying`() = runTest {
        val flaky = FlakyEmbedder(failures = 99)
        val embedder = flaky.retrying(maxAttempts = 5, initialDelay = 1.milliseconds) { it !is IOException }

        assertFailsWith<IOException> { embedder.embed("hello") }
        assertEquals(1, flaky.attempts, "an IOException was declared not retryable")
    }

    // --- Negative caching ---------------------------------------------------------------------

    @Test
    fun `a repeated brand-new miss is embedded once when the negative cache is on`() = runTest {
        val embedder = CountingEmbedder(HashingEmbedder())
        val cache = SemanticCache(embedder, negativeCacheSize = 100)

        cache.lookup("a question nobody has asked before")
        cache.lookup("a question nobody has asked before")
        cache.lookup("a question nobody has asked before")

        assertEquals(1, embedder.calls, "the negative cache should reuse the first miss's embedding")
    }

    @Test
    fun `without the negative cache every miss embeds again`() = runTest {
        val embedder = CountingEmbedder(HashingEmbedder())
        val cache = SemanticCache(embedder) // negativeCacheSize defaults to 0 (off)

        cache.lookup("a question nobody has asked before")
        cache.lookup("a question nobody has asked before")

        assertEquals(2, embedder.calls)
    }

    @Test
    fun `the negative cache never hides a newly written entry`() = runTest {
        val cache = SemanticCache(HashingEmbedder(), negativeCacheSize = 100)

        cache.lookup("unique question about reversing a list") // miss, remembered
        cache.put("unique question about reversing a list", "use reversed()")

        // The remembered embedding must not suppress the search: the entry just written has to be found.
        val hit = assertIs<CacheLookup.Hit>(cache.lookup("unique question about reversing a list"))
        assertEquals("use reversed()", hit.response)
    }

    @Test
    fun `a remembered miss expires with its ttl`() = runTest {
        val clock = MutableClock()
        val embedder = CountingEmbedder(HashingEmbedder())
        val cache = SemanticCache(
            embedder,
            negativeCacheSize = 100,
            negativeCacheTtl = 1.minutes,
            clock = clock,
        )

        cache.lookup("a question nobody has asked before") // embeds, remembers at t0
        clock.advance(2.minutes)
        cache.lookup("a question nobody has asked before") // note expired → embeds again

        assertEquals(2, embedder.calls)
    }

    // --- warm() -------------------------------------------------------------------------------

    @Test
    fun `warm preloads answers and returns their ids in order`() = runTest {
        val embedder = RecordingEmbedder()
        val cache = SemanticCache(embedder)

        val ids = cache.warm(
            listOf(
                WarmEntry("How do I reverse a list?", "use reversed()"),
                WarmEntry("What is 2 plus 2?", "four", scope = "math"),
            ),
        )

        assertEquals(2, ids.size)
        assertEquals("use reversed()", cache.get("How do I reverse a list?"))
        assertEquals("four", cache.get("What is 2 plus 2?", scope = "math"))
        // Scope is honoured exactly as it is for put: a warmed entry in the wrong scope is invisible.
        assertNull(cache.get("What is 2 plus 2?"))
    }

    @Test
    fun `warm embeds the whole batch in one call`() = runTest {
        val embedder = RecordingEmbedder()
        val cache = SemanticCache(embedder)

        cache.warm((1..10).map { WarmEntry("question number $it", "answer $it") })

        assertEquals(1, embedder.embedAllCalls, "warm must batch, not embed one prompt at a time")
    }

    @Test
    fun `warming an empty list does nothing`() = runTest {
        val embedder = RecordingEmbedder()
        val cache = SemanticCache(embedder)

        assertTrue(cache.warm(emptyList()).isEmpty())
        assertEquals(0, embedder.embedAllCalls)
        assertEquals(0, cache.size())
    }

    // --- test doubles -------------------------------------------------------------------------

    /** Always throws, to exercise the embed-failure paths. */
    private class AlwaysFailingEmbedder : Embedder {
        override suspend fun embed(text: String): FloatArray = throw IOException("embedder is down")
    }

    /** Throws [CancellationException], which must always propagate and never be retried. */
    private class CancellingEmbedder : Embedder {
        var calls: Int = 0
            private set

        override suspend fun embed(text: String): FloatArray {
            calls++
            throw CancellationException("cancelled")
        }
    }

    /** Fails its first [failures] calls, then delegates. Counts attempts for retry assertions. */
    private class FlakyEmbedder(
        private val failures: Int,
        private val delegate: Embedder = HashingEmbedder(),
    ) : Embedder {
        var attempts: Int = 0
            private set

        override suspend fun embed(text: String): FloatArray {
            attempts++
            if (attempts <= failures) throw IOException("transient failure #$attempts")
            return delegate.embed(text)
        }
    }

    /** Records whether the batch or the single-prompt path was taken. */
    private class RecordingEmbedder(private val delegate: Embedder = HashingEmbedder()) : Embedder {
        var embedAllCalls: Int = 0
            private set

        override suspend fun embed(text: String): FloatArray = delegate.embed(text)

        override suspend fun embedAll(texts: List<String>): List<FloatArray> {
            embedAllCalls++
            return delegate.embedAll(texts)
        }
    }
}
