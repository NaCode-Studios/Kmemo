<p align="center">
  <img src="docs/kmemo-hero.png" alt="Kmemo, a semantic cache for LLM calls on Kotlin/JVM with guards against false cache hits" width="100%">
</p>

# Kmemo

**A semantic cache for LLM calls on Kotlin/JVM that refuses to serve you the wrong answer.**

[![CI](https://github.com/NaCode-Studios/Kmemo/actions/workflows/ci.yml/badge.svg)](https://github.com/NaCode-Studios/Kmemo/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.nacode-studios/kmemo-core?label=Maven%20Central&labelColor=080C18&color=5B9CFF)](https://central.sonatype.com/artifact/io.github.nacode-studios/kmemo-core)
[![License](https://img.shields.io/badge/license-Apache%202.0-1E2A45?labelColor=080C18)](LICENSE)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4-7F52FF?logo=kotlin&logoColor=white&labelColor=080C18)](https://kotlinlang.org)
[![API docs](https://img.shields.io/badge/API%20docs-Dokka-1E2A45?labelColor=080C18)](https://nacode-studios.github.io/Kmemo/)
[![Website](https://img.shields.io/badge/website-nacodestudios.it-1E2A45?labelColor=080C18)](https://nacodestudios.it/en/project/kmemo)

An exact-match cache misses "how do I reverse a list in Python" when it has already answered "python
list reverse". A semantic cache does not: it embeds the prompt, finds the closest one it has seen, and
replays that answer instead of calling the model. Fewer API calls, lower latency, same answers. Except
for the part where it hands back the wrong one.

```
"Convert 100 USD to EUR"
"Convert 250 USD to EUR"      cosine similarity: ~0.99
```

Every mainstream embedding model scores that pair around 0.99. No threshold separates it from a genuine
paraphrase, because on the similarity axis the near miss sits closer than most paraphrases do. So a
cache built on a threshold alone will tell someone that 250 dollars is 92 euros. Quickly, with no error
and nothing in the logs. Kmemo treats that as the main event: similarity is only the first filter, and
candidates that clear it are read as text by a chain of guards looking for concrete evidence the answers
must differ.

```kotlin
val cache = SemanticCache(
    embedder = Embedder { text -> openAi.embed(text) },
    store = InMemoryStore(maxEntries = 10_000, ttl = 1.hours),
)

val answer = cache.getOrPut(prompt) { llm.complete(it) }
```

Kmemo caches responses for embeddings you already have. `openAi.embed` above is your own embedding
source; Kmemo ships none and depends on no provider SDK.

> **See it end to end.** [`examples/`](examples) is a runnable demo (no API key needed) that shows a
> guard catching a live near miss, with a `docker-compose` for the Redis store.

> **Status — `1.1`, stable.** The cache, the ten guards, the in-memory / Redis / Postgres / HNSW stores,
> the threshold calibrator, an optional verifier, observability (events, Micrometer, SLF4J), a
> `CachePolicy` veto for data that must never be persisted, and Spring Boot / Spring AI / LangChain4j /
> Ktor integrations are implemented and measured against a labelled corpus. The public API is stable
> under SemVer; see [STABILITY.md](STABILITY.md).

## Why Kmemo

- Ten lexical checks catch near misses a threshold cannot: swapped numbers, units, entities, time
  references, negation, flipped antonyms, reversed comparisons, a different answer being asked for.
  They run against a labelled corpus on every build, and the numbers are reported honestly. See
  [Correctness, measured](#correctness-measured).
- The costs are asymmetric. A wrong rejection costs one API call; a wrong acceptance costs a wrong
  answer. The defaults follow that, and the guards abstain rather than guess.
- `ThresholdCalibrator` measures the right threshold for *your* embedding model. A value from a blog
  post was tuned for somebody else's.
- `Embedder` and `CacheStore` are one-method seams. Bring OpenAI, Cohere, Voyage or a local ONNX
  model; start in memory and move to a vector database without touching the match logic.
- Every operation is a `suspend` function, and `kmemo-core` declares `kotlinx-coroutines-core` as its
  only dependency.

## Installation

Requires JDK 17+. Artifacts are published to Maven Central under `io.github.nacode-studios`.

```kotlin
dependencies {
    implementation("io.github.nacode-studios:kmemo-core:1.1.0")
}
```

You also need an embedding source, which is any function from `String` to `FloatArray`. Multi-module
users can pin one version with the BOM (`io.github.nacode-studios:kmemo-bom`); every module past
`kmemo-core` is opt-in and never lands on the core classpath.

From the next release on, every published jar carries a signed [SLSA build provenance](https://slsa.dev/)
attestation, so you can check that the artifact you resolved was built by this repository's release
workflow and not by someone else. `1.0.0` and `1.1.0` shipped before this and have none.

```bash
gh attestation verify <jar> --repo NaCode-Studios/Kmemo
```

## Usage

### Caching a call

`getOrPut` embeds the prompt once and reuses the vector for both the lookup and the write:

```kotlin
val answer = cache.getOrPut(prompt) { llm.complete(it) }
```

Concurrent callers asking the same thing are coalesced: the first computes, the rest wait and are served
its answer.

### Reading a miss

A cache whose hit rate is 4% is untunable unless you know *why*, because the fix is opposite for a
threshold miss and a guard rejection. Every miss says which:

```kotlin
when (val result = cache.lookup(prompt)) {
    is CacheLookup.Hit  -> result.response
    is CacheLookup.Miss -> when (result.reason) {
        MissReason.BELOW_THRESHOLD   -> // traffic repeats less than you assumed, or threshold too tight
        MissReason.REJECTED_BY_GUARD -> // a guard found a concrete difference; result.detail says which
        else -> null
    }
}
```

`cache.explain(prompt)` is a read-only companion that shows every candidate with every guard's verdict.
Reach for it when a hit you expected did not happen.

### Scopes

Anything that changes what a correct answer looks like belongs in the scope: model, temperature, system
prompt, tenant, language. Leave one out and the cache serves one model's answer to another model's
caller.

```kotlin
cache.getOrPut(prompt, scope = "gpt-4o|t=0.0|v3") { llm.complete(it) }
```

### Choosing guards

```kotlin
SemanticCache(embedder)                                    // MatchGuards.standard()
SemanticCache(embedder, guards = MatchGuards.strict())     // trades hit rate for margin
SemanticCache(embedder, guards = MatchGuards.none())       // the naive similarity-only baseline
```

The guards work outside English too. Curated packs ship for Italian, Spanish, German and French, each
covered by a localized near-miss test set. Those sets are hand-written and in-sample, so they are a
regression check on the packs, not a blind measurement like the English corpora further down:

```kotlin
SemanticCache(embedder, guards = MatchGuards.standard(Locale.ITALIAN))
```

### Typed and streaming responses

Cache more than a `String`: a structured object through a `ResponseCodec`, or a streamed answer
replayed as a `Flow` on a hit, where only a stream that completes cleanly gets cached.

```kotlin
val weather: Weather = cache.getOrPut(prompt, weatherCodec) { llm.extractWeather(it) }

cache.getOrPutStreaming(prompt) { llm.completeStreaming(it) }.collect { print(it) }
```

### Observability

`stats()` gives lifetime counters (hit rate, per-reason and per-guard misses). For dashboards and logs,
subscribe to the event stream instead. It costs nothing when unused:

```kotlin
val metrics = KmemoMetrics().also { it.bindTo(meterRegistry) }   // kmemo-micrometer
val cache = SemanticCache(embedder, listeners = listOf(metrics, Slf4jCacheListener()))
```

### Resilience

The `Embedder` is a network call on every lookup, so own its failure. Fall back to the model when it is
down, retry transient blips, and warm the cache from an FAQ at startup:

```kotlin
val cache = SemanticCache(
    embedder = myEmbedder.retrying(maxAttempts = 4),
    embedFailurePolicy = EmbedFailurePolicy.FALL_BACK_TO_COMPUTE,
    negativeCacheSize = 10_000,
)
cache.warm(faqPairs.map { WarmEntry(it.question, it.answer) })
```

Falling back is never silent: every degraded call moves `stats().degradedLookups` and emits a
`CacheEvent.Degraded` naming the operation and the cause. A cache that has quietly become a
pass-through is otherwise the one failure mode with no telemetry pointing at it.

### Calibrating on your own traffic before serving anything

`ThresholdCalibrator` measures the right threshold, but it needs a labelled set — so the honest answer
to "what threshold should I use?" is "measure it, on data you first have to build", and that first step
is what stops teams putting a cache in front of production traffic.

Shadow mode removes it. The cache runs the full lookup against real traffic, **serves nothing**, and
reports what it *would* have decided at every threshold you name, in one pass:

```kotlin
val cache = semanticCache(embedder) {
    shadowThresholds = listOf(0.99, 0.97, 0.95, 0.90)
    listeners = listOf(CacheListener { e -> if (e is CacheEvent.Shadow) record(e.report) })
}
```

Every `getOrPut` computes as if there were no cache, so a false hit cannot reach a user while you are
still deciding. Writes still happen, because a shadow cache that never fills would report a miss for
everything. The output is your own precision and recall curve, against your own questions, rather than
somebody else's corpus. `kmemo-micrometer` exposes it as `kmemo.cache.shadow`, tagged by threshold and
outcome.

One threshold is also rarely right for a whole service. Override it per scope:

```kotlin
SemanticCache(embedder, threshold = 0.97, thresholds = mapOf("billing" to 0.995))
```

### Conversations

A cache that keys on the last turn alone will answer "what about the second one?" from a completely
different exchange. Pass the prior turns and they become part of the question:

```kotlin
cache.getOrPut("what about the second one", context = history) { llm.complete(it) }
```

`compute` still receives the bare prompt — the context is the cache's business, not the model's. It is
folded into the embedded text rather than into the scope, so two conversations that differ only in
phrasing can still match and the guards still read the whole thing.

### Invalidating when a fact changes

A TTL is a guess about when knowledge might go stale, not a way to act on knowing that it just did. The
pattern that needs no new API is to **version the scope** and clear the old one:

```kotlin
// pricing-v3.2 → pricing-v3.3 when the price list changes
cache.getOrPut(prompt, scope = "pricing-v3.3") { llm.complete(it) }
cache.clear("pricing-v3.2")
```

It cuts over atomically: the new scope is empty and starts filling, and nothing can serve the old
answers in the meantime. For a single entry proven wrong, `invalidate(id)` takes the id from the
`CacheLookup.Hit` that reported it.

Where a whole scope is too coarse, tag entries by the fact they depend on and drop exactly those:

```kotlin
cache.put(prompt, answer, tags = setOf("price-list"))
// the price list changed — every answer derived from it goes, nothing else does
cache.invalidateByTag("price-list")
```

Tags are indexed by the store, so this is a query rather than a scan: a GIN index on Postgres, a
RediSearch `TAG` field on Redis. Keep them about the source of truth (`price-list`, `policy-2026`), not
about the request — a tag per prompt is a tag that never gets used. A `CacheStore` that does not index
tags **throws** rather than reporting `0`, because a caller who believes stale answers were dropped when
they were not is exactly the failure this library exists to avoid.

### The repeat that costs nothing

Retries, replayed agent loops, polling clients and test suites send the *same* prompt over and over, and
each one pays an embedding call. The exact-match layer answers a byte-for-byte repeat in the same scope
without embedding it or searching for it:

```kotlin
val cache = semanticCache(embedder) {
    exactCacheSize = 10_000
    exactCacheTtl = 5.minutes      // no longer than your store's TTL — see below
}
```

An identical prompt in the same scope is the same question, so this path runs no guards and adds no
false-hit risk by construction rather than by measurement. `stats().exactHits` says how much of your
traffic it caught; if it stays near zero, set the size back to `0` and reclaim the memory.

The one trade, stated plainly: answering without asking the store means not consulting the thing that
owns expiry and eviction, so `exactCacheTtl` must not outlast your store's TTL. Past that TTL nothing
stale is served — the remembered *embedding* is still reused, so the lookup goes through the ordinary
threshold-guards-verifier path with the network call already paid.

### What must never be cached

The cache stores prompts and responses verbatim. `CachePolicy` is the seam that vetoes a write for data
that must not be persisted at all — consulted once per write, on every write path including `warm`:

```kotlin
val cache = semanticCache(embedder) {
    cachePolicy = CachePolicy { prompt, response, _ ->
        if (containsPii(prompt) || containsPii(response)) PolicyVerdict.Veto("pii") else PolicyVerdict.Store
    }
}
```

A vetoed write is a policy decision, not a failure: the call still returns its computed response, and
the veto surfaces as `CacheEvent.WriteVetoed` and `stats().writesVetoed` rather than being
indistinguishable from a miss. Kmemo ships the seam and no detector, for the same reason `Embedder` and
`Verifier` are seams. Isolation *between* tenants is a different problem and is already `scope`, which
the store TCK has enforced on every store since M4.

### Verifying what lexical guards cannot see

About a third of near misses get past the guards on the blind splits: 25 of 86 on held-out, 34 of 102 on
validation. That residual is what an optional `Verifier` exists for — typically a cheap model call, it
runs only on candidates that already cleared the threshold and every guard, and it fails closed: a
timeout or an error rejects rather than serving something unconfirmed. Cases like `deworm a puppy` vs
`an adult dog` or `boiling point of ethanol` vs `methanol` turn on world knowledge no lexical check has.
How much of the residual a verifier actually catches has not been measured yet.

## Architecture

| Module | Contents |
| --- | --- |
| `kmemo-core` | `SemanticCache`, the `Embedder` and `CacheStore` seams, the guard chain, `InMemoryStore`, `ThresholdCalibrator`, resilience, the `CacheEvent` stream, with no provider or database knowledge. |
| `kmemo-store-redis` | A `CacheStore` on Redis (RediSearch KNN), for a cache shared across processes. |
| `kmemo-store-postgres` | A durable `CacheStore` on Postgres / pgvector. |
| `kmemo-store-hnsw` | An opt-in in-process approximate (HNSW) `CacheStore` that scales past the exact scan. |
| `kmemo-micrometer` / `kmemo-slf4j` | A Micrometer `MeterBinder` and an SLF4J logging listener. |
| `kmemo-spring-boot-starter` / `kmemo-spring-ai` | Auto-config for a `SemanticCache` bean, and a caching `Advisor` for Spring AI's `ChatClient`. |
| `kmemo-langchain4j` / `kmemo-ktor` | A caching `ChatModel` wrapper, and a Ktor server plugin. |
| `kmemo-bom` | A `java-platform` BOM to pin one version. |

A lookup is decided in stages, each cheaper than the one it protects:

```
prompt ─► embed ─► nearest 5 in scope ─► similarity ≥ threshold?
                                              │ no ─► MISS (below_threshold)
                                              ▼ yes
                                         guards ─► reject? ─► try next candidate ─► MISS (rejected_by_guard)
                                              ▼ pass
                                         verifier (optional) ─► reject? ─► MISS (rejected_by_verifier)
                                              ▼ pass
                                             HIT
```

### Against a threshold-only cache

The claim is that Kmemo refuses near misses a similarity-only cache serves. Measured, on the blind
corpora, with the same inputs and no verifier in the loop:

| Corpus | Configuration | Precision | Recall | F1 | False-hit rate |
| --- | --- | --- | --- | --- | --- |
| held-out | threshold-only | 0.328 | 1.000 | 0.494 | **1.000** |
| held-out | `standard()` | 0.597 | 0.881 | 0.712 | **0.291** |
| held-out | `strict()` | 0.589 | 0.786 | 0.673 | **0.267** |
| validation | threshold-only | 0.333 | 1.000 | 0.500 | **1.000** |
| validation | `standard()` | 0.570 | 0.882 | 0.692 | **0.333** |
| validation | `strict()` | 0.536 | 0.725 | 0.617 | **0.314** |

The false-hit rate is the share of near misses that were **served**, and it is the number the project
turns on. A threshold-only cache serves all of them: that is not a straw man, it is what every "add a
semantic cache" tutorial builds. `standard()` cuts it to roughly a third while keeping 88% of
paraphrases. `strict()` buys a little more margin and pays for it in recall, which is the trade stated
rather than hidden.

Reproduce with:

```bash
./gradlew :kmemo-core:test --tests '*ComparativeBenchmarkTest*'
```

Not measured here: anything against GPTCache, the Python incumbent. That comparison needs a second
runtime in the harness and is still open. And deliberately no cross-runtime latency — a JVM against
Python wall-clock figure compares runtimes while appearing to compare caches. Latency and throughput
live in `kmemo-benchmarks`, across Kmemo's own configurations.

### Is it worth it

The arithmetic whoever approves this is already doing, with the measured numbers in it.

At **Q** queries a day, a hit rate of **H**, and a model call costing **C**:

```
saved per day     = Q × H × C
false hits per day = Q × H × (near-miss share of your traffic) × false-hit rate
```

At 100,000 queries a day, a 40% hit rate and $0.002 a call, the cache saves **$80 a day**. If 5% of
those hits are near misses rather than paraphrases, a threshold-only cache serves **2,000 wrong answers
a day** at a false-hit rate of 1.0; `standard()` serves about **660**.

Whether that trade is acceptable is not something a library can decide for you — it depends entirely on
what a wrong answer costs in your domain, which is the one input no benchmark can supply. What the
library can do is make sure you are looking at a measured number instead of an assumed one, and
[shadow mode](#calibrating-on-your-own-traffic-before-serving-anything) puts *your* traffic on that
axis before you serve a single cached answer.

### Correctness, measured

The guards are judged against three labelled corpora with blind splits that no guard was tuned against,
run as a CI regression gate on every build. **These figures are guard-only**: `CorpusTest` runs
`MatchGuards.standard()` with no `Verifier` in the loop, so they describe the free lexical layer rather
than the cache as a whole. On the validation split, near misses are rejected 67% of the time and
paraphrases are kept 88%; on the held-out split, 71% and 88%. Neither is 100%. The near misses that get
through are what the optional `Verifier` is for, but its catch rate on them is not measured here and is
not folded into these numbers. How the blind splits grow without getting contaminated is written up in
[docs/CORPUS.md](docs/CORPUS.md); reproduce the numbers with:

```bash
./gradlew :kmemo-core:test --tests '*CorpusTest*'
```

## Roadmap

**Shipped (`1.0.0`).** The guarded semantic cache, calibrated thresholds and an optional verifier;
Redis, Postgres and HNSW stores behind one `CacheStore` seam; resilience (embed-failure fall-back,
retries, negative caching, `warm`); observability (a `CacheEvent` stream, Micrometer, SLF4J);
ergonomics (typed and streaming `getOrPut`, a config DSL, a BOM); multilingual guard packs
(IT/ES/DE/FR); and Spring Boot, Spring AI, LangChain4j and Ktor integrations with a runnable
[`examples/`](examples) demo. The public API is stable under SemVer.

**Shipped (`1.1.0`).** The first slice of Tier 6: a `CachePolicy` veto for data that must never be
persisted, enforced at the single choke point every write goes through; telemetry for the one failure
mode that previously left no trace, an embed failure that degrades a `getOrPut` to an uncached call; and
an honesty pass on the published corpus numbers, which are now labelled guard-only.

**Next.** An exact-match fast path that answers a repeated prompt without embedding it at all; shadow
mode, which runs the full lookup against your own traffic and reports what *would* have matched at a
range of thresholds without serving anything, so the threshold is calibrated before a single cached
answer goes out; invalidation beyond TTL, for when the fact behind an answer changes; and a comparative
false-hit benchmark against GPTCache and a threshold-only baseline.

**Later.** Kotlin Multiplatform (`commonMain`) for on-device, browser and edge caches, and
advanced matching (reranking/MMR, near-duplicate eviction, quantized candidates with exact rescoring,
adaptive thresholds).

The plan lives on the [Kmemo board](https://github.com/orgs/NaCode-Studios/projects/5) — one item per milestone, each with its exit
criterion — and every tier is a [milestone](https://github.com/NaCode-Studios/Kmemo/milestones) in this repository. See
[STABILITY.md](STABILITY.md) for the versioning and stability policy.

## Building and testing

```bash
./gradlew build         # compile, run unit tests, lint (ktlint + detekt), verify public API
./gradlew apiCheck      # check the tracked public API in *.api
./gradlew apiDump       # regenerate *.api after an intentional public-API change
./gradlew ktlintFormat  # auto-fix formatting before committing
```

Unit tests need no external services. The store integration tests spin up real backends with
[Testcontainers](https://testcontainers.com) and are skipped automatically when Docker is unavailable.

## Contributing

Contributions are welcome; see [CONTRIBUTING.md](CONTRIBUTING.md). Please run `./gradlew build` before
opening a pull request, and if you change the public API, run `./gradlew apiDump` and commit the
updated `*.api` files.

## License

Licensed under the [Apache License 2.0](LICENSE). Brand assets (wordmark, symbol, and the colour and
type tokens) are in [`docs/brand`](docs/brand).

## Sponsor

If Kmemo is useful to you, consider [sponsoring NaCode Studios](https://github.com/sponsors/NaCode-Studios).
