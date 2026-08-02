package dev.kmemo.tck

import dev.kmemo.CacheEntry
import dev.kmemo.CacheStore
import dev.kmemo.Embedder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * The conformance suite for [CacheStore]: write it once, hold every backend to it.
 *
 * kmemo's design leans hard on the [CacheStore] seam — match logic (threshold, guards, verification)
 * lives in the cache, and a backend only has to *store vectors and return the nearest `k` in a
 * scope*. That is a small contract, but it is a real one, and an adapter that gets a corner of it
 * wrong (returns an expired entry, leaks across scopes, mis-sorts, races) is worse than no cache: it
 * serves wrong answers silently. This class encodes the three rules the [CacheStore] KDoc states,
 * plus the behaviour of every method, so a Redis or Postgres adapter proves it belongs before it
 * ships.
 *
 * To use it, subclass from a test source set and wire the store under test to [clock]:
 *
 * ```kotlin
 * class InMemoryStoreConformanceTest : CacheStoreContract() {
 *     override fun createStore(ttl: Duration?): CacheStore = InMemoryStore(ttl = ttl, clock = clock)
 * }
 * ```
 *
 * Backend-specific behaviour that is *not* part of the seam — capacity eviction, dimension-mismatch
 * rejection, backend statistics — belongs in the adapter's own tests, not here.
 */
public abstract class CacheStoreContract {

    /**
     * The time source the TTL tests advance. Wire it into every store you build so expiry is
     * observed against the same clock the test drives.
     */
    protected val clock: FakeClock = FakeClock()

    /**
     * Returns a fresh, empty store bound to [clock], expiring entries [ttl] after they are written
     * (`null` keeps them until they are removed).
     *
     * Implementations **must** use [clock] as the store's time source, or the TTL tests cannot be
     * deterministic. A backend whose expiry is genuinely external (a Redis key TTL) should compute an
     * `expires_at` from [clock] for the suite and cover real server-side expiry in its own
     * integration test.
     */
    protected abstract fun createStore(ttl: Duration? = null): CacheStore

    // ---- put & search: the happy path ------------------------------------------------------------

    @Test
    public fun `put stores an entry that search then finds`() = runTest {
        val store = createStore()
        store.put(entry("a", vector = floatArrayOf(1f, 0f)))

        val hits = store.search("default", floatArrayOf(1f, 0f), limit = 10)

        assertEquals(listOf("a"), hits.map { it.entry.id })
        assertTrue(hits.single().similarity > 0.99, "an exact vector match should score ~1.0")
    }

    @Test
    public fun `put replaces the entry with the same id rather than duplicating it`() = runTest {
        val store = createStore()
        store.put(entry("a", response = "first"))
        store.put(entry("a", response = "second"))

        assertEquals(1, store.size())
        assertEquals("second", store.search("default", query, limit = 10).single().entry.response)
    }

    /**
     * The embedder identity is not decoration on the entry, it is half of what makes the vector
     * meaningful, so a store that drops it in transit turns the one check standing between a model
     * swap and a wrong answer into a check that always passes. A backend that adds a column or a field
     * for every other value and forgets this one fails here rather than in production.
     */
    @Test
    public fun `search round-trips the embedder that wrote the entry`() = runTest {
        val store = createStore()
        store.put(entry("declared", embedder = "openai:text-embedding-3-small:1536"))

        val found = store.search("default", query, limit = 10).single().entry

        assertEquals("openai:text-embedding-3-small:1536", found.embedder)
    }

    /**
     * The default is a value, not an absence, and it has to survive the round trip as that value.
     * A backend that returned `null` or an empty string here would make every pre-2.1.0 entry
     * unreadable rather than undeclared.
     */
    @Test
    public fun `an entry written by an undeclared embedder comes back undeclared`() = runTest {
        val store = createStore()
        store.put(entry("plain"))

        assertEquals(Embedder.UNDECLARED, store.search("default", query, limit = 10).single().entry.embedder)
    }

    /**
     * A streamed answer is stored with the boundaries it arrived in, and a store that loses them
     * turns every replay into one lump. Not a correctness failure, since the text is still right, but
     * it is the difference between a cache hit that looks like the model and one that visibly does not,
     * which is the entire reason a streaming caller would reach for the cache.
     */
    @Test
    public fun `search round-trips the chunk boundaries of a streamed answer`() = runTest {
        val store = createStore()
        store.put(entry("streamed", response = "abcdef", chunkLengths = listOf(2, 3, 1)))

        val found = store.search("default", query, limit = 10).single().entry

        assertEquals(listOf(2, 3, 1), found.chunkLengths)
    }

    @Test
    public fun `an entry that was never streamed comes back with no boundaries`() = runTest {
        val store = createStore()
        store.put(entry("plain"))

        assertTrue(store.search("default", query, limit = 10).single().entry.chunkLengths.isEmpty())
    }

