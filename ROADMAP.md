# Kmemo Roadmap

This document tracks where Kmemo is going. It complements the [CHANGELOG](CHANGELOG.md)
(which records what has already shipped) and the short *Roadmap* section in the
[README](README.md). How milestones and shipped-state are marked here follows the shared
[roadmap conventions](ROADMAP-CONVENTIONS.md) — the same standard Kdrant uses.

Kmemo is pre-`1.0`: the public API may change between minor versions, and the milestones below may be
re-ordered as the project learns. Every public-API change is tracked by the
binary-compatibility-validator (`*.api` files), so breakage is never silent.

## Guiding principles

- **Correctness over hit rate.** The failure mode of a semantic cache is not a miss, it is a
  **false hit** — serving a cached answer to a question it does not answer. The costs are asymmetric:
  a wrong rejection costs one API call, a wrong acceptance costs a wrong answer. Every feature is
  judged against that asymmetry first, hit rate second. Guards abstain rather than guess, and a guard
  may never reject a genuine paraphrase.
- **Small footprint, provider-agnostic by default.** `kmemo-core` depends only on
  `kotlinx-coroutines-core` and ships no embedding provider and no database. Embedders and stores are
  one-method seams (`Embedder`, `CacheStore`); every adapter — Redis, pgvector, OpenAI, ONNX — is an
  *opt-in* module that never lands on the core classpath.
- **Coroutine-first and idiomatic.** Every operation is a `suspend` function; cancellation is
  cooperative and `CancellationException` is always propagated. New surface area follows the same
  scope-isolated, type-safe style rather than exposing raw config objects.
- **Measured, not asserted.** Correctness claims are backed by labelled corpora with a blind
  validation split, and the numbers are reported honestly — including where the guards fail. Every
  new guard or matcher earns its place against that corpus before it ships. Positioning competes on
  false-hit protection, diagnosability, DX and footprint — not on being the fastest ANN index.

## Status — `1.0.0` (current)

`1.0.0` is the **Tier 5 "quality & the road to `1.0`"** release, and the `1.0` milestone it leads to: CI,
supply chain and test depth brought up to a mature OSS standard, and a written stability commitment now
in effect.

- **Test depth (M15):** property-based tests (kotest-property) for the `Vectors` maths and the `Text`
  tokenizer; the near-miss corpus is a CI regression gate (floors on all three splits) with a documented
  process for growing the blind splits without contaminating them ([docs/CORPUS.md](docs/CORPUS.md)).
- **Quality & supply chain (M15):** ktlint and detekt as CI gates, configured to the project's deliberate
  house style rather than against it; a JDK `17 / 21 / 23` matrix; Dependabot; and a dependency-review
  CVE gate. Coverage is deferred (Kover is blocked by a Kotlin 2.4 incompatibility).
- **`1.0` (M16):** the written semver / stability policy — backwards-compatible within `1.x` — the
  Java-interop position, and the rationale behind every default, in [docs/STABILITY.md](docs/STABILITY.md).

Releases are tag-driven (no SNAPSHOT publishing), the convention shared across NaCode Studios' libraries.
Post-`1.0` work is Kotlin Multiplatform (M17) and advanced matching (M18).

## Status — `0.5.0`

`0.5.0` ships **Tier 3 "DX & reach"** and **Tier 4 "ecosystem & adoption"** together: lower the friction
from "interesting" to "in my service by lunch," make the guards usable outside English, and meet JVM
developers inside the frameworks they already use — where no semantic cache ships today.

- **Ergonomics (M11):** `catching { }`, a coroutine-safe `Result` wrapper that re-throws
  `CancellationException`; a `ResponseCodec<T>` seam and a typed `getOrPut<T>` that caches structured
  outputs, not just text; `getOrPutStreaming` that replays a streamed answer as a `Flow<String>` on a
  hit and caches only a stream that completes cleanly; a `semanticCache { }` config DSL over the growing
  constructor; and a `kmemo-bom` (`java-platform`) so multi-module users pin one version.
- **Multilingual (M12):** a `GuardVocabulary` bundle and `MatchGuards.standard(vocabulary)` /
  `standard(locale)`, with curated, conservative packs for **Italian, Spanish, German and French** in
  `Vocabularies`. `EntityGuard` is fully parameterized (sentence openers, non-entity capitals), so every
  guard is language-swappable. Each pack is *measured* — a localized near-miss corpus proves the guards
  catch the near-misses and keep the paraphrases in all four languages.
- **Spring (M13):** `kmemo-spring-boot-starter` auto-configures a `SemanticCache` bean from your
  `Embedder`, under `kmemo.*`; a user `CacheStore` / `Verifier` / `CacheListener` bean is picked up, and
  a metrics auto-config (gated on `kmemo-micrometer`) registers a `KmemoMetrics` bean that Actuator binds.
  `kmemo-spring-ai` adds `KmemoAdvisor`, a caching `Advisor` for Spring AI's `ChatClient` — verified
  against the real 1.0.0 advisor API.
- **LangChain4j & Ktor (M14):** `kmemo-langchain4j`'s `CachingChatModel` drops a cache in front of any
  `ChatModel`, keyed on the whole conversation so context is never ignored; `kmemo-ktor`'s `Kmemo` plugin
  exposes the cache to route handlers with a one-line `call.getOrPut`.
- **Runnable demo & write-up (M14):** `examples/` runs with no API key (`./gradlew :examples:run`) and
  shows a guard catching a live near-miss, with a `docker-compose.yml` for the Redis store; plus a
  write-up built around the honest measured numbers.

Targeting Maven Central and GitHub Packages as `0.5.0`. The next release opens **Tier 5** (quality &
supply chain, and the road to `1.0`).

## Status — `0.4.0`

`0.4.0` is the **Tier 2 "production reliability & observability"** release: the failure behaviour,
telemetry and hot-path performance a team needs before putting Kmemo on a request path.

