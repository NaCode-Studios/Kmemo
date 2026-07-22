# Stability and versioning

This document is kmemo's written promise about what may change and when. It complements the
[ROADMAP](../ROADMAP.md) (where the project is going) and the [CHANGELOG](../CHANGELOG.md) (what has
shipped).

## Versioning policy

kmemo follows [Semantic Versioning](https://semver.org/). As of **`1.0`**, the stability guarantee below
is in effect.

- **Backwards compatibility within the `1.x` line.** No breaking change to a stable public API without a
  major version bump. Deprecations come first, with a migration path, and last at least one minor version
  before removal.
- **Every public-API change is tracked, never silent.** The binary-compatibility-validator holds each
  module to its `*.api` file; a breaking change fails CI unless the `.api` file is updated in the same
  change, and it is then called out in the CHANGELOG. This is now the enforced guarantee, not just a
  tripwire.
- **Minor releases** add capability compatibly; **patch releases** are bug fixes.
- Adapters and integrations (`kmemo-store-*`, `kmemo-spring-*`, `kmemo-langchain4j`, `kmemo-ktor`) may
  move faster than the core to keep pace with the frameworks they wrap, but stay within semver.

## Releases

Releases are **tag-driven**: pushing a `vX.Y.Z` tag publishes that immutable version to Maven Central and
GitHub Packages. There is **no SNAPSHOT stream** — the tagged `1.x` artifacts are the supported ones, the
same convention used across NaCode Studios' libraries.

## What `1.0` commits to

`1.0` is a **stability** milestone, not a feature one:

1. **The core API is settled.** `SemanticCache`, the `Embedder` / `CacheStore` / `Verifier` seams, the
   guard interfaces, and the event/observability types are stable and held to the `*.api` contract.
2. **The correctness story is measured, not asserted.** Near-miss rejection and paraphrase retention are
   reported on a *blind* corpus that no guard was tuned against, gated in CI against regression
   ([CORPUS.md](CORPUS.md)), and honest about the world-knowledge gap the `Verifier` fills.
3. **The defaults are the considered ones** (below), each justified by the corpus.

Real-world hardening continues within `1.x` — persistent stores are green against the shared conformance
suite and Testcontainers, and production feedback will refine them without breaking the API.

## Java interoperability

kmemo is **coroutine-first**: every operation is a `suspend` function, because a cache that fronts
network calls belongs in the same structured-concurrency and cancellation model as the calls it
replaces. That is a deliberate design choice, not an oversight.

**From Java**, the same API is reachable through the standard Kotlin↔Java coroutine bridges:

- `kotlinx.coroutines.future.FutureKt` — call a `suspend` function as a `CompletableFuture`.
- `runBlocking` for a synchronous call at a boundary that has no async context.

A dedicated `kmemo-jdk` facade (a `CompletableFuture`-returning mirror of the API) is **deferred, not
rejected**: it will ship if there is real demand from Java-only callers. Until then, the bridges above
are the supported path, and the position is documented rather than left implicit.

## The defaults, and why

kmemo's defaults err toward **missing rather than serving a wrong answer** — the asymmetry the whole
library turns on (a wrong rejection costs one API call; a wrong acceptance costs a wrong answer). Each
is a starting point to calibrate against your own model and traffic, not a universal constant.

| Default | Value | Why |
| --- | --- | --- |
| `threshold` | `0.95` | Deliberately tight. The same prompt pair scores 0.86 with one embedding model and 0.94 with another, so no library default is right for everyone; this one errs toward a miss. Calibrate with `ThresholdCalibrator`. |
| `candidates` | `5` | Enough to recover when a guard vetoes the closest entry and the second-nearest is a correct answer, without scanning the whole scope. |
| `guards` | `MatchGuards.standard()` | Every guard that pays for itself on the corpus, ordered cheapest-and-most-decisive first, tuned to reject **no** genuine paraphrase. |
| `coalesceConcurrentMisses` | `true` | A cold cache under load is where duplicate model calls are most expensive and most likely; the first caller computes, the rest wait. |
| `embedFailurePolicy` | `PROPAGATE` | A failing embedder should be visible in your metrics and retries, not silently turn the cache into a pass-through. |
