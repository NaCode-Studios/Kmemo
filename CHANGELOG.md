# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versioning follows
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.6.0] - 2026-07-22

`0.6.0` is the **Tier 5 "quality & the road to `1.0`"** release: CI, supply chain and test depth brought
up to a mature OSS standard, and the written groundwork for `1.0`.

### Added

- Property-based tests (M15): kotest-property invariants for the `Vectors` maths (normalize → unit
  length, dot symmetry, cosine scale-invariance, non-finite rejection) and the `Text` tokenizer.
- Coverage (M15): a JaCoCo line-coverage report and a 90% floor on `kmemo-core`, wired into `check`.
  (Kover is the natural choice but its 0.9.x line does not support the Kotlin 2.4 compilation model;
  JaCoCo works on bytecode and is unaffected.)
- Linters (M15): ktlint and detekt as CI gates. ktlint is configured to the two rules the project wants
  (no wildcard imports, the 120-column limit) with its opinionated formatting set left off, since the
  codebase has a deliberate house style; detekt runs `buildUponDefaultConfig` with a small library-aware
  config and a per-module baseline so it gates new smells, not existing ones.
- CI & supply chain (M15): a JDK 17/21/23 build matrix; `.github/dependabot.yml` (Gradle + Actions); a
  dependency-review CVE gate on PRs; and SLSA build-provenance attestation of the published jars on
  release.
- SNAPSHOT publishing (M15): the version is a `kmemoVersion` Gradle property defaulting to a
  `-SNAPSHOT`; `snapshot.yml` publishes it from `main`, and the release workflow overrides it with the
  tag version.
- Docs (M15/M16): `docs/CORPUS.md` (the process for growing the blind corpus splits without
  contaminating them) and `docs/STABILITY.md` (the semver/stability policy, the `1.0` scope gates, the
  Java-interop position, and the rationale behind every default).

### Changed

- The project version is now resolved from the `kmemoVersion` property (default `0.6.0-SNAPSHOT`) so
  `main` can carry a snapshot between releases; a tagged release sets the exact version.

## [0.5.0] - 2026-07-22

`0.5.0` covers **Tier 3 "DX & reach"** and **Tier 4 "ecosystem & adoption"** together: lower the friction
from "interesting" to "in my service by lunch," make the guards usable outside English, and meet JVM
developers inside the frameworks they already use — where no semantic cache ships today.

### Added

- `catching { }` (M11): a coroutine-safe `Result` wrapper — like `runCatching`, but it re-throws
  `CancellationException` (and `Error`s) instead of capturing them, so structured concurrency still
  works. The exception / `null` style stays primary across kmemo.
- Typed responses (M11): a `ResponseCodec<T>` seam and a `getOrPut(prompt, codec) { … }` overload that
  caches a **structured** value — a parsed object, a tool-call — as the text the store keeps, decoding
  it on a hit. No serialization library lands on the core classpath; you bring the codec.
- Streaming responses (M11): `getOrPutStreaming(prompt) { … }` returns a `Flow<String>`. On a hit it
  replays the assembled answer; on a miss it passes the upstream chunks through while accumulating them,
  and caches the text **only if the stream completes normally** — a partial or failed stream caches
  nothing.
- Config DSL (M11): `semanticCache(embedder) { … }` over a `SemanticCacheBuilder`, so a cache that sets
  a few of the constructor's options reads by name instead of threading past the rest.
- BOM (M11): a new `kmemo-bom` (`java-platform`) module — import it once and depend on any kmemo
  artifact without repeating the version.
- Multilingual guard packs (M12): a `GuardVocabulary` bundle and `MatchGuards.standard(vocabulary)` /
  `standard(locale)`, plus curated, conservative packs for **Italian, Spanish, German and French** in
  `Vocabularies` (negation, antonyms, temporal / scope / directional markers, and local unit spellings
  aliased to the vetted canonicals). `forLocale(Locale)` resolves by language code and fails loudly for
  an unsupported one. Each pack is measured against a localized near-miss corpus — near-misses caught,
  paraphrases kept — not asserted.