- **Resilience (M8):** an `EmbedFailurePolicy` so a `getOrPut` can fall back to `compute` when the
  embedder is down — never worse than no cache, and `CancellationException` always propagates; an opt-in
  `RetryingEmbedder` / `Embedder.retrying(…)` with jittered exponential backoff; an opt-in negative cache
  (`negativeCacheSize` / `negativeCacheTtl`) that reuses a just-missed prompt's embedding so a sequential
  burst of the same brand-new prompt embeds once; and `SemanticCache.warm(entries)` for batch-embedded
  startup preloading. Negative caching only ever reuses a vector — it never suppresses the search — so it
  cannot manufacture a false hit.
- **Observability (M9):** a zero-dependency `CacheEvent` stream (`CacheListener`, with `CacheEvents`
  bridging it to a `Flow`) covering hit / miss / write / eviction with per-stage latencies;
  `kmemo-micrometer`, a Micrometer `MeterBinder` for hit rate, per-`MissReason` and per-guard counters and
  embed / search / verify timers; and `kmemo-slf4j`, a structured logging listener with prompt redaction
  on by default and an optional correlation id. Emission is gated on having listeners, so the default
  hot path builds no events and measures nothing.
- **Performance (M10):** `getOrPutAll(prompts)`, embedding a whole batch in one `Embedder.embedAll` call;
  opt-in, ordered **write-behind** (`writeBehindScope`) that takes the store write off the caller's
  critical path; a `kmemo-benchmarks` JMH module (lookup latency vs cache size, guard-chain cost, exact
  vs ANN, and a plain `HashMap` exact baseline); and a zero-boxing pass that keeps embeddings as
  `FloatArray` end-to-end and removes the one boxed `Double` sort key on the search path.

Targeting Maven Central and GitHub Packages as `0.4.0`. The next release — `0.5.0` — opens **Tier 3**
(DX & reach, M11–M12).

## Status — `0.3.0`

`0.3.0` is the **Tier 1 "stores beyond memory"** release: the `CacheStore` seam — match logic in the
cache, a backend only stores vectors and returns the nearest `k` in a scope — proven with real adapters
and a shared conformance suite, and the default store given a path to scale.

- **Store conformance suite (M4):** a reusable `CacheStoreContract` (`kmemo-store-tck`) with a `FakeClock`,
  so `InMemoryStore` and every adapter are held to the same seam rules.
- **Redis store (M5):** `kmemo-store-redis` — RediSearch `FT.SEARCH` KNN on a Lettuce coroutine client,
  scope a `TAG`, TTL a clock-driven `expires_at` filter plus a real key TTL for reclamation.
- **Postgres / pgvector store (M6):** `kmemo-store-postgres` — durable, over JDBC on pgvector (`<=>`),
  scope an indexed column, table auto-created (or from the shipped `schema.sql`); the JDBC driver is the
  caller's only added dependency.
- **HNSW store & byte-aware bounds (M7):** `kmemo-store-hnsw` — an opt-in pure-Kotlin approximate index
  whose candidates are rescored exactly (recall ≥ 0.9 vs exact), plus an optional `maxBytes` memory bound
  on `InMemoryStore`. The exact scan stays the default and the correctness reference.

Published to Maven Central and GitHub Packages as `0.3.0` (tag `v0.3.0`, 2026-07-21). The next release —
`0.4.0` — opens **Tier 2** (production reliability & observability, M8–M10).

## Status — `0.2.0`

`0.2.0` sharpens the two things Kmemo competes on — knowing *why* a lookup was decided the way it was,
and covering the near misses lexical rules cannot — completing **Tier 0** on top of the `0.1.0` core:

- **Per-guard measurement (M2):** `CacheStats.guardRejectionsByGuard` (a per-`MatchGuard.name`
  breakdown where every configured guard is a key, so a silent guard reads as `0`), and
  `SemanticCache.explain(prompt, scope)` — a read-only diagnostic returning each nearby candidate with
  *every* guard's verdict and whether the threshold or a guard stood in the way. It moves no counter and
  never runs the `Verifier`.
- **The Verifier, completed (M3):** fail-closed semantics — a `Verifier` that throws or exceeds
  `verifierTimeout` now *rejects* the candidate rather than serving it unconfirmed (`CancellationException`
  still propagates) — and `CachingVerifier` / `Verifier.caching(…)`, which memoizes verdicts per
  `(query, cachedPrompt)` so a hot near miss is judged once, not on every lookup.
- **Docs & canonical home:** the API reference is published to GitHub Pages via Dokka and linked from the
  README; the repository is now `NaCode-Studios/Kmemo`, with POM/SCM metadata and CI badges to match.

Published to Maven Central and GitHub Packages as `0.2.0` (tag `v0.2.0`, 2026-07-20).

## Status — `0.1.0`

`0.1.0` is the first release: a complete, tested single-module core. It provides:

- **The cache (`SemanticCache`):** `getOrPut` (embed-once for lookup and write), `lookup` / `get` /
  `put`, `invalidate` / `clear` / `size`, per-scope isolation, concurrent-miss coalescing (per exact
  prompt), typed `CacheLookup.Hit` / `Miss`, `MissReason`, and lifetime `stats()`.
- **The seams:** `Embedder` and `CacheStore` (with `ScoredEntry`), each a single `suspend` method, so
  the store owns *where entries live and when they expire* while the cache owns *whether a candidate
  is good enough to serve*.
