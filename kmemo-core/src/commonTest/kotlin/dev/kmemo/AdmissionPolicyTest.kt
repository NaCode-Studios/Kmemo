package dev.kmemo

import dev.kmemo.fixtures.HashingEmbedder
import dev.kmemo.store.InMemoryStore
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M32: making a prompt earn its place before its answer is stored.
 *
 * Every miss wrote. That is right for a cache being filled deliberately and wrong for one in front of
 * real traffic, where most prompts are asked once and never again: the store fills with entries that
 * will never be hit, `search` scans them, and on the exact-scan stores the cost is linear in the store
 * size and lands on every request rather than on the ones that caused it.
 *
 * These are the properties. The trade itself is a measurement rather than a property, and it lives in
 * `AdmissionWorkloadTest` with the workload it was measured on.
 */
class AdmissionPolicyTest {

    @Test
    fun `off by default, so every miss still writes`() = runTest {
        val cache = SemanticCache(HashingEmbedder(), InMemoryStore())
        cache.getOrPut("asked exactly once") { "an answer" }

        assertEquals(1, cache.size())
        assertEquals(0, cache.stats().writesNotAdmitted)
    }

    @Test
    fun `a prompt asked once is not stored, and the same prompt asked twice is`() = runTest {
        val cache = SemanticCache(
            HashingEmbedder(),
            InMemoryStore(),
            admissionPolicy = AdmissionPolicy(),
        )

        cache.getOrPut("asked exactly once") { "an answer" }
        assertEquals(0, cache.size(), "one sighting is not enough to earn a slot")
        assertEquals(1, cache.stats().writesNotAdmitted)

        cache.getOrPut("asked exactly once") { "an answer" }
        assertEquals(1, cache.size(), "the repeat is what earns it")
    }

    /**
     * The constraint that makes a wrong admission decision cheap. Admission decides whether to write
     * and never whether to serve, so an entry that is in the store is served exactly as it would be
     * without a policy, and the worst a bad decision can do is cost a future miss.
     */
    @Test
    fun `admission never suppresses a lookup`() = runTest {
        val cache = SemanticCache(
            HashingEmbedder(),
            InMemoryStore(),
            admissionPolicy = AdmissionPolicy(minSightings = 5),
        )
        cache.put("a deliberately stored answer", "the answer")

        // Well under the five sightings admission would demand before writing this prompt.
        val hit = cache.lookup("a deliberately stored answer")

        assertTrue(hit is CacheLookup.Hit, "the entry is there, so it is served, whatever the sketch thinks")
        assertEquals("the answer", hit.response)
    }

    /**
     * `put` and `warm` are a caller saying "store this" rather than traffic arriving. Second-guessing
     * them with a frequency estimate would be surprising in a way nothing here can justify, and it
     * would make warming a cache from an FAQ silently do nothing.
     */
    @Test
    fun `deliberate writes are not subject to admission`() = runTest {
        val cache = SemanticCache(
            HashingEmbedder(),
            InMemoryStore(),
            admissionPolicy = AdmissionPolicy(minSightings = 3),
        )

        cache.put("stored on purpose", "an answer")
        cache.warm(listOf(WarmEntry("warmed on purpose", "another answer")))

        assertEquals(2, cache.size())
        assertEquals(0, cache.stats().writesNotAdmitted)
    }

    @Test
    fun `the streaming path is admitted on the same terms`() = runTest {
        val cache = SemanticCache(
            HashingEmbedder(),
            InMemoryStore(),
            admissionPolicy = AdmissionPolicy(),
        )

        cache.getOrPutStreaming("a streamed question") { flowOf("an ", "answer") }.toList()
        assertEquals(0, cache.size())

        cache.getOrPutStreaming("a streamed question") { flowOf("an ", "answer") }.toList()
        assertEquals(1, cache.size())
    }

    @Test
    fun `two different questions never share a sighting`() = runTest {
        val cache = SemanticCache(
            HashingEmbedder(),
            InMemoryStore(),
            admissionPolicy = AdmissionPolicy(),
        )

        cache.getOrPut("what is the capital of austria") { "vienna" }
        cache.getOrPut("what is the capital of australia") { "canberra" }

        assertEquals(
            0,
            cache.size(),
            "the sketch is keyed on exact prompt text; two near-identical questions are two questions",
        )
    }

    @Test
    fun `scopes do not share sightings either`() = runTest {
        val cache = SemanticCache(
            HashingEmbedder(),
            InMemoryStore(),
            admissionPolicy = AdmissionPolicy(),
        )

        cache.getOrPut("the same words", scope = "one") { "a" }
        cache.getOrPut("the same words", scope = "two") { "b" }

        assertEquals(0, cache.size(), "a scope is a partition, and so is its frequency estimate")
    }

    @Test
    fun `minSightings of one admits everything, like no policy at all`() = runTest {
        val cache = SemanticCache(
            HashingEmbedder(),
            InMemoryStore(),
            admissionPolicy = AdmissionPolicy(minSightings = 1),
        )
        cache.getOrPut("asked exactly once") { "an answer" }

        assertEquals(1, cache.size())
    }
}