- Spring Boot starter (M13): a new `kmemo-spring-boot-starter` module. Auto-configures a `SemanticCache`
  bean the moment an `Embedder` bean is present, bound from `kmemo.*` properties; the store defaults to
  `InMemoryStore` but a user `CacheStore` bean wins, and `Verifier` / `CacheListener` beans are attached.
  A separate metrics auto-config, gated on `kmemo-micrometer` being on the classpath, registers a
  `KmemoMetrics` bean — at once a cache listener and an Actuator `MeterBinder`.
- Spring AI advisor (M13): a new `kmemo-spring-ai` module — `KmemoAdvisor`, a caching `Advisor` for
  Spring AI's `ChatClient`. A hit short-circuits the chain and serves the cached answer; a miss calls the
  model, caches the reply text, and returns the model's real response untouched. Guards included;
  streaming passes through. Verified against the Spring AI 1.0.0 advisor API.
- LangChain4j integration (M14): a new `kmemo-langchain4j` module — `CachingChatModel`, a `ChatModel`
  that puts a `SemanticCache` in front of another model. The cache key is the whole conversation, so a
  question after a different exchange cannot be served the earlier answer; non-text / tool requests pass
  through uncached. Verified against the LangChain4j 1.0.1 API.
- Ktor plugin (M14): a new `kmemo-ktor` module — a `Kmemo` server plugin (`install(Kmemo) { cache = … }`)
  that exposes the cache to route handlers, with a `call.getOrPut(…)` convenience for caching an LLM call
  in one line.
- Runnable demo (M14): a new `examples/` module — `./gradlew :examples:run` warms an FAQ and shows a
  paraphrase served from cache, a numeric near-miss refused by the guard, and an unrelated question
  missing on threshold. Runs with no API key (a local embedder); `KMEMO_REDIS_URL` switches it to the
  Redis store, with a `docker-compose.yml` included.
- Write-up (M14): `docs/blog/your-semantic-cache-has-a-false-hit-problem.md`, on the false-hit problem
  and the guards, built around the honest measured numbers.

### Changed

- `EntityGuard` and `Text.entityTokens` now take the sentence-opener and non-entity-capital sets as
  parameters (defaulting to the English `Vocabulary`), so the entity guard is language-swappable like
  the rest. `Vocabulary.NON_ENTITY_CAPITALS` is now public. Existing English callers are unaffected.
- `kmemo-bom` constrains every published module, including the four ecosystem modules
  (`kmemo-spring-boot-starter`, `kmemo-spring-ai`, `kmemo-langchain4j`, `kmemo-ktor`).

## [0.4.0] - 2026-07-21

`0.4.0` is the **Tier 2 "production reliability & observability"** release: predictable failure
behaviour, telemetry in the tools teams already run, and a hot path that does not become the bottleneck
it was meant to remove.

### Added

- Embed-failure policy (M8): `SemanticCache(embedFailurePolicy = …)` — `PROPAGATE` (the default) or
  `FALL_BACK_TO_COMPUTE`, which degrades a `getOrPut` to an uncached model call when the embedder throws,
  so a lookup is never worse than no cache. The fall-back cannot write back (there is no embedding to key
  it), and `CancellationException` always propagates. `lookup` / `get` / `put` always propagate.
- Retrying embedder (M8): `RetryingEmbedder` and the `Embedder.retrying(…)` extension — opt-in, jittered
  exponential backoff around `embed` / `embedAll`, never retrying `CancellationException`.
- Negative caching (M8): opt-in `SemanticCache(negativeCacheSize = …, negativeCacheTtl = …)` remembers a
  just-missed prompt's embedding so an immediate repeat of the same brand-new prompt embeds once, not
  once per caller. It only ever reuses a vector — it never suppresses the store search — so it cannot
  cause a false hit.
- Bulk preload (M8): `SemanticCache.warm(entries)` seeds a cache from known prompt/response pairs (an
  FAQ, a golden set), embedding the whole batch in one call.
- Event stream (M9): a zero-dependency `CacheEvent` (`Hit` / `Miss` / `Write` / `Eviction`) delivered to
  `CacheListener`s inline, with per-stage latencies and the guard name on a rejection; `CacheEvents`
  republishes the stream as a `Flow`. Emission is gated on having listeners, so the default hot path
  builds no events and measures nothing. `InMemoryStore(listener = …)` emits eviction/expiry events.
