package dev.kmemo.fixtures

import dev.kmemo.Embedder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * A deterministic embedder built to fail the way real ones do.
 *
 * Testing a semantic cache with a naive bag-of-words embedder proves nothing: such an embedder gives
 * "capital of France" and "capital of Germany" a low similarity, the threshold rejects the pair, and
 * the test passes without any guard ever running. Real embedding models do the opposite — they place
 * those two prompts almost on top of each other, because `France` and `Germany` are both European
 * countries and the sentences are otherwise identical. That closeness is the entire problem.
 *
 * So this embedder reproduces it. Tokens belonging to the same concept — two countries, two
 * currencies, two databases — are assigned nearly the same vector, with only [identityWeight] of
 * their direction left to tell them apart. A one-token swap between members of a concept therefore
 * scores well above any usable threshold, and the only thing standing between the test and a wrong
 * answer is the guard layer. Which is the point.
 */
class ConceptEmbedder(
    private val dimensions: Int = 96,
    private val concepts: Map<String, String> = DEFAULT_CONCEPTS,
    private val identityWeight: Float = 0.10f,
) : Embedder {

    private val cache = HashMap<String, FloatArray>()

    override suspend fun embed(text: String): FloatArray {
        val tokens = TOKEN.findAll(text).map { it.value.lowercase() }.toList()
        val accumulator = FloatArray(dimensions)
        if (tokens.isEmpty()) {
            accumulator[0] = 1.0f
            return accumulator
        }

        for (token in tokens) {
            val concept = axis(concepts[token] ?: "token:$token")
            val identity = axis("identity:$token")
            for (i in 0 until dimensions) {
                accumulator[i] += concept[i] * (1.0f - identityWeight) + identity[i] * identityWeight
            }
        }
        return accumulator
    }

    /** A stable pseudo-random unit vector per key, so the same text always embeds the same way. */
    private fun axis(key: String): FloatArray = cache.getOrPut(key) {
        val random = Random(key.hashCode().toLong())
        val vector = FloatArray(dimensions) { random.nextGaussian() }
        var magnitude = 0.0
        for (value in vector) magnitude += value.toDouble() * value.toDouble()
        val length = kotlin.math.sqrt(magnitude).toFloat()
        FloatArray(dimensions) { vector[it] / length }
    }

    companion object {
        private val TOKEN = Regex("[\\p{L}\\p{N}]+")

        /**
         * Tokens that a real embedding model would place close together. Anything absent gets its
         * own axis, which is how unrelated prompts stay unrelated.
         */
        val DEFAULT_CONCEPTS: Map<String, String> = buildMap {
            putAll("country", "france", "germany", "australia", "austria", "spain", "italy", "japan")
            putAll("city", "paris", "london", "berlin", "madrid", "rome", "tokyo")
            putAll("currency", "usd", "eur", "gbp", "jpy", "dollars", "euros")
            putAll("unit", "km", "miles", "meters", "kg", "pounds", "celsius", "fahrenheit")
            putAll("database", "postgres", "postgresql", "mysql", "mongodb", "sqlite", "redis")
            putAll("language", "python", "ruby", "java", "kotlin", "javascript", "go")
            putAll("framework", "react", "vue", "angular", "svelte", "django", "rails")
            putAll("cloud", "aws", "azure", "gcp")
            putAll("toggle", "enable", "disable", "on", "off", "turn")
            putAll("direction", "increase", "decrease", "raise", "lower")
            putAll("time", "today", "tomorrow", "yesterday", "now", "current")
            putAll("digit", "1", "2", "3", "5", "10", "20", "50", "100", "250", "500", "1000")
            putAll("year", "2010", "2023", "2024", "2025", "2026")
        }

        private fun MutableMap<String, String>.putAll(concept: String, vararg tokens: String) {
            for (token in tokens) put(token, concept)
        }
    }
}

/**
 * A standard-normal sample, by the Box-Muller transform.
 *
 * `java.util.Random.nextGaussian` is a JVM method and this fixture has to run on every target. The
 * transform is three lines and gives the same distribution, which is all the embedder needs: vectors
 * whose directions are spread evenly rather than clustered.
 */
private fun Random.nextGaussian(): Float {
    val u1 = nextDouble().coerceAtLeast(1e-12)
    val u2 = nextDouble()
    return (sqrt(-2.0 * ln(u1)) * cos(2.0 * PI * u2)).toFloat()
}