    @Test
    public fun `search returns the closest entries first`() = runTest {
        val store = createStore()
        store.put(entry("far", vector = floatArrayOf(0f, 1f)))
        store.put(entry("near", vector = floatArrayOf(1f, 0.1f)))
        store.put(entry("mid", vector = floatArrayOf(0.7f, 0.7f)))

        val ids = store.search("default", floatArrayOf(1f, 0f), limit = 10).map { it.entry.id }

        assertEquals(listOf("near", "mid", "far"), ids)
    }

    @Test
    public fun `search similarities are non-increasing`() = runTest {
        val store = createStore()
        store.put(entry("far", vector = floatArrayOf(0f, 1f)))
        store.put(entry("near", vector = floatArrayOf(1f, 0.1f)))
        store.put(entry("mid", vector = floatArrayOf(0.7f, 0.7f)))

        val scores = store.search("default", floatArrayOf(1f, 0f), limit = 10).map { it.similarity }

        assertEquals(scores.sortedDescending(), scores, "results must be sorted best-first")
    }

    @Test
    public fun `search never returns more than the limit`() = runTest {
        val store = createStore()
        repeat(5) { store.put(entry("id$it")) }

        assertEquals(2, store.search("default", query, limit = 2).size)
    }

    @Test
    public fun `search keeps the best entries when the limit truncates`() = runTest {
        val store = createStore()
        store.put(entry("far", vector = floatArrayOf(0f, 1f)))
        store.put(entry("near", vector = floatArrayOf(1f, 0.05f)))
        store.put(entry("mid", vector = floatArrayOf(0.6f, 0.8f)))

        val top = store.search("default", floatArrayOf(1f, 0f), limit = 2).map { it.entry.id }

        assertEquals(listOf("near", "mid"), top, "truncation must drop the worst, not the best")
    }

    // ---- scopes ----------------------------------------------------------------------------------

    @Test
    public fun `search only returns entries in the requested scope`() = runTest {
        val store = createStore()
        store.put(entry("a", scope = "gpt-4o"))
        store.put(entry("b", scope = "haiku"))

        assertEquals(listOf("a"), store.search("gpt-4o", query, limit = 10).map { it.entry.id })
        assertEquals(listOf("b"), store.search("haiku", query, limit = 10).map { it.entry.id })
    }

    @Test
    public fun `search on a scope with no entries is empty`() = runTest {
        val store = createStore()
        store.put(entry("a", scope = "one"))

        assertTrue(store.search("nonexistent", query, limit = 10).isEmpty())
    }

    @Test
    public fun `size counts one scope and the whole store`() = runTest {
        val store = createStore()
        store.put(entry("a", scope = "one"))
        store.put(entry("b", scope = "two"))
        store.put(entry("c", scope = "two"))

        assertEquals(1, store.size("one"))
        assertEquals(2, store.size("two"))
        assertEquals(3, store.size())
        assertEquals(0, store.size("empty"))
    }

    // ---- remove & clear --------------------------------------------------------------------------

    @Test
    public fun `remove reports whether an entry was actually there`() = runTest {
        val store = createStore()
        store.put(entry("a"))

        assertTrue(store.remove("a"))
        assertFalse(store.remove("a"))
        assertEquals(0, store.size())
    }

    @Test
    public fun `remove deletes only its target`() = runTest {
        val store = createStore()
        store.put(entry("a"))
        store.put(entry("b"))

        store.remove("a")

        assertEquals(listOf("b"), store.search("default", query, limit = 10).map { it.entry.id })
    }

    @Test
    public fun `clear can target a single scope`() = runTest {
        val store = createStore()
        store.put(entry("a", scope = "one"))
        store.put(entry("b", scope = "two"))

        store.clear("one")

        assertEquals(0, store.size("one"))
        assertEquals(1, store.size("two"))
    }

    @Test
    public fun `clear with no scope empties the whole store`() = runTest {
        val store = createStore()
        store.put(entry("a", scope = "one"))
        store.put(entry("b", scope = "two"))

        store.clear()

        assertEquals(0, store.size())
    }

    // ---- touch -----------------------------------------------------------------------------------

    @Test
    public fun `touch on a present entry is safe and keeps it findable`() = runTest {
        val store = createStore()
        store.put(entry("a"))

        store.touch("a")

        assertEquals(1, store.size())
        assertEquals(listOf("a"), store.search("default", query, limit = 10).map { it.entry.id })
    }

    @Test
    public fun `touch on an absent id does nothing`() = runTest {
        val store = createStore()

        store.touch("ghost") // must not throw, must not create anything

        assertEquals(0, store.size())
    }

    // ---- TTL -------------------------------------------------------------------------------------

    @Test
    public fun `an entry past its ttl is never returned by search`() = runTest {
        val store = createStore(ttl = 1.hours)
        store.put(entry("a", createdAt = clock.now()))

        clock.advance(59.minutes)
        assertEquals(1, store.search("default", query, limit = 10).size)

        clock.advance(2.minutes)
        assertTrue(store.search("default", query, limit = 10).isEmpty())
    }

