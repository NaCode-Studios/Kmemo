package dev.kmemo.store.hnsw

import dev.kmemo.CacheEntry
import dev.kmemo.Vectors
import kotlinx.coroutines.test.runTest
import java.time.Instant
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The approximate regime: with far more entries than the query width, the HNSW graph must still surface
 * almost all of the true nearest neighbours. Recall is measured against an exact brute-force ranking.
 */
class HnswRecallTest {

    @Test
    fun `recall against exact search is high`() = runTest {
        val random = Random(20260721)
        val dimensions = 32
        val count = 1000
        val k = 10

        val store = HnswStore(efSearch = 128)
        val corpus = ArrayList<Pair<String, FloatArray>>(count)
        repeat(count) { i ->
            val vector = randomUnitVector(random, dimensions)
            corpus += "id$i" to vector
            store.put(entry("id$i", vector))
        }

        var recallSum = 0.0
        val queries = 50
        repeat(queries) {
            val query = randomUnitVector(random, dimensions)
            val exact = corpus
                .sortedByDescending { Vectors.dot(query, it.second) }
                .take(k)
                .mapTo(HashSet()) { it.first }
            val approximate = store.search("default", query, k).map { it.entry.id }
            recallSum += approximate.count { it in exact }.toDouble() / k
        }

        val meanRecall = recallSum / queries
        assertTrue(meanRecall >= 0.9, "mean recall@$k was $meanRecall over $count vectors, expected >= 0.9")
    }

    private fun randomUnitVector(random: Random, dimensions: Int): FloatArray =
        Vectors.normalize(FloatArray(dimensions) { random.nextGaussian().toFloat() })

    private fun entry(id: String, vector: FloatArray): CacheEntry = CacheEntry(
        id = id,
        scope = "default",
        prompt = id,
        response = id,
        embedding = vector,
        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
    )
}
