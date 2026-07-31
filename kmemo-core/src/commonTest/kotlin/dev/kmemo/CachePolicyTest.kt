package dev.kmemo

import dev.kmemo.fixtures.HashingEmbedder
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** M22 — cache policy: the writes that must never happen, and the fact that they are reported. */
class CachePolicyTest {

    private val vetoIban = CachePolicy { prompt, response, _ ->
        if ("IT60" in prompt || "IT60" in response) PolicyVerdict.Veto("iban") else PolicyVerdict.Store
    }

    @Test
    fun `no policy caches everything exactly as before`() = runTest {
        val cache = SemanticCache(HashingEmbedder())

        cache.getOrPut("what is my balance") { "1200 EUR" }

        assertEquals(1, cache.size())
        assertEquals(0, cache.stats().writesVetoed)
    }

    @Test
    fun `a vetoed getOrPut still returns the computed response`() = runTest {
        val cache = SemanticCache(HashingEmbedder(), cachePolicy = vetoIban)

        val answer = cache.getOrPut("pay to IT60X0542811101000000123456") { "transfer scheduled" }

        // The caller is not punished for the policy: the call behaves exactly as an uncached one.
        assertEquals("transfer scheduled", answer)
        assertEquals(0, cache.size())
    }

    @Test
    fun `a vetoed write is counted and is not a miss`() = runTest {
        val cache = SemanticCache(HashingEmbedder(), cachePolicy = vetoIban)

        cache.getOrPut("pay to IT60X0542811101000000123456") { "transfer scheduled" }

        val stats = cache.stats()
        assertEquals(1, stats.writesVetoed)
        assertEquals(0, stats.writes)
        // One lookup happened and it missed; the veto is a separate fact about the write.
        assertEquals(1, stats.lookups)
        assertEquals(0, stats.degradedLookups)
    }

    @Test
    fun `a veto emits WriteVetoed carrying the reason`() = runTest {
        val listener = CapturingListener()
        val cache = SemanticCache(HashingEmbedder(), listeners = listOf(listener), cachePolicy = vetoIban)

        cache.getOrPut("pay to IT60X0542811101000000123456") { "transfer scheduled" }

        val vetoed = assertIs<CacheEvent.WriteVetoed>(listener.events.single { it is CacheEvent.WriteVetoed })
        assertEquals("iban", vetoed.reason)
        assertEquals(SemanticCache.DEFAULT_SCOPE, vetoed.scope)
        assertTrue(listener.events.none { it is CacheEvent.Write }, "a vetoed write must not also emit Write")
    }

    @Test
    fun `the policy sees the computed response not only the prompt`() = runTest {
        val cache = SemanticCache(HashingEmbedder(), cachePolicy = vetoIban)

        // Clean prompt, sensitive answer. This is the case a prompt-only filter cannot catch.
        cache.getOrPut("where should I send it") { "to IT60X0542811101000000123456" }

        assertEquals(0, cache.size())
        assertEquals(1, cache.stats().writesVetoed)
    }

    @Test
    fun `a policy that stores leaves every path untouched`() = runTest {
        val cache = SemanticCache(HashingEmbedder(), cachePolicy = vetoIban)

        cache.getOrPut("how do I reverse a list") { "use reversed()" }

        assertEquals(1, cache.size())
        assertEquals(1, cache.stats().writes)
        assertEquals(0, cache.stats().writesVetoed)
        assertEquals("use reversed()", cache.get("how do I reverse a list"))
    }

    @Test
    fun `every write path is covered including put warm and streaming`() = runTest {
        val cache = SemanticCache(HashingEmbedder(), cachePolicy = vetoIban)
        val sensitive = "IT60X0542811101000000123456"

        cache.put("prompt a", sensitive)
        cache.warm(listOf(WarmEntry("prompt b", sensitive)))
        cache.getOrPutStreaming("prompt c") { flowOf(sensitive) }.toList()
        cache.getOrPutAll(listOf("prompt d")) { sensitive }

        // A guarantee with one entry point around it is not a guarantee.
        assertEquals(0, cache.size(), "no write path may bypass the policy")
        assertEquals(4, cache.stats().writesVetoed)
    }

    @Test
    fun `a vetoed streaming call still streams its chunks to the collector`() = runTest {
        val cache = SemanticCache(HashingEmbedder(), cachePolicy = vetoIban)

        val chunks = cache.getOrPutStreaming("prompt") { flowOf("IT60", "X05428") }.toList()

        assertEquals(listOf("IT60", "X05428"), chunks)
        assertEquals(0, cache.size())
    }

    @Test
    fun `a vetoed entry is never readable afterwards`() = runTest {
        val cache = SemanticCache(HashingEmbedder(), cachePolicy = vetoIban)
        cache.getOrPut("pay to IT60X0542811101000000123456") { "transfer scheduled" }

        assertNull(cache.get("pay to IT60X0542811101000000123456"))
    }

    @Test
    fun `the policy is told which scope the write belongs to`() = runTest {
        val seen = mutableListOf<String>()
        val cache = SemanticCache(
            HashingEmbedder(),
            cachePolicy = CachePolicy { _, _, scope -> seen += scope; PolicyVerdict.Store },
        )

        cache.getOrPut("prompt", scope = "tenant-a") { "answer" }

        assertEquals(listOf("tenant-a"), seen)
    }

    @Test
    fun `the builder DSL wires the policy through`() = runTest {
        val cache = semanticCache(HashingEmbedder()) { cachePolicy = vetoIban }

        cache.getOrPut("pay to IT60X0542811101000000123456") { "transfer scheduled" }

        assertEquals(0, cache.size())
        assertEquals(1, cache.stats().writesVetoed)
    }

    private class CapturingListener : CacheListener {
        val events: MutableList<CacheEvent> = mutableListOf()

        override fun onEvent(event: CacheEvent) {
            events += event
        }
    }
}
