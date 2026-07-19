package dev.kmemo

import dev.kmemo.fixtures.HashingEmbedder
import dev.kmemo.fixtures.MutableClock
import dev.kmemo.guard.DirectionGuard
import dev.kmemo.guard.EntityGuard
import dev.kmemo.guard.GuardVerdict
import dev.kmemo.guard.LexicalDivergenceGuard
import dev.kmemo.guard.MatchGuard
import dev.kmemo.guard.MatchGuards
import dev.kmemo.guard.NumericGuard
import dev.kmemo.guard.UnitGuard
import dev.kmemo.store.InMemoryStore
import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

/**
 * One test per defect found in review, each pinned to the input that exposed it.
 *
 * These are kept together rather than filed under the class they belong to because what they have
 * in common is more useful than what separates them: every one of them passed review by inspection
 * and failed when someone actually ran it.
 */
class RegressionTest {

    private fun rejects(guard: MatchGuard, a: String, b: String) =
        guard.evaluate(a, b) is GuardVerdict.Reject || guard.evaluate(b, a) is GuardVerdict.Reject

    private fun chainRejects(a: String, b: String) =
        MatchGuards.standard().any { rejects(it, a, b) }

    // --- false hits --------------------------------------------------------------------------

    @Test
    fun `an abbreviation period does not blind the entity guard`() {
        // "vs." made every capital after it look sentence-initial, so Java and Ruby vanished and
        // the chain served one answer for the other.
        assertTrue(chainRejects("Compare Python vs. Java", "Compare Python vs. Ruby"))
        assertTrue(chainRejects("Compare Python vs Java", "Compare Python vs Ruby"))
    }

    @Test
    fun `a colon in a templated prompt does not blind the entity guard`() {
        assertTrue(
            chainRejects(
                "Country: Austria. Give me the capital.",
                "Country: Australia. Give me the capital.",
            ),
        )
    }

    @Test
    fun `the opening word is still not treated as an entity`() {
        assertEquals(setOf("docker"), dev.kmemo.guard.Text.entityTokens("What is Docker?"))
        assertTrue(EntityGuard().evaluate("how do i use docker", "How do I use Docker?") == GuardVerdict.Accept)
    }

    @Test
    fun `a decimal comma is not a thousands separator`() {
        // "3,5" was normalized to "35", so 3.5 km matched the answer cached for 35 km.
        assertTrue(rejects(NumericGuard(), "Convert 3,5 km to miles", "Convert 35 km to miles"))
        assertTrue(rejects(NumericGuard(), "What is 1,5 percent of 200?", "What is 15 percent of 200?"))
    }

    @Test
    fun `a thousands separator is still ignored`() {
        assertTrue(!rejects(NumericGuard(), "What is 1,000 divided by 4?", "What is 1000 divided by 4?"))
    }

    @Test
    fun `a non-finite embedding is refused instead of bypassing the threshold`() {
        // NaN similarity is never below the threshold, so a poisoned vector was served as a hit
        // and outranked every real match in its scope.
        assertFailsWith<IllegalArgumentException> {
            Vectors.normalize(floatArrayOf(Float.POSITIVE_INFINITY, 0.0f))
        }
        assertFailsWith<IllegalArgumentException> { Vectors.normalize(floatArrayOf(Float.NaN, 1.0f)) }
    }

    @Test
    fun `a cache backed by a broken embedder fails loudly rather than serving nonsense`() = runTest {
        val cache = SemanticCache({ floatArrayOf(Float.NaN, 1.0f) })

        assertFailsWith<IllegalArgumentException> { cache.put("a prompt", "an answer") }
    }

    // --- false rejections --------------------------------------------------------------------

    @Test
    fun `a fronted phrase is not an argument swap`() {
        // Every cue word used to reject any reordering, including moving a trailing phrase to the
        // front — which never changes the question.
        val guard = DirectionGuard()
        assertTrue(!rejects(guard, "How do I move a file in Linux?", "In Linux, how do I move a file?"))
        assertTrue(!rejects(guard, "How do I map a list in Python?", "In Python, how do I map a list?"))
        assertTrue(!rejects(guard, "How do I switch branches in git?", "In git, how do I switch branches?"))
        assertTrue(
            !rejects(guard, "How do I transfer a file with scp?", "With scp, how do I transfer a file?"),
        )
    }

