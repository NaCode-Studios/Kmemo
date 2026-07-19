# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versioning follows
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.1.0]

First release. Core semantic cache, provider-agnostic, one transitive dependency.

### Added

- `SemanticCache` — embed, search, threshold, guard, optionally verify. `getOrPut` embeds a prompt
  once and reuses the vector for both the lookup and the write.
- `Embedder` — bring your own embedding source; kmemo ships none and depends on no provider SDK.
- `CacheStore` — storage and nearest-neighbour SPI, with `InMemoryStore` as the default: bounded,
  LRU-evicted on confirmed hits, optional TTL, safe across coroutines.
- Nine guards against false cache hits, all on by default: `NumericGuard`, `UnitGuard`,
  `TemporalGuard`, `NegationGuard`, `AntonymGuard`, `EntityGuard`, `ScopeGuard`, `DirectionGuard`,
  `LexicalDivergenceGuard`. `LengthRatioGuard` ships too but stays out of `standard()`, since terse
  and verbose phrasings of the same question differ too much in length to judge that way.
- The `MatchGuards.standard()` / `strict()` / `none()` presets.
- `Verifier` — optional final check on candidates that already cleared threshold and guards.
- `ThresholdCalibrator` — sweeps thresholds over labelled prompt pairs and reports what each setting
  costs in wrong answers and missed hits, so the threshold is measured against your embedding model
  rather than copied from someone else's.
- Scopes — entries are partitioned, so one model's answers are never served to another's callers.
- `CacheStats` — hit rate plus a breakdown of misses by cause.
- A corpus of 109 labelled prompt pairs, checked on every build: 68 of 71 near misses rejected, 0 of
  38 genuine paraphrases rejected.

[Unreleased]: https://github.com/NaCode-Studios/kmemo/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/NaCode-Studios/kmemo/releases/tag/v0.1.0
