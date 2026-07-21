package dev.kmemo.benchmarks

import dev.kmemo.Embedder
import kotlin.math.abs

/**
 * A cheap, deterministic embedder for the benchmarks: it never makes a network call, so a measurement
 * reflects the cache machinery — embedding normalization, the store scan, the guard chain — and not a
 * provider's latency. Bag-of-words hashing into [dim] buckets, which is enough for realistic
 * similarities between the synthetic prompts.
 */
internal class BenchEmbedder(private val dim: Int = 256) : Embedder {

    override suspend fun embed(text: String): FloatArray {
        val vector = FloatArray(dim)
        var start = 0
        for (i in 0..text.length) {
            if (i == text.length || !text[i].isLetterOrDigit()) {
                if (i > start) {
                    val bucket = abs(text.substring(start, i).hashCode()) % dim
                    vector[bucket] += 1.0f
                }
                start = i + 1
            }
        }
        if (vector.all { it == 0.0f }) vector[0] = 1.0f
        return vector
    }
}