    @Test
    fun `a genuine argument swap is still rejected`() {
        val guard = DirectionGuard()
        assertTrue(rejects(guard, "Is Postgres better than MySQL?", "Is MySQL better than Postgres?"))
        assertTrue(rejects(guard, "Convert 500 EUR to USD", "Convert 500 USD to EUR"))
        assertTrue(
            rejects(
                guard,
                "How do I migrate a project from Redux to Zustand?",
                "How do I migrate a project from Zustand to Redux?",
            ),
        )
    }

    @Test
    fun `an abbreviated unit is the same unit as the spelled-out one`() {
        val guard = UnitGuard()
        assertTrue(!rejects(guard, "Convert 50 km to miles", "Convert 50 kilometers to miles"))
        assertTrue(!rejects(guard, "Convert 10 kg to pounds", "Convert 10 kilograms to lbs"))
        assertTrue(!rejects(guard, "How much is 100 USD in euros?", "How much is 100 dollars in EUR?"))
    }

    @Test
    fun `a swapped unit is still rejected once aliases are resolved`() {
        val guard = UnitGuard()
        assertTrue(rejects(guard, "Convert 50 km to miles", "Convert 50 kilometers to meters"))
        assertTrue(rejects(guard, "Convert 100 USD to EUR", "Convert 100 dollars to GBP"))
    }

    @Test
    fun `an all-stopword pair does not divide by zero`() {
        assertEquals(
            GuardVerdict.Accept,
            LexicalDivergenceGuard(minTokens = 0).evaluate("what is it", "how is it"),
        )
    }

    // --- bookkeeping -------------------------------------------------------------------------

    @Test
    fun `a miss reports the reason and the score of the same candidate`() = runTest {
        // The reason used to come from whichever candidate a guard refused, while the prompt and
        // similarity always came from the closest one — two halves describing different entries.
        val cache = SemanticCache(
            embedder = dev.kmemo.fixtures.FixedEmbedder(
                mapOf(
                    "how do i reverse a list in python" to unit(0.99),
                    "How do I reverse a list in Ruby?" to unit(0.97),
                    "How do I reverse a list in Python?" to unit(1.0),
                ),
            ),
            threshold = 0.95,
            verifier = Verifier { _, _, _ -> false },
        )
        cache.put("how do i reverse a list in python", "python answer")
        cache.put("How do I reverse a list in Ruby?", "ruby answer")

        val miss = assertIs<CacheLookup.Miss>(cache.lookup("How do I reverse a list in Python?"))

        assertEquals(MissReason.REJECTED_BY_VERIFIER, miss.reason)
        assertEquals("how do i reverse a list in python", miss.bestPrompt)
        assertEquals(1, cache.stats().verifierRejections)
        assertEquals(0, cache.stats().guardRejections)
    }

    @Test
    fun `an emptied store accepts a new embedding model`() = runTest {
        val store = InMemoryStore()
        store.put(entry("a", scope = "s1", vector = floatArrayOf(1.0f, 0.0f)))

        store.clear("s1")
        assertEquals(0, store.size())

        // Used to throw, with a message telling you to clear a store that was already empty.
        store.put(entry("b", vector = floatArrayOf(1.0f, 0.0f, 0.0f)))
        assertEquals(1, store.size())
    }

    @Test
    fun `removing the last entry also releases the model`() = runTest {
        val store = InMemoryStore()
        store.put(entry("a", vector = floatArrayOf(1.0f, 0.0f)))
        store.remove("a")

        store.put(entry("b", vector = floatArrayOf(1.0f, 0.0f, 0.0f)))
        assertEquals(1, store.size())
    }

    @Test
    fun `an expired entry is expired, not evicted`() = runTest {
        // Eviction compared raw map size, so it discarded a live entry and booked a dead one as an
        // eviction — both counters wrong for the same operation.
        val clock = MutableClock()
        val store = InMemoryStore(maxEntries = 2, ttl = 1.hours, clock = clock)
        store.put(entry("a", createdAt = clock.instant()))
        store.put(entry("b", createdAt = clock.instant()))

        clock.advance(2.hours)
        store.put(entry("c", createdAt = clock.instant()))

        val stats = store.stats()
        assertEquals(1, stats.size)
        assertEquals(0, stats.evictions)
        assertEquals(2, stats.expirations)
    }

