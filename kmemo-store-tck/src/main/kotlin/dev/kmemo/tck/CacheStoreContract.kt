package dev.kmemo.tck

import dev.kmemo.CacheEntry
import dev.kmemo.CacheStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
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
        store.put(entry("a", createdAt = clock.instant()))

        clock.advance(59.minutes)
        assertEquals(1, store.search("default", query, limit = 10).size)

        clock.advance(2.minutes)
        assertTrue(store.search("default", query, limit = 10).isEmpty())
    }

    @Test
    public fun `an entry past its ttl is not counted by size`() = runTest {
        val store = createStore(ttl = 1.hours)
        store.put(entry("a", createdAt = clock.instant()))

        clock.advance(2.hours)

        assertEquals(0, store.size())
    }

    @Test
    public fun `without a ttl an entry never expires`() = runTest {
        val store = createStore(ttl = null)
        store.put(entry("a", createdAt = clock.instant()))

        clock.advance((24 * 365).hours)

        assertEquals(1, store.size())
    }

    // ---- concurrency -----------------------------------------------------------------------------

    @Test
    public fun `concurrent writers all land`() = runTest {
        val store = createStore()

        // Real threads, so a store that is not actually concurrency-safe is caught here rather than
        // in production. Distinct ids, so the assertion is exact.
        withContext(Dispatchers.Default) {
            (1..200).map { i -> async { store.put(entry("id$i")) } }.awaitAll()
        }

        assertEquals(200, store.size())
        assertEquals(200, store.search("default", query, limit = 500).size)
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

    // ---- helpers ---------------------------------------------------------------------------------

    private val query = floatArrayOf(1f, 0f)

    /** Builds a [CacheEntry] stamped at the current [clock] time by default. */
    protected fun entry(
        id: String,
        scope: String = "default",
        vector: FloatArray = floatArrayOf(1f, 0f),
        response: String = "response for $id",
        createdAt: Instant = clock.instant(),
    ): CacheEntry = CacheEntry(
        id = id,
        scope = scope,
        prompt = "prompt for $id",
        response = response,
        embedding = vector,
        createdAt = createdAt,
    )
}
