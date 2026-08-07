package dev.kmemo

import dev.kmemo.fixtures.HashingEmbedder
import dev.kmemo.store.InMemoryStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M31: the calculation anybody deciding whether to adopt this library actually cares about.
 *
 * `CacheStats` counted lookups, hits, misses and rejections, and none of that is money. A hit on a two
 * hundred token answer from a cheap model and a hit on a four thousand token answer from an expensive
 * one were one increment each. The person who has to justify the dependency was being asked to
 * multiply a hit count by an average they do not have.
 *
 * Two things here are constraints rather than features. A saving is only ever counted for a call that
 * was going to be made, so the denominator is lookups and the numerator is hits, never writes. And no
 * figure is reported without the inputs it rests on, because a saving with no price per token attached
 * is a marketing claim rather than a measurement.
 */
class SavingsTest {

    private val prices = TokenPrices(
        currency = "USD",
        perInputToken = 2.50 / 1_000_000,
        perOutputToken = 10.00 / 1_000_000,
    )

    private fun cache(store: CacheStore = InMemoryStore()) = SemanticCache(
        embedder = HashingEmbedder(),
        store = store,
        prices = mapOf("gpt-4o" to prices),
    )

    private fun usage(input: Long, output: Long) =
        mapOf("inputTokens" to input.toString(), "outputTokens" to output.toString())

    @Test
    fun `a hit is worth what the call it avoided would have cost`() = runTest {
        val cache = cache()
        cache.getOrPut("What is a semantic cache?", scope = "gpt-4o", metadata = usage(120, 800)) {
            "A cache keyed by meaning."
        }
        cache.getOrPut("What is a semantic cache?", scope = "gpt-4o") { error("must not be called") }

        val savings = cache.stats().savings.getValue("gpt-4o")
        assertEquals(1, savings.hits, "one hit, and the write that filled the cache is not a saving")
        assertEquals(120, savings.inputTokens)
        assertEquals(800, savings.outputTokens)
        assertEquals(120 * prices.perInputToken + 800 * prices.perOutputToken, savings.amount)
        assertEquals("USD", savings.currency)
    }

    /**
     * The difference between this and a hit count multiplied by an average. Two hits on answers an
     * order of magnitude apart in length are not the same event, and the whole point of reading the
     * served entry's own metadata is that the figure knows it.
     */
    @Test
    fun `a hit on a long answer is worth more than a hit on a short one`() = runTest {
        val cache = cache()
        cache.getOrPut("short", scope = "gpt-4o", metadata = usage(10, 20)) { "brief" }
        cache.getOrPut("long", scope = "gpt-4o", metadata = usage(10, 4_000)) { "at length" }

        cache.getOrPut("short", scope = "gpt-4o") { error("hit") }
        val afterShort = cache.stats().savings.getValue("gpt-4o").amount
        cache.getOrPut("long", scope = "gpt-4o") { error("hit") }
        val afterLong = cache.stats().savings.getValue("gpt-4o").amount

        assertTrue(
            afterLong - afterShort > afterShort,
            "the second hit was worth far more than the first, and the total says so",
        )
    }

    @Test
    fun `a scope with no declared price reports nothing rather than guessing`() = runTest {
        val cache = cache()
        cache.getOrPut("anything", scope = "some-other-model", metadata = usage(100, 100)) { "answer" }
        cache.getOrPut("anything", scope = "some-other-model") { error("hit") }

        assertNull(
            cache.stats().savings["some-other-model"],
            "the library ships no price table, so an undeclared scope has no saving to report",
        )
        assertEquals(1, cache.stats().hits, "the hit itself is still counted")
    }

