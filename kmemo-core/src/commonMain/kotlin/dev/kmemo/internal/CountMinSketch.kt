package dev.kmemo.internal

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A fixed-size frequency estimator: how often a key has been seen, in constant memory.
 *
 * The whole point is that it does not hold the keys. A map from prompt text to a count would grow with
 * the number of distinct prompts, which for a cache in front of real traffic is the number of requests,
 * and remembering every one-off prompt to decide not to store it would cost more than storing it. This
 * keeps [depth] rows of [width] counters and hashes each key into one column per row, so a key's
 * estimate is the smallest of the [depth] counters it lands on.
 *
 * Hash collisions can only make an estimate **too high**, never too low, since a shared counter has
 * been incremented by both keys. For admission that is the safe direction: a collision admits an entry
 * that had not earned its place, which costs one wasted write, and it can never suppress one that had.
 *
 * Counters halve every [resetAfter] increments so the estimate follows recent traffic. Without it a
 * prompt asked twice a year apart looks exactly like one asked twice this morning, and a sketch that
 * never forgets slowly admits everything.
 */
internal class CountMinSketch(
    private val width: Int,
    private val depth: Int,
    private val resetAfter: Int,
) {
    init {
        require(width > 0) { "width must be positive, was $width" }
        require(depth > 0) { "depth must be positive, was $depth" }
        require(resetAfter > 0) { "resetAfter must be positive, was $resetAfter" }
    }

    private val mutex = Mutex()
    private val rows = Array(depth) { IntArray(width) }
    private var increments = 0

    /** Records one sighting of [key] and returns the new estimate. */
    suspend fun addAndEstimate(key: String): Int = mutex.withLock {
        var estimate = Int.MAX_VALUE
        val (first, second) = hashes(key)
        for (row in 0 until depth) {
            val column = column(first, second, row)
            val counter = rows[row][column]
            if (counter < MAX_COUNT) rows[row][column] = counter + 1
            estimate = minOf(estimate, rows[row][column])
        }
        if (++increments >= resetAfter) age()
        estimate
    }

    /** The current estimate for [key], without recording a sighting. */
    suspend fun estimate(key: String): Int = mutex.withLock {
        var estimate = Int.MAX_VALUE
        val (first, second) = hashes(key)
        for (row in 0 until depth) {
            estimate = minOf(estimate, rows[row][column(first, second, row)])
        }
        estimate
    }

    /** Must be called with [mutex] held. Halves every counter, which is how the sketch forgets. */
    private fun age() {
        increments = 0
        for (row in rows) {
            for (column in row.indices) row[column] = row[column] shr 1
        }
    }

    private fun column(first: Int, second: Int, row: Int): Int {
        // Kirsch-Mitzenmacher: `depth` independent-enough hashes from two, which is all a sketch needs
        // and avoids carrying a hash function per row.
        val combined = first + row * second
        return ((combined % width) + width) % width
    }

    private fun hashes(key: String): Pair<Int, Int> {
        var fnv = FNV_OFFSET
        for (character in key) {
            fnv = (fnv xor character.code) * FNV_PRIME
        }
        // The second hash must never be zero, or every row would read the same column and the sketch
        // would have depth 1 with the memory of depth `depth`.
        return key.hashCode() to (fnv or 1)
    }

    private companion object {
        private const val MAX_COUNT = 255
        private const val FNV_OFFSET = -2128831035
        private const val FNV_PRIME = 16777619
    }
}
