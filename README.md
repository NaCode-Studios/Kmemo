# Kmemo

**A semantic cache for LLM calls on Kotlin/JVM — that refuses to serve you the wrong answer.**

[![CI](https://github.com/NaCode-Studios/Kmemo/actions/workflows/ci.yml/badge.svg)](https://github.com/NaCode-Studios/Kmemo/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.nacode-studios/kmemo-core?label=Maven%20Central)](https://central.sonatype.com/artifact/io.github.nacode-studios/kmemo-core)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4-7F52FF.svg?logo=kotlin)](https://kotlinlang.org)
[![API docs](https://img.shields.io/badge/API%20docs-Dokka-blue.svg)](https://nacode-studios.github.io/Kmemo/)

An exact-match cache misses "how do I reverse a list in Python" when it has already answered "python
list reverse". A semantic cache does not: it embeds the prompt, finds the closest one it has seen,
and replays that answer instead of calling the model. Fewer API calls, lower latency, same answers —
except for the part where it hands back the wrong one.

```
"Convert 100 USD to EUR"
"Convert 250 USD to EUR"      cosine similarity: ~0.99
```

Every mainstream embedding model scores that pair around 0.99. No threshold separates it from a
genuine paraphrase, because on the similarity axis **the near miss is closer than most paraphrases
are**. So a cache built on a threshold alone will tell someone that 250 dollars is 92 euros —
quickly, with no error and nothing in the logs. Kmemo treats that as the main event: similarity is
only the first filter, and candidates that clear it are read as text by a chain of guards looking for
concrete evidence the answers must differ.

```kotlin
val cache = SemanticCache(
    embedder = Embedder { text -> openAi.embed(text) },
    store = InMemoryStore(maxEntries = 10_000, ttl = 1.hours),
)

val answer = cache.getOrPut(prompt) { llm.complete(it) }
```

Kmemo caches responses for embeddings you already have — `openAi.embed` above is your own embedding
source; Kmemo ships none and depends on no provider SDK.

> **Status — `0.3`, early development.** Published to Maven Central; the cache, the ten guards, the
> in-memory store, the threshold calibrator and an optional verifier — plus opt-in Redis, Postgres and
> HNSW stores — implemented and measured against a labelled corpus. The API may change before `1.0`.

## Why Kmemo

- **Guards against false hits** — ten lexical checks catch near misses a threshold cannot: swapped
  numbers, units, entities, time references, negation, flipped antonyms, reversed comparisons, a
  different answer being asked for. They run against a labelled corpus on every build, and the
  numbers are reported honestly — see [Correctness, measured](#correctness-measured) for what they
  do and do not cover.
- **Safety by default** — the costs are asymmetric. A wrong rejection costs one API call, the exact
  call you would have made without a cache. A wrong acceptance costs a wrong answer. Defaults follow
  that, and guards abstain rather than guess.
- **Calibrated, not copied** — `ThresholdCalibrator` measures the right threshold for *your*
  embedding model. A value from a blog post is tuned to somebody else's.
- **Provider-agnostic** — `Embedder` and `CacheStore` are one-method seams. Bring OpenAI, Cohere,
  Voyage or a local ONNX model; start in memory and move to a vector database without touching
  match logic.
- **Coroutine-first, small footprint** — every operation is a `suspend` function, and the published
  artifact declares `kotlinx-coroutines-core` as its only dependency (which brings the Kotlin
  stdlib with it, as every Kotlin library does).
- **Diagnosable** — every miss reports *why*. A cache with a 4% hit rate is untunable unless you know
  whether prompts land below the threshold or are vetoed by a guard; the fix is opposite in each case.

## Installation

Requires **JDK 17+**. Artifacts are published to Maven Central under `io.github.nacode-studios`.

```kotlin
dependencies {
    implementation("io.github.nacode-studios:kmemo-core:0.6.0")
}
```

You also need an embedding source. Any function from `String` to `FloatArray` will do.

## Usage

### Caching a call

```kotlin
val answer = cache.getOrPut(prompt) { llm.complete(it) }
```

`getOrPut` embeds the prompt **once** and reuses the vector for both the lookup and the write. Doing
it by hand with `get` then `put` costs two embedding calls on every miss.

Concurrent callers asking the same thing are coalesced: the first computes, the rest wait and are
served its answer. A cold cache under load is otherwise worse than no cache — fifty requests for one
prompt arrive together, all miss, and all pay.

### Reading a miss

```kotlin
when (val result = cache.lookup(prompt)) {
    is CacheLookup.Hit -> log.info("hit at {} (age {})", result.similarity, result.age)
    is CacheLookup.Miss -> log.info("miss: {} — {}", result.reason, result.detail)
}
```

`MissReason` is one of `EMPTY_SCOPE`, `BELOW_THRESHOLD`, `REJECTED_BY_GUARD` or
`REJECTED_BY_VERIFIER`, and `detail` names the guard that fired and why.

### Scopes

Anything that changes what a correct answer looks like belongs in the scope string — model,
temperature, system prompt, tenant, user language. Lookups only ever see their own scope.

```kotlin
cache.getOrPut(prompt, scope = "gpt-4o|t=0.0|sysprompt-v3") { llm.complete(it) }
```

Without this, a cache shared between two models will serve one model's answer to the other's caller.
It is the second most common way a hand-rolled semantic cache goes wrong.

### Choosing guards

```kotlin
SemanticCache(embedder)                                            // MatchGuards.standard()
SemanticCache(embedder, guards = MatchGuards.strict())             // trades hit rate for margin
SemanticCache(embedder, guards = MatchGuards.none())               // the naive baseline
SemanticCache(embedder, guards = MatchGuards.standard() + MyGuard())
```

The guards work outside English too. Curated packs ship for Italian, Spanish, German and French — each
measured against a localized near-miss corpus — and any guard's word lists are still a constructor
parameter, so your own language or vocabulary is configuration, not a fork:

```kotlin
SemanticCache(embedder, guards = MatchGuards.standard(Locale.ITALIAN))       // a shipped pack
SemanticCache(embedder, guards = MatchGuards.standard(Vocabularies.GERMAN))  // the same, by vocabulary
SemanticCache(embedder, guards = MatchGuards.standard(myVocabulary))         // your own pack
```

### Typed and streaming responses

Cache more than a `String` — a structured object via a `ResponseCodec`, or a streamed answer replayed
as a `Flow` on a hit:

```kotlin
// Structured: the cache stores text, the codec turns your object into it and back.
val weather: Weather = cache.getOrPut(prompt, weatherCodec) { llm.extractWeather(it) }

// Streaming: chunks pass through on a miss and are cached once the stream completes; replayed on a hit.
cache.getOrPutStreaming(prompt) { llm.completeStreaming(it) }.collect { print(it) }
```

### Configuring with the DSL

```kotlin
val cache = semanticCache(embedder) {
    threshold = 0.9
    negativeCacheSize = 10_000
    listeners = listOf(metrics)
}
```

### Verifying what lexical guards cannot see

Two prompts that differ only in world knowledge — `bake salmon fillets` versus `bake chicken
breasts` — are invisible to a lexical guard. A `Verifier` runs only on candidates that already
cleared the threshold and every guard, so it fires on a small fraction of lookups:

```kotlin
val verifier = Verifier { query, cached, _ ->
    haiku.complete("Same correct answer? Reply YES or NO.\nA: $cached\nB: $query").startsWith("YES")
}
```

Wire it in with a timeout and verdict caching, so a slow or repeated check never costs more than it
should:

```kotlin
val cache = SemanticCache(
    embedder,
    verifier = verifier.caching(ttl = 6.hours),   // judge each pair once, not on every lookup
    verifierTimeout = 2.seconds,                   // and fail closed if it stalls
)
```

If the verifier throws or times out, the candidate is **rejected**, never served — an answer it could
not confirm is exactly what it exists to keep out.

### Calibrating the threshold

```kotlin
val report = ThresholdCalibrator(myEmbedder).calibrate(myLabelledPairs)
println(report.summary())

val cache = SemanticCache(myEmbedder, threshold = report.recommended.threshold)
```

A hundred pairs from your own traffic is plenty, and half should be near misses. Pairs that are
obviously unrelated teach the calibrator nothing, because no threshold ever confuses them.

### Observability

```kotlin
val stats = cache.stats()
stats.hitRate            // fraction served from cache
stats.guardRejections    // wrong answers that were not served
stats.belowThreshold     // prompts that simply did not repeat
```

A high `belowThreshold` means your traffic repeats less than you assumed, or your threshold is too
tight for your model. A high `guardRejections` means the opposite — prompts are landing close
together and the guards are catching near misses your threshold alone would have served.

For dashboards and logs, subscribe to the event stream instead of polling. It is free when unused — no
listeners, no events built:

```kotlin
// Metrics (kmemo-micrometer) and structured logs (kmemo-slf4j), both just CacheListeners:
val metrics = KmemoMetrics().also { it.bindTo(meterRegistry) }
val cache = SemanticCache(embedder, listeners = listOf(metrics, Slf4jCacheListener()))

// …or tap the raw CacheEvent stream (hit / miss / write / eviction) as a Flow:
val events = CacheEvents()
val cache = SemanticCache(embedder, listeners = listOf(events))
scope.launch { events.events.collect { event -> /* ship it */ } }
```

### Resilience

The `Embedder` is a network call on every lookup, so own its failure. Fall back to the model when it is
down, retry transient blips, and remember a brand-new prompt's embedding so a burst embeds once:

```kotlin
val cache = SemanticCache(
    embedder = myEmbedder.retrying(maxAttempts = 4),           // jittered backoff, opt-in
    embedFailurePolicy = EmbedFailurePolicy.FALL_BACK_TO_COMPUTE, // never worse than no cache
    negativeCacheSize = 10_000,                                 // a repeated miss embeds once
)
cache.warm(faqPairs.map { WarmEntry(it.question, it.answer) }) // batch-embedded startup preload
```

## Architecture

| Module | Contents |
| --- | --- |
| `kmemo-core` | `SemanticCache`, the `Embedder` and `CacheStore` seams, the guard chain, `InMemoryStore`, `ThresholdCalibrator`, resilience (`EmbedFailurePolicy`, `Embedder.retrying`, negative caching, `warm`), the `CacheEvent` stream — and no provider or database knowledge. |
| `kmemo-store-redis` | A `CacheStore` on Redis (RediSearch `FT.SEARCH` KNN), for a cache shared across processes. |
| `kmemo-store-postgres` | A durable `CacheStore` on Postgres / pgvector (`<=>`), for a one-dependency persistent cache. |
| `kmemo-store-hnsw` | An opt-in in-process approximate (HNSW) `CacheStore` that scales past the exact scan. |
| `kmemo-micrometer` | A Micrometer `MeterBinder` — hit rate, per-reason and per-guard counters, embed/search/verify timers. |
| `kmemo-slf4j` | An SLF4J `CacheListener` — a structured log line per event, prompt redaction on by default. |
| `kmemo-bom` | A `java-platform` BOM — pin one version and depend on every kmemo module without repeating it. |
| `kmemo-spring-boot-starter` | Spring Boot auto-config — a `SemanticCache` bean from your `Embedder`, under `kmemo.*`. |
| `kmemo-spring-ai` | A caching `Advisor` for Spring AI's `ChatClient`. |
| `kmemo-langchain4j` | A `CachingChatModel` that wraps any LangChain4j `ChatModel`. |
| `kmemo-ktor` | A Ktor server plugin exposing the cache to route handlers. |

Every module past `kmemo-core` is opt-in and never lands on the core classpath; the core still depends
only on `kotlinx-coroutines-core`. There's a runnable [`examples/`](examples) demo, and framework users
get a one-liner — a Spring AI advisor, a LangChain4j wrapper, a Ktor plugin, or the Spring Boot starter.

A lookup is decided in four stages, each cheaper than the one it protects:

```
prompt ──► embed ──► nearest 5 in scope ──► similarity ≥ threshold?
                                                  │ no ──► MISS (below_threshold)
                                                  ▼ yes
                                             guards ──► reject? ──► try next candidate ──► MISS (rejected_by_guard)
                                                  ▼ pass
                                             verifier (optional) ──► reject? ──► MISS (rejected_by_verifier)
                                                  ▼ pass
                                                 HIT
```

Looking past the nearest neighbour matters: when a guard vetoes the closest entry, the second closest
is often a perfectly good answer.

### The guards

| Guard | Rejects when | Example it catches |
| --- | --- | --- |
| `NumericGuard` | the prompts contain different numbers | `100 USD` vs `250 USD` |
| `UnitGuard` | units or currencies are substituted | `50 km to miles` vs `50 km to meters` |
| `TemporalGuard` | different absolute time reference | `weather today` vs `weather tomorrow` |
| `NegationGuard` | one prompt is negated, the other is not | `should I eat X` vs `should I not eat X` |
| `AntonymGuard` | a word is flipped to its opposite | `enable 2FA` vs `disable 2FA` |
| `EntityGuard` | capitalized named entities are swapped | `capital of Australia` vs `capital of Austria` |
| `SubstitutionGuard` | prompts are identical but for one word | `sales tax in oregon` vs `sales tax in washington` |
| `ScopeGuard` | a different answer is asked for | `write a haiku` vs `write a sonnet` |
| `DirectionGuard` | same words, reversed comparison or conversion | `Convert 500 EUR to USD` vs `Convert 500 USD to EUR` |
| `LexicalDivergenceGuard` | the prompts share almost no content words | backstop for everything else |

`LengthRatioGuard` ships too, but stays out of `standard()`. Terse and verbose phrasings of one
question routinely differ two- or three-fold in length — "python list reverse" against "How do I
reverse a list in Python?" is 2.7× — so length is a weak signal that costs hits before it catches
anything.

The interesting engineering is in **not** over-rejecting:

- `EntityGuard` and `UnitGuard` reject a *substitution*, never an *addition* — so "what does SOLID
  stand for in OOP" still matches "what does each letter of SOLID mean in object oriented design",
  and `km` and `kilometers` are one unit rather than a swap.
- `AntonymGuard` counts occurrences instead of testing membership, so "turn **on** format **on**
  save" vs "turn **off** format **on** save" is caught even though both prompts contain `on`.
- `DirectionGuard` fires only on a comparison or conversion cue, and ignores rotations. Moving a
  trailing phrase to the front never changes the question: "How do I move a file in Linux?" and "In
  Linux, how do I move a file?" stay a hit, while "Is Postgres better than MySQL?" and "Is MySQL
  better than Postgres?" do not.
- `LexicalDivergenceGuard` matches typos within one edit — including transpositions, so `cahce`
  finds `cache` — and inflections by strict prefix, so `commit` finds `committed`. Neither rule can
  merge `Austria` with `Australia`: two edits apart, and neither is a prefix of the other.

### Correctness, measured

Kmemo ships **three** labelled corpora, and the separation between them is the point. Tuning a guard
against a corpus and then quoting that corpus as evidence measures the tuning, not the guard.

| Corpus | Pairs | Role |
| --- | --- | --- |
| `near-miss-corpus.json` | 109 | What the guards were **tuned on**. In-sample; a regression test, not a measurement. |
| `held-out-corpus.json` | 128 | Written after the guards, in domains the first never touches. Its failures were used to guide fixes, so it is no longer pristine. |
| `validation-corpus.json` | 153 | Written **blind** — its author saw no guard source, no vocabulary, no other corpus — and never tuned against. This is the number to trust. |

```
tuned       near misses rejected  65/71  (92%),  paraphrases kept 38/38 (100%)
held-out    near misses rejected  61/86  (71%),  paraphrases kept 37/42  (88%)
validation  near misses rejected  68/102 (67%),  paraphrases kept 45/51  (88%)
```

Nine tenths of the validation prompts are lowercase, deliberately. Real users type that way, and an
earlier measurement showed capitalization was quietly carrying a third of the entity catches — a
corpus written in tidy prose hides exactly that failure. The two out-of-sample corpora agree with
each other (71%/88% and 67%/88%), which is the evidence that the guards now generalize rather than
remember.

**How this got here, since the trajectory is the honest part of the story.** The first measurement
against out-of-sample prompts scored **26%**, against 96% in-sample. Two changes closed most of the
gap. `SubstitutionGuard` reads structure instead of capitalization: same words in the same order,
differing in exactly one position, means a term was substituted whatever case it was typed in.
And the marker guards — negation, temporal, scope — stopped firing on lone keywords and now require
the rest of the prompt to match, which is why "why can't I connect to the VPN" no longer counts as a
different question from "why is my connection to the VPN failing".

What the guards still cannot do is anything that needs world knowledge. `deworm a puppy` versus
`deworm an adult dog`, `boiling point of ethanol` versus `methanol` — no lexical rule separates
those, and a third of the validation near misses are of that kind. That is what the optional
`Verifier` is for.

Run it yourself:

```bash
./gradlew :kmemo-core:test --tests '*CorpusTest*'
```

## Roadmap

**Shipped (`0.6.0`)** — **quality & the road to `1.0`**: property-based tests, a JaCoCo coverage floor,
ktlint and detekt gates, a JDK 17/21/23 matrix, Dependabot + a dependency-review CVE gate, SLSA
build-provenance on release, and `-SNAPSHOT` publishing from `main`; plus the written
[stability policy and `1.0` plan](docs/STABILITY.md) and the [defended corpus process](docs/CORPUS.md).

**Shipped (`0.5.0`)** — **DX & reach + ecosystem & adoption**: ergonomics (`catching { }`, a typed
`getOrPut<T>` over a `ResponseCodec`, streaming `getOrPutStreaming` → `Flow<String>`, a `semanticCache { }`
DSL, and a `kmemo-bom`); multilingual guard packs — `MatchGuards.standard(locale)` with curated
`Vocabularies` for Italian, Spanish, German and French, each measured against a localized near-miss
corpus; and framework integrations — a Spring Boot starter (`kmemo-spring-boot-starter`), a Spring AI
advisor (`kmemo-spring-ai`), a LangChain4j wrapper (`kmemo-langchain4j`), a Ktor plugin (`kmemo-ktor`),
and a runnable [`examples/`](examples) demo, none of which those frameworks ship of their own.

**Shipped (`0.4.0`)** — **production reliability & observability**: resilience (an `EmbedFailurePolicy`
fall-back, `Embedder.retrying(…)`, opt-in negative caching, `warm(...)` preload), observability (a
zero-dependency `CacheEvent` stream, plus `kmemo-micrometer` metrics and `kmemo-slf4j` structured
logging), and performance (`getOrPutAll(...)` batch embedding, opt-in write-behind, a `kmemo-benchmarks`
JMH module, and a zero-boxing search path).

**Shipped (`0.3.0`)** — **stores beyond memory**: a shared store conformance suite (`kmemo-store-tck`),
Redis (`kmemo-store-redis`, RediSearch KNN) and Postgres / pgvector (`kmemo-store-postgres`) adapters
behind the same `CacheStore` seam, an opt-in in-process HNSW index (`kmemo-store-hnsw`), and a `maxBytes`
bound on `InMemoryStore` — on top of `0.2.0`'s per-guard measurement (`guardRejectionsByGuard`,
`explain(...)`) and completed `Verifier` path, and the `0.1.0` core. All on Maven Central and GitHub Packages.

**Next** — `1.0`: proving a persistent store in production and finalizing the defaults against real
traffic (see [STABILITY.md](docs/STABILITY.md)), then Kotlin Multiplatform after that.

See **[ROADMAP.md](ROADMAP.md)** for the full milestone plan (`M1`–`M18`), and the shared
**[roadmap conventions](ROADMAP-CONVENTIONS.md)**.

## Building and testing

```bash
./gradlew build          # compile, test, verify public API (binary-compatibility-validator)
./gradlew apiCheck       # check the tracked public API in *.api
./gradlew apiDump        # regenerate *.api after an intentional public-API change

./gradlew :kmemo-core:test --tests '*CorpusTest*'   # the corpus report above
```

The build runs with explicit API mode and `allWarningsAsErrors`. Tests need no external services —
113 of them, all offline.

## Contributing

Contributions are welcome — see [CONTRIBUTING.md](CONTRIBUTING.md). Please run `./gradlew build`
before opening a pull request; if you change the public API, run `./gradlew apiDump` and commit the
updated `*.api` files.

Guard changes have a stricter bar: they must be measured against the corpus, and a guard may never
reject a genuine paraphrase. A near-miss pair that slips through is a useful contribution on its own,
even without a fix.

## License

Licensed under the [Apache License 2.0](LICENSE).

## Sponsor

If Kmemo is useful to you, consider [sponsoring NaCode Studios](https://github.com/sponsors/NaCode-Studios).
