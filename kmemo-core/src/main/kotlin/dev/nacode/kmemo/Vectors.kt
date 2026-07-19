package dev.nacode.kmemo

import kotlin.math.sqrt

/**
 * Vector maths shared by [SemanticCache] and by [CacheStore] implementations.
 *
 * kmemo works exclusively with **unit-normalized** vectors: once a vector has length 1, cosine
 * similarity collapses into a plain dot product, which removes two square roots from every
 * comparison in the search loop. [CacheEntry] normalizes on construction, so store implementations
 * can safely use [dot] instead of [cosineSimilarity].
 */
public object Vectors {

    /**
     * Returns a copy of [vector] scaled to unit length.
     *
     * This is the only place a broken embedder can be caught, so it is strict: empty, zero-magnitude
     * and non-finite vectors are all rejected here rather than allowed downstream.
     *
     * The non-finite case is the dangerous one. A single `Infinity` or `NaN` component normalizes to
     * a vector of `NaN`, every similarity computed from it is `NaN`, and `NaN < threshold` is
     * `false` — so a poisoned vector does not score badly, it *skips the threshold entirely* and is
     * served as a hit. Worse, sorting puts `NaN` ahead of a perfect 1.0, so one bad entry outranks
     * every real match in its scope for as long as it lives.
     *
     * @throws IllegalArgumentException if [vector] is empty, has zero magnitude, or contains a
     *   non-finite component.
     */
    public fun normalize(vector: FloatArray): FloatArray {
        require(vector.isNotEmpty()) { "cannot normalize an empty vector" }
        val magnitude = magnitude(vector)
        require(magnitude.isFinite()) {
            "embedding contains a non-finite component (NaN or infinity); this is a broken embedder, " +
                "and letting it through would produce NaN similarities that bypass the threshold"
        }
        require(magnitude > 0.0) { "cannot normalize a zero-magnitude vector" }
        val result = FloatArray(vector.size)
        for (i in vector.indices) {
            result[i] = (vector[i] / magnitude).toFloat()
        }
        return result
    }

    /** Euclidean length of [vector], accumulated in [Double] to limit drift on large dimensions. */
    public fun magnitude(vector: FloatArray): Double {
        var sum = 0.0
        for (value in vector) {
            sum += value.toDouble() * value.toDouble()
        }
        return sqrt(sum)
    }

    /**
     * Dot product of [a] and [b]. Equals the cosine similarity **only when both vectors are
     * unit-normalized**; use [cosineSimilarity] when that is not guaranteed.
     *
     * @throws IllegalArgumentException if the two vectors have different dimensions.
     */
    public fun dot(a: FloatArray, b: FloatArray): Double {
        require(a.size == b.size) { "vector dimension mismatch: ${a.size} vs ${b.size}" }
        var sum = 0.0
        for (i in a.indices) {
            sum += a[i].toDouble() * b[i].toDouble()
        }
        return sum
    }

    /**
     * Cosine similarity of [a] and [b], in `[-1.0, 1.0]`. Returns `0.0` when either vector has zero
     * magnitude, since an undefined angle is better reported as "unrelated" than as an exception in
     * the middle of a search loop.
     */
    public fun cosineSimilarity(a: FloatArray, b: FloatArray): Double {
        val magnitudeA = magnitude(a)
        val magnitudeB = magnitude(b)
        if (magnitudeA == 0.0 || magnitudeB == 0.0) return 0.0
        return dot(a, b) / (magnitudeA * magnitudeB)
    }

    /** Whether [vector] already has unit length, within [tolerance]. */
    public fun isNormalized(vector: FloatArray, tolerance: Double = 1e-4): Boolean =
        kotlin.math.abs(magnitude(vector) - 1.0) <= tolerance
}