    @Test
    public fun `an entry past its ttl is not counted by size`() = runTest {
        val store = createStore(ttl = 1.hours)
        store.put(entry("a", createdAt = clock.now()))

        clock.advance(2.hours)

        assertEquals(0, store.size())
    }

    @Test
    public fun `without a ttl an entry never expires`() = runTest {
        val store = createStore(ttl = null)
        store.put(entry("a", createdAt = clock.now()))

        clock.advance((24 * 365).hours)

        assertEquals(1, store.size())
    }

    // ---- concurrency -----------------------------------------------------------------------------

    @Test
    public fun `concurrent writers all land`() = runTest {
        val store = createStore()

        // Real threads, so a store that is not actually concurrency-safe is caught here rather than
        // in production. Distinct ids, so `size` is exact — the check is that every write landed, not
        // that search is complete (an approximate store need not return every match).
        withContext(Dispatchers.Default) {
            (1..200).map { i -> async { store.put(entry("id$i")) } }.awaitAll()
        }

        assertEquals(200, store.size())
    }

    @Test
    public fun `concurrent reads and writes leave a consistent store`() = runTest {
        val store = createStore()
        repeat(50) { store.put(entry("seed$it")) }

        withContext(Dispatchers.Default) {
            val writers = (1..50).map { i -> async { store.put(entry("w$i")) } }
            val readers = (1..50).map { async { store.search("default", query, limit = 10) } }
            (writers + readers).awaitAll()
        }

        assertEquals(100, store.size())
    }

    // ---- tag invalidation ------------------------------------------------------------------------

    /**
     * Whether the store under test indexes tags.
     *
     * A store that returns `false` is expected to throw from [CacheStore.invalidateByTag], and the
     * cases below assert exactly that instead of skipping. A contract that quietly skips the cases a
     * store does not satisfy is not a contract.
     */
    protected open val supportsTagInvalidation: Boolean get() = true

    @Test
    public fun `invalidateByTag removes only the entries carrying the tag`() = runTest {
        val store = createStore()
        store.put(entry("a", tags = setOf("price-list")))
        store.put(entry("b", tags = setOf("price-list", "policy")))
        store.put(entry("c", tags = setOf("policy")))
        store.put(entry("d"))

        if (!supportsTagInvalidation) {
            assertFailsWith<UnsupportedOperationException> { store.invalidateByTag("price-list") }
            return@runTest
        }

        val removed = store.invalidateByTag("price-list")

        assertEquals(2, removed)
        assertEquals(2, store.size())
        val ids = store.search("default", query, 10).map { it.entry.id }.toSet()
        assertEquals(setOf("c", "d"), ids)
    }

    @Test
    public fun `invalidateByTag can be scoped, leaving other scopes alone`() = runTest {
        if (!supportsTagInvalidation) return@runTest
        val store = createStore()
        store.put(entry("a", scope = "one", tags = setOf("shared")))
        store.put(entry("b", scope = "two", tags = setOf("shared")))

        val removed = store.invalidateByTag("shared", scope = "one")

        assertEquals(1, removed)
        assertEquals(0, store.size("one"))
        assertEquals(1, store.size("two"))
    }

    @Test
    public fun `invalidateByTag on an unknown tag removes nothing and says so`() = runTest {
        if (!supportsTagInvalidation) return@runTest
        val store = createStore()
        store.put(entry("a", tags = setOf("price-list")))

        assertEquals(0, store.invalidateByTag("no-such-tag"))
        assertEquals(1, store.size())
    }

    @Test
    public fun `an entry keeps its tags through a round trip`() = runTest {
        if (!supportsTagInvalidation) return@runTest
        val store = createStore()
        store.put(entry("a", tags = setOf("alpha", "beta")))

        val found = store.search("default", query, 1).single().entry

        assertEquals(setOf("alpha", "beta"), found.tags)
    }

    @Test
    public fun `an entry with no tags is untouched by any tag invalidation`() = runTest {
        if (!supportsTagInvalidation) return@runTest
        val store = createStore()
        store.put(entry("a"))

        assertEquals(0, store.invalidateByTag("anything"))
        assertEquals(1, store.size())
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private val query = floatArrayOf(1f, 0f)

    /** Builds a [CacheEntry] stamped at the current [clock] time by default. */
    protected fun entry(
        id: String,
        scope: String = "default",
        vector: FloatArray = floatArrayOf(1f, 0f),
        response: String = "response for $id",
        createdAt: Instant = clock.now(),
        tags: Set<String> = emptySet(),
        embedder: String = Embedder.UNDECLARED,
        chunkLengths: List<Int> = emptyList(),
    ): CacheEntry = CacheEntry(
        id = id,
        scope = scope,
        prompt = "prompt for $id",
        response = response,
        embedding = vector,
        createdAt = createdAt,
        tags = tags,
        embedder = embedder,
        chunkLengths = chunkLengths,
    )
}
