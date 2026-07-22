package dev.kmemo.examples

import dev.kmemo.CacheLookup
import dev.kmemo.CacheStore
import dev.kmemo.Embedder
import dev.kmemo.SemanticCache
import dev.kmemo.WarmEntry
import dev.kmemo.store.InMemoryStore
import dev.kmemo.store.redis.RedisStore
import io.lettuce.core.RedisClient
import kotlinx.coroutines.runBlocking
import kotlin.math.abs

/**
 * A runnable demo of the one thing kmemo is for: catching a near-miss a plain similarity cache would
 * serve wrong. No API key required — the embedder is local and deterministic — so `./gradlew :examples:run`
 * just works. Set `KMEMO_REDIS_URL` (there is a `docker-compose.yml` for Redis) to run against a
 * persistent, cross-process store instead of the in-memory default.
 */

/** The FAQ the cache is warmed with. */
internal val faq: List<WarmEntry> = listOf(
    WarmEntry("How do I reverse a list in Python?", "Use reversed() or list.reverse()."),
    WarmEntry("Convert 100 USD to EUR", "100 USD is about 92 EUR."),
    WarmEntry(
        "How do I enable two-factor authentication on GitHub?",
        "Settings → Password and authentication → Enable 2FA.",
    ),
)

/**
 * A local bag-of-words embedder. Deterministic and free, and — because it scores by shared words —
 * it puts paraphrases *and* near-misses both above the threshold, which is exactly where the guards,
 * not the threshold, have to do the work. That is the point of the demo.
 */
internal class DemoEmbedder(private val dim: Int = 256) : Embedder {
    override suspend fun embed(text: String): FloatArray {
        val vector = FloatArray(dim)
        val tokens = text.lowercase().split(Regex("\\W+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) vector[0] = 1.0f
        for (token in tokens) vector[abs(token.hashCode()) % dim] += 1.0f
        return vector
    }
}

/** Builds the demo cache. The threshold is deliberately loose so near-misses reach the guards. */
internal fun demoCache(store: CacheStore): SemanticCache =
    SemanticCache(embedder = DemoEmbedder(), store = store, threshold = 0.7)

/** Chooses the store from the environment: Redis when `KMEMO_REDIS_URL` is set, else in-memory. */
internal fun demoStore(): CacheStore {
    val redisUrl = System.getenv("KMEMO_REDIS_URL")
    return if (redisUrl.isNullOrBlank()) InMemoryStore() else RedisStore(RedisClient.create(redisUrl))
}

private fun describe(prompt: String, lookup: CacheLookup): String = when (lookup) {
    is CacheLookup.Hit ->
        "HIT   \"$prompt\"\n        served: ${lookup.response}  (similarity ${"%.2f".format(lookup.similarity)})"
    is CacheLookup.Miss ->
        "MISS  \"$prompt\"\n        reason: ${lookup.reason}${lookup.detail?.let { " — $it" } ?: ""}"
}

fun main(): Unit = runBlocking {
    val store = demoStore()
    val cache = demoCache(store)
    try {
        cache.warm(faq)
        println("Warmed the cache with ${faq.size} FAQ answers.\n")

        // 1. A genuine paraphrase of a cached question — served from cache.
        println(describe("How can I reverse a Python list?", cache.lookup("How can I reverse a Python list?")))
        // 2. A near-miss that scores just as high — the numeric guard refuses it, so it is NOT served
        //    the 100-USD answer. This is the false hit a plain similarity cache would ship.
        println(describe("Convert 250 USD to EUR", cache.lookup("Convert 250 USD to EUR")))
        // 3. An unrelated question — below the threshold, a plain miss.
        println(describe("What is the best sourdough recipe?", cache.lookup("What is the best sourdough recipe?")))

        println("\nThe near-miss on line 2 is the whole point: 100 and 250 embed at ~0.99 with any model,")
        println("so no threshold separates them — only the numeric guard does.")
    } finally {
        (store as? AutoCloseable)?.close()
    }
}
