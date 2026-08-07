package dev.kmemo.store.qdrant

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The one piece of this store that can be wrong without a server to tell you.
 *
 * Qdrant takes an unsigned integer or a UUID string as a point id and nothing else, and [CacheStore]
 * takes any string. The conformance suite writes entries with ids like `a` and `declared`, and a store
 * that handed those to Qdrant unchanged would be rejected by name on every write. So ids are mixed down
 * to 128 bits, and this holds the mixing to what a point id needs: total, deterministic, well formed,
 * and free of collisions across the kinds of id a cache actually sees.
 */
class PointIdTest {

    private val mapper = QdrantPointIds

    @Test
    fun `a kmemo id keeps its own bits`() {
        val id = "0123456789abcdef0123456789abcdef"

        assertEquals("01234567-89ab-cdef-0123-456789abcdef", mapper.of(id))
    }

    @Test
    fun `any other string becomes a well-formed uuid`() {
        for (id in listOf("a", "", "declared", "an id with spaces and ünïcödé", "42")) {
            val uuid = mapper.of(id)
            assertEquals(36, uuid.length, "'$id' produced '$uuid'")
            assertEquals(listOf(8, 13, 18, 23), uuid.indices.filter { uuid[it] == '-' })
            assertTrue(
                uuid.filter { it != '-' }.all { it in "0123456789abcdef" },
                "'$id' produced '$uuid'",
            )
        }
    }

    @Test
    fun `the same id always maps to the same point`() {
        assertEquals(mapper.of("some entry id"), mapper.of("some entry id"))
    }

    /**
     * A collision is two cache entries overwriting each other, silently, which is the kind of failure
     * this project spends its corpus discipline on. Ten thousand ids of the shapes a cache meets is not
     * a proof, and it is enough to catch a mixing function whose halves are correlated, which is the way
     * this actually goes wrong.
     */
    @Test
    fun `distinct ids do not meet`() {
        val ids = buildList {
            for (n in 0 until 2_500) {
                add("$n")
                add("entry-$n")
                add("scope$n|prompt$n")
                add(n.toString(16).padStart(32, '0'))
            }
        }
        val points = ids.map { mapper.of(it) }.toSet()

        assertEquals(ids.size, points.size, "two ids landed on one point")
    }
}
