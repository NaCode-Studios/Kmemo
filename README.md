<p align="center">
  <img src="docs/kmemo-hero.png" alt="Kmemo, a Kotlin Multiplatform semantic cache for LLM calls with guards against false cache hits" width="100%">
</p>

# Kmemo

**A Kotlin Multiplatform semantic cache for LLM calls that refuses to serve you the wrong answer.**

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

> **Status — `2.1`, stable.** The cache and its eleven guards run on the JVM, iOS, macOS, Linux,
> Windows, JS and WasmJS. The Redis, Postgres and HNSW stores, the framework integrations, the threshold
> calibrator, the optional verifier and the observability stream are JVM-side and unchanged. Everything
> here is measured against labelled corpora with blind splits, one of which this project did not write.
> The public API is stable under SemVer; `2.1` is source compatible with `2.0` and needs a recompile,
> and every hop is listed in [docs/MIGRATION.md](docs/MIGRATION.md) with the policy in
> [STABILITY.md](STABILITY.md).

## Why Kmemo

- Eleven lexical checks catch near misses a threshold cannot: swapped numbers, units, entities, time
  references, negation, flipped antonyms, reversed comparisons, a different answer being asked for, and a
  clause one prompt adds that narrows the question.
  They run against four labelled corpora on every build, and the numbers are reported honestly,
  including the one from a corpus written by somebody else, years earlier, which is much the worst of
  them. See [Correctness, measured](#correctness-measured).
- The costs are asymmetric. A wrong rejection costs one API call; a wrong acceptance costs a wrong
  answer. The defaults follow that, and the guards abstain rather than guess.
- `ThresholdCalibrator` measures the right threshold for *your* embedding model. A value from a blog
  post was tuned for somebody else's.
- `Embedder` and `CacheStore` are one-method seams. Bring OpenAI, Cohere, Voyage or a local ONNX
  model; start in memory and move to a vector database without touching the match logic.
- Every operation is a `suspend` function, and `kmemo-core` declares `kotlinx-coroutines-core` as its
  only dependency, on every platform it targets rather than only the JVM.

## Installation

`kmemo-core` is a Kotlin Multiplatform library: JVM (17+), Android via the JVM artifact, iOS, macOS,
Linux, Windows, JS and WasmJS. The store adapters and framework integrations are JVM-only. Artifacts
are published to Maven Central under `io.github.nacode-studios`.

```kotlin
dependencies {
    implementation("io.github.nacode-studios:kmemo-core:2.1.0")
}
```

You also need an embedding source, which is any function from `String` to `FloatArray`. Multi-module
users can pin one version with the BOM (`io.github.nacode-studios:kmemo-bom`); every module past
`kmemo-core` is opt-in and never lands on the core classpath.

Every artifact published from `2.0.0` onwards carries a signed [SLSA build provenance](https://slsa.dev/)
attestation, so you can check that what you resolved was built by this repository's release workflow and
not by someone else. `1.0.0` and `1.1.0` shipped before this existed and have none.

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
SemanticCache(embedder)                                        // MatchGuards.standard()
SemanticCache(embedder, guards = MatchGuards.strict())         // trades hit rate for margin
SemanticCache(embedder, guards = MatchGuards.none())           // the naive similarity-only baseline
SemanticCache(embedder, guards = MatchGuards.responseAware())  // standard(), plus reads the cached answer
SemanticCache(embedder, guards = MatchGuards.longPrompts())    // for prompts carrying retrieved context
```

`longPrompts()` is `standard()` with one guard bounded, and it exists because of a measurement rather
than a preference: see [what prompt length does to the guards](#what-prompt-length-does-to-the-guards).
Reach for it when your prompts carry retrieved passages. On prompts of a dozen content words or fewer
it is byte-for-byte the same chain, so it costs nothing to pick if you are not sure.

Every guard in `standard()` compares two prompts, which leaves one near miss structurally invisible:
two honest paraphrases whose answers differ by something neither question contains. "What is the
capital gains tax rate when I sell a second home" against "…a primary residence" clears the whole
chain, and the cached answer opens "Gain on a second home is taxable in full". `responseAware()` adds
the one guard that reads that answer and refuses it when it names the word the query replaced.

It is opt-in because of how it is measured rather than how it performs. It refuses 14 of the 116
near-miss lookups `standard()` still serves on the blind corpora and **none** of the 164 paraphrase
lookups, moving the false-hit rate from 0.291 to 0.238 held-out and 0.324 to 0.299 on validation. But
those answers were written for the measurement, because no corpus of real paired answers exists to
harvest. That makes the number a regression check rather than the blind measurement every other guard is
held to, and
mixing the two under one default would quietly downgrade the evidence behind all of them.
[docs/CORPUS.md](docs/CORPUS.md) has the provenance rules.

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

Concurrent streaming misses are coalesced, on the same switch as `getOrPut`. Fifty callers asking one
new question together open one provider stream: the first opens it, the rest attach, are replayed
whatever has already arrived, and then follow it live. Attaching rather than waiting is the point,
since a streaming caller made to wait for the end is paying the latency they streamed to avoid.

The safety rules do not soften when a stream is shared. A provider that fails partway fails every
attached collector and writes nothing, because a truncated answer served confidently to fifty people is
fifty wrong answers rather than one. The provider is stopped when the **last** collector leaves rather
than the first, so a caller closing a tab no longer takes the answer away from everyone else, while a
lone caller who cancels still stops the stream and still caches nothing.

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

`ThresholdCalibrator` measures the right threshold, but it needs a labelled set, so the honest answer
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

`compute` still receives the bare prompt. The context is the cache's business, not the model's. It is
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
// the price list changed: every answer derived from it goes, and nothing else does
cache.invalidateByTag("price-list")
```

Tags are indexed by the store, so this is a query rather than a scan: a GIN index on Postgres, a
RediSearch `TAG` field on Redis. Keep them about the source of truth (`price-list`, `policy-2026`), not
about the request. A tag per prompt is a tag that never gets used. A `CacheStore` that does not index
tags **throws** rather than reporting `0`, because a caller who believes stale answers were dropped when
they were not is exactly the failure this library exists to avoid.

### The repeat that costs nothing

Retries, replayed agent loops, polling clients and test suites send the *same* prompt over and over, and
each one pays an embedding call. The exact-match layer answers a byte-for-byte repeat in the same scope
without embedding it or searching for it:

```kotlin
val cache = semanticCache(embedder) {
    exactCacheSize = 10_000
    exactCacheTtl = 5.minutes      // no longer than your store's TTL, see below
}
```

An identical prompt in the same scope is the same question, so this path runs no guards and adds no
false-hit risk by construction rather than by measurement. `stats().exactHits` says how much of your
traffic it caught; if it stays near zero, set the size back to `0` and reclaim the memory.

The one trade, stated plainly: answering without asking the store means not consulting the thing that
owns expiry and eviction, so `exactCacheTtl` must not outlast your store's TTL. Past that TTL nothing
stale is served. The remembered *embedding* is still reused, so the lookup goes through the ordinary
threshold-guards-verifier path with the network call already paid.

### Working the candidate set harder

Four opt-in pieces sit around the match path. Each is off by default, because each trades something,
and the trade is the thing worth reading.

```kotlin
val adaptive = AdaptiveThresholds(floor = 0.88, ceiling = 0.97)

val cache = semanticCache(embedder) {
    store = InMemoryStore(quantization = Quantization.INT8)  // cheaper scan, exact decisions
    reranker = MmrReranker()                                 // try a different candidate, not the same one twice
    deduplicateWrites = 0.98                                 // one answer, not six phrasings of it
    verifier = myVerifier
    adaptiveThresholds = adaptive                            // requires the verifier above
    listeners = listOf(adaptive)
}
```

**`MmrReranker`** reorders the candidates that cleared the threshold so each one the cache tries adds
something the last did not. On a cache that has been running a while the nearest entries are often
rephrasings of each other, and a `Verifier` costs a model call per candidate. Five paid calls that all
inspect what is effectively one entry is four wasted. It reorders and never rescores, and it runs
*after* the threshold filter, so it cannot make anything servable that the threshold refused.

**`Quantization`** compresses vectors for the scan only. Survivors are rescored against the
full-precision vectors, so every similarity that reaches a threshold, a guard or a verifier is exact,
and the worst a bad approximation can do is fail to surface a candidate, which is a miss costing one
API call.
`INT8` recovers everything an exact scan finds at four times oversampling; `BINARY` is a thirty-second
of the memory traffic and needs six times that shortlist to reach 99%. Measured at 64 and 1,536
dimensions in `M18MatchingTest`.

**`deduplicateWrites`** replaces an existing entry when a new one says the same thing, instead of
storing both. Only an entry that clears the similarity *and* passes every guard in both directions is
replaced. The write path can produce a false hit exactly as the read path can, and the same guards
stop it. Reported as an eviction with cause `NEAR_DUPLICATE`.

**`AdaptiveThresholds`** lets each scope's threshold follow its own traffic, and **throws at
construction without a verifier**. It moves the threshold down as well as up, and the only thing that
makes lowering safe is something above the threshold that can tell a right answer from a wrong one.
With a verifier in the loop the threshold stops being a correctness knob and becomes a cost knob: it
decides how many candidates reach the verifier, and that is a quantity the cache can honestly observe
about itself. Pass it as a listener too, or only as a listener, to watch what it *would* do first.

### What must never be cached

The cache stores prompts and responses verbatim. `CachePolicy` is the seam that vetoes a write for data
that must not be persisted at all. It is consulted once per write, on every write path including
`warm`:

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

About a third of near misses get past the guards on the blind splits: 25 of 86 on held-out, 33 of 102 on
validation. That residual is what an optional `Verifier` exists for. Typically a cheap model call, it
runs only on candidates that already cleared the threshold and every guard, and it fails closed: a
timeout or an error rejects rather than serving something unconfirmed. Cases like `deworm a puppy` vs
`an adult dog` or `boiling point of ethanol` vs `methanol` turn on world knowledge no lexical check has.

**How much of that residual a verifier actually stops is measured**, against a named reference
implementation, `sentence_transformers.CrossEncoder` over `cross-encoder/quora-distilroberta-base`,
serving at a duplicate probability of 0.5:

| Corpus | Residual the guards serve | The verifier stops | False-hit rate | Paraphrases kept |
| --- | --- | --- | --- | --- |
| held-out | 50 lookups | 40 (80%) | 0.291 → **0.058** | 0.881 → **0.452** |
| validation | 66 lookups | 51 (77%) | 0.324 → **0.074** | 0.882 → **0.686** |

It stops about four fifths of what the guards miss, and that is the answer to the question. It is also
expensive in the other direction, and this reference model is expensive unevenly: it keeps 69% of
validation's paraphrases and 45% of held-out's, which is heavier on software questions than on everyday
ones. **Your verifier is not this one.** What the table says is how much of the residual is reachable at
all by a model that reads the two prompts, and that a verifier is a hit-rate decision as much as a
correctness one, which is why it is a seam and not a default. Reproduce it with
[tools/verifier-catch-rate](tools/verifier-catch-rate); it is deliberately not a CI gate, because a
build that spends a model call per run is a build nobody keeps.

## Architecture

| Module | Contents |
| --- | --- |
| `kmemo-core` | `SemanticCache`, the `Embedder` and `CacheStore` seams, the guard chain, `InMemoryStore`, `ThresholdCalibrator`, resilience, the `CacheEvent` stream, with no provider or database knowledge. **Multiplatform**: JVM, iOS, macOS, Linux, Windows, JS, WasmJS. |
| `kmemo-store-redis` | A `CacheStore` on Redis (RediSearch KNN), for a cache shared across processes. |
| `kmemo-store-postgres` | A durable `CacheStore` on Postgres / pgvector. |
| `kmemo-store-hnsw` | An opt-in in-process approximate (HNSW) `CacheStore` that scales past the exact scan. |
| `kmemo-micrometer` / `kmemo-slf4j` | A Micrometer `MeterBinder` and an SLF4J logging listener. |
| `kmemo-spring-boot-starter` / `kmemo-spring-ai` | Auto-config for a `SemanticCache` bean, and a caching `Advisor` for Spring AI's `ChatClient`. |
| `kmemo-langchain4j` / `kmemo-ktor` | A caching `ChatModel` wrapper, and a Ktor server plugin. |
| `kmemo-guard-tck` | The conformance suite for a custom `MatchGuard`: the properties every guard must satisfy, the labelled corpora, and the confusion matrix the built-in guards are measured with. A test dependency. |
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

### Against a threshold-only cache, and against GPTCache

The claim is that Kmemo refuses near misses a similarity-only cache serves. Measured, on the blind
corpora, with the same inputs and no verifier in the loop:

| Corpus | Configuration | Precision | Recall | F1 | False-hit rate |
| --- | --- | --- | --- | --- | --- |
| held-out | threshold-only | 0.328 | 1.000 | 0.494 | **1.000** |
| held-out | `standard()` | 0.597 | 0.881 | 0.712 | **0.291** |
| held-out | `strict()` | 0.589 | 0.786 | 0.673 | **0.267** |
| held-out | GPTCache `OnnxModelEvaluation` | 0.513 | 0.476 | 0.494 | **0.221** |
| validation | threshold-only | 0.333 | 1.000 | 0.500 | **1.000** |
| validation | `standard()` | 0.577 | 0.882 | 0.698 | **0.324** |
| validation | `strict()` | 0.544 | 0.725 | 0.622 | **0.304** |
| validation | GPTCache `OnnxModelEvaluation` | 0.676 | 0.451 | 0.541 | **0.108** |

The false-hit rate is the share of near misses that were **served**, and it is the number the project
turns on. A threshold-only cache serves all of them: that is not a straw man, it is what every "add a
semantic cache" tutorial builds. `standard()` cuts it to roughly a third while keeping 88% of
paraphrases. `strict()` buys a little more margin and pays for it in recall, which is the trade stated
rather than hidden.

**Against GPTCache the result is a trade, not a win, and the table says so.** Its ONNX cross-encoder
serves *fewer* near misses than `standard()` on both splits. It is a stricter filter, not a weaker
one. It buys that by refusing more than half the genuine paraphrases it is shown, where `standard()`
keeps 88%, so the cache does roughly half the work. Kmemo's decision quality is higher by F1 on both
splits and its hit rate is close to double; GPTCache's false-hit rate is lower. Which of those you
should want depends on what a wrong answer costs you, and the arithmetic is below.

Two things about that row. GPTCache's *default* evaluator is `SearchDistanceEvaluation`, which scores
the vector distance the retrieval step already produced. That is the threshold-only row under another
name, and re-running it through GPTCache would measure the embedding model rather than the cache. And
retrieval is factored out of the whole table: every configuration is handed the same candidate pair and
asked only whether to serve it, which controls for the embedder more tightly than matching embedders
would.

Reproduce the Kmemo rows with:

```bash
./gradlew :kmemo-core:jvmTest --tests '*ComparativeBenchmarkTest*'
```

The GPTCache row is measured out of band, because GPTCache is a Python package that downloads a model
on first use and CI is a JVM build. See [tools/gptcache-comparison](tools/gptcache-comparison). The
recorded numbers carry the SHA-256 of the corpus files they were taken against, and the build fails if
a corpus changes without the harness being re-run.

Deliberately absent: cross-runtime latency. A JVM against Python wall-clock figure compares runtimes
while appearing to compare caches. Latency and throughput live in `kmemo-benchmarks`, across Kmemo's
own configurations.

### Is it worth it

The cache does this arithmetic itself. Declare what a call costs in a scope and `stats()` reports what
the hits in it did not cost, from the token counts on the entries that were actually served rather than
from an average applied to a hit count:

```kotlin
val cache = semanticCache(embedder) {
    prices["gpt-4o"] = TokenPrices(
        currency = "USD",
        perInputToken = 2.50 / 1_000_000,
        perOutputToken = 10.00 / 1_000_000,
    )
}

cache.getOrPut(prompt, scope = "gpt-4o", metadata = mapOf(
    "inputTokens" to usage.input.toString(),
    "outputTokens" to usage.output.toString(),
)) { llm.complete(it) }

cache.stats().savings["gpt-4o"]        // amount, currency, hits, tokens, and the prices behind them
```

The price comes from you. Kmemo ships no table of provider prices, because prices change weekly, a
vendored list is wrong the month after it ships, and a library that quietly reports the wrong saving is
worse than one that reports none. Only hits ever add to the figure, never writes: a write is a call
somebody made, not a call somebody avoided. `Savings` carries the prices it was computed from and the
count of hits whose entries had no token counts, so a total that is too small says why instead of
looking like a disappointing result. The same number reaches Micrometer as `kmemo.cache.saved`, tagged
by currency.

The rest of the arithmetic is not something the cache can do for you, because it needs an input no
library has. At **Q** queries a day, a hit rate of **H**, and a model call costing **C**:

```
saved per day      = Q × H × C
false hits per day = Q × H × (near-miss share of your traffic) × false-hit rate
```

At 100,000 queries a day, a 40% hit rate and $0.002 a call, the cache saves **$80 a day**. If 5% of
those hits are near misses rather than paraphrases, a threshold-only cache serves **2,000 wrong answers
a day** at a false-hit rate of 1.0; `standard()` serves about **650**, and `responseAware()` about
**600**.

The GPTCache row cannot be read off the false-hit rate alone, which is exactly why the saving sits in
the formula next to it. Facing the same 2,000 near-miss lookups, its evaluator serves about **220** of
them, a third of `standard()`. But it also refuses half the genuine paraphrases, so the hit rate that
produced the $80 falls with it, to about **$41 a day**. Roughly $39 a day of extra model calls, and a
transformer inference on every candidate, to avoid around 440 wrong answers. Whether that is worth it
is the same question as before, now with numbers in it.

Whether that trade is acceptable is not something a library can decide for you. It depends entirely on
what a wrong answer costs in your domain, which is the one input no benchmark can supply. What the
library can do is make sure you are looking at a measured number instead of an assumed one, and
[shadow mode](#calibrating-on-your-own-traffic-before-serving-anything) puts *your* traffic on that
axis before you serve a single cached answer.

### Correctness, measured

The guards are judged against four labelled corpora, three of them blind splits no guard was tuned
against, run as a CI regression gate on every build. **These figures are guard-only**: `CorpusTest` runs
`MatchGuards.standard()` with no `Verifier` in the loop, so they describe the free lexical layer rather
than the cache as a whole.

| Split | Written by | Near misses rejected | Paraphrases kept |
| --- | --- | --- | --- |
| tuned | this project, with the guards in view | in-sample, not evidence | in-sample, not evidence |
| held-out | this project, after the guards existed | 71% | 88% |
| validation | this project, blind | 68% | 88% |
| **external**, PAWS-Wiki `test` | **Google Research, 2019** | **14%** (647/4,464) | **79%** (2,807/3,536) |

None of them is 100%. The near misses that get through are what the optional `Verifier` is for; its
catch rate on them is measured [above](#verifying-what-lexical-guards-cannot-see) against a named
reference model, and is deliberately not folded into these numbers, which describe the free layer alone.

**The external row is much worse than the others, and it is here for that reason.** The first three
share a weakness no process can remove: the same person wrote the pairs and the guards, so they test
the near misses that were *thought of* rather than the ones that *exist*. PAWS is
[Paraphrase Adversaries from Word Scrambling](https://github.com/google-research-datasets/paws), built
in 2019 to see whether a model can separate a paraphrase from a near-paraphrase when word overlap is
deliberately high, which is the exact case a similarity threshold cannot handle, by people who had never
heard of this library. Two things explain the gap and neither shrinks it: a corpus built to defeat lexical
overlap is harder than one written from realistic traffic, and its pairs are declarative Wikipedia
sentences where the guards read prompts. A lower figure from a harder source is worth more than another
figure from the same source, so both ship. [docs/CORPUS.md](docs/CORPUS.md) has the full argument and
the per-guard breakdown; the external split is fetched rather than committed, so the licence stays with
the dataset.

Reproduce them with:

```bash
./gradlew :kmemo-core:jvmTest --tests '*CorpusTest*'
```

```bash
python tools/external-corpus/fetch.py && ./gradlew :kmemo-core:jvmTest --tests '*ExternalCorpusTest*'
```

### What prompt length does to the guards

Most of the gap between the external row and the other three is prompt length, not subject matter.
The four figures above each average over whatever lengths their split happens to contain, and the
splits contain very different ones: the three written here run from 19 to 85 characters, PAWS runs
from 32 to 214. Filing every pair by the mean length of its two prompts and measuring each band
separately says so directly. `substitution` rejects genuine paraphrases at 0% below 48 characters,
12% between 48 and 95, and 15% from 96 characters up. The validation split is 76% shorter than 48
characters and PAWS is 69% longer than 96, so the two averages that looked like 4% against 14% were
describing different lengths. In the one band where they overlap they read 10% and 12%.

The mechanism is the guard's own arithmetic. It rejects when two prompts have the same content words
in the same order and differ in exactly one position. One differing word out of five is a term
somebody swapped; one out of forty is a word somebody chose differently, and the guard cannot tell
those apart because it counts differing positions and never asks what share of the prompt one position
is. `MatchGuards.longPrompts()` is the bound that says so: past twelve content words it abstains. On
the external split that gives up 12 of 647 catches and keeps 125 more of 3,536 paraphrases, about ten
kept for each one lost, and it changes nothing at all on the three written splits because none of
their prompts is that long.

There is no cliff further up. Wrapping both sides of every PAWS pair in an identical retrieval
envelope, which leaves the difference between them untouched and only buries it, holds `substitution`
at 15% at 512, 1024 and 2048 characters. What does move is a different pair of guards, and no preset
can bound it away: `entity` goes from 6% to 10% and `direction` from 0% to 4%, because both treat the
first word of the text they are handed as a sentence opener and stop exempting it once a question has
passages in front of it. Fixing that needs a way to tell a guard where the question starts, which this
API does not have.

Two limits on all of the above. The envelope splits are derived from PAWS rather than written, so they
measure dilution and cannot be read as a fifth independent score. And **nothing here measures a
written prompt longer than 214 characters**: every long figure comes from wrapping short pairs, which
is why the report prints its empty bands instead of stopping at the last one with data in it.

```bash
python tools/external-corpus/fetch.py && ./gradlew :kmemo-core:jvmTest --tests '*GuardLengthTest*'
```

## Roadmap

**Shipped (`1.0.0`).** The guarded semantic cache, calibrated thresholds and an optional verifier;
Redis, Postgres and HNSW stores behind one `CacheStore` seam; resilience (embed-failure fall-back,
retries, negative caching, `warm`); observability (a `CacheEvent` stream, Micrometer, SLF4J);
ergonomics (typed and streaming `getOrPut`, a config DSL, a BOM); multilingual guard packs
(IT/ES/DE/FR); and Spring Boot, Spring AI, LangChain4j and Ktor integrations with a runnable
[`examples/`](examples) demo. The public API is stable under SemVer.

**Shipped (`1.1.0`).** A `CachePolicy` veto for data that must never be persisted, enforced at the
single choke point every write goes through; telemetry for an embed failure that degrades a `getOrPut`
to an uncached call, which previously left no trace; and an honesty pass on the published corpus
numbers, which are now labelled guard-only.

**Shipped (`2.0.0`).** The rest of Tier 6, and the two Post-1.0 milestones with it.

The cache moved off the JVM: `kmemo-core` and `InMemoryStore` build for iOS, macOS, Linux, Windows, JS
and WasmJS as well, at the cost of no new dependency. The lookup path grew an exact-match fast path,
conversation-aware keys, per-scope thresholds and shadow mode, which reports what your own traffic
*would* have matched at a range of thresholds without serving anything. Tag invalidation covers the case
a TTL only guesses at. Reranking, quantized retrieval with exact rescoring, write deduplication and
adaptive thresholds sit around the match path, each opt-in and each with its trade written down.

Three claims stopped being claims. GPTCache is measured on the same blind corpora, and the result is a
trade rather than a win: its cross-encoder serves fewer near misses than `standard()` and refuses more
than half the genuine paraphrases to do it. A named reference verifier is measured on the residual the
guards leave, stopping about four fifths of it at a real cost in hit rate. And a guard now reads the
cached *answer*, not only the two prompts, which is the near miss no prompt-side check can see.

**Shipped (`2.1.0`).** Tier 8: independent proof, and the path onto a production request.

The guards are now measured on a corpus this project did not write. PAWS, built by Google Research in
2019 to defeat exactly the lexical overlap a similarity threshold cannot see through, is a fourth split
under the blind rule, fetched rather than vendored and gated in CI. It scores the guards far lower than
the three internal splits do, and that number ships beside them with both halves of the explanation
attached, because a lower figure from a harder source is worth more than another figure from the same
source.

The embedding model is part of the key. An entry records which embedder wrote it and a lookup refuses
one written by a different model instead of scoring vectors from two spaces that do not share a
meaning, which was the false hit arriving through the one door nothing guarded. A streamed answer keeps
the chunks it arrived in, so a cache hit on a streaming path replays what the first caller saw rather
than one lump, with the decision about replay timing made explicitly in the API. And `kmemo-guard-tck`
puts the harness the eleven built-in guards are held to in a form a consumer can run, so a guard for a
domain nobody here understands can arrive with a measured number attached rather than with a claim.

**Next.** Nothing is scheduled. Tier 8 is complete, and the 2027 plan is written in December against
whatever the year's traffic and feedback have shown.

The plan lives on the [Kmemo board](https://github.com/orgs/NaCode-Studios/projects/5), one item per milestone with its exit
criterion, and every tier is a [milestone](https://github.com/NaCode-Studios/Kmemo/milestones) in this repository. See
[STABILITY.md](STABILITY.md) for the versioning and stability policy, and
[docs/MIGRATION.md](docs/MIGRATION.md) to move from `1.x` to `2.0`.

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
