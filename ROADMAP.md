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

## Status — `0.2.0` (current)

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

Published to Maven Central and GitHub Packages as `0.2.0` (tag `v0.2.0`, 2026-07-20). The next release —
`0.3.0` — is the **Tier 1** store work (M4–M7) below.

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
| **M4** · Store conformance suite (TCK) | 🚧 In progress (targeting `0.3.0`). |
| **M5** · Redis store | 🚧 In progress (targeting `0.3.0`). |
| **M6** · Postgres / pgvector store | 🚧 In progress (targeting `0.3.0`). |
| **M7** · Scaling the in-memory store (ANN) | 🚧 In progress (targeting `0.3.0`). |
| **M8** · Resilience: embedder failures & negative results | Planned. |
| **M9** · Observability: metrics, tracing, logging | Planned. |
| **M10** · Performance: batching, write-behind, benchmarks | Planned. |
| **M11** · Ergonomics: BOM, config DSL, typed & streaming responses | Planned. |
| **M12** · Multilingual vocabularies & guard packs | Planned. |
| **M13** · Spring Boot starter + Spring AI advisor | Planned. |
| **M14** · LangChain4j, Ktor plugin & a runnable demo | Planned. |
| **M15** · Quality, supply chain & test depth (CI) | Planned. |
| **M16** · The road to `1.0` | Planned. |
| **M17** · Kotlin Multiplatform core | Post-`1.0`. |
| **M18** · Advanced matching & adaptive caching | Post-`1.0`. |

**Deferred sub-items:** speculative **batch / parallel verification** (M3) is decided *against* rather
than postponed — the lookup verifies candidates best-first and short-circuits, so parallelizing would
issue more model calls to save latency, inverting the cost model the cache is built on. The
**`SNAPSHOT`-on-`main` job** (originally M1) moves to **M15**: it needs `-SNAPSHOT` versioning
discipline, and like Kdrant, Kmemo ships tag-driven releases only until then.

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
contract on every push and PR). **Deferred:** a `SNAPSHOT`-on-`main` job → M15 — it needs `-SNAPSHOT`
versioning discipline, and like Kdrant, Kmemo ships tag-driven releases until then. The milestone is kept
as the record of how Kmemo ships.

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

## Tier 1 — Stores beyond memory

The `CacheStore` seam is the Kdrant-transport analogue: match logic lives in `SemanticCache`, and a
backend only has to store vectors and return the nearest `k` in a scope. This tier proves that seam
with real adapters and a shared conformance suite, and makes the default store scale.

### M4 · Store conformance suite (TCK) — `S`

**Status: 🚧 In progress (targeting `0.3.0`).** Delivered on the `tier-1-stores` branch: a dedicated
`kmemo-store-tck` module with `CacheStoreContract` (20 cases over the seam) and a reusable `FakeClock`;
`InMemoryStore` passes it. Redis (M5) and Postgres (M6) subclass the same contract.

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

**Status: 🚧 In progress (targeting `0.3.0`).** Delivered on the `tier-1-stores` branch: `kmemo-store-redis`
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

**Status: 🚧 In progress (targeting `0.3.0`).** Delivered on the `tier-1-stores` branch: `kmemo-store-postgres`
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

**Status: 🚧 In progress (targeting `0.3.0`).** Delivered on the `tier-1-stores` branch: an opt-in
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

Make Kmemo legible to the tools teams already run, building on the per-guard counters from M2.

- A Micrometer `MeterBinder` (and/or OpenTelemetry) exposing hit rate, per-`MissReason` and per-guard
  counters, embed latency, store-search latency, and verifier latency — per scope where cardinality
  allows.
- An optional SLF4J logging hook with prompt redaction on by default (prompts can carry PII) and a
  correlation id.
- A structured `CacheEvent` stream (hit / miss / write / eviction) integrators can subscribe to without
  polling `stats()`.

### M10 · Performance: batching, write-behind & benchmarks — `L`

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

- `kmemo-spring-boot-starter`: `@ConfigurationProperties("kmemo")` + auto-config exposing a
  `SemanticCache` bean (`@ConditionalOnMissingBean`, store auto-selected from what is on the classpath).
- `kmemo-spring-ai`: a caching `Advisor` for Spring AI's `ChatClient` — Spring AI has the advisor seam
  but no semantic-cache implementation, so this is a one-annotation win with the false-hit guards
  included.
- Actuator wiring for the M9 metrics.

### M14 · LangChain4j, Ktor plugin & a runnable demo — `L`

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

### M18 · Advanced matching & adaptive caching — `L`

The research-flavoured work that deepens the moat once the fundamentals are stable.

- **Reranking / MMR** over the candidate set before the guards, so the best-answering entry — not merely
  the nearest — is the one evaluated first.
- **Near-duplicate eviction:** when a new entry is within ε of an existing one in the same scope, merge
  rather than store both, keeping the cache dense and search fast.
- **Adaptive threshold:** per-scope online calibration that nudges the threshold from observed
  hit/verifier-rejection rates, on top of the static `ThresholdCalibrator`.
- A **semantic sub-span guard**: use span embeddings to catch entity/number swaps the lexical guards
  miss without a full model call — bridging the gap between the lexical guards and the Verifier.
