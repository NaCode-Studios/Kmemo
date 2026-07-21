package dev.kmemo.benchmarks

import dev.kmemo.SemanticCache
import dev.kmemo.guard.MatchGuards
import dev.kmemo.store.InMemoryStore
import kotlinx.coroutines.runBlocking
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.infra.Blackhole
import java.util.concurrent.TimeUnit

/**
 * What a lookup costs, and where the cost goes.
 *
 * Run: `./gradlew :kmemo-benchmarks:jmh -Pjmh.includes=LookupBenchmark`
 *
 * Three questions:
 *  - **`lookupHit`**: end-to-end cost of a semantic hit (embed → exact scan → guard chain) as the cache
 *    grows. The scan is `O(n)`, so this is where the [InMemoryStore]'s honesty about its ceiling shows.
 *  - **`guards`** param (`standard` vs `none`): the price of the guard chain on its own — the false-hit
 *    protection that is the whole point, measured against turning it off.
 *  - **`plainExactGet`**: a plain [HashMap] exact-match lookup as the floor. It cannot catch a paraphrase
 *    at all, but it shows how much a semantic lookup costs over a dictionary get — the honest baseline.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
open class LookupBenchmark {

    @Param("1000", "10000", "100000")
    var size: Int = 0

    @Param("standard", "none")
    var guards: String = "standard"

    private lateinit var cache: SemanticCache
    private lateinit var exact: HashMap<String, String>
    private lateinit var query: String

    @Setup
    fun setup() {
        val embedder = BenchEmbedder(dim = 256)
        val guardSet = if (guards == "standard") MatchGuards.standard() else MatchGuards.none()
        cache = SemanticCache(
            embedder = embedder,
            store = InMemoryStore(maxEntries = size + 1),
            guards = guardSet,
            threshold = 0.5,
        )
        exact = HashMap(size)
        runBlocking {
            for (i in 0 until size) {
                val prompt = "benchmark prompt number $i asking about subject $i in detail"
                cache.put(prompt, "answer $i")
                exact[prompt] = "answer $i"
            }
        }
        // A prompt that is present, so lookupHit exercises the full path through to a served hit.
        query = "benchmark prompt number 0 asking about subject 0 in detail"
    }

    @Benchmark
    open fun lookupHit(blackhole: Blackhole) {
        blackhole.consume(runBlocking { cache.lookup(query) })
    }

    @Benchmark
    open fun plainExactGet(blackhole: Blackhole) {
        blackhole.consume(exact[query])
    }
}
