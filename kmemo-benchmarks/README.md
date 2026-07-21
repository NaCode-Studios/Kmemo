# kmemo-benchmarks

JMH microbenchmarks for Kmemo. **Not published** and not part of the binary-compatibility contract —
a developer tool for keeping the footprint/latency story honest with numbers.

## Running

```bash
# everything (slow — forks a JVM per benchmark)
./gradlew :kmemo-benchmarks:jmh

# one class
./gradlew :kmemo-benchmarks:jmh -Pjmh.includes=LookupBenchmark
```

The benchmarks compile on every `./gradlew check` (so they cannot rot against the core API) but are
only *run* on demand — running them in CI on shared runners produces numbers too noisy to gate on.

## What is measured

- **`LookupBenchmark`** — end-to-end cost of a semantic hit as the cache grows (`size` = 1k/10k/100k),
  the guard chain's share of it (`guards` = `standard` vs `none`), and a plain `HashMap` exact `get` as
  the honest floor a semantic lookup is paying over.
- **`SearchBenchmark`** — exact `InMemoryStore` scan vs the approximate `HnswStore` index at 10k and
  100k entries, quantifying the latency ANN buys and the exact scan's `O(n)` ceiling. (Recall is
  asserted in `kmemo-store-hnsw`'s own tests; this only measures speed.)

## Zero-boxing audit (M10)

Embeddings are `FloatArray` end-to-end — there is no `List<Float>` / `Array<Float>` anywhere in the
core, so vectors are never boxed on the lookup path. The dot product (`Vectors.dot`) accumulates in a
primitive `Double`. The token lists the guard chain builds (`Text`, `NumericGuard`) hold `String`s, not
numbers, so they box nothing float-related.

The one hidden box the audit found and fixed: `InMemoryStore.search` sorted candidates with
`sortByDescending { it.similarity }`, whose selector boxes a `Double` key per entry across the whole
scope. It now uses a primitive comparator (`b.similarity.compareTo(a.similarity)`), which compiles to a
`dcmpl` and allocates nothing.
