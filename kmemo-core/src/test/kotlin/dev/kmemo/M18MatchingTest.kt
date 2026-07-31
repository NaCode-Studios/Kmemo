package dev.kmemo

import dev.kmemo.fixtures.ConceptEmbedder
import dev.kmemo.store.InMemoryStore
import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The M18 matching layer: reranking, quantized retrieval, write deduplication and adaptive thresholds.
 *
 * Every one of these trades something for something, and each test here pins the side of the trade that
 * must not move. A reranker may reorder but never change what is eligible; quantization may cost a
 * candidate but never a decision; deduplication may merge entries the guards call interchangeable and
 * no others; adaptation may move the threshold only where a verifier is watching what comes through.
 */
class M18MatchingTest {

    // ---- reranking ----------------------------------------------------------------------------

    @Test
    fun `MMR keeps every candidate and leaves the nearest one first`() {
        val candidates = listOf(
            scored("a", vector(1.0f, 0.0f, 0.0f), 0.99),
            scored("b", vector(0.99f, 0.14f, 0.0f), 0.98),
            scored("c", vector(0.0f, 1.0f, 0.0f), 0.70),
        )
        val reranked = MmrReranker().rerank(vector(1.0f, 0.0f, 0.0f), candidates)

        assertEquals(candidates.map { it.entry.id }.toSet(), reranked.map { it.entry.id }.toSet())
        assertEquals("a", reranked.first().entry.id)
    }

    @Test
    fun `MMR promotes the candidate that adds something over the near-duplicate of the first`() {
        val candidates = listOf(
            scored("a", vector(1.0f, 0.0f, 0.0f), 0.99),
            scored("duplicate-of-a", vector(0.999f, 0.045f, 0.0f), 0.98),
            scored("different", vector(0.6f, 0.8f, 0.0f), 0.60),
        )
        val reranked = MmrReranker(lambda = 0.5).rerank(vector(1.0f, 0.0f, 0.0f), candidates)

        assertEquals("different", reranked[1].entry.id, "the duplicate should not be tried second")
    }

    @Test
    fun `at lambda one MMR is the identity ordering`() {
        val candidates = listOf(
            scored("a", vector(1.0f, 0.0f, 0.0f), 0.99),
            scored("b", vector(0.999f, 0.045f, 0.0f), 0.98),
            scored("c", vector(0.6f, 0.8f, 0.0f), 0.60),
        )
        val reranked = MmrReranker(lambda = 1.0).rerank(vector(1.0f, 0.0f, 0.0f), candidates)
        assertEquals(candidates.map { it.entry.id }, reranked.map { it.entry.id })
    }

    @Test
    fun `MMR rejects a lambda outside the unit interval`() {
        assertFailsWith<IllegalArgumentException> { MmrReranker(lambda = 1.5) }
    }

    /**
     * The invariant the whole design rests on: reranking happens *after* the threshold filter, so a
     * reranker cannot make an entry servable that the threshold refused.
     */
    @Test
    fun `a reranker cannot promote a candidate the threshold excluded`() = runTest {
        val hostile = CandidateReranker { _, candidates -> candidates.reversed() }
        val cache = SemanticCache(
            embedder = ConceptEmbedder(),
            store = InMemoryStore(),
            threshold = 0.99,
            reranker = hostile,
        )
        cache.put("What is the capital of France?", "Paris")
        cache.put("How do I bake bread?", "Mix flour and water")

        val result = cache.lookup("How do I bake bread?")
        // The unrelated entry is nowhere near the threshold, so reversing the order must not surface it.
        if (result is CacheLookup.Hit) {
            assertEquals("Mix flour and water", result.response)
        }
    }

    @Test
    fun `a reranker that loses a candidate is refused rather than trusted`() = runTest {
        val lossy = CandidateReranker { _, candidates -> candidates.drop(1) }
        val cache = SemanticCache(
            embedder = ConceptEmbedder(),
            store = InMemoryStore(),
            threshold = -1.0,
            guards = emptyList(),
            reranker = lossy,
        )
        cache.put("one", "1")
        cache.put("two", "2")
        cache.put("three", "3")

        assertFailsWith<IllegalArgumentException> { cache.lookup("one") }
    }

    // ---- quantized retrieval ------------------------------------------------------------------

    @Test
    fun `a quantized store returns exact similarities, never the approximate ones`() = runTest {
        val random = Random(7)
        val vectors = List(40) { FloatArray(64) { random.nextFloat() - 0.5f } }

        for (quantization in listOf(Quantization.INT8, Quantization.BINARY)) {
            val exactStore = InMemoryStore()
            val quantizedStore = InMemoryStore(quantization = quantization)
            for ((index, vector) in vectors.withIndex()) {
                exactStore.put(entry("e$index", vector))
                quantizedStore.put(entry("e$index", vector))
            }

            val query = vectors.first()
            val exactById = exactStore.search(SCOPE, Vectors.normalize(query), 40)
                .associate { it.entry.id to it.similarity }
            for (result in quantizedStore.search(SCOPE, Vectors.normalize(query), 5)) {
                assertEquals(
                    exactById.getValue(result.entry.id),
                    result.similarity,
                    "$quantization returned an approximate similarity for ${result.entry.id}",
                )
            }
        }
    }

