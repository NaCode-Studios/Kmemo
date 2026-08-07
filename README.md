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

> **Status — `2.3`, stable.** The cache and its eleven guards run on the JVM, iOS, macOS, Linux,
> Windows, JS and WasmJS, and so does the persistent store, so a phone no longer starts cold. The
> framework integrations, the threshold calibrator, the optional verifier and the store adapters that
> wrap a JVM driver are JVM-side. Everything here is measured against labelled corpora whose blind
> splits this project did not write and cannot add to, and the results are published whichever way they
> come out, with the interval each sample supports. The public API is stable under SemVer, every hop is listed in
> [docs/MIGRATION.md](docs/MIGRATION.md), the policy is in [STABILITY.md](STABILITY.md), and the plan is
> on the [board](https://github.com/orgs/NaCode-Studios/projects/5).

## Why Kmemo

- **Eleven lexical guards catch near misses a threshold cannot**: swapped numbers, units, entities, time
  references, negation, flipped antonyms, reversed comparisons, a different answer being asked for, and a
  clause one prompt adds that narrows the question. They run against four labelled corpora on every
  build.
- **The numbers are reported whichever way they come out.** The two corpora nobody here wrote disagree
  with each other: on external question traffic the guards catch 65% of near misses, and on an
  adversarial paraphrase set built to defeat lexical overlap they catch 14%. Both ship, and so does the
  finding that external questions cost 79% paraphrase retention where this project's own splits reported
  88%. See [Correctness, measured](#correctness-measured).
- **The costs are asymmetric and the defaults follow that.** A wrong rejection costs one API call; a
  wrong acceptance costs a wrong answer. Guards abstain rather than guess, and every opt-in piece states
  what it trades.
- **`ThresholdCalibrator` and shadow mode measure the right threshold for *your* embedding model**, on
  your own traffic, before a single cached answer is served. A value from a blog post was tuned for
  somebody else's.
- **`kmemo-core` declares `kotlinx-coroutines-core` as its only dependency**, on every platform it
  targets rather than only the JVM.

## Installation

`kmemo-core` is a Kotlin Multiplatform library: JVM (17+), Android via the JVM artifact, iOS, macOS,
Linux, Windows, JS and WasmJS. `kmemo-store-file` follows it to every one of those targets; the other
store adapters and the framework integrations are JVM-side, because they wrap drivers that exist nowhere
else. Artifacts are published to Maven Central under `io.github.nacode-studios`.

```kotlin
dependencies {
    implementation("io.github.nacode-studios:kmemo-core:2.2.0")
}
```

You also need an embedding source, which is any function from `String` to `FloatArray`. Multi-module
users can pin one version with the BOM (`io.github.nacode-studios:kmemo-bom`); every module past
`kmemo-core` is opt-in and never lands on the core classpath.

Every artifact published from `2.0.0` onwards carries a signed [SLSA build provenance](https://slsa.dev/)
attestation, so you can check that what you resolved was built by this repository's release workflow:

```bash
gh attestation verify <jar> --repo NaCode-Studios/Kmemo
```

## Usage

The [API documentation](https://nacode-studios.github.io/Kmemo/) covers every option with its trade
written out. This section is the shape of the library, not a manual.

### Caching a call

`getOrPut` embeds the prompt once and reuses the vector for both the lookup and the write. Concurrent
callers asking the same thing are coalesced, on the blocking and the streaming path alike: the first
calls the model, the rest are served its answer.

```kotlin
val answer = cache.getOrPut(prompt) { llm.complete(it) }
```

### Reading a miss

A cache whose hit rate is 4% is untunable unless you know *why*, because the fix is opposite for a
threshold miss and a guard rejection. Every miss says which, and `cache.explain(prompt)` shows every
candidate with every guard's verdict without touching the counters.

```kotlin
when (val result = cache.lookup(prompt)) {
    is CacheLookup.Hit  -> result.response
    is CacheLookup.Miss -> when (result.reason) {
        MissReason.BELOW_THRESHOLD   -> // traffic repeats less than you assumed, or threshold too tight
        MissReason.REJECTED_BY_GUARD -> // result.detail says which guard fired and why
        else -> null
    }
}
```

### Scopes and tenants

Anything that changes what a correct answer looks like belongs in the scope: model, temperature, system
prompt, language. Leave one out and the cache serves one model's answer to another model's caller.

```kotlin
cache.getOrPut(prompt, scope = "gpt-4o|t=0.0|v3") { llm.complete(it) }
```

**A tenant is not a scope.** Serving more than one customer from one cache used to mean one key space
between all of them, and on the exact-match fast path the second tenant was served the first one's answer
without similarity, without guards and without the verifier, because skipping all three is what that path
is for. `forTenant` partitions everything, including that path:

```kotlin
val cache = semanticCache(embedder) { requireTenant = true }

cache.forTenant(request.customerId).getOrPut(prompt) { llm.complete(it) }
```

A view rather than a parameter, because a parameter is something somebody can omit, and isolation that
depends on nobody making a mistake later is not isolation. With `requireTenant` on, a call that did not
come through a view is refused rather than defaulted.

### Choosing guards

```kotlin
SemanticCache(embedder)                                        // MatchGuards.standard()
SemanticCache(embedder, guards = MatchGuards.strict())         // trades hit rate for margin
SemanticCache(embedder, guards = MatchGuards.responseAware())  // standard(), plus reads the cached answer
SemanticCache(embedder, guards = MatchGuards.longPrompts())    // prompts carrying retrieved context
SemanticCache(embedder, guards = MatchGuards.prose())          // declarative traffic, not questions
SemanticCache(embedder, guards = MatchGuards.none())           // the naive similarity-only baseline
```

The three named presets exist because of measurements rather than preferences, and each publishes what it
costs on all four corpora: see [what register and length do to the
guards](docs/MEASUREMENTS.md#what-register-does-to-the-guards-and-what-it-does-not-explain). The guards
also work outside English, with curated packs for Italian, Spanish, German and French:
`MatchGuards.standard(Locale.ITALIAN)`.

### Typed and streaming responses

A structured object through a `ResponseCodec`, or a streamed answer replayed as a `Flow` on a hit. Only a
stream that completes cleanly is cached, and a provider that fails partway fails every attached collector
and writes nothing.

```kotlin
val weather: Weather = cache.getOrPut(prompt, weatherCodec) { llm.extractWeather(it) }

cache.getOrPutStreaming(prompt) { llm.completeStreaming(it) }.collect { print(it) }
```

### Observability, and what it saved

`stats()` gives lifetime counters. For dashboards and logs, subscribe to the event stream instead; it
costs nothing when unused. Declare what a call costs and the same surfaces report the money, from the
token counts on the entries actually served rather than from an average applied to a hit count.

```kotlin
val cache = semanticCache(embedder) {
    listeners = listOf(KmemoMetrics().also { it.bindTo(registry) }, Slf4jCacheListener())
    prices["gpt-4o"] = TokenPrices(currency = "USD", perOutputToken = 10.00 / 1_000_000)
}

cache.stats().savings["gpt-4o"]   // amount, currency, hits, tokens, and the prices behind them
```

Kmemo ships no table of provider prices: they change weekly, and a library that quietly reports the wrong
saving is worse than one that reports none.

### Calibrating before you serve anything

`ThresholdCalibrator` measures the right threshold, but it needs a labelled set, and building one is what
stops teams putting a cache in front of production traffic. Shadow mode removes that step: the cache runs
the full lookup against real traffic, **serves nothing**, and reports what it would have decided at every
threshold you name.

```kotlin
val cache = semanticCache(embedder) {
    shadowThresholds = listOf(0.99, 0.97, 0.95, 0.90)
    listeners = listOf(CacheListener { e -> if (e is CacheEvent.Shadow) record(e.report) })
}
```

The output is your own precision and recall curve, against your own questions, with no risk of a false
hit reaching a user while you are still deciding.

### The rest

Each of these is off by default, and each states its trade in the API documentation: an exact-match layer
for byte-for-byte repeats, conversation-aware keys, per-scope thresholds, `MmrReranker`, quantized
retrieval with exact rescoring, write deduplication, adaptive thresholds, tag invalidation, an admission
policy that makes a prompt earn its place, a `CachePolicy` veto for data that must never be persisted, an
`EntryCipher` seam for a store that may hold no readable prompt, write-behind, negative caching and an
embed-failure fall-back that is counted rather than silent.

[docs/THREAT-MODEL.md](docs/THREAT-MODEL.md) is what a security review asks for: the assets, the
adversaries, the trust boundaries, and what a cache still discloses once every mitigation here is
switched on. The embedding cannot be encrypted, and that section is the one worth reading first.

## Architecture

| Module | Contents |
| --- | --- |
| `kmemo-core` | `SemanticCache`, the `Embedder` and `CacheStore` seams, the guard chain, `InMemoryStore`, `ThresholdCalibrator`, resilience, the `CacheEvent` stream, with no provider or database knowledge. **Multiplatform**: JVM, iOS, macOS, Linux, Windows, JS, WasmJS. |
| `kmemo-store-file` | A persistent `CacheStore` on an append-only journal. **Multiplatform**: every target `kmemo-core` has. |
| `kmemo-store-qdrant` | A `CacheStore` on Qdrant, through the Kdrant client. JVM and seven native targets. |
| `kmemo-store-redis` | A `CacheStore` on Redis (RediSearch KNN), for a cache shared across processes. |
| `kmemo-store-postgres` | A durable `CacheStore` on Postgres / pgvector. |
| `kmemo-store-hnsw` | An opt-in in-process approximate (HNSW) `CacheStore` that scales past the exact scan. |
| `kmemo-micrometer` / `kmemo-slf4j` | A Micrometer `MeterBinder` and an SLF4J logging listener. |
| `kmemo-spring-boot-starter` / `kmemo-spring-ai` | Auto-config for a `SemanticCache` bean, and a caching `Advisor` for Spring AI's `ChatClient`. |
| `kmemo-langchain4j` / `kmemo-ktor` | A caching `ChatModel` wrapper, and a Ktor server plugin. |
| `kmemo-guard-tck` / `kmemo-store-tck` | Conformance suites for a custom `MatchGuard` and a custom `CacheStore`. Test dependencies. |
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

**Which store to use where.** One process that can start cold wants `InMemoryStore`; one that cannot,
which is every phone, desktop and edge deployment, wants `kmemo-store-file`. More than one process wants
a server, and there the first question is what you already run: if that is Qdrant, add nothing new.
`kmemo-store-hnsw` is a different axis, for a single in-memory store large enough that the exact scan has
become the cost.

## Correctness, measured

The guards are judged against five labelled corpora, two of them blind splits written by other people
years earlier, run as a CI regression gate on every build. **These figures are guard-only**: no
`Verifier` is in the loop, so they describe the free lexical layer rather than the cache as a whole.

Every rate carries the 95% interval its sample supports, because a rate from a hundred pairs and one
from five thousand are not the same kind of number.

| Split | Near misses rejected | Paraphrases kept |
| --- | --- | --- |
| tuned | in-sample, not evidence | in-sample, not evidence |
| held-out | 71% ±9 (61/86) | 88% ±10 (37/42) |
| validation | 68% ±9 (69/102) | 88% ±9 (45/51) |
| qqp | 65% ±2 (1634/2500) | 79% ±2 (2205/2796) |
| external | 14% ±1 (647/4464) | 79% ±1 (2807/3536) |

**Only the last two rows are evidence, and neither was written here.** `qqp` is Quora Question Pairs;
`external` is [PAWS](https://github.com/google-research-datasets/paws). The three written splits are a
fitted set and two spent ones, kept as regression gates because their failures have been read and that
cannot be undone. [docs/CORPUS.md](docs/CORPUS.md) states the policy.

**The two blind rows disagree, and the disagreement is the line.** On external questions the guards
catch 65%, at a sample twenty-five times the written splits; on an adversarial paraphrase set they
catch 14%. Kmemo catches near misses that arise in traffic and does not catch adversarially constructed
ones at a price worth paying. Those questions also cost 79% paraphrase retention where the small splits
reported 88%, which is a fifth of real hits refused and the least comfortable number on this page.

Two explanations for the PAWS gap have been offered and checked. **Prompt length is most of it**: filing
every pair by length, the guard responsible rejects paraphrases at 0% under 48 characters and 15% above
96, and the splits sit on opposite sides of that. **Register is not**: at the same register PAWS still
catches 9% where validation catches 65%. What is left is difficulty, which is what PAWS was built for.

**And the number was attacked, against a target written down first.** A guard aimed at how PAWS
constructs its pairs takes that 14% to 39.7%, past the 25% registered as success, and pays for it with a
ninth of the genuine paraphrases on the same corpus. That is the boundary case as it was defined before
the attempt, so the guard ships in no preset and `standard()` is untouched. The line it draws is the
useful part: **Kmemo catches near misses that arise in traffic, and does not catch adversarially
constructed ones at a price worth paying.**

Against a threshold-only cache on the written splits, `standard()` cuts the false-hit rate from 1.000
to 0.291 and 0.324 while keeping 88% of paraphrases. Against GPTCache the result is a trade rather than
a win, and both halves are published.

On a retrieval pipeline over SQuAD, a second run of the same questions makes **no model calls at all**,
and the guards **halve** the wrong answers a threshold-only cache serves there. Folding the retrieved
document into the key takes it from 12 to 2. That workload is the one this library is most often put in
front of and the one its own corpora cannot measure, because a RAG false hit needs two prompts that are
the same and two documents that are not.

An optional `Verifier` stops about four fifths of what the guards still serve, and **its price is
published beside its catch rate**: 22 tokens per avoided false hit on the measured residual, with the
invocation rates stated as the upper bounds they are, because the corpora are two thirds near misses and
real traffic is not.

**The data and the rules that grade it are published as a standard.** [`spec/`](spec) carries the corpus
schema, the false-hit metric and the eleven guard rules written to be implemented without reading this
repository's Kotlin, with a conformance vector per rule per pair. A Python implementation from that
specification alone reproduces every figure above to the pair, with no JVM involved, and the directory
ships as `kmemo-corpus-<version>.zip` on each release.

[**docs/MEASUREMENTS.md**](docs/MEASUREMENTS.md) has all of it: the methodology, the per-register and
per-length breakdowns, the verifier's catch rate and cost, what admission costs, the on-device embedding
result, and the command that reproduces each figure. [docs/CORPUS.md](docs/CORPUS.md) has the provenance rules
that keep the data honest.

```bash
./gradlew :kmemo-core:jvmTest --tests '*CorpusTest*'
```

## Roadmap

**Shipped (`2.2.0`).** The guarded semantic cache with eleven guards and their measured numbers,
calibrated thresholds and an optional verifier; six stores behind one `CacheStore` seam, two of which
follow `kmemo-core` to every target it publishes; a key space per tenant; a cipher seam for a store that
may hold no readable prompt; the cost of a hit reported in the currency you declare; and Spring Boot,
Spring AI, LangChain4j and Ktor integrations. The measurements, including the ones that came out against
the library, are in [docs/MEASUREMENTS.md](docs/MEASUREMENTS.md). Every version is in
[CHANGELOG.md](CHANGELOG.md).

**Next.** The plan lives on the [Kmemo board](https://github.com/orgs/NaCode-Studios/projects/5), one
item per milestone with its exit criterion, and every tier is a
[milestone](https://github.com/NaCode-Studios/Kmemo/milestones) in this repository. Nothing restates it
here, because a roadmap in two places is a roadmap that will disagree with itself.

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