- Micrometer metrics (M9): a new `kmemo-micrometer` module — `KmemoMetrics`, a `MeterBinder` exposing hit
  rate, per-`MissReason` and per-guard counters, and embed / search / verify timers. Scope is left
  untagged to keep cardinality bounded.
- SLF4J logging (M9): a new `kmemo-slf4j` module — `Slf4jCacheListener`, a structured log line per event
  with prompt redaction on by default (prompts can carry PII) and an optional correlation id.
- Batch embedding (M10): `SemanticCache.getOrPutAll(prompts)` looks up many prompts at once, embedding
  the whole batch in a single `Embedder.embedAll` call — the win where a provider prices per request, not
  per token.
- Write-behind puts (M10): opt-in `SemanticCache(writeBehindScope = …)` returns from a `getOrPut` miss as
  soon as `compute` does and applies the store write off the caller's critical path, in order, by one
  worker. A full buffer falls through to a synchronous write, so no write is ever lost; `put` and `warm`
  always write through.
- Benchmarks (M10): a new `kmemo-benchmarks` JMH module (not published) — lookup latency vs cache size,
  guard-chain cost, exact scan vs HNSW, and a plain-`HashMap` exact baseline. Compiled on every `check`,
  run on demand with `./gradlew :kmemo-benchmarks:jmh`.

### Changed

- `InMemoryStore.search` now sorts candidates with a primitive comparator instead of
  `sortByDescending { it.similarity }`, whose selector boxed a `Double` per entry across the whole scope
  on the lookup hot path. Behaviour is unchanged; the allocation is gone (part of the M10 zero-boxing
  pass, which confirmed embeddings stay `FloatArray` end-to-end).

## [0.3.0] - 2026-07-21

`0.3.0` is the **Tier 1 "stores beyond memory"** release: the `CacheStore` seam proven with real backends
and a shared conformance suite, plus a path for the default store to scale.

### Added

- Store conformance suite (M4): a new `kmemo-store-tck` module exposing `CacheStoreContract` — the
  reusable test every `CacheStore` must pass (put / replace-by-id, scope isolation, TTL expiry, `limit`
  and best-first ordering, `touch`, `remove` / `clear(scope)` / `size`, and real-threaded concurrent
  access) — plus a `FakeClock` for deterministic TTL tests. `InMemoryStore` is now held to it, and every
  future store adapter ships green against the same contract or does not ship.
- Redis store (M5): `kmemo-store-redis` — a `CacheStore` backed by Redis with RediSearch, for a cache
  shared across processes. Nearest-neighbour search is `FT.SEARCH ... KNN` on an exact `FLAT` index, so
  the adapter reimplements no match logic; scope is a `TAG` field, and TTL is a clock-driven `expires_at`
  filter plus a real Redis key TTL for reclamation. Built on a Lettuce coroutine client; green against the
  M4 conformance suite under Testcontainers.
- Postgres / pgvector store (M6): `kmemo-store-postgres` — a durable `CacheStore` over JDBC using
  pgvector's cosine-distance operator (`<=>`), scope as an indexed column, and an `expires_at` predicate
  driven by the injected clock. The table is created on first use (or provision it from the shipped
  `schema.sql`); the Postgres driver is the caller's only added runtime dependency. Green against the M4
  conformance suite under Testcontainers.
- HNSW store and byte-aware bounds (M7): `kmemo-store-hnsw` — an opt-in, pure-Kotlin approximate-nearest-
  neighbour `CacheStore` that scales past the exact in-memory scan. The graph only proposes candidates,
  which are then rescored exactly, so scope, TTL, `size` and `remove` stay exact and only recall is
  approximate (measured ≥ 0.9 vs exact search). `InMemoryStore` also gains an optional `maxBytes` memory
  bound (evicted LRU alongside `maxEntries`) and a `bytes` figure in its stats, so a cache in a
  memory-constrained service cannot grow without bound.

## [0.2.0] - 2026-07-20

### Added

- Fail-closed verifier semantics (M3) and `SemanticCache(verifierTimeout = …)` — a `Verifier` that throws
  or exceeds the timeout now rejects the candidate (a `REJECTED_BY_VERIFIER` miss whose `detail` says
  which), instead of propagating the error or serving an unconfirmed answer. `CancellationException`
  still propagates, so cancellation is unaffected.
