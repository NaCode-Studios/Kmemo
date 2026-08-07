package dev.kmemo.internal

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * One provider stream being watched by any number of collectors.
 *
 * A blocking miss produces one value that arrives once and can be handed to everyone waiting. A
 * streaming miss produces a sequence that arrives over seconds, and a caller who joins halfway has
 * missed the beginning. Handing them the tail is wrong and making them wait for the end throws away
 * the latency they came for, so this keeps what has already arrived and replays it before continuing
 * live. The buffer is the price, and it is bounded by the length of one answer.
 *
 * **The producer never runs ahead of the fastest collector.** After each chunk it waits until one of
 * them has passed it downstream, which is what an uncoalesced collection did implicitly by driving the
 * provider from the caller's own coroutine. Keeping it makes every rule M26 wrote still hold to the
 * letter: a caller who walks away stops the provider rather than leaving a complete answer to be
 * written behind their back, and a stream is only ever as far ahead as one chunk. The slowest
 * collector never holds anyone up, because the wait is on *any* of them and the rest read from the
 * buffer at their own pace.
 *
 * Terminal state is remembered, not just signalled. A collector that attaches after the stream has
 * finished still gets every chunk and then the same ending, which makes the window between the
 * producer finishing and the registry dropping it harmless rather than a second provider call.
 */
internal class SharedStream {

    private val mutex = Mutex()
    private val chunks = ArrayList<String>()

    /** Set exactly once. `null` while live. */
    private var ended: Ended? = null

    /** Collectors parked because there is nothing new and no ending yet. */
    private val waiting = ArrayList<CompletableDeferred<Unit>>()

    /** The producer parked because no collector has passed the last chunk downstream yet. */
    private var gate: CompletableDeferred<Unit>? = null

    /** How far the furthest-ahead collector has got, in chunks actually emitted downstream. */
    private var delivered = 0

    private var collectors = 0

    /** The coroutine collecting the provider, or `null` until the leader starts one. */
    private var producer: Job? = null

    private class Ended(val cause: Throwable?) {
        companion object {
            val NORMAL = Ended(null)
        }
    }

    suspend fun attach(): Int = mutex.withLock { ++collectors }

    /**
     * Detaches one collector and reports how many are left.
     *
     * Releases the producer when the last one goes, so a producer parked on [gate] resumes and
     * observes its own cancellation instead of waiting for a reader that will never come back.
     */
    suspend fun detach(): Int = mutex.withLock {
        val left = --collectors
        if (left == 0) openGate()
        left
    }

    fun leadWith(job: Job) {
        producer = job
    }

    /**
     * Stops the provider.
     *
     * Called when the **last** collector leaves, never when the first one does. A single caller who
     * walks away still cancels the stream, which is the rule M26 set and this does not bend: a
     * cancelled stream is a failed stream and is not written. What changes is that the caller who
     * happened to open it is no longer special, so fifty people do not lose an answer because one of
     * them closed a tab.
     */
    fun cancelProducer() {
        producer?.cancel()
    }

    /** Publishes [chunk], then waits for a collector to pass it on. */
    suspend fun append(chunk: String) {
        val parked = mutex.withLock {
            chunks += chunk
            wakeCollectors()
            if (collectors > 0 && delivered < chunks.size) {
                CompletableDeferred<Unit>().also { gate = it }
            } else {
                null
            }
        }
        parked?.await()
    }

    suspend fun complete() {
        mutex.withLock {
            if (ended == null) ended = Ended.NORMAL
            wakeCollectors()
        }
    }

    suspend fun fail(cause: Throwable) {
        mutex.withLock {
            if (ended == null) ended = Ended(cause)
            wakeCollectors()
        }
    }

    /**
     * Replays what has arrived, then follows the stream live until it ends.
     *
     * Rethrows whatever the provider threw, to every collector attached, so a failure reaches all of
     * them rather than only the one that happened to open the stream. They share the throwable
     * instance: the failure is one event, and copying it per collector would invent stack traces
     * nobody executed.
     */
    suspend fun collect(emit: suspend (String) -> Unit) {
        var next = 0
        while (true) {
            var parked: CompletableDeferred<Unit>? = null
            var batch: List<String> = emptyList()
            var finish: Ended? = null
            mutex.withLock {
                if (next < chunks.size) {
                    batch = ArrayList(chunks.subList(next, chunks.size))
                    next = chunks.size
                }
                finish = ended
                if (batch.isEmpty() && finish == null) {
                    parked = CompletableDeferred<Unit>().also { waiting += it }
                }
            }

            for (chunk in batch) emit(chunk)

            if (batch.isNotEmpty()) {
                // After the emit, not before: the producer is being held until this collector has
                // actually passed the chunk downstream, which is the whole point of holding it.
                mutex.withLock {
                    if (next > delivered) delivered = next
                    if (delivered >= chunks.size) openGate()
                }
                continue
            }

            val ending = finish
            if (ending != null) {
                ending.cause?.let { throw it }
                return
            }
            parked?.await()
        }
    }

    /** Must be called with [mutex] held. */
    private fun wakeCollectors() {
        for (waiter in waiting) waiter.complete(Unit)
        waiting.clear()
    }

    /** Must be called with [mutex] held. */
    private fun openGate() {
        gate?.complete(Unit)
        gate = null
    }
}

/**
 * The registry of in-flight streams, one entry per (scope, prompt) somebody is currently streaming.
 *
 * The same shape as [KeyedMutex] and for the same reason: entries are reference counted and removed
 * when the last collector leaves, because a plain map keyed on prompt text leaks one entry per
 * distinct prompt and seeing a great many distinct prompts is the job.
 */
internal class StreamCoalescer {

    private val guard = Mutex()
    private val inFlight = HashMap<String, SharedStream>()

    class Attachment(val stream: SharedStream, val leader: Boolean)

    /**
     * Attaches to the stream already running for [key], or creates one and reports that the caller is
     * now responsible for producing it.
     */
    suspend fun joinOrLead(key: String): Attachment = guard.withLock {
        val existing = inFlight[key]
        if (existing != null) {
            existing.attach()
            Attachment(existing, leader = false)
        } else {
            val fresh = SharedStream()
            fresh.attach()
            inFlight[key] = fresh
            Attachment(fresh, leader = true)
        }
    }

    /** Detaches one collector, cancelling the provider when it was the last one. */
    suspend fun leave(key: String, stream: SharedStream) {
        withContext(NonCancellable) {
            guard.withLock {
                if (stream.detach() == 0) {
                    if (inFlight[key] === stream) inFlight.remove(key)
                    stream.cancelProducer()
                }
            }
        }
    }

    /**
     * Drops [key] once its provider has finished, so the next caller starts a fresh one.
     *
     * Only when the entry is still this stream. A leader that was cancelled has already been removed
     * and possibly replaced, and removing a live successor here would let two providers run for one
     * prompt, which is the thing this class exists to prevent.
     */
    suspend fun finished(key: String, stream: SharedStream) {
        withContext(NonCancellable) {
            guard.withLock {
                if (inFlight[key] === stream) inFlight.remove(key)
            }
        }
    }

    /** In-flight streams. For tests: it must return to zero. */
    suspend fun size(): Int = guard.withLock { inFlight.size }
}