- **The guards (the project's whole point):** ten lexical guards in `MatchGuards.standard()`
  (`Numeric`, `Unit`, `Temporal`, `Negation`, `Antonym`, `Entity`, `Substitution`, `Scope`,
  `Direction`, `LexicalDivergence`) plus an opt-in `LengthRatioGuard`, each taking its word lists as
  a constructor parameter; an optional `Verifier` for what lexical rules cannot see.
- **Storage:** `InMemoryStore` with TTL and LRU eviction.
- **Calibration:** `ThresholdCalibrator` that measures the right threshold for *your* embedding model.
- **Correctness, measured:** three labelled corpora (`near-miss` 109, `held-out` 128, `validation`
  153), the last written blind. Blind validation: near misses rejected **67%**, paraphrases kept
  **88%**.

Published to Maven Central and GitHub Packages as `0.1.0` (tag `v0.1.0`, 2026-07-19).

## Progress

| Milestone | Status |
| --- | --- |
| **0.1.0 core** | ✅ Shipped in `0.1.0`. |
| **M1** · Ship `0.1.0` to Maven Central | ✅ Shipped in `0.1.0`. |
| **M2** · Per-guard measurement & observability | ✅ Shipped in `0.2.0`. |
| **M3** · The Verifier, completed | ✅ Shipped in `0.2.0`. |
| **M4** · Store conformance suite (TCK) | ✅ Shipped in `0.3.0`. |
| **M5** · Redis store | ✅ Shipped in `0.3.0`. |
| **M6** · Postgres / pgvector store | ✅ Shipped in `0.3.0`. |
| **M7** · Scaling the in-memory store (ANN) | ✅ Shipped in `0.3.0`. |
| **M8** · Resilience: embedder failures & negative results | ✅ Shipped in `0.4.0`. |
| **M9** · Observability: metrics, tracing, logging | ✅ Shipped in `0.4.0`. |
| **M10** · Performance: batching, write-behind, benchmarks | ✅ Shipped in `0.4.0`. |
| **M11** · Ergonomics: BOM, config DSL, typed & streaming responses | ✅ Shipped in `0.5.0`. |
| **M12** · Multilingual vocabularies & guard packs | ✅ Shipped in `0.5.0`. |
| **M13** · Spring Boot starter + Spring AI advisor | ✅ Shipped in `0.5.0`. |
| **M14** · LangChain4j, Ktor plugin & a runnable demo | ✅ Shipped in `0.5.0`. |
| **M15** · Quality, supply chain & test depth (CI) | ✅ Shipped in `1.0.0`. |
| **M16** · The road to `1.0` | ✅ Shipped in `1.0.0` — `1.0` cut. |
| **M17** · Kotlin Multiplatform core | Post-`1.0`. |
| **M18** · Advanced matching & adaptive caching | Post-`1.0`. |
| **M19** · Keying: exact-match fast path & conversation-aware keys | Planned. |
| **M20** · Shadow mode: calibrate on your own traffic | Planned. |
| **M21** · Invalidation beyond TTL | Planned. |
| **M22** · Cache policy: what must never be cached | Planned. |
| **M23** · The comparative benchmark | Planned. |

**Next up.** Tier 7 (M19–M23) is sequenced *before* Tier 6's M17–M18: it answers what teams hit once the
cache is on a request path, where M17 opens a new market and M18 is research-flavoured. Within it,
**M19** and **M20** come first — one removes the embedding call from the most common lookup, the other
removes the reason a team hesitates to serve a cached answer at all — then M22, M21 and M23.

**Deferred sub-items:** speculative **batch / parallel verification** (M3) is decided *against* rather
than postponed — the lookup verifies candidates best-first and short-circuits, so parallelizing would
issue more model calls to save latency, inverting the cost model the cache is built on. The
**`SNAPSHOT`-on-`main` job** (originally M1, revisited in M15) is **decided against**: like Kdrant, Kmemo
ships **tag-driven releases only**, and a mutable snapshot stream is not worth the versioning machinery
for a library this size. The `1.x` line is the supported artifact.

**Considered and declined (Tier 7 review):**

- **Provider prompt caching as a third cache layer.** The KV reuse behind Anthropic's and OpenAI's
  prompt caching happens inside the provider's API — it is billing and transport behaviour with no seam
  for Kmemo to own. Presenting it as a Kmemo layer would take credit for a saving the library does not
  produce. It belongs in the docs as context on how the layers compose, not on the roadmap as work.
- **Automatic PII redaction with a reversible mapping.** Reversible means the mapping is stored, which
  relocates the sensitive data rather than removing it — a weaker posture than simply not caching the
  entry (M22), sold as a stronger one. Detection stays a user-supplied predicate.
- **Distributed propagation of the negative cache.** The negative cache is deliberately local and only
  ever reuses a vector; it never suppresses a search, which is exactly why it cannot manufacture a false
  hit. Making it a coherent distributed structure would add a coherence problem and a network hop to
  defend that property, in exchange for saving embedding calls.
- **A shipped event-bus module (Redis Pub/Sub / Kafka).** `CacheListener` already *is* that seam, and
  bridging it to a broker is a few lines in the application — where the broker dependency and its
  configuration belong. It ships as a documented recipe, not a module.
- **Distributed cache-warming locks.** `warm()` racing across instances wastes embedding calls at
  startup; it does not corrupt anything. Teams running N instances already hold a lock primitive, and a
  library-owned distributed lock is a large permanent surface for a one-off startup cost.
- **PCA / dimensionality reduction** of stored embeddings — see M18, where quantization with exact
  rescoring is accepted and PCA is not.

## Effort legend

`S` ≈ hours–1 day · `M` ≈ several days · `L` ≈ 1–2 weeks · `XL` ≈ multi-week / multiple sub-parts.

---

## Tier 0 — Release & measurement foundations

Ship what exists, then sharpen the two things Kmemo competes on: knowing exactly *why* a lookup was
decided the way it was, and covering the near misses lexical rules cannot.

### M1 · Ship `0.1.0` to Maven Central — `S`

**Status: ✅ Shipped in `0.1.0`.** Delivered: `kmemo-core` published to Maven Central under
`io.github.nacode-studios` (signing, `sources` + `javadoc` jars) and mirrored to GitHub Packages via the
tag-driven `release.yml`; rich POM metadata and a Dokka API-docs site on GitHub Pages (`docs.yml`) linked
from the README; and `apiCheck` as a CI release gate (`./gradlew build` verifies the `*.api` compatibility
contract on every push and PR). Like Kdrant, Kmemo ships **tag-driven releases only** — the
`SNAPSHOT`-on-`main` job once floated for M15 was decided against. The milestone is kept as the record of
how Kmemo ships.

Turn the built core into an artifact people can depend on.

- Publish `kmemo-core` to Maven Central under `io.github.nacode-studios` (signing, `sources` + `javadoc`
  jars) and mirror to GitHub Packages, via the tag-driven `release.yml`.
- Rich POM metadata (`description`, `url`, `scm`, license, developers) and a Dokka API-docs site on
  GitHub Pages (`docs.yml`), linked from the README.
- `apiCheck` runs in CI as a release gate — `./gradlew build` verifies the `*.api` compatibility
  contract on every push and PR.

### M2 · Per-guard measurement & observability — `S`

**Status: ✅ Shipped in `0.2.0`.** Delivered: `CacheStats.guardRejectionsByGuard` (a per-`MatchGuard.name`
breakdown that sums to `guardRejections`, with every configured guard a key so a silent guard reads as
`0`); `SemanticCache.explain(prompt, scope)` returning each nearby candidate with *every* guard's verdict;
the corpus harness `GuardReport` (per-guard precision / recall across all three corpora, emitted as a
machine-readable artifact); and stable, documented `CacheLookup.Miss.detail` guard attribution.

Today `CacheStats` counts *why* a lookup missed at the reason granularity (`belowThreshold`,
`guardRejections`, `verifierRejections`). Tuning needs one level finer: *which* guard, how often, and
at what cost.

- Extend `stats()` with a per-guard breakdown (`Map<String, Long>` keyed by `MatchGuard.name`) so a
  noisy guard is visible in production, not only in the corpus test.
- A `GuardReport` in the corpus harness: per-guard precision / recall (near misses caught vs
  paraphrases wrongly rejected) across all three corpora, printed by the `CorpusTest` and emitted as a
  machine-readable artifact.
- Make the `CacheLookup.Miss.detail` guard attribution stable and documented (guard name + reason),
  since integrators log and alert on it.
- A `dryRun`/`explain(prompt, scope)` entry point that returns every candidate with each guard's
  verdict — the tool you reach for when a hit you expected did not happen.

### M3 · The Verifier, completed — `M`

**Status: ✅ Shipped in `0.2.0`.** Delivered: fail-closed semantics — a `Verifier` that throws or exceeds
`verifierTimeout` rejects the candidate (a `REJECTED_BY_VERIFIER` miss) rather than serving it unconfirmed,
`CancellationException` still propagating — and `CachingVerifier` / `Verifier.caching(…)`, memoizing
verdicts per `(query, cachedPrompt)`, bounded and optionally TTL'd, with a throwing delegate never cached;
plus a reference judge prompt documented as a provider-agnostic recipe. **Deferred:** speculative **batch /
parallel verification** is decided *against*, not postponed — the lookup verifies candidates best-first and
short-circuits, so parallelizing would issue more model calls to save latency, inverting the cost model the
cache is built on.

A third of the validation near misses need world knowledge (`deworm a puppy` vs `deworm an adult dog`,
`boiling point of ethanol` vs `methanol`) — invisible to any lexical rule. The `Verifier` seam exists;
this milestone makes it a first-class, safe, affordable path.

- A reference `Verifier` contract and prompt template (a strict "same correct answer? YES/NO" judge)
  documented as a recipe, staying provider-agnostic — Kmemo ships the seam, not the model call.
- **Verdict caching:** memoize `(query, cached)` verdicts so a repeated near-miss pair is judged once,
  not on every lookup; key on normalized text, bounded and TTL'd.
- **Fail-safe semantics:** decide and document what a verifier *exception* or timeout means (default:
  treat as a miss — never serve unverified on error), with a configurable `verifierTimeout`.
- Batch/parallel verification when `candidates > 1` so the extra check does not serialize the hot path.
- Corpus wiring: an optional corpus run *with* a stub verifier to quantify the ceiling the Verifier
  raises the 67% toward.

---

## Tier 1 — Stores beyond memory — ✅ Shipped in `0.3.0`

The `CacheStore` seam is the Kdrant-transport analogue: match logic lives in `SemanticCache`, and a
backend only has to store vectors and return the nearest `k` in a scope. This tier proves that seam
with real adapters and a shared conformance suite, and makes the default store scale.

### M4 · Store conformance suite (TCK) — `S`

**Status: ✅ Shipped in `0.3.0`.** Delivered: a dedicated
`kmemo-store-tck` module with `CacheStoreContract` (20 cases over the seam) and a reusable `FakeClock`;
`InMemoryStore` passes it, and the Redis, Postgres and HNSW stores subclass the same contract.

Write the contract tests once, before the adapters, so every store is held to the same three rules the
`CacheStore` KDoc already states (never return an expired or out-of-scope entry; at most `limit`
results, best-first; concurrency-safe).

- A reusable `CacheStoreContract` (abstract test / test factory) covering put/replace-by-id, scope
  isolation, TTL expiry, `limit` and ordering, `touch` recency, `remove` / `clear(scope)` / `size`,
  and concurrent access.
- Run it against `InMemoryStore` today; every new adapter (M5, M6) ships green against it or does not
  ship.
- A tiny `FakeClock`-driven expiry harness reused across stores.

### M5 · Redis store — `M`

**Status: ✅ Shipped in `0.3.0`.** Delivered: `kmemo-store-redis`
using RediSearch `FT.SEARCH` KNN on a Lettuce coroutine client — scope as a `TAG`, a clock-driven
`expires_at` filter plus a real Redis key TTL, and the RediSearch-absent case failing fast. Green against
the M4 conformance suite via Testcontainers.

The most-requested backend and the one that proves cross-process sharing (neither Spring AI nor
LangChain4j ships a semantic cache — see M13/M14).

- `kmemo-store-redis` using vector search (RediSearch `FT.SEARCH` KNN) with a Lettuce coroutine client;
  scope as a tag field, TTL delegated to Redis key expiry.
- Graceful degradation and a documented fallback when the RediSearch module is absent.
- Green against the M4 conformance suite; a Testcontainers integration test.
- Redis owns eviction and expiry; Kmemo owns matching — no match logic reimplemented in the adapter.

### M6 · Postgres / pgvector store — `L`

**Status: ✅ Shipped in `0.3.0`.** Delivered: `kmemo-store-postgres`
on pgvector (`<=>`), scope an indexed column, an `expires_at` predicate driven by the injected clock, the
table auto-created (or provisioned from the shipped `schema.sql`), and the JDBC driver left as the caller's
only added dependency. Green against the M4 conformance suite via Testcontainers.

The backend teams already run, and the one that makes "durable semantic cache" a one-dependency
choice.

- `kmemo-store-postgres` on `pgvector` (`<->` / `<=>` operators, an HNSW or IVFFlat index), scope as an
  indexed column, TTL as an `expires_at` predicate + a sweep, via R2DBC or a coroutine-friendly JDBC
  pool.
- Schema migration SQL shipped and documented; nullable-dimension safety for mixed embedding sizes.
- Green against the M4 conformance suite; Testcontainers integration test on a real Postgres + pgvector.

### M7 · Scaling the in-memory store (ANN) — `L`

**Status: ✅ Shipped in `0.3.0`.** Delivered: an opt-in
pure-Kotlin HNSW store (`kmemo-store-hnsw`) whose candidates are rescored exactly (recall measured ≥ 0.9
vs an exact ranking), and an optional `maxBytes` memory bound on `InMemoryStore` (LRU-evicted alongside
`maxEntries`, with a `bytes` figure in its stats). The exact scan stays the default and the correctness
reference; recall/latency benchmarking is M10.

`InMemoryStore.search` is an exact linear scan — correct and fine to tens of thousands of entries,
O(n) beyond that. Give the default store a path to large caches without changing the seam.

- An optional in-process approximate index (HNSW) behind the same `CacheStore`, selected by size or by
  explicit construction; exact scan stays the default and the correctness reference.
- **Byte-aware bounds:** today `maxEntries` bounds *count*; add an optional memory-size bound
  (embeddings dominate: `dimensions * 4` bytes each) so a cache in a constrained service cannot OOM.
- Benchmarks (M10) quantify recall vs latency vs memory for exact vs ANN.

---

## Tier 2 — Production reliability & observability

Everything a team needs before it will put Kmemo on a request path: predictable failure behaviour,
numbers in their dashboards, and a hot path that does not become the bottleneck it was meant to remove.

### M8 · Resilience: embedder failures & negative results — `M`

**Status: ✅ Shipped in `0.4.0`.** Delivered: `EmbedFailurePolicy`
(`PROPAGATE` / `FALL_BACK_TO_COMPUTE`, `CancellationException` always propagated); `RetryingEmbedder`
and `Embedder.retrying(…)` with jittered exponential backoff; an opt-in negative cache
(`negativeCacheSize` / `negativeCacheTtl`) that reuses a just-missed prompt's embedding without ever
suppressing the search; and `SemanticCache.warm(entries)` for batch-embedded startup preloading.

The `Embedder` is a network call the cache makes on **every** lookup. Its failure modes are currently
the caller's problem; own them.

- Defined behaviour when `embed` throws: propagate vs fall back to `compute` (never fail a `getOrPut`
  that could have just called the model), configurable, with `CancellationException` always propagated.
- Optional retry-with-backoff around `embed` (opt-in, jittered), mirroring the resilience posture of the
  wider ecosystem.
- **Negative caching (opt-in):** remember prompts that just missed so a burst of the same brand-new
  prompt embeds once, not once per caller beyond the existing exact-text coalescing.
- A `warm(entries)` / bulk-preload path for seeding a cache from known FAQ pairs at startup.

### M9 · Observability: metrics, tracing, structured logging — `M`

**Status: ✅ Shipped in `0.4.0`.** Delivered: a zero-dependency
`CacheEvent` stream (`CacheListener`; `CacheEvents` republishes it as a `Flow`) covering hit / miss /
write / eviction with per-stage latencies and the guard name on a rejection; `kmemo-micrometer`, a
`MeterBinder` for hit rate, per-`MissReason` and per-guard counters and embed / search / verify timers
(scope left untagged to bound cardinality); and `kmemo-slf4j`, a structured logging listener with prompt
redaction on by default and an optional correlation id. OpenTelemetry is left to a future adapter on the
same seam.

Make Kmemo legible to the tools teams already run, building on the per-guard counters from M2.

- A Micrometer `MeterBinder` (and/or OpenTelemetry) exposing hit rate, per-`MissReason` and per-guard
  counters, embed latency, store-search latency, and verifier latency — per scope where cardinality
  allows.
- An optional SLF4J logging hook with prompt redaction on by default (prompts can carry PII) and a
  correlation id.
- A structured `CacheEvent` stream (hit / miss / write / eviction) integrators can subscribe to without
  polling `stats()`.

### M10 · Performance: batching, write-behind & benchmarks — `L`

**Status: ✅ Shipped in `0.4.0`.** Delivered: `getOrPutAll(prompts)`
over the existing `Embedder.embedAll` batch default; opt-in ordered write-behind (`writeBehindScope`,
falling through to a synchronous write when the buffer is full so no write is lost); a `kmemo-benchmarks`
JMH module (lookup vs cache size, guard-chain cost, exact vs ANN, plain-`HashMap` baseline); and a
zero-boxing pass — `FloatArray` end-to-end, with the one boxed sort key on the search path removed.
Per-scope latency percentiles beyond the JMH figures are left to the deployed metrics.

Optimize the paths that run on every request and prove the footprint/latency story with numbers.

- **Batch embedding:** a `getOrPutAll(prompts)` / batch lookup that hands the `Embedder` many prompts
  at once (most providers price and rate-limit per request, not per token) — an `Embedder.embedBatch`
  default that maps over `embed`, overridable.
- **Write-behind puts:** make the cache write on a hit-miss non-blocking so `getOrPut` returns as soon
  as `compute` does, with the store write off the caller's critical path (opt-in, ordered).
- Zero-boxing hot path: keep embeddings as `FloatArray` end-to-end (already the case in `Vectors`);
  audit for hidden boxing in the guard chain and search.
- A JMH benchmark module: lookup p50/p99 vs cache size, guard-chain cost, exact vs ANN — repeatable in
  CI, honest about where a plain `HashMap` exact cache wins.

---

## Tier 3 — DX & reach

Lower the friction from "interesting" to "in my service by lunch," and make the guards usable outside
English.

### M11 · Ergonomics: BOM, config DSL, typed & streaming responses — `M`

**Status: ✅ Shipped in `0.5.0`.** Delivered: `kmemo-bom`
(`java-platform`); a `catching { }` `Result` helper that re-throws `CancellationException`; a typed
`getOrPut<T>` over a `ResponseCodec<T>` seam; `getOrPutStreaming` returning a `Flow<String>` (caching
only a cleanly-completed stream); and a `semanticCache { }` builder DSL.

- A `kmemo-bom` (`java-platform`) so multi-module users pin one version.
- A `catching { }` helper returning `Result<T>` (re-throwing `CancellationException`); the
  exception/`null` style stays primary.
- **Typed responses:** a `getOrPut<T>` overload that caches structured outputs (JSON tool-calls,
  extracted objects) via a pluggable serializer, not just `String` — the second-most-common LLM caching
  shape.
- **Streaming responses:** cache the assembled text of a streamed completion and replay it as a
  `Flow<String>` on a hit, so streaming callers are not forced onto the blocking path.
- A small config DSL / builder for the `SemanticCache(...)` parameter set, matching the library's
  scope-isolated style.

### M12 · Multilingual vocabularies & guard packs — `M`

**Status: ✅ Shipped in `0.5.0`.** Delivered: a `GuardVocabulary`
bundle and `MatchGuards.standard(vocabulary)` / `standard(locale)`; conservative packs for Italian,
Spanish, German and French in `Vocabularies`; `EntityGuard` parameterized (sentence openers, non-entity
capitals) so every guard is language-swappable; and a localized near-miss corpus that measures each pack
(near-misses caught, paraphrases kept) rather than asserting it.

Every guard already takes its markers as a constructor parameter, so adapting to a language is
configuration, not a fork. Ship that configuration.

- Curated vocabulary packs (`Vocabulary` / marker sets) for the highest-traffic languages — negation,
  antonyms, temporal and scope markers, units — starting with Italian, Spanish, German, French.
- A `MatchGuards.standard(locale)` factory and documented guidance on building a pack from a language's
  traffic.
- Language-specific near-miss corpus slices so a non-English pack is *measured*, not asserted — the same
  bar as an English guard.

---

## Tier 4 — Ecosystem & adoption

The single highest-leverage adoption driver: meet JVM developers inside the frameworks they already
use — where, notably, **no semantic cache ships today** — and give them something runnable.

### M13 · Spring Boot starter + Spring AI advisor — `L`

**Status: ✅ Shipped in `0.5.0`.** Delivered: `kmemo-spring-boot-starter`
(a `SemanticCache` bean from an `Embedder` bean, `kmemo.*` properties, store/verifier/listener beans
picked up, a `KmemoMetrics` bean auto-configured for Actuator when `kmemo-micrometer` is present) and
`kmemo-spring-ai` (`KmemoAdvisor`, a caching `Advisor` for `ChatClient`, verified against the real Spring
AI 1.0.0 advisor API).

- `kmemo-spring-boot-starter`: `@ConfigurationProperties("kmemo")` + auto-config exposing a
  `SemanticCache` bean (`@ConditionalOnMissingBean`, store auto-selected from what is on the classpath).
- `kmemo-spring-ai`: a caching `Advisor` for Spring AI's `ChatClient` — Spring AI has the advisor seam
  but no semantic-cache implementation, so this is a one-annotation win with the false-hit guards
  included.
- Actuator wiring for the M9 metrics.

### M14 · LangChain4j, Ktor plugin & a runnable demo — `L`

**Status: ✅ Shipped in `0.5.0`.** Delivered: `kmemo-langchain4j`
(`CachingChatModel` wrapping any `ChatModel`, keyed on the whole conversation, verified against the real
LangChain4j 1.0.1 API), `kmemo-ktor` (a `Kmemo` server plugin with a `call.getOrPut` convenience,
driven through a real route under `testApplication`), a runnable `examples/` demo (no API key; a
`docker-compose.yml` for the Redis store), and an honest-measurement write-up. The coordinated
announcement is left for when `1.0` lands.

- `kmemo-langchain4j`: a wrapper on LangChain4j's model interfaces so a cache drops in front of an
  existing `ChatLanguageModel`.
- `kmemo-ktor`: a small server plugin / client wrapper for the Ktor-native crowd.
- `examples/`: a runnable app (a chatbot or RAG endpoint with a real embedder and a persistent store,
  docker-compose included) that demonstrates the guards catching a live near miss — linked at the top of
  the README, the single best onboarding asset.
- A coordinated write-up (a blog post + Kotlin Weekly / r/Kotlin) built around the honest
  measurement story.

---

## Tier 5 — Quality & the road to `1.0`

### M15 · Quality, supply chain & test depth (CI) — `M`

**Status: ✅ Shipped in `1.0.0`.** Delivered: ktlint and detekt as
CI gates tuned to the house style; a JDK 17/21/23 matrix; Dependabot + a dependency-review CVE gate;
property-based tests on `Vectors` and `Text`; and the corpus documented as a defended, CI-gated asset
([docs/CORPUS.md](docs/CORPUS.md)). The `guard-report.json` artifact is the reproducible false-hit
benchmark; a separately-hosted public version is left for later. **Deferred:** Kover coverage (its 0.9.x
line does not support Kotlin 2.4's `KotlinWithJavaCompilation`), and — following the tag-driven convention
shared with Kdrant — SNAPSHOT publishing and release-time provenance.

Bring CI and tests up to a mature OSS standard, and make the corpus a first-class, defended asset.

- Kover (coverage report + minimum threshold + badge), detekt and ktlint as Gradle tasks and CI gates
  (the build already runs explicit-API mode and `allWarningsAsErrors`).
- Dependabot / Renovate (Gradle + GitHub Actions) and a dependency-review / CVE step on PRs.
- A JDK `17 / 21 / 23` matrix; build-provenance / SLSA attestation on release.
- A `SNAPSHOT` publish job on `main` (with `-SNAPSHOT` versioning) so integrators can track unreleased
  fixes between tagged releases — carried over from M1.
- **The corpus as CI:** run all three corpora on every PR and fail on regression; a documented process
  for growing the *validation* split without contaminating it (its whole value is that no guard was
  tuned against it). Property-based tests on `Vectors` (normalize/dot invariants) and the text
  normalizer.
- A public, versioned false-hit benchmark others can reproduce and cite.

### M16 · The road to `1.0` — `M`

**Status: ✅ Shipped in `1.0.0` — `1.0` cut.** Delivered: a written
semver / stability policy (backwards-compatible within `1.x`), the Java-interop position (coroutine-first;
`CompletableFuture` bridges now, a `kmemo-jdk` facade deferred to demand), and the documented rationale
behind every default — all in [docs/STABILITY.md](docs/STABILITY.md).

Cut `1.0` with written guarantees and reproducible numbers behind every claim.

- A written semver / stability policy and a `1.0` scope-and-date plan; cut `1.0` once the core API is
  stable and at least one persistent store (M5 or M6) is production-proven.
- The headline `1.0` claim — near-miss rejection and paraphrase retention on the blind corpus, plus
  lookup latency and footprint — stated as reproducible figures, honest about the world-knowledge gap
  the Verifier fills.
- Decide and document the Java-interop position; an optional `kmemo-jdk` facade (`CompletableFuture`)
  if the demand is there.
- Finalize the defaults (`threshold`, `candidates`, the `standard()` guard set) with the corpus and real
  traffic behind each choice.

---

## Tier 6 — Post-`1.0`

### M17 · Kotlin Multiplatform core — `L`

Expand the market after `1.0` without delaying its time-to-market. The core is close but not free of
the JVM.

- Move `kmemo-core` to `commonMain`, replacing the JVM-only APIs it uses today: `java.time.Clock` /
  `Instant` / `Duration` → `kotlinx-datetime` and `kotlin.time`; `java.util.UUID` → a multiplatform id;
  `java.util.concurrent.atomic` → `kotlinx.atomicfu`.
- Publish KMP targets of the core and `InMemoryStore`; keep the JVM adapters (Redis, pgvector, Spring)
  JVM-only. Announce on klibs.io / kmp-awesome.
- **Why it earns the migration:** an on-device cache is a different product from a server-side one. A
  mobile LLM app pays for every call over a mobile network, a browser or Wasm tool has no server to cache
  on, and an edge or embedded deployment may have no reliable uplink at all — cases where a local
  semantic cache is not an optimization but the thing that makes the feature work. The groundwork is
  favourable: `FloatArray`, the `Embedder` and `CacheStore` seams and the guards are already pure Kotlin,
  and `InMemoryStore` is portable as written. Redis, Postgres and Spring stay JVM-only by design, not by
  omission.
- **Kdrant's M25 is the same migration on the same toolchain** — run them together and pay the learning
  once.

### M18 · Advanced matching & adaptive caching — `L`

The research-flavoured work that deepens the moat once the fundamentals are stable.

- **Reranking / MMR** over the candidate set before the guards, so the best-answering entry — not merely
  the nearest — is the one evaluated first. A *cross-encoder* reranker is deliberately **not** the shape:
  a cross-encoder is a model call, which is precisely what the `Verifier` seam already is, so it would
  duplicate an existing stage while putting an ML dependency next to a core that has one dependency.
- **Quantized candidates with exact rescoring:** store `int8` (or binary) embeddings to cut what an entry
  costs in memory, retrieve candidates on the quantized vectors, then rescore the survivors on exact
  vectors before the threshold decision. The precision loss then lands only on *which* candidates get
  considered, never on the accept/reject decision itself — the same rescore-exactly discipline the HNSW
  store already follows (M7), and the only form of compression compatible with "correctness over hit
  rate". **PCA / dimensionality reduction is declined:** it needs a projection fitted per embedding
  model, it degrades similarity in a way rescoring cannot recover, and it puts model-fitting machinery
  inside a library whose core deliberately has none.
- **Near-duplicate eviction:** when a new entry is within ε of an existing one in the same scope, merge
  rather than store both, keeping the cache dense and search fast.
- **Adaptive threshold:** per-scope online calibration that nudges the threshold from observed
  hit/verifier-rejection rates, on top of the static `ThresholdCalibrator` and the traffic curve M20's
  shadow mode produces.
- A **semantic sub-span guard**: use span embeddings to catch entity/number swaps the lexical guards
  miss without a full model call — bridging the gap between the lexical guards and the Verifier.

---

## Tier 7 — Production depth & proof

What a team runs into *after* the cache is on a request path: the cost of the lookup itself, trusting a
threshold against their own traffic rather than the project's corpus, invalidating on something other
than the passage of time, keeping data out of the cache that must never be in it — and, for the project,
finally measuring the central claim against an alternative instead of only against itself.

### M19 · Keying: an exact-match fast path and conversation-aware keys — `M`

Two questions the API answers implicitly today, and each implicit answer costs a user either money or
correctness.

- **An exact-match layer (L1) ahead of the embedding call.** Every `getOrPut` currently embeds — a
  network call — even when the prompt is byte-for-byte one already cached. Retries, replayed agent
  loops, automated test suites and polling clients hit that path constantly. A hash-keyed exact layer
  answers them in microseconds, and it **needs no guards at all**: an identical prompt in the same scope
  is the same question, so the fast path adds no false-hit risk by construction rather than by
  measurement. It composes with the existing negative cache (which already reuses a just-missed prompt's
  embedding), and it is the single change that most improves the cost model, because embedding — not
  search, not the guard chain — dominates what a lookup costs.
- **Conversation-aware keys, made explicit.** `kmemo-langchain4j` already keys on the whole conversation,
  but core `getOrPut` has no notion of a turn — so a caller who caches turn five of a dialogue on its
  text alone will serve a confident wrong answer. That is exactly the failure this library exists to
  prevent, arrived at through the key rather than through the threshold, where no guard can see it.
  `scope` is already the primitive that fixes it; what is missing is the documented pattern, a helper
  for deriving a key from the last *N* turns, and a plain statement that a context-free first turn is
  the shape semantic caching actually pays off on.

### M20 · Shadow mode: calibrate on your own traffic — `M`

`ThresholdCalibrator` measures the right threshold, but it needs a labelled set — so the honest answer to
"what threshold should I use?" is currently "measure it, on data you first have to build". That first
step, not the cache itself, is what stops teams putting a semantic cache in front of production traffic.

- An observe-only mode: run the full lookup — embed, search, guards, threshold — **serve nothing**, and
  record what *would* have happened, over a configurable window.
- Record the decision across a *range* of thresholds in a single pass, so the output is the team's own
  precision/recall curve against their own traffic, not one yes/no at one setting.
- Report it through the seams that already exist — a `CacheEvent` variant and a `MeterBinder` counter set
  — so the curve lands in dashboards teams already run, and reuse `explain()`'s per-candidate machinery
  rather than growing a parallel code path beside it.
- **Per-scope threshold override**, the small companion: one global threshold is necessarily wrong for a
  service answering both regulated and casual questions.
- This is "measured, not asserted" turned around to face the *user's* deployment instead of the
  project's corpus — the same principle, applied where the adoption decision actually gets made.

### M21 · Invalidation beyond TTL — `L`

When the fact behind a cached answer changes, the only options today are `invalidate` on one exact prompt
or `clear(scope)` on everything. A TTL is a guess about when knowledge *might* go stale; it is not a way
to act on knowing that it just did.

- **Document the pattern that already works, first:** version the scope (`pricing-v3.2` →
  `pricing-v3.3`) and clear the old one. It needs no new API, it cuts over atomically, and for a great
  many teams it is the entire answer. This ships as documentation before any seam changes.
- Where that granularity is too coarse, grow the seam: entry tags at `put` time, and an
  `invalidateByTag` / predicate-based bulk delete on `CacheStore`.
- **It is a seam change, so it is priced as one:** it touches `CacheStore`, the `kmemo-store-tck`
  contract and all four store adapters (in-memory, Redis, Postgres, HNSW). The TCK cases get written
  first, the way M4 established.
- Distributed coherence largely falls out for free: with the Redis or Postgres store the store *is* the
  shared state, so an invalidation is already global. Only `InMemoryStore` is per-instance, which is
  inherent to what it is rather than a gap to close.

### M22 · Cache policy: what must never be cached — `S`

`kmemo-slf4j` redacts prompts in *logs*, but the cache *stores* prompts and responses verbatim. For a
regulated adopter the first question is not how accurate the guards are, it is whether they can prove a
given class of data never got persisted — and today answering it means wrapping `getOrPut` yourself.

- A `CachePolicy` seam: one predicate over the prompt and the computed response that can veto the
  **write** while the call still returns normally. A vetoed write is a policy decision, not a failure, so
  it surfaces as its own `CacheEvent` and counter rather than being silently indistinguishable from a
  miss in the stats.
- Kmemo ships the seam, not a PII detector — the same posture as `Embedder` and `Verifier`, and for the
  same reason: `kmemo-core` depends on `kotlinx-coroutines-core` and nothing else.
- Document that **per-tenant isolation is already `scope`**, and that the TCK has enforced scope
  isolation on every store since M4 — so "can tenant B retrieve tenant A's entry" has a tested answer,
  not an asserted one.

### M23 · The comparative benchmark — `L`

Kmemo's whole claim is that it rejects near misses other semantic caches serve. That claim has never been
measured *against* another semantic cache — only against Kmemo's own corpus. This is the one artifact
that turns the positioning into something a reader can check, and the one most likely to be cited.

- A corpus harness running the same labelled near-miss corpus through **Kmemo**, **a threshold-only
  baseline** (the naive configuration most teams actually deploy), and **GPTCache**, the Python
  incumbent.
- Report **precision, recall, F1 and false-hit rate** — decision quality, on identical inputs, with the
  same embedding model. Deliberately **do not** report cross-runtime latency or throughput against
  GPTCache: a JVM-versus-Python wall-clock figure compares runtimes while appearing to compare caches,
  and publishing it would undercut the honesty the rest of the corpus work is built on. Latency, storage
  per 1K entries and throughput stay in `kmemo-benchmarks`, measured across Kmemo's own configurations.
- A **cost model** — at X queries/day and Y% hit rate, the saving against no cache, set against the cost
  of a false hit at Z% — because whoever has to approve adopting this is already doing that arithmetic,
  with worse numbers.
- Publish the figures together with the corpus and the harness, versioned, so the claim is reproducible
  rather than asserted. This completes M15's "a public, versioned false-hit benchmark others can
  reproduce and cite".
