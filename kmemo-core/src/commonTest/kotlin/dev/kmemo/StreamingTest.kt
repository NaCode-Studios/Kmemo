package dev.kmemo

import dev.kmemo.fixtures.ConceptEmbedder
import dev.kmemo.fixtures.HashingEmbedder
import dev.kmemo.store.InMemoryStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * M26 — a cache that can sit on a streaming path.
 *
 * The three properties the milestone turns on, and they are properties rather than features: a
 * streamed miss yields the same tokens to the caller and to the store, a later lookup replays them
 * identically, and a stream that fails partway leaves nothing behind. The rest of the file is the
 * replay policy and the rule that no token is served before the guards have finished.
 */
class StreamingTest {

    private val prompt = "How do I reverse a list in Kotlin?"
    private val tokens = listOf("You ", "can ", "call ", "reversed", "().")

    @Test
    fun `a streamed miss yields the same tokens to the caller and to the store`() = runTest {
        val store = InMemoryStore()
        val cache = SemanticCache(HashingEmbedder(), store)

        val seen = cache.getOrPutStreaming(prompt) { tokens.asFlow() }.toList()

        assertEquals(tokens, seen)
        val entry = store.search(SemanticCache.DEFAULT_SCOPE, HashingEmbedder().embed(prompt), 1).single().entry
        assertEquals(tokens.joinToString(""), entry.response)
        assertEquals(tokens.map { it.length }, entry.chunkLengths)
    }

    @Test
    fun `a later lookup replays the tokens identically`() = runTest {
        val cache = SemanticCache(HashingEmbedder())
        cache.getOrPutStreaming(prompt) { tokens.asFlow() }.toList()

        val replayed = cache.getOrPutStreaming(prompt) { error("the model must not be called on a hit") }

        assertEquals(tokens, replayed.toList())
    }

    /**
     * The rule that makes the write path safe. A truncated answer stored as a complete one is worse
     * than no entry: it is a wrong answer that will be served confidently to everyone who asks that
     * question afterwards, and it will look exactly like a right one.
     */
    @Test
    fun `a provider stream that throws after n tokens leaves the store with no entry`() = runTest {
        val store = InMemoryStore()
        val cache = SemanticCache(HashingEmbedder(), store)

        val delivered = mutableListOf<String>()
        assertFailsWith<IllegalStateException> {
            cache.getOrPutStreaming(prompt) {
                flow {
                    emit("You ")
                    emit("can ")
                    error("the provider dropped the connection")
                }
            }.toList(delivered)
        }

        assertEquals(listOf("You ", "can "), delivered, "what did arrive still reached the caller")
        assertEquals(0, store.size(), "and none of it was kept")
    }

    @Test
    fun `a cancelled collection writes nothing either`() = runTest {
        val store = InMemoryStore()
        val cache = SemanticCache(HashingEmbedder(), store)

        // `take` cancels the upstream once it has what it wants, which is the ordinary way a caller
        // walks away from a stream — a user closing a tab, a request timing out.
        val partial = cache.getOrPutStreaming(prompt) { tokens.asFlow() }.take(2).toList()

        assertEquals(tokens.take(2), partial)
        assertEquals(0, store.size())
    }

    /**
     * The rule that makes the read path safe. A token already handed to the caller cannot be taken
     * back, so a guard that fired halfway through would have served a wrong answer to somebody who is
     * already reading it. Everything that decides *whether* to serve runs while the call suspends.
     */
    @Test
    fun `the guards have finished before the first token is served`() = runTest {
        val cache = SemanticCache(ConceptEmbedder())
        cache.getOrPutStreaming("Convert 100 USD to EUR") { listOf("about ", "92 EUR").asFlow() }.toList()

        var computed = false
        // The numeric guard refuses this pair, so the cached answer must not surface even partially.
        val seen = cache.getOrPutStreaming("Convert 250 USD to EUR") {
            computed = true
            listOf("about ", "230 EUR").asFlow()
        }.toList()

        assertTrue(computed, "the guard rejected the cached answer, so the model had to run")
        assertEquals(listOf("about ", "230 EUR"), seen)
        assertFalse(seen.any { it.contains("92") }, "no token of the refused answer was ever emitted")
    }

    @Test
    fun `nothing is computed and nothing is emitted until the flow is collected`() = runTest {
        val cache = SemanticCache(HashingEmbedder())

        var computed = false
        val cold = cache.getOrPutStreaming(prompt) {
            computed = true
            tokens.asFlow()
        }

        assertFalse(computed, "the flow is cold; building it must not call the model")
        cold.toList()
        assertTrue(computed)
    }

    @Test
    fun `WHOLE replays the answer as one element`() = runTest {
        val cache = SemanticCache(HashingEmbedder())
        cache.getOrPutStreaming(prompt) { tokens.asFlow() }.toList()

        val replayed = cache
            .getOrPutStreaming(prompt, StreamReplay.WHOLE) { error("must not be called") }
            .toList()

        assertEquals(listOf(tokens.joinToString("")), replayed)
    }

    /**
     * An entry nobody streamed has no boundaries to replay, and inventing one would be a claim about
     * how the answer arrived. It replays whole, which is what actually happened.
     */
    @Test
    fun `an answer written by getOrPut replays as a single chunk`() = runTest {
        val cache = SemanticCache(HashingEmbedder())
        cache.getOrPut(prompt) { "You can call reversed()." }

        val replayed = cache.getOrPutStreaming(prompt) { error("must not be called") }.toList()

        assertEquals(listOf("You can call reversed()."), replayed)
    }

    @Test
    fun `an empty keep-alive chunk reaches the caller but is not recorded`() = runTest {
        val store = InMemoryStore()
        val cache = SemanticCache(HashingEmbedder(), store)

        val seen = cache.getOrPutStreaming(prompt) { listOf("You ", "", "win").asFlow() }.toList()

        assertEquals(listOf("You ", "", "win"), seen)
        val entry = store.search(SemanticCache.DEFAULT_SCOPE, HashingEmbedder().embed(prompt), 1).single().entry
        assertEquals(listOf(4, 3), entry.chunkLengths)
        assertEquals("You win", entry.response)
    }

    @Test
    fun `a hit on a streamed entry is still counted as a hit`() = runTest {
        val cache = SemanticCache(HashingEmbedder())
        cache.getOrPutStreaming(prompt) { tokens.asFlow() }.toList()

        cache.getOrPutStreaming(prompt) { error("must not be called") }.toList()

        assertEquals(1, cache.stats().hits)
        assertEquals(2, cache.stats().lookups)
    }

    @Test
    fun `chunk lengths that no longer describe the response are refused outright`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            CacheEntry(
                id = "x",
                scope = "default",
                prompt = prompt,
                response = "abcdef",
                embedding = floatArrayOf(1f, 0f),
                createdAt = kotlin.time.Instant.parse("2026-01-01T00:00:00Z"),
                chunkLengths = listOf(2, 2),
            )
        }
        assertTrue(failure.message.orEmpty().contains("no longer describe"))
    }

    @Test
    fun `a cancellation from the provider is never swallowed`() = runTest {
        val store = InMemoryStore()
        val cache = SemanticCache(HashingEmbedder(), store)

        assertFailsWith<CancellationException> {
            cache.getOrPutStreaming(prompt) {
                flow {
                    emit("half an ")
                    throw CancellationException("upstream gone")
                }
            }.toList()
        }

        assertEquals(0, store.size())
    }
}