    /**
     * Recall against an exact scan, which is the only thing quantization can cost.
     *
     * The oversampling factors in [Quantization] are set from this. A number that drifts down here is
     * the store quietly failing to find entries it holds, with nothing in the logs.
     */
    @Test
    fun `quantized retrieval finds what the exact scan finds`() = runTest {
        // Both a small dimension and a realistic one: binary quantization behaves differently with
        // dimension, and a floor measured only at 64 would say nothing about a 1,536-wide model.
        for (dimensions in listOf(64, 1536)) {
            for ((quantization, floor) in mapOf(Quantization.INT8 to 1.0, Quantization.BINARY to 0.98)) {
                val recall = recallAgainstExactScan(dimensions, quantization)
                assertTrue(
                    recall >= floor,
                    "$quantization recall $recall at $dimensions dimensions is below its $floor floor",
                )
            }
        }
    }

    private suspend fun recallAgainstExactScan(dimensions: Int, quantization: Quantization): Double {
        val random = Random(11)
        val vectors = List(300) { FloatArray(dimensions) { random.nextFloat() - 0.5f } }
        val queries = List(30) { FloatArray(dimensions) { random.nextFloat() - 0.5f } }

        val exactStore = InMemoryStore()
        val quantizedStore = InMemoryStore(quantization = quantization)
        for ((index, vector) in vectors.withIndex()) {
            exactStore.put(entry("e$index", vector))
            quantizedStore.put(entry("e$index", vector))
        }

        var found = 0
        var wanted = 0
        for (query in queries) {
            val normalized = Vectors.normalize(query)
            val exact = exactStore.search(SCOPE, normalized, 5).map { it.entry.id }.toSet()
            val approximate = quantizedStore.search(SCOPE, normalized, 5).map { it.entry.id }.toSet()
            found += exact.count { it in approximate }
            wanted += exact.size
        }
        return found.toDouble() / wanted
    }

    @Test
    fun `a removed entry leaves no compressed code behind to be scanned`() = runTest {
        val store = InMemoryStore(quantization = Quantization.INT8)
        val random = Random(3)
        repeat(30) { store.put(entry("e$it", FloatArray(32) { random.nextFloat() - 0.5f })) }

        store.remove("e0")
        store.clear(SCOPE)

        assertEquals(0, store.size())
        assertTrue(store.search(SCOPE, Vectors.normalize(FloatArray(32) { 1.0f }), 5).isEmpty())
    }

    // ---- write deduplication -------------------------------------------------------------------

    @Test
    fun `a second phrasing of the same question replaces the first`() = runTest {
        val store = InMemoryStore()
        val cache = SemanticCache(
            embedder = ConceptEmbedder(),
            store = store,
            threshold = -1.0,
            guards = emptyList(),
            deduplicateWrites = 0.9,
        )
        cache.put("How do I exit vim?", "Press escape then colon q")
        cache.put("How do I exit vim?", "Escape, then :q")

        assertEquals(1, store.size(), "the same question stored twice is one answer stored twice")
    }

    /**
     * The write path is exactly as capable of a false hit as the read path, and the same guards stop
     * it. Merging two entries a guard would have refused to serve for each other would delete one
     * answer and leave the other to be served for both questions.
     */
    @Test
    fun `two prompts the guards would refuse are never merged`() = runTest {
        val store = InMemoryStore()
        val cache = SemanticCache(
            embedder = ConceptEmbedder(),
            store = store,
            threshold = -1.0,
            deduplicateWrites = 0.5,
        )
        cache.put("Convert 100 USD to EUR", "About 92 euros")
        cache.put("Convert 250 USD to EUR", "About 230 euros")

        assertEquals(2, store.size(), "a numeric near miss must survive as its own entry")
    }

    @Test
    fun `replacing a duplicate is reported as an eviction`() = runTest {
        val seen = mutableListOf<CacheEvent>()
        val cache = SemanticCache(
            embedder = ConceptEmbedder(),
            store = InMemoryStore(),
            threshold = -1.0,
            guards = emptyList(),
            deduplicateWrites = 0.9,
            listeners = listOf(CacheListener { seen += it }),
        )
        cache.put("How do I exit vim?", "Press escape then colon q")
        cache.put("How do I exit vim?", "Escape, then :q")

        val evictions = seen.filterIsInstance<CacheEvent.Eviction>()
        assertEquals(1, evictions.size)
        assertEquals(EvictionCause.NEAR_DUPLICATE, evictions.single().cause)
    }

    // ---- adaptive thresholds --------------------------------------------------------------------

