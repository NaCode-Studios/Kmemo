package dev.kmemo

import dev.kmemo.fixtures.HashingEmbedder
import dev.kmemo.store.InMemoryStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** M10 — batch embedding (getOrPutAll) and opt-in, ordered write-behind puts. */
class BatchWriteBehindTest {

    // --- getOrPutAll ---------------------------------------------------------------------------

    @Test
    fun `getOrPutAll embeds the whole batch in a single call`() = runTest {
        val embedder = RecordingEmbedder()
        val cache = SemanticCache(embedder)

        cache.getOrPutAll(listOf("q1", "q2", "q3", "q4")) { "answer" }

        assertEquals(1, embedder.embedAllCalls, "the batch path must embed once, not once per prompt")
    }

    @Test
    fun `getOrPutAll returns answers in order, serving hits and computing misses`() = runTest {
        val cache = SemanticCache(HashingEmbedder())
        cache.put("a cached question about reversing lists", "cached answer")
        var computes = 0

        val answers = cache.getOrPutAll(
            listOf("a cached question about reversing lists", "a brand new question about sorting maps"),
        ) { prompt -> computes++; "computed for $prompt" }

        assertEquals(
            listOf("cached answer", "computed for a brand new question about sorting maps"),
            answers,
        )
        assertEquals(1, computes, "only the miss should have been computed")
    }

    @Test
    fun `getOrPutAll on an empty list embeds nothing`() = runTest {
        val embedder = RecordingEmbedder()
        val cache = SemanticCache(embedder)

        assertTrue(cache.getOrPutAll(emptyList()) { "answer" }.isEmpty())
        assertEquals(0, embedder.embedAllCalls)
    }

    @Test
    fun `getOrPutAll honours the fall-back policy when the batch embed fails`() = runTest {
        val cache = SemanticCache(
            AlwaysFailingEmbedder(),
            embedFailurePolicy = EmbedFailurePolicy.FALL_BACK_TO_COMPUTE,
        )

        val answers = cache.getOrPutAll(listOf("q1", "q2")) { prompt -> "computed:$prompt" }

        assertEquals(listOf("computed:q1", "computed:q2"), answers)
        assertEquals(0, cache.size(), "a fall-back batch cannot write back without embeddings")
    }

    // --- write-behind --------------------------------------------------------------------------

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `write-behind returns before the store write and applies it afterwards`() = runTest {
        // An unconfined worker drains the queue eagerly, so the test is deterministic without advancing
        // virtual time; the gated store is what proves the write is deferred rather than awaited.
        val writeScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        try {
            val gated = GatedStore(InMemoryStore())
            val cache = SemanticCache(HashingEmbedder(), store = gated, writeBehindScope = writeScope)

            val answer = cache.getOrPut("a brand new question about lists") { "computed" }

            assertEquals("computed", answer)
            assertEquals(0, cache.size(), "the worker is parked at the gate; the write is not applied yet")

            gated.release()

            assertEquals(1, cache.size())
            assertEquals("computed", cache.get("a brand new question about lists"))
        } finally {
            writeScope.cancel()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `every write-behind write lands`() = runTest {
        val writeScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        try {
            val cache = SemanticCache(
                HashingEmbedder(),
                writeBehindScope = writeScope,
                writeBehindCapacity = 4,
            )

            for (i in 1..20) {
                cache.getOrPut("wholly distinct question $i about subject $i and nothing else $i") { "answer $i" }
            }

            assertEquals(20, cache.size(), "no write-behind write may be lost")
        } finally {
            writeScope.cancel()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a hit is served after a write-behind write`() = runTest {
        val writeScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        try {
            val cache = SemanticCache(HashingEmbedder(), writeBehindScope = writeScope)

            cache.getOrPut("how do I reverse a list in Kotlin?") { "use reversed()" }

            val hit = assertIs<CacheLookup.Hit>(cache.lookup("how do I reverse a list in Kotlin?"))
            assertEquals("use reversed()", hit.response)
        } finally {
            writeScope.cancel()
        }
    }

    // --- test doubles --------------------------------------------------------------------------

    /** Records whether the batch path was taken. */
    private class RecordingEmbedder(private val delegate: Embedder = HashingEmbedder()) : Embedder {
        var embedAllCalls: Int = 0
            private set

        override suspend fun embed(text: String): FloatArray = delegate.embed(text)

        override suspend fun embedAll(texts: List<String>): List<FloatArray> {
            embedAllCalls++
            return delegate.embedAll(texts)
        }
    }

    /** Always throws, to exercise the batch fall-back path. */
    private class AlwaysFailingEmbedder : Embedder {
        override suspend fun embed(text: String): FloatArray = throw IOException("embedder is down")
    }

    /** A store whose [put] parks until [release] is called, to prove write-behind does not await it. */
    private class GatedStore(private val delegate: CacheStore) : CacheStore by delegate {
        private val gate = CompletableDeferred<Unit>()

        override suspend fun put(entry: CacheEntry) {
            gate.await()
            delegate.put(entry)
        }

        fun release() {
            gate.complete(Unit)
        }
    }
}
