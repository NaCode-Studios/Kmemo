package dev.kmemo

import dev.kmemo.store.InMemoryStore
import kotlinx.coroutines.test.runTest
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * What [AdmissionPolicy] costs, on a replayed workload with a stated shape.
 *
 * The measurement is as much the deliverable as the policy. An admission policy that saves memory while
 * costing hit rate is a trade, and publishing it without its price would be a claim that it is free.
 *
 * It lives on the JVM rather than beside the behavioural tests in `commonTest` because it is one
 * measurement rather than a property: the policy and the sketch are pure `commonMain` code held to the
 * same assertions on every target, and replaying 20,000 requests three times on each of them buys no
 * extra information and does not fit inside the JS runner's per-test budget.
 */
class AdmissionWorkloadTest {

    /** Not an assertion: the table the README quotes. */
    @Test
    fun `print the replayed workload`() = runTest {
        println()
        println("Replayed workload: $REQUESTS requests over $DISTINCT distinct prompts,")
        println("Zipf(s=1.0) over rank, deterministic seed $SEED. Exact repeats only, no paraphrases.")
        println()
        println("  policy                 hit rate   store size   writes held back")
        for (policy in listOf(null, AdmissionPolicy(), AdmissionPolicy(minSightings = 3))) {
            val cache = replayed(policy)
            val stats = cache.stats()
            println(
                String.format(
                    Locale.ROOT,
                    "  %-22s %7.1f%% %12d %18d",
                    policy?.let { "admit on ${it.minSightings}" } ?: "none (the default)",
                    100.0 * stats.hitRate,
                    cache.size(),
                    stats.writesNotAdmitted,
                ),
            )
        }
        println()
    }

    @Test
    fun `admission trades hit rate for store size, in that direction`() = runTest {
        val without = replayed(null)
        val with = replayed(AdmissionPolicy())

        assertTrue(
            with.size() < without.size(),
            "admission exists to keep the store smaller: ${with.size()} against ${without.size()}",
        )
        assertTrue(
            with.stats().hitRate < without.stats().hitRate,
            "and it is a trade, not a free win: every prompt now misses once more than it did",
        )
        assertTrue(
            with.stats().hitRate > 0.5,
            "a repeat-heavy workload still gets most of its hits, or the policy would be useless",
        )
    }

    private suspend fun replayed(policy: AdmissionPolicy?): SemanticCache {
        // Room for every distinct prompt, so eviction never confounds the store-size column, and an
        // embedder that gives each distinct prompt its own near-orthogonal vector. The workload is
        // about exact repeats, so anything an embedder decided about near-misses would be measuring the
        // test double instead of the policy.
        val cache = SemanticCache(
            embedder = DistinctEmbedder(),
            store = InMemoryStore(maxEntries = DISTINCT * 4),
            admissionPolicy = policy,
        )
        replay(cache)
        return cache
    }

    private suspend fun replay(cache: SemanticCache) {
        // A tiny LCG rather than kotlin.random, so the workload is identical on every run and the
        // printed numbers are a measurement rather than a sample.
        var state = SEED
        fun next(): Double {
            state = state * 6_364_136_223_846_793_005L + 1_442_695_040_888_963_407L
            return ((state ushr 33).toInt() and Int.MAX_VALUE).toDouble() / Int.MAX_VALUE.toDouble()
        }
        repeat(REQUESTS) {
            // Zipf(s=1) by inverse transform: rank = DISTINCT^u is log-uniform over the ranks, so a
            // few prompts are asked constantly and the long tail is asked once. That is the shape real
            // traffic has and the shape admission is for.
            val rank = Math.pow(DISTINCT.toDouble(), next()).toInt().coerceIn(1, DISTINCT)
            cache.getOrPut("prompt number $rank") { "the answer to $rank" }
        }
    }

    /**
     * One near-orthogonal unit vector per distinct prompt, identical for the same prompt.
     *
     * The bag-of-words test embedder is right for tests about cache mechanics and wrong here: in 64
     * dimensions `prompt number 5` and `prompt number 69` collide often enough that the replay would
     * spend its time measuring hash collisions and guard rejections.
     */
    private class DistinctEmbedder(private val dimensions: Int = 128) : Embedder {
        override suspend fun embed(text: String): FloatArray {
            var state = text.hashCode().toLong()
            val vector = FloatArray(dimensions)
            for (index in 0 until dimensions) {
                state = state * 6_364_136_223_846_793_005L + 1_442_695_040_888_963_407L
                vector[index] = ((state ushr 40).toInt() and 0xFFFF) / 65_535.0f - 0.5f
            }
            return vector
        }
    }

    private companion object {
        private const val REQUESTS = 20_000
        private const val DISTINCT = 4_000
        private const val SEED = 20_260_807L
    }
}
