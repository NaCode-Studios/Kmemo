package dev.kmemo.store

import dev.kmemo.CacheEntry
import dev.kmemo.fixtures.MutableClock
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class InMemoryStoreTest {

    private val query = floatArrayOf(1.0f, 0.0f)

    @Test
    fun `search returns the closest entries first`() = runTest {
        val store = InMemoryStore()
        store.put(entry("far", vector = floatArrayOf(0.0f, 1.0f)))
        store.put(entry("near", vector = floatArrayOf(1.0f, 0.1f)))

        val results = store.search("default", query, limit = 10)

        assertEquals(listOf("near", "far"), results.map { it.entry.id })
        assertTrue(results.first().similarity > results.last().similarity)
    }

    @Test
    fun `search respects the limit`() = runTest {
        val store = InMemoryStore()
        repeat(5) { store.put(entry("id$it")) }

        assertEquals(2, store.search("default", query, limit = 2).size)
    }

    @Test
    fun `search only sees its own scope`() = runTest {
        val store = InMemoryStore()
        store.put(entry("a", scope = "gpt-4o"))
        store.put(entry("b", scope = "haiku"))

        assertEquals(listOf("a"), store.search("gpt-4o", query, limit = 10).map { it.entry.id })
        assertEquals(1, store.size("gpt-4o"))
        assertEquals(2, store.size())
    }

    @Test
    fun `clear can target one scope`() = runTest {
        val store = InMemoryStore()
        store.put(entry("a", scope = "one"))
        store.put(entry("b", scope = "two"))

        store.clear("one")

        assertEquals(0, store.size("one"))
        assertEquals(1, store.size("two"))
    }

    @Test
    fun `the least recently used entry is the one evicted`() = runTest {
        val store = InMemoryStore(maxEntries = 2)
        store.put(entry("a"))
        store.put(entry("b"))

        // "a" was written first but served most recently, so "b" is the stale one.
        store.touch("a")
        store.put(entry("c"))

        val remaining = store.search("default", query, limit = 10).map { it.entry.id }.toSet()
        assertEquals(setOf("a", "c"), remaining)
    }

    @Test
    fun `eviction is counted`() = runTest {
        val store = InMemoryStore(maxEntries = 1)
        store.put(entry("a"))
        store.put(entry("b"))

        val stats = store.stats()
        assertEquals(1, stats.size)
        assertEquals(1, stats.evictions)
    }

    @Test
    fun `an expired entry is never returned`() = runTest {
        val clock = MutableClock()
        val store = InMemoryStore(ttl = 1.hours, clock = clock)
        store.put(entry("a", createdAt = clock.instant()))

        clock.advance(59.minutes)
        assertEquals(1, store.search("default", query, limit = 10).size)

        clock.advance(2.minutes)
        assertEquals(0, store.search("default", query, limit = 10).size)
        assertEquals(0, store.size())
    }

    @Test
    fun `purgeExpired reclaims entries nobody looked for`() = runTest {
        val clock = MutableClock()
        val store = InMemoryStore(ttl = 1.hours, clock = clock)
        store.put(entry("a", createdAt = clock.instant()))
        store.put(entry("b", createdAt = clock.instant()))

        clock.advance(2.hours)

        assertEquals(2, store.purgeExpired())
        assertEquals(2, store.stats().expirations)
        assertEquals(0, store.purgeExpired())
    }

    @Test
    fun `entries with no ttl stay forever`() = runTest {
        val clock = MutableClock()
        val store = InMemoryStore(clock = clock)
        store.put(entry("a", createdAt = clock.instant()))

        clock.advance((24 * 365).hours)

        assertEquals(1, store.size())
    }

    @Test
    fun `remove reports whether anything was there`() = runTest {
        val store = InMemoryStore()
        store.put(entry("a"))

        assertTrue(store.remove("a"))
        assertFalse(store.remove("a"))
    }

    @Test
    fun `mixing embedding models is refused instead of silently scoring nonsense`() = runTest {
        val store = InMemoryStore()
        store.put(entry("a", vector = floatArrayOf(1.0f, 0.0f)))

        val failure = assertFailsWith<IllegalArgumentException> {
            store.put(entry("b", vector = floatArrayOf(1.0f, 0.0f, 0.0f)))
        }
        assertTrue(failure.message.orEmpty().contains("dimension mismatch"))
    }

    @Test
    fun `a query from the wrong model is refused too`() = runTest {
        val store = InMemoryStore()
        store.put(entry("a", vector = floatArrayOf(1.0f, 0.0f)))

        assertFailsWith<IllegalStateException> {
            store.search("default", floatArrayOf(1.0f, 0.0f, 0.0f), limit = 5)
        }
    }

    @Test
    fun `concurrent writers all land`() = runTest {
        val store = InMemoryStore(maxEntries = 500)

        (1..200).map { index -> async { store.put(entry("id$index")) } }.awaitAll()

        assertEquals(200, store.size())
    }

    @Test
    fun `the byte bound evicts the least recently used entry`() = runTest {
        // Each 2-dim entry is ~162 bytes, so 350 holds two but not three.
        val store = InMemoryStore(maxBytes = 350)
        store.put(entry("a"))
        store.put(entry("b"))
        store.touch("a") // "b" is now the least recently used
        store.put(entry("c"))

        val remaining = store.search("default", query, limit = 10).map { it.entry.id }.toSet()
        assertEquals(setOf("a", "c"), remaining)
        assertTrue(store.stats().bytes <= 350)
    }

    @Test
    fun `stats estimate resident bytes and return to zero when emptied`() = runTest {
        val store = InMemoryStore()
        store.put(entry("a"))
        assertTrue(store.stats().bytes > 0)

        store.remove("a")
        assertEquals(0L, store.stats().bytes)
    }

    @Test
    fun `an invalid configuration fails at construction`() {
        assertFailsWith<IllegalArgumentException> { InMemoryStore(maxEntries = 0) }
        assertFailsWith<IllegalArgumentException> { InMemoryStore(ttl = 0.minutes) }
        assertFailsWith<IllegalArgumentException> { InMemoryStore(maxBytes = 0) }
    }

    private fun entry(
        id: String,
        scope: String = "default",
        vector: FloatArray = floatArrayOf(1.0f, 0.0f),
        createdAt: Instant = Instant.parse("2026-01-01T00:00:00Z"),
    ) = CacheEntry(
        id = id,
        scope = scope,
        prompt = "prompt for $id",
        response = "response for $id",
        embedding = vector,
        createdAt = createdAt,
    )
}
