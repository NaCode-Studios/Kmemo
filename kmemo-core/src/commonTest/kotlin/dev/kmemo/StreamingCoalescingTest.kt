@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.kmemo

import dev.kmemo.fixtures.HashingEmbedder
import dev.kmemo.store.InMemoryStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * M29: the hole M26 left in the streaming path, and the traffic that falls through it.
 *
 * A cold cache under load is the case coalescing exists for, and streaming is the path a chat product
 * actually serves users on. Until this, `getOrPut` coalesced the traffic that costs least and
 * `getOrPutStreaming` let through the traffic that costs most: fifty callers, fifty provider streams,
 * all for one answer.
 *
 * The four properties here are the milestone. One provider call. Everyone gets the whole sequence,
 * including whoever joined halfway. A failure reaches every attached collector and writes nothing.
 * And the caller who happened to open the stream is not the one the others depend on.
 */
class StreamingCoalescingTest {

    private val prompt = "How do I reverse a list in Kotlin?"
    private val tokens = listOf("You ", "can ", "call ", "reversed", "().")

    @Test
    fun `fifty concurrent misses for one prompt make one provider call`() = runTest {
        val store = InMemoryStore()
        val cache = SemanticCache(HashingEmbedder(), store)
        var calls = 0
        val release = CompletableDeferred<Unit>()

        val callers = (1..50).map {
            async {
                cache.getOrPutStreaming(prompt) {
                    calls++
                    held(release, tokens)
                }.toList()
            }
        }
        // Everyone has looked up, missed and attached; the provider is holding its first chunk.
        runCurrent()
        assertEquals(1, calls, "fifty callers, one provider stream")

        release.complete(Unit)
        val received = callers.awaitAll()

        assertEquals(1, calls)
        for (caller in received) assertEquals(tokens, caller, "every caller sees the same whole sequence")
        assertEquals(1, store.size(), "and one entry is written, not fifty")
        assertEquals(0, cache.inFlightStreams(), "the registry does not leak the finished stream")
    }

    /**
     * The rule from M26, extended rather than bent. A truncated answer served confidently to fifty
     * people is fifty wrong answers rather than one, so the failure has to reach all of them and the
     * store has to stay empty.
     */
    @Test
    fun `a provider that throws partway fails every attached collector and writes nothing`() = runTest {
        val store = InMemoryStore()
        val cache = SemanticCache(HashingEmbedder(), store)
        val release = CompletableDeferred<Unit>()

        val callers = (1..10).map {
            async {
                val seen = mutableListOf<String>()
                val failure = assertFailsWith<IllegalStateException> {
                    cache.getOrPutStreaming(prompt) {
                        flow {
                            release.await()
                            emit("You ")
                            emit("can ")
                            error("the provider dropped the connection")
                        }
                    }.toList(seen)
                }
                seen to failure
            }
        }
        runCurrent()
        release.complete(Unit)
        val outcomes = callers.awaitAll()

        for ((seen, failure) in outcomes) {
            assertEquals(listOf("You ", "can "), seen, "what did arrive still reached every caller")
            assertTrue(failure.message.orEmpty().contains("dropped the connection"))
        }
        assertEquals(0, store.size(), "and none of it was kept")
        assertEquals(0, cache.inFlightStreams())
    }

    /**
     * The case that makes a shared buffer necessary rather than merely convenient. A caller who joins
     * after the third chunk has not asked for the tail of an answer, and handing them one would be a
     * wrong answer that looks like a short one.
     */
    @Test
    fun `a caller who joins halfway is replayed the beginning and then follows live`() = runTest {
        val cache = SemanticCache(HashingEmbedder(), InMemoryStore())
        val release = CompletableDeferred<Unit>()
        var calls = 0

        val first = async {
            cache.getOrPutStreaming(prompt) {
                calls++
                flow {
                    emit(tokens[0])
                    emit(tokens[1])
                    release.await()
                    emit(tokens[2])
                    emit(tokens[3])
                    emit(tokens[4])
                }
            }.toList()
        }
        runCurrent()

        val late = async { cache.getOrPutStreaming(prompt) { error("must not be called") }.toList() }
        runCurrent()

        release.complete(Unit)
        assertEquals(tokens, late.await(), "the late caller gets the beginning too, not the tail")
        assertEquals(tokens, first.await())
        assertEquals(1, calls)
    }

    /**
     * Dropping the stream because the caller who happened to open it walked away is a behaviour
     * nobody would choose deliberately. The producer belongs to the set of collectors, not to the
     * first of them.
     */
    @Test
    fun `the leader leaving does not take the answer away from the others`() = runTest {
        val store = InMemoryStore()
        val cache = SemanticCache(HashingEmbedder(), store)
        val release = CompletableDeferred<Unit>()

        val leader = async { cache.getOrPutStreaming(prompt) { held(release, tokens) }.toList() }
        val follower = async { cache.getOrPutStreaming(prompt) { error("must not be called") }.toList() }
        runCurrent()

        leader.cancel()
        release.complete(Unit)

        assertEquals(tokens, follower.await(), "the follower still gets the whole answer")
        assertEquals(1, store.size(), "and it is still written")
        assertEquals(0, cache.inFlightStreams())
    }

    /**
     * The other half of the same rule, and the one M26 already had: a lone caller who walks away does
     * stop the stream, and nothing is written. Coalescing changes who the stream belongs to, not
     * whether an answer nobody is reading gets cached.
     */
    @Test
    fun `a lone caller who leaves still stops the provider and writes nothing`() = runTest {
        val store = InMemoryStore()
        val cache = SemanticCache(HashingEmbedder(), store)
        val release = CompletableDeferred<Unit>()
        var finished = false

        val lone = async {
            cache.getOrPutStreaming(prompt) {
                flow {
                    release.await()
                    tokens.forEach { emit(it) }
                    finished = true
                }
            }.toList()
        }
        runCurrent()

        lone.cancel()
        release.complete(Unit)
        runCurrent()

        assertEquals(0, store.size())
        assertEquals(0, cache.inFlightStreams())
        assertTrue(!finished, "the provider was stopped rather than left to run for nobody")
    }

    @Test
    fun `coalesceConcurrentMisses false leaves every caller with its own provider stream`() = runTest {
        val cache = SemanticCache(
            HashingEmbedder(),
            InMemoryStore(),
            coalesceConcurrentMisses = false,
        )
        var calls = 0
        val release = CompletableDeferred<Unit>()

        val callers = (1..5).map {
            async {
                cache.getOrPutStreaming(prompt) {
                    calls++
                    held(release, tokens)
                }.toList()
            }
        }
        runCurrent()
        release.complete(Unit)
        val received = callers.awaitAll()

        assertEquals(5, calls, "the switch is the same one getOrPut honours, and it is off")
        for (caller in received) assertEquals(tokens, caller)
        assertEquals(0, cache.inFlightStreams())
    }

    /** A provider that emits nothing until [release], so every caller can attach first. */
    private fun held(release: CompletableDeferred<Unit>, chunks: List<String>): Flow<String> = flow {
        release.await()
        for (chunk in chunks) emit(chunk)
    }
}
