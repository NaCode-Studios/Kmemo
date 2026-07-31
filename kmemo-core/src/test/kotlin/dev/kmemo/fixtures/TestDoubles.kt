package dev.kmemo.fixtures

import dev.kmemo.Embedder
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Bag-of-words hashing embedder: cheap, deterministic, and roughly sane about similarity.
 *
 * Used by the tests that care about cache mechanics — eviction, scopes, expiry — rather than about
 * match quality. Reach for [ConceptEmbedder] when the point of the test is a near-miss.
 */
class HashingEmbedder(private val dimensions: Int = 64) : Embedder {

    override suspend fun embed(text: String): FloatArray {
        val vector = FloatArray(dimensions)
        val tokens = TOKEN.findAll(text.lowercase()).map { it.value }.toList()
        if (tokens.isEmpty()) {
            vector[0] = 1.0f
            return vector
        }
        for (token in tokens) {
            val bucket = Math.floorMod(token.hashCode(), dimensions)
            vector[bucket] += 1.0f
        }
        return vector
    }

    private companion object {
        private val TOKEN = Regex("[\\p{L}\\p{N}]+")
    }
}

/** An [Embedder] pinned to fixed vectors, for tests that need exact similarities. */
class FixedEmbedder(private val vectors: Map<String, FloatArray>) : Embedder {
    override suspend fun embed(text: String): FloatArray =
        vectors[text] ?: error("no vector configured for '$text'")
}

/** Counts calls, to prove [dev.kmemo.SemanticCache.getOrPut] embeds a prompt only once. */
class CountingEmbedder(private val delegate: Embedder) : Embedder {
    var calls: Int = 0
        private set

    override suspend fun embed(text: String): FloatArray {
        calls++
        return delegate.embed(text)
    }
}

/** A [Clock] that only moves when a test tells it to, so TTL tests never sleep. */
class MutableClock(private var current: Instant = Instant.parse("2026-01-01T00:00:00Z")) : Clock {
    override fun now(): Instant = current

    fun advance(duration: Duration) {
        current += duration
    }
}
