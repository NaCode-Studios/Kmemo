# Stability, versioning, and the road to `1.0`

This document is kmemo's written promise about what may change and when. It complements the
[ROADMAP](../ROADMAP.md) (where the project is going) and the [CHANGELOG](../CHANGELOG.md) (what has
shipped).

## Versioning policy

kmemo follows [Semantic Versioning](https://semver.org/).

### Before `1.0` (now)

- **The public API may change between minor versions.** kmemo is pre-`1.0`, and the surface is still
  being shaped by what the corpus and real usage teach.
- **Every public-API change is tracked, never silent.** The binary-compatibility-validator holds each
  module to its `*.api` file; a breaking change fails CI unless the `.api` file is updated in the same
  change, and it is then called out in the CHANGELOG.
- **Patch releases** are bug fixes and additive, non-breaking changes.
- Adapters and integrations (`kmemo-store-*`, `kmemo-spring-*`, `kmemo-langchain4j`, `kmemo-ktor`) may
  move faster than the core to keep pace with the frameworks they wrap.

### From `1.0` on

- **Backwards compatibility within a major version.** No breaking change to a stable public API without
  a major version bump. Deprecations come first, with a migration path, and last at least one minor
  version before removal.
- The binary-compatibility contract (`*.api`) becomes the enforced guarantee, not just a tripwire.

## Between releases: snapshots

`main` always carries the in-development `-SNAPSHOT` version, published to the Maven Central snapshots
repository on every push (see `.github/workflows/snapshot.yml`). Integrators who need an unreleased fix
before the next tag can depend on it — with the understanding that a snapshot is mutable and unstable by
definition. Tagged releases (`vX.Y.Z`) remain the only immutable, supported artifacts.

## The road to `1.0`

`1.0` is not a feature milestone; it is a **stability** milestone. It will be cut when all of the
following are true, not on a fixed date:

1. **The core API is settled.** `SemanticCache`, the `Embedder` / `CacheStore` / `Verifier` seams, the
   guard interfaces, and the event/observability types have gone at least one minor version without a
   breaking change, and no reshaping is planned.
2. **At least one persistent store is production-proven.** Redis (`kmemo-store-redis`) or Postgres
   (`kmemo-store-postgres`) has real deployment behind it, not just a green Testcontainers run.
3. **The headline numbers are reproducible and stated honestly.** Near-miss rejection and paraphrase
   retention on the *blind* corpus, plus lookup latency and footprint, published as figures anyone can
   reproduce — and honest about the world-knowledge gap the `Verifier` fills.
4. **The defaults are final** (see below), each justified by the corpus and real traffic.

The `1.0` CHANGELOG will restate every guarantee above with the numbers behind it.

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

Finalizing these — with the corpus and real traffic behind each choice — is one of the `1.0` gates
above.
