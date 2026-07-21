package dev.kmemo.benchmarks

import dev.kmemo.CacheEntry
import dev.kmemo.CacheStore
import dev.kmemo.store.InMemoryStore
import dev.kmemo.store.hnsw.HnswStore
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
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Exact scan versus the approximate (HNSW) index, at the store level.
 *
 * Run: `./gradlew :kmemo-benchmarks:jmh -Pjmh.includes=SearchBenchmark`
 *
 * The [InMemoryStore] scan is `O(n)` and exact; [HnswStore] is sub-linear and approximate. This
 * benchmark quantifies the trade the M7 work is about — latency won against recall given up — so the
 * README's "move to ANN past ~100k entries" advice rests on a number, not a guess. Recall itself is
 * asserted in `kmemo-store-hnsw`'s own tests; here we only measure speed.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
open class SearchBenchmark {

    @Param("10000", "100000")
    var size: Int = 0

    @Param("exact", "hnsw")
    var store: String = "exact"

    private lateinit var cacheStore: CacheStore
    private lateinit var query: FloatArray

    @Setup
    fun setup() {
        val embedder = BenchEmbedder(dim = 256)
        cacheStore = if (store == "exact") InMemoryStore(maxEntries = size + 1) else HnswStore()
        runBlocking {
            for (i in 0 until size) {
                val embedding = embedder.embed("benchmark prompt number $i asking about subject $i in detail")
                cacheStore.put(
                    CacheEntry(
                        id = i.toString(),
                        scope = "default",
                        prompt = "benchmark prompt number $i asking about subject $i in detail",
                        response = "answer $i",
                        embedding = embedding,
                        createdAt = Instant.EPOCH,
                    ),
                )
            }
            query = embedder.embed("benchmark prompt number 0 asking about subject 0 in detail")
        }
    }

    @Benchmark
    open fun search(blackhole: Blackhole) {
        blackhole.consume(runBlocking { cacheStore.search("default", query, 5) })
    }
}