- `CachingVerifier` and the `Verifier.caching(…)` extension (M3) — memoizes verdicts per
  `(query, cachedPrompt)`, bounded and optionally TTL'd, so a hot near miss is judged once rather than
  on every lookup. A delegate that throws is never cached, so a transient outage cannot freeze a
  rejection into the cache.
- `SemanticCache.explain(prompt, scope)` (M2) — a read-only diagnostic returning a `CacheExplanation`: the
  nearest candidates with *every* guard's verdict (not just the first rejection), and a `decision`
  that says whether the threshold or a guard would stand in the way. It moves no counter, marks
  nothing recently-used, and never runs the `Verifier` — the tool for "why wasn't this a hit?".
- `CacheStats.guardRejectionsByGuard` (M2) — guard rejections broken down by `MatchGuard.name`, so a noisy
  or silent guard is visible in production and not only in the corpus test. The values sum to
  `guardRejections`, and every configured guard is a key, so one that never fires reads as `0` rather
  than being absent.

### Changed

- The API reference is now published to GitHub Pages with Dokka (`docs.yml`) and linked from the
  README; the repository was renamed to `NaCode-Studios/Kmemo`, and the POM/SCM metadata, GitHub
  Packages URL and CI badges were updated to the canonical location.

## [0.1.0] - 2026-07-19

First release. Core semantic cache, provider-agnostic, one transitive dependency.

### Added

- `SemanticCache` — embed, search, threshold, guard, optionally verify. `getOrPut` embeds a prompt
  once and reuses the vector for both the lookup and the write. Concurrent `getOrPut` calls for the
  same prompt and scope are coalesced: the first computes, the rest wait and are served its answer,
  since a cold cache under load is exactly when duplicate calls are most likely and most expensive.
- `Embedder` — bring your own embedding source; Kmemo ships none and depends on no provider SDK.
- `CacheStore` — storage and nearest-neighbour SPI, with `InMemoryStore` as the default: bounded,
  LRU-evicted on confirmed hits, optional TTL, safe across coroutines.
- Ten guards against false cache hits, all on by default: `NumericGuard`, `UnitGuard` (units carry
  the dimension they measure via `MeasurementUnit`, so a mass appearing where a currency does is not
  read as a swapped unit), `TemporalGuard`, `NegationGuard`, `AntonymGuard`, `EntityGuard` (which
  also recognises an acronym written out in full, so `GDPR` matches `General Data Protection
  Regulation`), `SubstitutionGuard` (rejects prompts identical but for one word, reading structure
  rather than capitalization), `ScopeGuard`, `DirectionGuard` (distinguishes an asymmetric comparison
  from a symmetric selection), and `LexicalDivergenceGuard`. `LengthRatioGuard` ships too but stays
  out of `standard()`. The marker guards require the rest of the prompt to match before a keyword
  counts as evidence, and the substitution guards reject a substitution, never an addition.
- The `MatchGuards.standard()` / `strict()` / `none()` presets.
- `Verifier` — optional final check on candidates that already cleared threshold and guards.
- `ThresholdCalibrator` — sweeps thresholds over labelled prompt pairs and reports what each setting
  costs in wrong answers and missed hits, so the threshold is measured against your embedding model
  rather than copied from someone else's.
- Scopes — entries are partitioned, so one model's answers are never served to another's callers.
- `CacheStats` — hit rate plus a breakdown of misses by cause.
- Three labelled corpora, checked on every build: `near-miss-corpus.json` (109 pairs, tuned on),
  `held-out-corpus.json` (128) and a blind `validation-corpus.json` (153, nine tenths lowercase,
  never tuned against). Blind validation rejects 67% of near misses while keeping 88% of paraphrases.
- Published to Maven Central and GitHub Packages under `io.github.nacode-studios` (package
  `dev.kmemo`), with the public API tracked by binary-compatibility-validator (`./gradlew apiCheck`).

[Unreleased]: https://github.com/NaCode-Studios/Kmemo/compare/v0.3.0...HEAD
[0.3.0]: https://github.com/NaCode-Studios/Kmemo/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/NaCode-Studios/Kmemo/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/NaCode-Studios/Kmemo/releases/tag/v0.1.0
