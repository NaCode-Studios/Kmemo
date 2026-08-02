package dev.kmemo

import dev.kmemo.fixtures.HashingEmbedder
import dev.kmemo.store.InMemoryStore
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import dev.kmemo.fixtures.UpstreamFailure
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** M11 — ergonomics: the catching{} helper, typed and streaming getOrPut, and the config DSL. */
class ErgonomicsTest {

    // --- catching {} --------------------------------------------------------------------------

    @Test
    fun `catching captures success and failure but re-throws cancellation`() = runTest {
        assertEquals("ok", catching { "ok" }.getOrNull())

        val failure = catching { error("boom") }
        assertTrue(failure.isFailure)
        assertTrue(failure.exceptionOrNull() is IllegalStateException)

        // Unlike runCatching, a CancellationException is never captured — cancellation still works.
        assertFailsWith<CancellationException> {
            catching { throw CancellationException("cancelled") }
        }
    }

    // --- typed getOrPut<T> --------------------------------------------------------------------

    @Test
    fun `typed getOrPut caches a structured value and decodes it on a hit`() = runTest {
        val cache = SemanticCache(HashingEmbedder())
        var computes = 0

        val first = cache.getOrPut("weather in Rome?", WeatherCodec) { computes++; Weather(30, "sunny") }
        val second = cache.getOrPut("weather in Rome?", WeatherCodec) { computes++; Weather(-1, "unused") }

        assertEquals(Weather(30, "sunny"), first)
        assertEquals(Weather(30, "sunny"), second)
        assertEquals(1, computes, "the second call must be served from cache")
    }

    // --- streaming getOrPut -------------------------------------------------------------------

    @Test
    fun `streaming getOrPut passes chunks through and replays them on a hit`() = runTest {
        val cache = SemanticCache(HashingEmbedder())

        val streamed = cache.getOrPutStreaming("tell me a short story") {
            flowOf("once ", "upon ", "a time")
        }.toList()
        assertEquals(listOf("once ", "upon ", "a time"), streamed)

        // Since M26 the second call replays the chunks the first one produced, not one lump the size
        // of the answer. `2.0` returned a single element here; StreamReplay.WHOLE still does.
        val replayed = cache.getOrPutStreaming("tell me a short story") {
            error("must not compute on a hit")
        }.toList()
        assertEquals(listOf("once ", "upon ", "a time"), replayed)
    }

    @Test
    fun `a stream that fails midway caches nothing`() = runTest {
        val cache = SemanticCache(HashingEmbedder())

        assertFailsWith<UpstreamFailure> {
            cache.getOrPutStreaming("a doomed request") {
                flow { emit("partial "); throw UpstreamFailure("upstream died") }
            }.toList()
        }

        assertEquals(0, cache.size(), "a partial stream must never be cached")
    }

    // --- config DSL ---------------------------------------------------------------------------

    @Test
    fun `the DSL builds a working cache and applies overrides`() = runTest {
        val cache = semanticCache(HashingEmbedder()) {
            store = InMemoryStore(maxEntries = 1)
        }

        cache.put("the first question about lists", "a")
        cache.put("a second question about maps", "b")

        // maxEntries = 1 from the override took effect.
        assertEquals(1, cache.size())
    }

    @Test
    fun `the DSL with no overrides is a plain cache`() = runTest {
        val cache = semanticCache(HashingEmbedder())
        cache.put("How do I reverse a list?", "use reversed()")
        assertEquals("use reversed()", cache.get("How do I reverse a list?"))
    }

    private data class Weather(val temp: Int, val sky: String)

    private object WeatherCodec : ResponseCodec<Weather> {
        override fun encode(value: Weather): String = "${value.temp}|${value.sky}"

        override fun decode(text: String): Weather {
            val (temp, sky) = text.split("|", limit = 2)
            return Weather(temp.toInt(), sky)
        }
    }
}
