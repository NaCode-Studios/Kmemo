package dev.kmemo

import io.kotest.property.Arb
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.filterNot
import io.kotest.property.arbitrary.flatMap
import io.kotest.property.arbitrary.float
import io.kotest.property.arbitrary.floatArray
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Invariants of [Vectors], checked against generated input rather than a handful of examples. The
 * whole cache rests on this maths behaving — a normalize that does not produce unit length, or a dot
 * product that is not symmetric, would quietly corrupt every similarity in the store.
 */
class VectorsPropertyTest {

    private val finiteFloats = Arb.float(-1_000f, 1_000f).filterNot { it.isNaN() || it.isInfinite() }

    // Arb.float(from, to) still emits NaN and the infinities as edge cases, so a "positive scaling" arb
    // has to strip them — a NaN factor is not a positive scaling and would poison the whole vector.
    private val positiveScale = Arb.float(0.01f, 1_000f).filterNot { it.isNaN() || it.isInfinite() }

    /** Non-zero, finite vectors of a given dimension — the only ones [Vectors.normalize] accepts. */
    private fun vector(dim: Int): Arb<FloatArray> =
        Arb.floatArray(Arb.int(dim..dim), finiteFloats).filter { Vectors.magnitude(it) > 1e-2 }

    private val sameDimPair: Arb<Pair<FloatArray, FloatArray>> =
        Arb.int(1..32).flatMap { dim -> vector(dim).flatMap { a -> vector(dim).map { b -> a to b } } }

    @Test
    fun `normalize always produces unit length`() = runTest {
        checkAll(Arb.int(1..48).flatMap { vector(it) }) { v ->
            assertTrue(abs(Vectors.magnitude(Vectors.normalize(v)) - 1.0) < 1e-3)
        }
    }

    @Test
    fun `the dot product is symmetric`() = runTest {
        checkAll(sameDimPair) { (a, b) ->
            assertTrue(abs(Vectors.dot(a, b) - Vectors.dot(b, a)) < 1e-3)
        }
    }

    @Test
    fun `dot of a normalized vector with itself is one`() = runTest {
        checkAll(Arb.int(1..48).flatMap { vector(it) }) { v ->
            val unit = Vectors.normalize(v)
            assertTrue(abs(Vectors.dot(unit, unit) - 1.0) < 1e-3)
        }
    }

    @Test
    fun `the dot product of two unit vectors stays within its cosine bounds`() = runTest {
        checkAll(sameDimPair) { (a, b) ->
            val similarity = Vectors.dot(Vectors.normalize(a), Vectors.normalize(b))
            assertTrue(similarity in -1.0001..1.0001, "dot of two unit vectors was $similarity")
        }
    }

    @Test
    fun `cosine similarity ignores positive scaling`() = runTest {
        checkAll(Arb.int(1..48).flatMap { vector(it) }, positiveScale) { v, scale ->
            val scaled = FloatArray(v.size) { v[it] * scale }
            assertTrue(abs(Vectors.cosineSimilarity(v, scaled) - 1.0) < 1e-3)
        }
    }

    @Test
    fun `a non-finite component is always rejected`() = runTest {
        checkAll(Arb.int(1..48).flatMap { vector(it) }, Arb.int(0..47)) { v, index ->
            val poisoned = v.copyOf()
            poisoned[index % poisoned.size] = Float.NaN
            assertFailsWith<IllegalArgumentException> { Vectors.normalize(poisoned) }
        }
    }
}
