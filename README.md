# kmemo

**Semantic cache for LLM calls on Kotlin/JVM — that refuses to serve you the wrong answer.**

[![build](https://github.com/NaCode-Studios/kmemo/actions/workflows/ci.yml/badge.svg)](https://github.com/NaCode-Studios/kmemo/actions/workflows/ci.yml)
[![license](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)

An exact-match cache misses "how do I reverse a list in Python" when it has already answered "python
list reverse". A semantic cache does not: it embeds the prompt, finds the closest one it has seen,
and replays that answer instead of calling the model. Fewer API calls, lower latency, same answers.

Except for the part where it hands back the wrong one.

---

## The problem this library is actually about

```
"Convert 100 USD to EUR"
"Convert 250 USD to EUR"      cosine similarity: ~0.99
```

Every mainstream embedding model scores that pair around 0.99. The sentences are near-identical and
no embedding model was trained to treat *magnitude* as meaning. There is no threshold that accepts
genuine paraphrases and rejects that pair, because on the similarity axis **the near miss is closer
than most paraphrases are**.

So a cache built on a threshold alone will tell someone that 250 dollars is 92 euros. And it will do
it quickly, with no error, no exception, and nothing in the logs. The same failure hides in:

| Cached prompt | Incoming prompt | Similarity says |
|---|---|---|
| what's the capital of Australia | what's the capital of Austria | same question |
| How do I **enable** 2FA on GitHub? | How do I **disable** 2FA on GitHub? | same question |
| Is Postgres better than MySQL? | Is MySQL better than Postgres? | *identical* bag of words |
| Weather in Chicago **today**? | Weather in Chicago **tomorrow**? | same question |
| Convert 500 **EUR to USD** | Convert 500 **USD to EUR** | *identical* bag of words |
| Write a **haiku** about the ocean | Write a **sonnet** about the ocean | same question |

kmemo treats these as the main event rather than an edge case. Similarity is only the first filter;
candidates that clear it are then read as text by a chain of guards looking for concrete evidence
that the answers must differ.

The costs are asymmetric, and the defaults follow that: **a wrong rejection costs one API call — the
exact call you would have made without a cache. A wrong acceptance costs a wrong answer.**

## Why this doesn't already exist on the JVM

- **Spring AI** has no built-in semantic cache. You can hand-roll one as a custom `ChatClient` advisor.
- **LangChain4j** has no built-in semantic cache either.
- **GPTCache**, the reference implementation, is Python-only.

The JVM AI ecosystem has matured everywhere else. This piece is missing, and the hand-rolled versions
that fill the gap are exactly the ones that get the false-hit problem wrong.

## Install

> **Not on Maven Central yet.** 0.1.0 is unreleased; build it locally with
> `./gradlew publishToMavenLocal` and add `mavenLocal()` to your repositories until it ships.

```kotlin
dependencies {
    implementation("dev.nacode.kmemo:kmemo-core:0.1.0")
}
```

One transitive dependency: `kotlinx-coroutines-core`. No provider SDKs, no vector database driver, no
serialization framework.

## Quickstart

```kotlin
import dev.nacode.kmemo.*
import dev.nacode.kmemo.store.InMemoryStore
import kotlin.time.Duration.Companion.hours

// You bring the embeddings — OpenAI, Cohere, Voyage, a local ONNX model, whatever you already pay for.
val embedder = Embedder { text -> openAi.embed(text) }

val cache = SemanticCache(
    embedder = embedder,
    store = InMemoryStore(maxEntries = 10_000, ttl = 1.hours),
)

val answer = cache.getOrPut(prompt) { llm.complete(it) }
```

`getOrPut` embeds the prompt **once** and reuses the vector for both the lookup and the write. Doing
it by hand with `get` then `put` costs two embedding calls on every miss.

When you want to know *why* a lookup went the way it did:

```kotlin
when (val result = cache.lookup(prompt)) {
    is CacheLookup.Hit -> log.info("hit at {} (age {})", result.similarity, result.age)
    is CacheLookup.Miss -> log.info("miss: {} — {}", result.reason, result.detail)
}
```

A cache whose hit rate is 4% is impossible to tune unless you know whether prompts are landing below
the threshold or being vetoed by a guard — the fix is the opposite in each case.

## How a lookup is decided

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

## The guards

Each one targets a distinct way a near miss slips past a threshold. All are pure, lexical and free —
no model call, no network.

| Guard | Rejects when | Example it catches |
|---|---|---|
| `NumericGuard` | the prompts contain different numbers | `100 USD` vs `250 USD` |
| `UnitGuard` | units or currencies are substituted | `50 km to miles` vs `50 km to meters` |
| `TemporalGuard` | different absolute time reference | `weather today` vs `weather tomorrow` |
| `NegationGuard` | one prompt is negated, the other is not | `should I eat X` vs `should I not eat X` |
| `AntonymGuard` | a word is flipped to its opposite | `enable 2FA` vs `disable 2FA` |
| `EntityGuard` | named entities are swapped | `capital of Australia` vs `capital of Austria` |
| `ScopeGuard` | a different answer is asked for | `write a haiku` vs `write a sonnet` |
| `DirectionGuard` | same words, reversed comparison or conversion | `EUR to USD` vs `USD to EUR` |
| `LexicalDivergenceGuard` | the prompts share almost no content words | backstop for everything else |

The interesting engineering is in **not** over-rejecting, since every wrong rejection is money:

- `EntityGuard` rejects a *substitution*, never an *addition* — so "what does SOLID stand for in OOP"
  still matches "what does each letter of SOLID mean in object oriented design".
- `AntonymGuard` counts occurrences instead of testing membership, so "turn **on** format **on** save"
  vs "turn **off** format **on** save" is caught even though both prompts contain `on`.
- `DirectionGuard` only fires when a comparison or conversion cue is present. "In Python, sort a
  dictionary" and "sort a dictionary in Python" are a reordering and stay a hit; "is A better than B"
  and "is B better than A" are not.
- `LexicalDivergenceGuard` matches typos and inflections fuzzily (`instal` → `install`, `commit` →
  `committed`) but caps fuzziness at one edit, which is deliberately too strict to merge `Austria`
  with `Australia`.

Swap the set at construction:

```kotlin
SemanticCache(embedder, guards = MatchGuards.strict())   // trades hit rate for margin
SemanticCache(embedder, guards = MatchGuards.none())     // the naive baseline, for comparison
SemanticCache(embedder, guards = MatchGuards.standard() + MyDomainGuard())
```

## Measured, not asserted

kmemo ships a corpus of **109 labelled prompt pairs** (`near-miss-corpus.json`): 71 that must never
share a cached answer, and 38 genuine paraphrases that must. The near misses are mostly a single
token apart, because that is where a semantic cache actually breaks — pairs that are obviously
unrelated prove nothing.

Guards are measured against it on every build, with no embedder involved:

```
category                  pairs   standard     strict
comparative-direction         8       100%       100%   must not match
entity-swap                  13        85%        85%   must not match
negation                      7       100%       100%   must not match
numeric-swap                 13       100%       100%   must not match
polarity-antonym              7       100%       100%   must not match
scope-shift                   8        88%        88%   must not match
temporal                      7       100%       100%   must not match
unit-swap                     8       100%       100%   must not match
paraphrase                   13       100%        62%   must match
politeness                    6       100%       100%   must match
typo                          6       100%       100%   must match
verbosity                     7       100%       100%   must match
word-order                    6       100%       100%   must match
```

**68 of 71 near misses rejected. 0 of 38 paraphrases rejected.**

The three that get through are honest limitations, not rounding: two need world knowledge
(`bake salmon fillets` vs `bake chicken breasts` — lexically almost identical, semantically not) and
one is an unmarked depth qualifier (`how does HTTPS work` vs `how does HTTPS work at the packet
level`). That is what the optional `Verifier` is for.

Note that `strict()` catches nothing extra on this corpus while rejecting 38% of paraphrases. It
exists for traffic whose near misses are lexically distant in ways this corpus does not model —
measure it against your own pairs before choosing it.

Run it yourself:

```bash
./gradlew :kmemo-core:test --tests '*NearMissCorpusTest*'
```

## Calibrating the threshold

A threshold copied from a blog post is a threshold tuned to somebody else's model. The same pair
scores 0.86 with one embedding model and 0.94 with another, and the failure is silent because a false
hit looks exactly like a fast response.

```kotlin
val report = ThresholdCalibrator(myEmbedder).calibrate(myLabelledPairs)
println(report.summary())

val cache = SemanticCache(myEmbedder, threshold = report.recommended.threshold)
```

A hundred pairs from your own traffic is plenty, and half should be near misses. The report sweeps
the range and shows what each setting costs in wrong answers and in missed hits.

Running it over the bundled corpus, with a deliberately adversarial stub embedder that scores every
near miss above 0.99:

| | best false-hit rate reachable | precision at that point |
|---|---|---|
| similarity threshold alone | **35.2%** (no threshold in `0.70..0.99` did better) | 0.19 |
| similarity + standard guards | **0%**, at threshold 0.81 | 1.00 |

Guards do not merely reduce false hits — they let you run a *lower* threshold safely, which returns
*more* hits than the similarity-only configuration managed at any setting.

> The stub embedder is built to make near misses look identical, so these false-hit figures
> demonstrate the mechanism honestly. The absolute recall numbers are not representative of a real
> embedding model, which rewards paraphrases in a way the stub deliberately does not. Calibrate
> against your own model and your own traffic.

## Scopes

Anything that changes what a correct answer looks like belongs in the scope string — model,
temperature, system prompt, tenant, user language. Lookups only ever see their own scope.

```kotlin
cache.getOrPut(prompt, scope = "gpt-4o|t=0.0|sysprompt-v3") { llm.complete(it) }
```

Without this, a cache shared between two models will serve one model's answer to the other's caller.
It is the second most common way a hand-rolled semantic cache goes wrong.

## Stores

`InMemoryStore` is the default: bounded, LRU-evicted, optional TTL, safe across coroutines. Search is
a linear scan, which at the default 10,000 entries and 1,536-dimensional vectors is well under a
millisecond — nothing next to the network call it replaces. Past ~100,000 entries, or as soon as you
want the cache shared between instances or surviving a restart, implement `CacheStore`:

```kotlin
public interface CacheStore {
    suspend fun put(entry: CacheEntry)
    suspend fun search(scope: String, embedding: FloatArray, limit: Int): List<ScoredEntry>
    suspend fun touch(id: String)
    suspend fun remove(id: String): Boolean
    suspend fun clear(scope: String?)
    suspend fun size(scope: String?): Int
}
```

Three rules: `search` never returns an expired entry, returns at most `limit` results sorted by
descending similarity, and every method is safe to call concurrently. Vectors are unit-normalized on
the way in, so similarity is a plain dot product.

## Observability

```kotlin
val stats = cache.stats()
stats.hitRate            // fraction served from cache
stats.guardRejections    // wrong answers that were not served
stats.belowThreshold     // prompts that simply did not repeat
```

A high `belowThreshold` means your traffic repeats less than you assumed, or your threshold is too
tight for your model. A high `guardRejections` means the opposite — prompts are landing close
together and the guards are catching near misses your threshold alone would have served.

## Scope of 0.1

**In:** `SemanticCache`, the `Embedder` and `CacheStore` abstractions, cosine search with a
configurable threshold, the nine default guards plus an opt-in `LengthRatioGuard`, an optional
`Verifier` hook, `InMemoryStore` with TTL and LRU, `ThresholdCalibrator`, the labelled corpus.

**Not yet:** Redis and Postgres/pgvector adapters, a Spring AI `Advisor`, a LangChain4j wrapper,
request coalescing for concurrent identical misses, non-English vocabularies (every guard takes its
word lists as a constructor parameter, so this is configuration rather than a fork).

## Known limitations

- Guards are lexical. Two prompts that differ only in world knowledge (`salmon` vs `chicken`) are
  invisible to them. Use a `Verifier` when that matters.
- Vocabularies are English. Everything is injectable, but nothing else ships.
- `getOrPut` is a cache, not a request coalescer: concurrent misses on the same prompt each call the
  model.
- The default threshold of 0.95 is conservative on purpose. It is a starting point, not a
  recommendation — calibrate it.

## Building

```bash
./gradlew build          # compile, explicit API mode, all warnings as errors
./gradlew test           # 87 tests
```

JDK 17+, Kotlin 2.4.

## License

Apache 2.0. Copyright © 2026 NaCode Studios.
