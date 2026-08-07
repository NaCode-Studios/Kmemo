package dev.kmemo

import dev.kmemo.internal.KeyedMutex
import dev.kmemo.internal.SharedStream
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.jetbrains.lincheck.datastructures.IntGen
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions
import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.lincheck.datastructures.Param
import org.jetbrains.lincheck.datastructures.Validate
import kotlin.test.Test

/**
 * M49: the concurrency claims, explored rather than asserted.
 *
 * Four promises in this library are load-bearing and all four were tested with `runTest` and a
 * `StandardTestDispatcher`, which is deterministic and therefore explores exactly one interleaving:
 * the one the scheduler happens to produce. That is why those tests are reliable, and it is also why
 * they prove much less than they appear to. A race that needs two coroutines to resume in the other
 * order is one those tests will never see, and the failure it produces in production is a truncated
 * answer served confidently or an entry written twice.
 *
 * Lincheck explores interleavings instead. It is JVM-only, which is a real limitation and an
 * acceptable one: everything it checks is `commonMain` code, so a data race here is a data race on
 * every target even though the exploration runs on one host.
 *
 * ### How the invariants are expressed, and why not as a sequential specification
 *
 * Lincheck's usual mode compares a concurrent structure against a sequential one and calls the
 * difference a bug. None of these three is linearizable in that sense, and pretending otherwise
 * would test the specification rather than the code. What a collector sees from a `SharedStream`
 * legitimately depends on when it attached; a write that falls through a full queue legitimately
 * arrives before one that queued.
 *
 * So every operation returns the same value whatever happens, and **the invariants are checked where
 * they are true**: inside the operation for the ones that hold at every moment, and in a
 * [Validate] function for the ones that hold only once the execution is quiescent. A schedule that
 * breaks one throws, and Lincheck reports the interleaving that produced it.
 *
 * ### What a passing run means
 *
 * Evidence rather than proof. The search is bounded by the settings below, and a bug needing more
 * threads or more operations than a scenario allows is still out there. Failing would be the more
 * valuable outcome; the result is recorded either way in `docs/MEASUREMENTS.md`.
 */
class ConcurrencyModelTest {

    /**
     * `KeyedMutex` promises two things and the second is the one that leaks.
     *
     * **Serialization per key**: two coroutines holding the same key are never inside the block at
     * once, which is what makes fifty callers into one model call. **No leak**: the map returns to
     * empty once the last waiter leaves, because a mutex per distinct prompt, in a cache whose job is
     * to see a great many distinct prompts, is an unbounded map.
     *
     * The second is where an interleaving can hurt. The count is incremented under one lock and
     * decremented under the same lock in a `finally`, and the entry is removed at zero. A schedule in
     * which a late arrival increments between another coroutine's decrement and its removal would
     * leave the newcomer holding a mutex nobody else can find.
     */
    @Test
    fun `the keyed mutex serializes per key and leaves nothing behind`() {
        ModelCheckingOptions()
            .iterations(ITERATIONS)
            .invocationsPerIteration(INVOCATIONS)
            .actorsBefore(0)
            .actorsAfter(0)
            .threads(3)
            .actorsPerThread(2)
            .check(KeyedMutexScenario::class)
    }

    /**
     * `SharedStream` carries the strongest promise here, because a truncated answer served to fifty
     * people is fifty wrong answers rather than one.
     *
     * Its state is a chunk buffer, a terminal marker, a collector count and two wait queues, mutated
     * from a producer coroutine and any number of collector coroutines. Two invariants are checked.
     * **A collector sees a contiguous run of what was appended, in order**, so nothing is skipped,
     * duplicated or reordered. And **the producer never runs more than one chunk ahead of the
     * fastest collector**, which is the property that keeps a walked-away caller from leaving a
     * complete answer to be written behind their back.
     */
    @Test
    fun `a shared stream replays a contiguous run of chunks in order`() {
        ModelCheckingOptions()
            .iterations(ITERATIONS)
            .invocationsPerIteration(INVOCATIONS)
            .actorsBefore(0)
            .actorsAfter(0)
            .threads(2)
            .actorsPerThread(3)
            .check(SharedStreamScenario::class)
    }

    /**
     * The write-behind queue promises that **a write is never lost**, only rarely reordered under
     * saturation.
     *
     * The mechanism is one line in `SemanticCache`: `if (channel.trySend(entry).isFailure)
     * putEntry(entry)`. Under saturation the caller writes synchronously instead of queueing, which
     * is what turns a full queue into a reordering rather than a loss. The interleaving that would
     * break it is a `trySend` whose failure is observed against a queue that had already drained, so
     * the entry is written twice, or one whose success is observed against a queue that then dropped
     * it.
     */
    @Test
    fun `the write-behind queue loses nothing and duplicates nothing when it saturates`() {
        ModelCheckingOptions()
            .iterations(ITERATIONS)
            .invocationsPerIteration(INVOCATIONS)
            .actorsBefore(0)
            .actorsAfter(0)
            .threads(3)
            .actorsPerThread(2)
            .check(WriteQueueScenario::class)
    }

    // ---- the scenarios ------------------------------------------------------------------------

    /**
     * Two keys, so both the contended and the uncontended path are explored.
     *
     * A counter per key rather than a map keyed by name, because a shared map mutated under two
     * different key locks is a race in the scenario rather than in the thing being checked, and a
     * checker that finds one of those has found nothing.
     */
    @Suppress("unused")
    class KeyedMutexScenario {
        private val keyed = KeyedMutex()
        private var insideA = 0
        private var insideB = 0

