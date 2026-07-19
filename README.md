# kmemo

**A semantic cache for LLM calls on Kotlin/JVM — that refuses to serve you the wrong answer.**

[![CI](https://github.com/NaCode-Studios/kmemo/actions/workflows/ci.yml/badge.svg)](https://github.com/NaCode-Studios/kmemo/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.nacode-studios/kmemo-core?label=Maven%20Central)](https://central.sonatype.com/artifact/io.github.nacode-studios/kmemo-core)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4-7F52FF.svg?logo=kotlin)](https://kotlinlang.org)

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
quickly, with no error and nothing in the logs. kmemo treats that as the main event: similarity is
only the first filter, and candidates that clear it are read as text by a chain of guards looking for
concrete evidence the answers must differ.

```kotlin
val cache = SemanticCache(
    embedder = Embedder { text -> openAi.embed(text) },
    store = InMemoryStore(maxEntries = 10_000, ttl = 1.hours),
)

val answer = cache.getOrPut(prompt) { llm.complete(it) }
```

kmemo caches responses for embeddings you already have — `openAi.embed` above is your own embedding
source; kmemo ships none and depends on no provider SDK.

> **Status — early development.** The cache, the nine guards, the in-memory store and the threshold
> calibrator are implemented and tested against a labelled corpus. APIs may change before `1.0`.

## Why kmemo

- **Guards against false hits** — nine lexical checks catch near misses a threshold cannot: swapped
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
    implementation("io.github.nacode-studios:kmemo-core:0.1.0")
}
```

> **Not published yet.** `0.1.0` is unreleased. Until it ships, build it locally with
> `./gradlew publishToMavenLocal` and add `mavenLocal()` to your repositories.

You also need an embedding source. Any function from `String` to `FloatArray` will do.

## Usage

### Caching a call

```kotlin
val answer = cache.getOrPut(prompt) { llm.complete(it) }
```

`getOrPut` embeds the prompt **once** and reuses the vector for both the lookup and the write. Doing
it by hand with `get` then `put` costs two embedding calls on every miss.

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

Every guard takes its word lists as a constructor parameter, so adapting to another language is
configuration rather than a fork:

```kotlin
NegationGuard(markers = setOf("non", "senza", "mai"))
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

## Architecture

| Module | Contents |
| --- | --- |
| `kmemo-core` | `SemanticCache`, the `Embedder` and `CacheStore` seams, the guard chain, `InMemoryStore`, `ThresholdCalibrator` — and no provider or database knowledge. |

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
| `EntityGuard` | named entities are swapped | `capital of Australia` vs `capital of Austria` |
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

kmemo ships a corpus of **109 labelled prompt pairs** (`near-miss-corpus.json`): 71 that must never
share a cached answer, and 38 genuine paraphrases that must. The near misses are mostly a single
token apart, because that is where a semantic cache actually breaks. The corpus carries its own
control group, so a guard set that rejects everything scores zero rather than looking perfect.

```
category                  pairs   standard     strict
comparative-direction         8       100%       100%   must not match
entity-swap                  13        85%        85%   must not match
negation                      5       100%       100%   must not match
numeric-swap                 13       100%       100%   must not match
polarity-antonym              9       100%       100%   must not match
scope-shift                   8        88%        88%   must not match
temporal                      7       100%       100%   must not match
unit-swap                     8       100%       100%   must not match
paraphrase                   13       100%        62%   must match
politeness                    6       100%       100%   must match
typo                          6       100%       100%   must match
verbosity                     7       100%       100%   must match
word-order                    6       100%       100%   must match
```

> **The numbers above are in-sample.** The guards were tuned against this corpus over three review
> rounds, so the table measures the corpus at least as much as it measures the guards. On a held-out
> set of 128 pairs written afterwards, in domains the corpus does not cover — clinical dosing, tax
> jurisdictions, database isolation levels, shell dialects, HTTP status codes — the same chain
> rejects **22 of 86 near misses (26%)** and keeps **27 of 42 paraphrases (64%)**.
>
> The corpus demonstrates the cause with its own data: lowercasing every prompt in it, changing
> nothing but letter case, drops its catch rate from 96% to 79%. A third of the catches ride on
> capitalization convention rather than on meaning.
>
> What generalizes is `NumericGuard`, which reads digits and needs no vocabulary, and `EntityGuard`
> *when the distinguishing word happens to be capitalized*. The guards built on closed word lists —
> `TemporalGuard`, `ScopeGuard`, `DirectionGuard`, `AntonymGuard` — are sized to the 109 pairs they
> were fitted against, and on the held-out set they caught nothing while causing false rejections.
>
> Read the table as a regression test, not as a forecast for your traffic. Calibrate against your
> own pairs, and use a `Verifier` for the distinctions no word list can make.

The three near misses that get through are honest limitations, and really two: a salmon-versus-chicken
swap that appears in the corpus twice and needs world knowledge no lexical rule has, plus an unmarked
depth qualifier (`how does HTTPS work` vs `how does HTTPS work at the packet level`). That is what the
`Verifier` hook is for.

Note that `strict()` catches nothing extra on this corpus while rejecting 5 of the 38 must-match
pairs — 13% overall, concentrated as 38% of the `paraphrase` category. It exists for traffic whose near misses are lexically distant in ways this corpus does not model —
measure it against your own pairs before choosing it.

Running the calibrator over the same corpus with a deliberately adversarial stub embedder — one
built so that a one-token swap barely moves the vector, putting 25 of the 71 near misses above 0.99:

| | best false-hit rate reachable | hits at that setting |
| --- | --- | --- |
| similarity threshold alone | **35.2%** (no threshold in `0.70..0.99` did better) | 6 |
| similarity + standard guards | **0%**, at threshold 0.81 | 7 |

The comparison that matters is at a *fixed* threshold, where guards cost nothing and remove almost
everything: at 0.70, 64 false hits become 3; at 0.81, 56 become 0 — with the identical number of
real hits in each case. Guards do not trade recall for safety, which is what lets you spend the
threshold on recall instead: 0.81 with guards returns more hits than 0.96 without, and returns no
wrong answers. Similarity alone reaches more hits only by dropping to 0.70, where two thirds of what
it serves is wrong.

> The stub embedder is built to make near misses look identical, so these false-hit figures
> demonstrate the mechanism honestly. The absolute recall numbers are not representative of a real
> embedding model, which rewards paraphrases in a way the stub deliberately does not.

## Roadmap

**Now** — `SemanticCache` with scopes, stats and typed misses; `Embedder` and `CacheStore` seams;
nine guards plus an opt-in `LengthRatioGuard`; an optional `Verifier`; `InMemoryStore` with TTL and
LRU; `ThresholdCalibrator`; the labelled corpus.

**Next** — Redis and Postgres/pgvector stores, a Spring AI `Advisor` and a LangChain4j wrapper
(neither framework ships a semantic cache), request coalescing for concurrent identical misses, and
non-English vocabularies.

## Building and testing

```bash
./gradlew build          # compile, test, verify public API (binary-compatibility-validator)
./gradlew apiCheck       # check the tracked public API in *.api
./gradlew apiDump        # regenerate *.api after an intentional public-API change

./gradlew :kmemo-core:test --tests '*NearMissCorpusTest*'   # the corpus report above
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

If kmemo is useful to you, consider [sponsoring NaCode Studios](https://github.com/sponsors/NaCode-Studios).
