# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versioning follows
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- `SubstitutionGuard` — rejects prompts identical but for one word, reading structure rather than
  capitalization. It is what raised out-of-sample near-miss detection from 26% to about 70%.
- `validation-corpus.json` — 153 pairs written blind, never tuned against, nine tenths lowercase.
  With `near-miss-corpus.json` (tuned on) and `held-out-corpus.json`, the project now reports three
  numbers and says which one to trust.
- `MeasurementUnit` — units carry the dimension they measure, so `UnitGuard` no longer reads a mass
  appearing where a currency does as a swapped unit.

### Changed

- The marker guards (`NegationGuard`, `TemporalGuard`) require the rest of the prompt to match
  before a keyword counts as evidence. A lone `not` or `current` was refusing genuine paraphrases —
  "why can't I connect to the VPN" against "why is my connection to the VPN failing" — while
  catching nothing out of sample.
- `ScopeGuard` and `EntityGuard` reject a substitution, never an addition. `EntityGuard` also
  recognises an acronym written out in full, so `GDPR` matches `General Data Protection Regulation`.
- `DirectionGuard` distinguishes an asymmetric comparison (`is A better than B`) from a symmetric
  selection (`which is better, A or B`), which reverses meaning only in the first case.

- Aligned with the NaCode Studios library conventions: coordinates are `io.github.nacode-studios`,
  the package is `dev.kmemo`, and the build tracks its public API with
  binary-compatibility-validator (`./gradlew apiCheck`).

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