    /**
     * The one way this figure can be quietly wrong: prices declared, and the metadata keys the entries
     * carry are not the ones being read. It is reported next to the number rather than left to be
     * discovered from a total that looks too small.
     */
    @Test
    fun `hits whose entries carry no token counts are reported, not hidden`() = runTest {
        val cache = cache()
        cache.getOrPut("q", scope = "gpt-4o", metadata = mapOf("prompt_tokens" to "500")) { "a" }
        cache.getOrPut("q", scope = "gpt-4o") { error("hit") }

        val savings = cache.stats().savings.getValue("gpt-4o")
        assertEquals(1, savings.hits)
        assertEquals(1, savings.hitsMissingTokenCounts)
        assertEquals(0.0, savings.amount, "no counts, no token cost, and no invented average either")
    }

    @Test
    fun `a per-call charge is counted even for an entry with no token counts`() = runTest {
        val cache = SemanticCache(
            embedder = HashingEmbedder(),
            prices = mapOf("flat" to TokenPrices(currency = "EUR", perCall = 0.004)),
        )
        cache.getOrPut("q", scope = "flat") { "a" }
        cache.getOrPut("q", scope = "flat") { error("hit") }

        val savings = cache.stats().savings.getValue("flat")
        assertEquals(0.004, savings.amount, "the flat charge did not depend on the token counts")
        assertEquals(1, savings.hitsMissingTokenCounts)
    }

    @Test
    fun `the figure carries the prices it was computed from`() = runTest {
        val cache = cache()
        cache.getOrPut("q", scope = "gpt-4o", metadata = usage(1, 1)) { "a" }
        cache.getOrPut("q", scope = "gpt-4o") { error("hit") }

        assertEquals(prices, cache.stats().savings.getValue("gpt-4o").prices)
    }

    @Test
    fun `an exact-layer hit saves the same as any other hit`() = runTest {
        val cache = SemanticCache(
            embedder = HashingEmbedder(),
            exactCacheSize = 16,
            prices = mapOf("gpt-4o" to prices),
        )
        cache.getOrPut("q", scope = "gpt-4o", metadata = usage(50, 100)) { "a" }
        cache.getOrPut("q", scope = "gpt-4o") { error("hit") }

        val savings = cache.stats().savings.getValue("gpt-4o")
        assertEquals(1, cache.stats().exactHits, "this went through the exact layer")
        assertEquals(150, savings.inputTokens + savings.outputTokens, "and still counted its saving")
    }

    @Test
    fun `the hit event carries what that one hit was worth`() = runTest {
        val seen = mutableListOf<CacheEvent.Hit>()
        val cache = SemanticCache(
            embedder = HashingEmbedder(),
            prices = mapOf("gpt-4o" to prices),
            listeners = listOf(CacheListener { if (it is CacheEvent.Hit) seen += it }),
        )
        cache.getOrPut("q", scope = "gpt-4o", metadata = usage(100, 1_000)) { "a" }
        cache.getOrPut("q", scope = "gpt-4o") { error("hit") }

        val hit = seen.single()
        assertEquals(100 * prices.perInputToken + 1_000 * prices.perOutputToken, hit.saved)
        assertEquals("USD", hit.currency)
    }

    @Test
    fun `two scopes in different currencies are never added together`() = runTest {
        val cache = SemanticCache(
            embedder = HashingEmbedder(),
            prices = mapOf(
                "usd" to TokenPrices(currency = "USD", perCall = 1.0),
                "eur" to TokenPrices(currency = "EUR", perCall = 1.0),
            ),
        )
        for (scope in listOf("usd", "eur")) {
            cache.getOrPut("q", scope = scope) { "a" }
            cache.getOrPut("q", scope = scope) { error("hit") }
        }

        val savings = cache.stats().savings
        assertEquals(setOf("usd", "eur"), savings.keys)
        assertEquals("USD", savings.getValue("usd").currency)
        assertEquals("EUR", savings.getValue("eur").currency)
    }

    @Test
    fun `a price with a negative rate is refused at construction`() {
        val failure = runCatching { TokenPrices(currency = "USD", perOutputToken = -1.0) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }
}
