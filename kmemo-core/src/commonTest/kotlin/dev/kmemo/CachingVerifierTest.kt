package dev.kmemo

import dev.kmemo.fixtures.MutableClock
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.hours

class CachingVerifierTest {

    @Test
    fun `a repeated pair is judged once`() = runTest {
        var calls = 0
        val verifier = CachingVerifier(Verifier { _, _, _ -> calls++; false })

        assertEquals(false, verifier.verify("reset my password", "reset my PIN", 0.98))
        assertEquals(false, verifier.verify("reset my password", "reset my PIN", 0.98))
        // The verdict is a function of the two prompts, not the similarity — a different score for the
        // same pair still hits the cache.
        assertEquals(false, verifier.verify("reset my password", "reset my PIN", 0.97))

        assertEquals(1, calls)
    }

    @Test
    fun `distinct pairs are judged separately`() = runTest {
        var calls = 0
        val verifier = CachingVerifier(Verifier { _, _, _ -> calls++; true })

        verifier.verify("a", "b", 0.99)
        verifier.verify("a", "c", 0.99)
        verifier.verify("d", "b", 0.99)

        assertEquals(3, calls)
    }

    @Test
    fun `a verdict expires after its ttl`() = runTest {
        var calls = 0
        val clock = MutableClock()
        val verifier = CachingVerifier(Verifier { _, _, _ -> calls++; true }, ttl = 1.hours, clock = clock)

        verifier.verify("q", "cached", 0.99)
        verifier.verify("q", "cached", 0.99)
        assertEquals(1, calls)

        clock.advance(2.hours)
        verifier.verify("q", "cached", 0.99) // stale — judged again
        assertEquals(2, calls)
    }

    @Test
    fun `a throwing delegate is not memoized`() = runTest {
        var calls = 0
        val verifier = CachingVerifier(
            Verifier { _, _, _ ->
                calls++
                if (calls == 1) throw IllegalStateException("down") else true
            },
        )

        // A transient failure must not freeze into a cached verdict; the next call retries.
        assertFailsWith<IllegalStateException> { verifier.verify("q", "cached", 0.99) }
        assertEquals(true, verifier.verify("q", "cached", 0.99))
        assertEquals(2, calls)
    }

    @Test
    fun `the caching extension memoizes just like the class`() = runTest {
        var calls = 0
        val verifier = Verifier { _, _, _ -> calls++; false }.caching()

        verifier.verify("q", "cached", 0.99)
        verifier.verify("q", "cached", 0.99)

        assertEquals(1, calls)
    }
}