        @Operation
        suspend fun useA(): String = keyed.withKeyLock("a") {
            insideA++
            check(insideA == 1) { "two coroutines held 'a' at once" }
            insideA--
            "ok"
        }

        @Operation
        suspend fun useB(): String = keyed.withKeyLock("b") {
            insideB++
            check(insideB == 1) { "two coroutines held 'b' at once" }
            insideB--
            "ok"
        }
    }

    /**
     * One stream, appended to and collected from concurrently.
     *
     * The collector's own invariant is checked inside the operation rather than compared afterwards,
     * because what it should have seen depends on when it attached and only the collector knows that.
     *
     * **Cancellation on suspension is off**, and that is a statement about the scenario rather than
     * about the stream. A collector parks when there is nothing new and no ending, and a schedule in
     * which no `complete` follows leaves it parked for good: the checker's cancellation path then has
     * a suspended operation with no continuation to resume and fails inside its own verifier. What a
     * cancelled collector does is already covered, by the detach path and by the tests that assert a
     * walked-away caller stops the producer.
     */
    @Suppress("unused")
    class SharedStreamScenario {
        private val stream = SharedStream()
        private val views = ArrayList<List<String>>()
        private var nextChunk = 0

        @Operation(cancellableOnSuspension = false)
        suspend fun append(@Param(gen = IntGen::class, conf = "1:2") value: Int): String {
            val chunk = synchronized(views) { "c$value#${nextChunk++}" }
            stream.append(chunk)
            return "ok"
        }

        @Operation(cancellableOnSuspension = false)
        suspend fun complete(): String {
            stream.complete()
            return "ok"
        }

        @Operation(cancellableOnSuspension = false)
        suspend fun collectAll(): String {
            stream.attach()
            val seen = ArrayList<String>()
            try {
                stream.collect { seen += it }
            } finally {
                stream.detach()
            }
            check(seen.size == seen.toSet().size) { "a collector saw a chunk twice: $seen" }
            synchronized(views) { views += seen }
            return "ok"
        }

        /**
         * Every collector must agree about the order, which is the property that matters when one
         * answer is being replayed to many people.
         *
         * The scenario deliberately does not keep its own record of the append order to compare
         * against. Two appends can be recorded here in one order and reach the stream in the other,
         * so such a record would describe the test rather than the stream, and a checker that fails
         * on that has found a bug in its own bookkeeping. What holds regardless is that no two
         * collectors may disagree about which of two chunks came first, and that nobody sees a chunk
         * twice.
         */
        @Validate
        fun everyCollectorAgreesOnTheOrder() {
            val snapshot = synchronized(views) { ArrayList(views) }
            for (left in snapshot) {
                for (right in snapshot) {
                    val shared = left.filter { it in right }
                    val other = right.filter { it in left }
                    check(shared == other) {
                        "two collectors disagree about the order: $shared against $other"
                    }
                }
            }
        }
    }

    /** The write-behind decision, isolated from the cache: queue when there is room, else write through. */
    @Suppress("unused")
    class WriteQueueScenario {
        private val channel = Channel<Int>(CAPACITY)
        private val offered = ArrayList<Int>()
        private val synchronous = ArrayList<Int>()
        private var next = 0

        @Operation
        fun enqueue(): String {
            val value = synchronized(offered) { next++.also { offered += it } }
            if (channel.trySend(value).isFailure) {
                synchronized(synchronous) { synchronous += value }
            }
            return "ok"
        }

        /** Drained plus written-through must be exactly what was offered, once and only once. */
        @Validate
        fun nothingLostOrDuplicated() {
            val drained = ArrayList<Int>()
            while (true) {
                val received = channel.tryReceive()
                if (received.isFailure) break
                drained += received.getOrThrow()
            }
            val written = (drained + synchronous).sorted()
            check(written == offered.sorted()) {
                "offered $offered, wrote $written: the queue lost or duplicated a write"
            }
        }
    }

    /**
     * The leak `KeyedMutex` exists to prevent, checked where it can be: after the fact, on real
     * threads.
     *
     * It is not a Lincheck operation because the invariant is only true once nobody is waiting, and a
     * validation function cannot block on a suspending call from inside the checker's managed
     * threads. So it runs here instead, with enough concurrent keys and repeats to exercise the
     * decrement-then-remove window that the reference count exists to close.
     */
    @Test
    fun `the keyed mutex removes every entry once its last waiter leaves`() = runBlocking {
        val keyed = KeyedMutex()
        repeat(LEAK_ROUNDS) {
            coroutineScope {
                repeat(LEAK_CONCURRENCY) { worker ->
                    launch(Dispatchers.Default) {
                        keyed.withKeyLock("key-${worker % LEAK_KEYS}") { yield() }
                    }
                }
            }
            check(keyed.size() == 0) { "round $it left ${keyed.size()} keyed mutexes behind" }
        }
    }

    private companion object {
        /**
         * Deliberately modest, because this runs on every build.
         *
         * A model checker is worth what somebody actually pays for, and a suite nobody runs finds
         * nothing. These settings explore thousands of schedules per scenario in a few seconds; a
         * longer search belongs in a scheduled job rather than in a gate that has to stay fast enough
         * to run on every push.
         */
        private const val ITERATIONS = 15
        private const val INVOCATIONS = 300
        private const val CAPACITY = 2
        private const val LEAK_ROUNDS = 100
        private const val LEAK_CONCURRENCY = 32
        private const val LEAK_KEYS = 4
    }
}