    @Test
    fun `stats never report more hits than lookups`() = runTest {
        val cache = SemanticCache(HashingEmbedder())
        cache.put("a prompt about lists", "answer")
        repeat(3) { cache.lookup("a prompt about lists") }

        val stats = cache.stats()
        assertTrue(stats.misses >= 0, "misses was ${stats.misses}")
        assertTrue(stats.hitRate in 0.0..1.0, "hitRate was ${stats.hitRate}")
        assertEquals(stats.lookups - stats.hits, stats.misses)
    }

    // --- second review: defects introduced by the first round of fixes -----------------------

    @Test
    fun `a short ton is not a metric tonne`() {
        // Canonicalising by "sounds like the same unit" collapsed 907 kg onto 1000 kg.
        assertTrue(chainRejects("Convert 5 tons to kg", "Convert 5 tonnes to kg"))
    }

    @Test
    fun `standard time is not daylight time`() {
        // One hour apart. Lowercase, so EntityGuard does not accidentally cover for it.
        assertTrue(chainRejects("what is 9 am est in local time", "what is 9 am edt in local time"))
        assertTrue(chainRejects("what is 9 am pst in local time", "what is 9 am pdt in local time"))
        assertTrue(chainRejects("what is 9 am cet in local time", "what is 9 am cest in local time"))
    }

    @Test
    fun `utc and gmt are still the same offset`() {
        assertTrue(!chainRejects("what is 9 am utc in local time", "what is 9 am gmt in local time"))
    }

    @Test
    fun `a decimal comma survives as one number, so swapped operands stay visible`() {
        // Splitting "3,5" into [3, 5] made it indistinguishable from "5,3" under multiset compare.
        assertTrue(rejects(NumericGuard(), "how much is 3,5 kg in pounds", "how much is 5,3 kg in pounds"))
        assertTrue(rejects(NumericGuard(), "the recipe needs 1,5 liters of milk", "the recipe needs 5,1 liters of milk"))
        assertTrue(rejects(NumericGuard(), "Convert 3,5 km to miles", "Convert 35 km to miles"))
    }

    @Test
    fun `a two-token argument swap is not excused as a rotation`() {
        // At two tokens every permutation is a rotation, so the rotation test waved this through.
        assertTrue(chainRejects("Are there more cats than dogs?", "Are there more dogs than cats?"))
    }

    @Test
    fun `a second sentence opening with a different word is not an entity swap`() {
        // Exempting only the first token of the prompt made "Show" and "Give" look like entities.
        assertTrue(
            !chainRejects(
                "How do I center a div in CSS? Show me an example.",
                "How do I center a div in CSS? Give me an example.",
            ),
        )
    }

    @Test
    fun `an abbreviation period still does not blind the entity guard`() {
        assertTrue(chainRejects("Compare Python vs. Java", "Compare Python vs. Ruby"))
        assertTrue(chainRejects("Country: Austria. Give me the capital.", "Country: Australia. Give me the capital."))
    }

    @Test
    fun `asking for more depth is a different question`() {
        assertTrue(chainRejects("How does HTTPS work?", "How does HTTPS work, in detail?"))
    }

    @Test
    fun `a store emptied by eviction accepts a new embedding model`() = runTest {
        // evictOverflow was the one mutation path that could empty the map without releasing the
        // latched dimension — exactly the defect the previous round claimed to have fixed.
        val clock = MutableClock()
        val store = InMemoryStore(maxEntries = 1, ttl = 1.hours, clock = clock)
        val stale = clock.instant().minusSeconds(4 * 60 * 60)
        store.put(entry("a", vector = floatArrayOf(1.0f, 0.0f), createdAt = stale))
        store.put(entry("b", vector = floatArrayOf(1.0f, 0.0f), createdAt = stale))
        assertEquals(0, store.size())

        store.put(entry("c", vector = floatArrayOf(1.0f, 0.0f, 0.0f), createdAt = clock.instant()))
        assertEquals(1, store.size())
    }

    private fun unit(similarity: Double): FloatArray =
        floatArrayOf(similarity.toFloat(), sqrt(1.0 - similarity * similarity).toFloat())

    private fun entry(
        id: String,
        scope: String = "default",
        vector: FloatArray = floatArrayOf(1.0f, 0.0f),
        createdAt: Instant = Instant.parse("2026-01-01T00:00:00Z"),
    ) = CacheEntry(id, scope, "prompt for $id", "response for $id", vector, createdAt)
}