    @Test
    fun `a verifier refusing most of what it sees raises the threshold`() {
        val adaptive = AdaptiveThresholds(floor = 0.80, ceiling = 0.99, minimumSamples = 10)
        repeat(10) { adaptive.onEvent(miss(MissReason.REJECTED_BY_VERIFIER)) }

        val raised = assertNotNull(adaptive.recommendationFor(SCOPE))
        assertTrue(raised > (0.80 + 0.99) / 2, "expected a raise, got $raised")
    }

    @Test
    fun `a verifier refusing almost nothing lowers it`() {
        val adaptive = AdaptiveThresholds(floor = 0.80, ceiling = 0.99, minimumSamples = 10)
        repeat(10) { adaptive.onEvent(hit()) }

        val lowered = assertNotNull(adaptive.recommendationFor(SCOPE))
        assertTrue(lowered < (0.80 + 0.99) / 2, "expected a drop, got $lowered")
    }

    @Test
    fun `it says nothing until it has seen enough traffic`() {
        val adaptive = AdaptiveThresholds(floor = 0.80, ceiling = 0.99, minimumSamples = 100)
        repeat(99) { adaptive.onEvent(hit()) }
        assertNull(adaptive.recommendationFor(SCOPE))
    }

    @Test
    fun `it never leaves the floor and ceiling it was given`() {
        val adaptive = AdaptiveThresholds(floor = 0.90, ceiling = 0.92, minimumSamples = 5, step = 0.05)
        repeat(200) { adaptive.onEvent(miss(MissReason.REJECTED_BY_VERIFIER)) }
        assertTrue(adaptive.recommendationFor(SCOPE)!! <= 0.92)

        val falling = AdaptiveThresholds(floor = 0.90, ceiling = 0.92, minimumSamples = 5, step = 0.05)
        repeat(200) { falling.onEvent(hit()) }
        assertTrue(falling.recommendationFor(SCOPE)!! >= 0.90)
    }

    @Test
    fun `scopes adapt independently of each other`() {
        val adaptive = AdaptiveThresholds(floor = 0.80, ceiling = 0.99, minimumSamples = 10)
        repeat(10) { adaptive.onEvent(miss(MissReason.REJECTED_BY_VERIFIER, scope = "strict")) }
        repeat(10) { adaptive.onEvent(hit(scope = "casual")) }

        assertTrue(adaptive.recommendationFor("strict")!! > adaptive.recommendationFor("casual")!!)
    }

    /**
     * The guard rail that is not negotiable. Adaptation lowers the threshold as well as raising it, and
     * the only thing making that safe is a verifier checking what comes through.
     */
    @Test
    fun `adapting without a verifier is refused at construction`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            SemanticCache(
                embedder = ConceptEmbedder(),
                adaptiveThresholds = AdaptiveThresholds(floor = 0.8, ceiling = 0.99),
            )
        }
        assertTrue("verifier" in failure.message.orEmpty(), failure.message.orEmpty())
    }

    @Test
    fun `a recommendation is what the cache actually uses`() = runTest {
        val adaptive = AdaptiveThresholds(floor = 0.99, ceiling = 0.99, minimumSamples = 1)
        adaptive.onEvent(hit())
        assertEquals(0.99, adaptive.recommendationFor(SCOPE)!!, 1e-9)

        val cache = SemanticCache(
            embedder = ConceptEmbedder(),
            store = InMemoryStore(),
            threshold = -1.0,
            guards = emptyList(),
            verifier = { _, _, _ -> true },
            adaptiveThresholds = adaptive,
            listeners = listOf(adaptive),
        )
        cache.put("What is the capital of France?", "Paris")
        // The configured threshold would serve anything; the recommendation must be what decides.
        val result = cache.lookup("How do I bake sourdough bread at home?")
        assertTrue(result is CacheLookup.Miss, "the recommended 0.99 threshold should have refused this")
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private fun vector(vararg values: Float) = Vectors.normalize(floatArrayOf(*values))

    private fun scored(id: String, embedding: FloatArray, similarity: Double) =
        ScoredEntry(entry(id, embedding), similarity)

    private fun entry(id: String, embedding: FloatArray) = CacheEntry(
        id = id,
        scope = SCOPE,
        prompt = id,
        response = "response for $id",
        embedding = embedding,
        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
    )

    private fun hit(scope: String = SCOPE) = CacheEvent.Hit(
        scope = scope,
        prompt = "p",
        matchedPrompt = "p",
        similarity = 0.95,
        entryId = "id",
        timings = EventTimings(0, 0, 0),
    )

    private fun miss(reason: MissReason, scope: String = SCOPE) = CacheEvent.Miss(
        scope = scope,
        prompt = "p",
        reason = reason,
        bestSimilarity = 0.95,
        detail = null,
        guardName = null,
        timings = EventTimings(0, 0, 0),
    )

    private companion object {
        private const val SCOPE = "default"

        private fun assertEquals(expected: Double, actual: Double, tolerance: Double) {
            assertTrue(abs(expected - actual) <= tolerance, "expected $expected, was $actual")
        }
    }
}
