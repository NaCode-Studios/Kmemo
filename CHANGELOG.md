# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versioning follows
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [2.1.0] - 2026-08-02

Tier 8: independent proof, and the path onto a production request.

### Added

- **The external corpus split (M24).** The three corpora in `docs/CORPUS.md` are careful about
  contamination and that discipline holds. It still cannot answer the objection that matters most to
  somebody deciding whether to trust this cache: the same person wrote the pairs and the guards, so
  they test the near misses that were *thought of* rather than the near misses that *exist*.
  A fourth split answers it. **PAWS** (Paraphrase Adversaries from Word Scrambling), Wiki
  `labeled_final`, **test** split. 8,000 pairs built by Google Research in 2019 to measure whether a
  model can separate a paraphrase from a near-paraphrase when word overlap is deliberately high, which
  is the one case a similarity threshold cannot handle and the case every guard here was built for, by
  people who had never heard of this library.
  **The number is much worse than the other three, and it ships beside them rather than in a drawer:**
  647 of 4,464 near misses rejected (14%) against roughly 68% on the blind internal splits, and 2,807
  of 3,536 paraphrases kept (79%) against 88%. Two things explain the gap and neither shrinks it. A
  corpus built to defeat lexical overlap is harder than one written from realistic traffic, which is
  what PAWS is for. And the register does not match: PAWS pairs are declarative Wikipedia sentences
  where the guards read prompts, which shows in the breakdown: `substitution` alone rejects 498
  paraphrases here against 2 on validation. A lower figure from a harder source is worth more than
  another figure from the same source.
  The data is **fetched, never vendored** (`tools/external-corpus/fetch.py`), so the licence stays with
  the dataset, and the revision is pinned to a commit with its SHA-256 verified. `ExternalCorpusTest`
  holds it to floors set *at* the measurement rather than under it, since nothing about it is
  stochastic. Absent, it skips and says how to get it; CI passes `-PexternalCorpusRequired=true`, where
  absent is a failure, because a floor nobody notices has stopped running is not a floor.
- **The embedder's identity as part of the key (M25).** An entry stored a vector and the prompt that
  produced it, and nothing about *what* produced the vector. Swap the embedding model for another with
  the same dimension count, which most upgrades inside a provider's family are, and every entry
  written by the old model was still searched, still scored and still served. The two models do not
  share a space, so those similarities meant nothing, and a meaningless number near a threshold is
  precisely the condition that produces a false hit. No error, nothing in the logs.
  `Embedder.identity` is declared by the caller, `CacheEntry.embedder` records it, and a lookup refuses
  an entry carrying a different one before the guards ever read it. New `MissReason.EMBEDDER_MISMATCH`,
  new `CacheEvent.EmbedderMismatch` naming both identities, new `CacheStats.embedderMismatches`, and a
  `kmemo.cache.embedder.mismatches` meter. The default is `Embedder.UNDECLARED`, and it is an identity
  rather than a wildcard: a caller who declares nothing sits on both sides of the check and behaves
  exactly as before. `PostgresStore` and `RedisStore` persist it; rows written earlier read as
  `undeclared`, which is the record they actually have. `docs/MIGRATION.md` names the two ways through
  an existing store: re-embed, or put the model in the scope.
- **Streaming responses, cached and replayed (M26).** `getOrPutStreaming` already forwarded a stream
  and cached its text, but a hit replayed that text as a single element, so the cache could sit on a
  streaming path without ever streaming back. `CacheEntry.chunkLengths` records the boundaries an
  answer arrived in and a hit replays those chunks. `StreamReplay` makes the timing decision explicit:
  `AS_STREAMED` (the default) emits the recorded chunks with no delay, `WHOLE` is the `2.0` behaviour.
  There is deliberately **no** option reproducing the original pacing. That would mean storing how
  long one model call took on one network on one day and then sleeping through it to make a cache hit
  look like the thing it replaced.
  The two rules that make the path safe are unchanged and now have tests naming them: every decision
  about whether to serve happens **before the first token is handed over**, since a token already read
  cannot be taken back; and a stream that fails or is cancelled partway **writes nothing**, since a
  truncated answer stored as a complete one is worse than no entry at all.
- **`kmemo-guard-tck`, the guard compliance suite (M27).** `MatchGuard` has been public since `1.0`, so
  a third party could always write a guard. What they could not do is find out whether it was any good,
  and a guard nobody measured fails in two directions that look nothing alike from the inside: one that
  abstains too rarely rejects real paraphrases and quietly turns a working cache into an expensive
  proxy, and one that abstains too often does nothing while looking like it does something.
  The module is **published**, which is the answer to the question the milestone posed about how it
  should ship: a guard author adds one test dependency, subclasses `MatchGuardContract`, and gets back
  the same confusion matrix this project reports for its own guards. Six properties, each documented
  with what it exists to catch: deterministic, total, reflexive, a reason on every rejection, a stable
  name, no false rejections on ordinary English. Symmetry is deliberately not among them: a directional
  guard is legitimate, `subspan` is one, so disagreements are counted and reported rather than failed.
  The three shipped corpora are general English, so a domain guard catches nothing in them and that is
  the correct result, because they are how an author shows the guard does *no harm*. Whether it does any good
  is a number only the author's own corpus produces, and the suite takes one.
  `RouteOfAdministrationGuard` in `examples/` is the worked case, living outside `kmemo-core`: same
  drug, same dose, oral against intravenous, every word overlapping and the answers half an hour apart.
  Nineteen lines of test to arrive with a number. The eleven built-in guards are put through the same
  suite from the same artifact a stranger downloads.

### Changed

- **Every published module's POM description names the platforms that module actually supports.** The
  POM Maven Central serves for `kmemo-core:2.0.0` reads "on Kotlin/JVM"; `2.0.0` is the release that
  made the core multiplatform, so the sentence was false on the day it was published, beside a
  `kotlin-tooling-metadata.json` from the same build listing iOS, Linux and Wasm. `checkPomPlatforms`
  runs in `check` and keeps it corrected in both directions: a module publishing native targets may not
  describe itself as JVM-only, and a JVM-only module must say so. A POM on Maven Central is immutable,
  so `2.0.0` cannot be repaired and the correct text reaches the public here.
- A `getOrPutStreaming` hit now emits the answer's original chunks rather than one element. Pass
  `StreamReplay.WHOLE` for the previous behaviour.
- **Binary compatibility.** `2.1.0` is **source compatible** with `2.0.0` and **not binary compatible**
  with it, which [STABILITY.md](STABILITY.md) names as a minor-version boundary: code compiled against
  `2.0.0` must be **recompiled**, and no source has to change. Four types gained a parameter with a
  default, which moves their constructor and `copy` signatures: `CacheEntry` (`embedder`,
  `chunkLengths`), `CacheStats` (`embedderMismatches`), `CacheLookup.Hit` (`chunkLengths`) and
  `CandidateTrace` (`embedderMatches`). Measured with `git diff v2.0.0 v2.1.0 -- '*/api/*.api'`, which
  reports thirteen removed lines, every one of them those signatures.
- **Two exhaustive `when`s stop compiling.** `MissReason` gained a fifth value and `CacheEvent` an
  eighth member, so a `when` over either with no `else` branch needs the new case before it builds.
  `kmemo-micrometer` and `kmemo-slf4j` already handle it; upgrade them together.
- **Twelve published modules**, up from eleven: `kmemo-guard-tck` joins the BOM and the aggregated API
  site.

### Fixed

- The shipped `schema.sql` for `PostgresStore` had drifted from the schema the code creates: it was
  missing the `tags` column and its GIN index, so a table provisioned by hand from that file could not
  serve `invalidateByTag`. Both are there now, along with the two columns `2.1.0` adds.

### Internal

- Three fixes the `2.0.0` release surfaced, none of which change what is published:
  the provenance step attests `.klib` artifacts as well as `.jar`s, so the Apple, Linux and Windows
  targets are covered by the same claim the README makes about every artifact; the linked-artifacts step
  uses `shasum`, since the release job moved to macOS where `sha256sum` does not exist; and Dependabot
  is told to leave `kotlin-js-store` alone, because that lock file is generated from the yarn
  resolutions in `build.gradle.kts` and a patch written into it is undone by the next build. Its alerts
  are answered by adding a resolution, which is what `serialize-javascript` 7.0.5 and `diff` 8.0.3 are.
- CI installs Python and runs `tools/external-corpus/fetch.py` before the build, then passes
  `-PexternalCorpusRequired=true` to every Gradle invocation, which is the only place the external
  split's floor is actually enforced.

## [2.0.0] - 2026-07-31

### Added

- **Kotlin Multiplatform core (M17).** `kmemo-core` and `InMemoryStore` build for the JVM, iOS, macOS,
  Linux, Windows, JS and WasmJS. A phone pays for every call over a mobile network, a browser or Wasm
  tool has no server to cache on, and an edge deployment may have no reliable uplink — none of which a
  JVM-only cache reaches. The Redis, Postgres, HNSW and framework adapters stay JVM-only by design: they
  wrap drivers that exist nowhere else.
  It costs **no new dependency**. `kotlin.time.Instant` and `kotlin.time.Clock` are stable in Kotlin
  2.4, so `kmemo-core` still declares `kotlinx-coroutines-core` and nothing more, on every target.
  What the port actually turned on was the access-ordered `LinkedHashMap`, which does not exist outside
  the JVM and which the store's eviction, the exact-match layer and the verifier's memo were all built
  on. The replacement draws a line the JVM version blurs: reading through `get` counts as a use and
  reading through `peek` does not, which matters because the store scans every entry in a scope on
  every search — if scanning counted, the eviction order would reset on every lookup.
  The klib ABI is validated alongside the JVM signature dump, so a change invisible on the JVM cannot
  break an iOS consumer unnoticed. Releases publish from a macOS runner, because Apple targets cannot
  be compiled anywhere else and a Linux runner would have shipped a release quietly missing them.
- **Advanced matching (M18)**, four opt-in pieces around the match path and one new guard. Each trades
  something, and each is off by default because the trade is the caller's to make.
  - `CandidateReranker` and `MmrReranker` reorder the candidates that cleared the threshold, so each one
    the cache tries adds something the last did not. On a cache that has been running a while the
    nearest entries are rephrasings of each other and a `Verifier` costs a model call per candidate, so
    five paid calls that all inspect one entry is four wasted. Reranking runs **after** the threshold
    filter, never before: `search` returns entries best-first, and reordering ahead of the filter would
    put a below-threshold entry in front of an above-threshold one and turn the cheap exit into a wrong
    answer. A reranker that returns a different number of candidates is refused rather than trusted.
  - `Quantization` on `InMemoryStore`: `INT8` or `BINARY` codes for the scan, with every survivor
    rescored against the full-precision vectors. The discipline is the point — quantization decides
    which candidates are *looked at*, never whether one is *served* — so the worst a bad approximation
    can do is cost a candidate, a miss worth one API call, and it cannot move a similarity across the
    threshold. Recall is measured against an exact scan at 64 and 1,536 dimensions: `INT8` recovers
    everything at four times oversampling, `BINARY` needs twenty-four to reach 99% and that cost is
    written down rather than hidden.
  - `deduplicateWrites`: a new entry replaces the one it duplicates instead of joining it, so a question
    answered in six phrasings stops being six copies every later lookup has to score. Similarity alone
    decides nothing here — the write path can produce a false hit exactly as the read path can, so the
    same guards run in both directions and only a pair they would have served for each other is merged.
    Reported as `EvictionCause.NEAR_DUPLICATE`.
  - `AdaptiveThresholds`: each scope's threshold follows its own traffic. **The constructor throws
    without a `Verifier`**, and that is not a warning that can be argued around: adaptation lowers the
    threshold as well as raising it, and the only thing that makes lowering safe is something above the
    threshold that can tell a right answer from a wrong one. With a verifier in the loop the threshold
    stops being a correctness knob and becomes a cost knob — how many candidates reach the verifier —
    which is a quantity the cache can honestly observe about itself.
  - `SubSpanGuard`, and it is in `standard()`. Every other guard looks for a word that *changed*; this
    one is for the near miss where nothing changed and something was *added*. `How do I deploy a Rails
    app` against the same question `on Heroku` has perfect word overlap, so no lexical guard sees it.
    Three conditions before it fires, each one there because dropping it refused a real paraphrase: one
    prompt's content words must contain the other's, the added words must all sit in one span, and that
    span must open with a qualifier rather than a pronoun or a hedge. Measured at **zero** false
    rejections across all three corpora and one new catch on the blind validation split, which moves
    `standard()` from 0.333 to 0.324 there and `strict()` from 0.314 to 0.304.
- **[docs/MIGRATION.md](docs/MIGRATION.md)**, for moving from `1.x`. Five breaks, each with who it
  affects and the edit that resolves it: a recompile, the Maven coordinate, `kotlin.time.Instant` on
  `CacheEntry.createdAt`, the import the `Locale` overloads now need, and the fourth `EvictionCause`
  value that makes an exhaustive `when` stop compiling. It also names the one behaviour change, the
  eleventh guard in `standard()`.
- The tuned corpus grows by twenty pairs covering the added-qualifier shape, twelve near misses and
  eight paraphrases that look like them and are not. The tuned split is in-sample by definition and
  `docs/CORPUS.md` says growing it is free; the blind splits are untouched, and the tuned near-miss
  floor rises from 63 to 74 with the validation floor from 65 to 68.
- The verifier's catch rate on the guard residual, measured. The README said the 67% / 88% figures were
  guard-only and that what a `Verifier` stops afterwards was unknown; that gap is closed. Against a
  named reference implementation — `sentence_transformers.CrossEncoder` over
  `cross-encoder/quora-distilroberta-base` — a verifier stops **80%** of the residual on held-out and
  **78%** on validation, taking the false-hit rate from 0.291 to 0.058 and from 0.333 to 0.074. It is
  expensive in the other direction and unevenly so: paraphrases kept fall to 0.686 on validation and
  0.452 on held-out, which is heavier on software questions than everyday ones. The table is in the
  README with both columns, because a verifier is a hit-rate decision as much as a correctness one.
  **The recorded file holds a verdict per lookup, never a rate.** The population is the residual, and the
  residual moves when a guard improves — a stored percentage would go on describing the set it was taken
  from. `VerifierCatchRateTest` intersects the verdicts with the residual it recomputes on the day it
  runs, asserts the verdicts still describe the current corpus and cover every residual lookup, and
  asserts **no floor at all**: a build that spends a model call per run is a build nobody keeps.
- Response-aware guards: `ResponseAwareGuard`, `AnswerAnchorGuard` and `MatchGuards.responseAware()`.
  Every guard until now compared two prompts, which left one near miss structurally invisible — two
  honest paraphrases whose answers differ by something neither question contains. "What is the capital
  gains tax rate when I sell a second home" against "…a primary residence" clears the entire chain, and
  the cached answer opens "Gain on a second home is taxable in full". The new guard reads the
  candidate's stored answer and refuses it when it names the word the query replaced. It rejects only on
  a clean substitution — same content-word count, at most two positions differing, compared with the
  same fuzzy rule that keeps `organise` and `organize` together — and only when the query does not use
  that word itself, which is what stops it refusing an expanded abbreviation or a word-order swap.
  **`MatchGuards.standard()` is unchanged**, and that is a statement about evidence rather than
  performance: the guard refuses 14 of the 118 near-miss lookups `standard()` still serves on the blind
  corpora and **none** of the 164 paraphrase lookups, moving the false-hit rate from 0.291 to 0.238
  held-out and 0.333 to 0.309 on validation — but it is measured on a corpus of **authored** answers,
  because no corpus of real paired answers exists to harvest. That makes it a regression check rather
  than the blind measurement every other guard is held to, and folding the two together under one
  default would quietly downgrade the evidence behind all of them. The answers were written before the
  guard was designed and written to be realistic rather than catchable; `docs/CORPUS.md` states the
  rules and `ResponseGuardTest` holds the numbers, including the naive alternative — comparing the
  query's numbers against the answer's — measured and rejected rather than dismissed in prose.
- Comparative benchmark (M23, second part): GPTCache, measured. `tools/gptcache-comparison` scores the
  same blind corpora with GPTCache's own `OnnxModelEvaluation` under its own default threshold, and
  `ComparativeBenchmarkTest` renders it as a fourth row. **The result is a trade, not a win, and the
  table says so.** Its cross-encoder serves *fewer* near misses than `standard()` — false-hit rate 0.221
  held-out and 0.108 validation against 0.291 and 0.333 — and buys that by refusing more than half the
  genuine paraphrases it is shown, where `standard()` keeps 88%. Kmemo is ahead on F1 on both splits
  with close to double the hit rate; GPTCache is ahead on false hits. The README carries both numbers
  and the arithmetic that turns them into money.
  **The evaluator is proved to work before a single score is believed.** `OnnxModelEvaluation.evaluation`
  wraps its whole body in `except Exception: return 0`, so every internal failure is reported as a
  similarity of zero — a harness that trusted the documented entry point would have recorded a false-hit
  rate of 0.000, concluded GPTCache refuses everything, and been wrong in Kmemo's favour. The harness
  gates on a mechanical sanity check and re-scores every zero through the raw inference path.
  It runs out of band, because GPTCache is a Python package that downloads a model and CI is a JVM
  build. What CI enforces instead is the link: each recorded row carries the SHA-256 of the corpus file
  it was measured against, so a corpus that grows without the harness being re-run fails the build
  rather than leaving a stale comparison above fresh data.
- Comparative benchmark (M23, first part): `ComparativeBenchmarkTest` runs the blind corpora through a
  threshold-only baseline, `standard()` and `strict()`, and reports precision, recall, F1 and false-hit
  rate on identical inputs, plus a machine-readable `comparative-report.json` for CI to diff. The
  positioning is now an assertion rather than a paragraph: a similarity-only cache serves **every** near
  miss it is shown (false-hit rate 1.000), `standard()` cuts that to 0.291 held-out and 0.333 validation
  while keeping 88% of paraphrases. The README also carries the cost model — saving per day against
  wrong answers per day, with the measured rates in it.
- Tag invalidation (M21): `CacheEntry.tags`, `CacheStore.invalidateByTag(tag, scope)` and
  `SemanticCache.invalidateByTag(…)`, plus `tags` on `put` and `getOrPut`. A TTL is a guess about when
  knowledge might go stale; this is how a caller acts on knowing that it just did. Tags are **indexed**
  by the store — a GIN index on Postgres, a RediSearch `TAG` field on Redis — which is why they are a
  field on the entry rather than a convention inside `metadata`, and which makes invalidation a query
  rather than a scan. The exact-match layer is purged alongside, so a retracted answer cannot survive
  in it. Implemented in all four stores and covered by five new cases in the shared conformance suite,
  written before the implementations as the milestone requires.
  **The default throws rather than returning `0`.** A `CacheStore` that has not implemented tag
  invalidation would otherwise silently invalidate nothing while its caller believed stale answers had
  been dropped.
- Shadow mode (M20): `SemanticCache(shadowThresholds = listOf(…))`. Every `getOrPut` runs the full
  lookup, reports what it *would* have decided at each threshold through `CacheEvent.Shadow`, and then
  always computes. Nothing is served, so a false hit cannot reach a user while a team is still choosing
  a threshold; writes still happen, because a shadow cache that never fills measures nothing. One search
  and one guard pass per candidate serve the whole curve — a candidate's guard verdict does not depend
  on the threshold — so a five-point curve costs one embed call, not five. Exposed as
  `kmemo.cache.shadow` (tagged `threshold`, `outcome`) and as a `shadow` log line.
- Per-scope thresholds (M20): `SemanticCache(thresholds = mapOf("billing" to 0.995))`. One global value
  is necessarily wrong for a service answering both regulated and casual questions, and tuning it to the
  strictest caller makes every other one pay. `explain()` reports the threshold that would actually
  apply in that scope.
- Conversation-aware keys (M19, part 2): `getOrPut(prompt, context, …)`, `lookup(prompt, scope, context)`
  and `get(prompt, scope, context)`. Without context the cache keys on the last turn alone, so "what
  about the second one?" can be answered from a different exchange — the context was not weighed, it was
  ignored. The prior turns are folded into the embedded text rather than into the scope, so
  conversations that differ only in phrasing can still match. `compute` still receives the bare prompt.
  Shipped as an **overload** rather than a parameter: inserting anything ahead of a trailing lambda
  rebinds it for every caller passing `scope` or `metadata` positionally, which `kmemo-ktor` caught at
  compile time and which `1.x` does not allow.
- Exact-match layer (M19, part 1): `SemanticCache(exactCacheSize = …, exactCacheTtl = …)`, off by
  default. A byte-for-byte repeat of a prompt in the same scope is answered without an `Embedder` call
  *or* a store search, which is the path retries, replayed agent loops, polling clients and test suites
  hammer. It runs no guards, because an identical prompt in the same scope is the same question — the
  fast path adds no false-hit risk by construction rather than by measurement. Counted in
  `CacheStats.exactHits`, a subset of `hits`.
  **The trade, stated rather than hidden:** `CacheStore` exposes no read-by-id, so this layer cannot ask
  whether an entry is still live without the search it exists to avoid. It therefore carries its own
  TTL, which must not outlast the store's. Past that TTL nothing stale is served: the remembered
  *embedding* is still reused, so the lookup falls through to the ordinary path with the network call
  already paid. `invalidate` and `clear` purge it, so a retracted answer cannot survive there.
- **Build provenance (SLSA)** on every published jar. The release workflow attests, through
  `actions/attest-build-provenance`, that each artifact was built by this workflow from a named commit,
  and the attestation is verifiable with `gh attestation verify <jar> --repo NaCode-Studios/Kmemo`.
  The attested set is derived from the build rather than listed in the workflow, so a module that starts
  publishing is covered without anyone remembering to add it, and the attestation runs *before* the
  publish step so a failure cannot leave released-but-unattested artifacts.
- Every tag now gets a **GitHub Release**, with its body extracted from this file rather than written
  separately, so the two cannot disagree. Releases were backfilled for `0.1.0` through `1.1.0`.
- Published jars are recorded on GitHub's **linked artifacts** page, so the repository shows what it
  built and points at Maven Central as the registry that holds it. This is metadata and not a second
  place to download from, which is the distinction from the GitHub Packages copy removed above. Each
  record carries the jar's digest, so it lines up with the provenance attestation for the same artifact.
  The step is non-fatal: it annotates a publish that already succeeded and must never fail a good
  release.

### Changed

- **`CacheEntry.createdAt` is now a `kotlin.time.Instant`**, and every store adapter with it. On the
  JVM the two types convert with `toKotlinInstant()` and `toJavaInstant()`, which is what the Postgres
  and Redis adapters do at the edge where they talk to a driver. A custom `CacheStore` needs the same
  one-line change; `java.time.Clock` becomes `kotlin.time.Clock` in the same way.
- **`MatchGuards.standard(Locale)` and `Vocabularies.forLocale` move to `jvmMain` as extensions.**
  `Locale` is the one part of the guard layer that cannot leave the JVM. The call site is unchanged and
  now needs `import dev.kmemo.guard.standard`; the new `standard(language: String)` takes an ISO 639
  code and works everywhere.
- **Binary compatibility is broken, source compatibility is not.** `GuardVocabulary` gains a
  `qualifierOpeners` field, and `SemanticCache` and `InMemoryStore` gain constructor parameters; all of
  them are defaulted, so nothing needs editing, but the constructor and `copy` signatures moved and a
  jar compiled against `1.x` will not link. `STABILITY.md` puts that boundary at a minor version and
  this is where the next release earns its number. Recompiling is the whole migration.
- Docs (M21): the README documents **scope versioning** as the invalidation pattern that
  needs no new API — bump `pricing-v3.2` to `pricing-v3.3` and clear the old scope, which cuts over
  atomically. The issue asks for this to ship before any seam change, and it is the whole answer for
  many teams. Tag-based bulk invalidation is still ahead and touches `CacheStore`, the TCK and all four
  adapters.

### Internal

- The GPTCache harness runs on **current** transformers instead of a pinned-back one. GPTCache's
  evaluator calls `tokenizer.encode_plus`, removed in transformers 5, and needs `token_type_ids`, which
  the ALBERT tokenizer no longer returns by default; six lines forward both to the documented
  replacement. Pinning back to 4.57.6 would also have worked and would have meant carrying two
  high-severity advisories with no fix below 5, so `ci.yml` now allows no advisory at all. The measured
  numbers are unchanged to four decimal places, which is the check that the shim is faithful.
- The Kotlin/JS toolchain's lock file no longer carries two high-severity npm advisories. The JS and
  Wasm targets test on Node and not in a browser, which drops the webpack and karma toolchain and cuts
  the lock file by more than half, and the two packages that survived that are forced to their patched
  versions through yarn resolutions.
- `release.yml` runs on macOS and creates the GitHub Release from `CHANGELOG.md`; `ci.yml` gained a job
  that compiles and tests the Apple targets, which a Linux runner disables rather than failing on.

- Tag invalidation needs a **schema migration** on the two remote stores, and both are idempotent and
  safe on a live deployment. `PostgresStore` creates its table with `CREATE TABLE IF NOT EXISTS`, which
  never adds a column to an existing table, so it now also runs `ALTER TABLE … ADD COLUMN IF NOT EXISTS
  tags` and creates a GIN index. `RedisStore` builds its index once with `FT.CREATE`, which does not add
  a field to an index that already exists, so it runs `FT.ALTER` when it finds one. Entries written by
  an earlier version carry no tags and are simply never matched by a tag query, which is correct; they
  gain the field as they are rewritten.

### Removed

- **Publishing to GitHub Packages.** Maven Central was always the primary registry and is where every
  released version lives; the GitHub Packages copy resolved only for consumers who configured a GitHub
  token, since that registry requires authentication even for public packages. A second distribution
  channel that most people cannot use without extra setup is a channel that goes stale unnoticed, so the
  `GitHubPackages` repository is gone from all eleven modules and the release workflow publishes to
  Maven Central alone. Nothing changes for anyone depending on `io.github.nacode-studios` coordinates.
  The packages previously pushed there for `0.1.0` through `1.1.0` have been deleted; every one of those
  45 versions was verified present on Maven Central first, so nothing was lost, but
  `maven.pkg.github.com` URLs for them now return 404 permanently.

## [1.1.0] - 2026-07-31

`1.1.0` is the first slice of **Tier 6 "production depth & proof"**: the two things the cache was doing
silently, and the honesty pass on the numbers it publishes. The tier is not closed — keying, shadow
mode, invalidation beyond TTL and the comparative benchmark are still open.

**Recompile against this version; no source change.** `CacheStats` gains two components, so its
constructor, `copy` and `componentN` signatures change, and `SemanticCache` gains a trailing
`cachePolicy` parameter, which changes its synthetic default-argument constructors. No calling code has
to be edited, and `CacheStats` is only ever constructed by the library. This ships as a minor because
[STABILITY.md](STABILITY.md) now separates source compatibility, guaranteed across `1.x`, from
binary compatibility, which is a minor-version boundary.

### Added

- Cache policy (M22): a `CachePolicy` seam and `SemanticCache(cachePolicy = …)`. One suspending
  predicate over the prompt, the computed response and the scope can veto a write, returning
  `PolicyVerdict.Veto(reason)` instead of `PolicyVerdict.Store`. It is consulted at the single choke
  point every write goes through, so `put`, `getOrPut`, `getOrPutAll`, `getOrPutStreaming`, `warm` and
  the write-behind queue are all covered — a guarantee with one path around it is not a guarantee. A
  vetoed write is a policy decision, not a failure: the call still returns its response. kmemo ships the
  seam and no detector, as with `Embedder` and `Verifier`.
- Degraded-lookup telemetry: `CacheEvent.Degraded` and `CacheStats.degradedLookups`. A
  `FALL_BACK_TO_COMPUTE` fall-back previously left no trace at all — it is not a miss, so it moved no
  `MissReason` and no counter, and a team running with a flapping embedder would watch its hit rate
  collapse with nothing naming the cause. The event carries the operation (`GET_OR_PUT`,
  `GET_OR_PUT_ALL`, `GET_OR_PUT_STREAMING`), the policy and the throwable; a `RetryingEmbedder` that
  exhausts its attempts arrives as that throwable rather than as a separate event, because retrying is
  transparent to the cache. The counter moves with no listener attached.
- Write-veto telemetry: `CacheEvent.WriteVetoed` and `CacheStats.writesVetoed`.
- Metrics (`kmemo-micrometer`): `kmemo.cache.degraded` tagged by `operation`, pre-registered so an alert
  can be written before the embedder ever fails, and `kmemo.cache.writes.vetoed` tagged by `reason`.
  Neither touches `kmemo.cache.lookups`, so the hit ratio is not moved by something that never consulted
  the cache.
- Logging (`kmemo-slf4j`): `degraded` and `write_vetoed` lines. The degraded line carries the throwable.

### Changed

- Docs: the corpus figures are now labelled **guard-only** wherever they appear. `CorpusTest` runs
  `MatchGuards.standard()` with no `Verifier`, so 67% / 88% describes the free lexical layer, not the
  cache as a whole. The README previously said the residual was "the world-knowledge cases the
  `Verifier` covers", asserting a coverage that has never been measured on any corpus.
- Docs: the README now reports the held-out split (71% near misses rejected, 88% paraphrases kept)
  alongside the validation split it already quoted, and sizes the residual the verifier is aimed at
  (25 of 86 held-out, 34 of 102 validation) instead of asserting "a third" without a source.
- Docs: the multilingual guard packs were described as "measured against a localized near-miss corpus".
  `LocalizedGuardsTest` asserts hand-written in-sample pairs, so the README now calls them a regression
  check on the packs rather than a blind measurement.
- Docs: `STABILITY.md` linked a `ROADMAP.md` that no longer exists; it now links the board.
- `STABILITY.md` now separates **source** compatibility, guaranteed across `1.x`, from **binary**
  compatibility, which is a minor-version boundary and never a patch one. The previous wording said only
  "no breaking change without a major version bump" and left the two indistinguishable, which priced a
  new counter on a result type at the cost of an ecosystem migration.
- Docs: the README prose, brand assets and the project-page link were refreshed after `1.0.0`.
- `STABILITY.md` moved from `docs/` to the repository root, where the NaCode Studios library standard
  puts it. Every link in the README and this file was repointed, including the ones in older entries, so
  no path in the repository is left resolving to nothing.

### Removed

- `ROADMAP.md` and `ROADMAP-CONVENTIONS.md`. The plan now lives on the
  [board](https://github.com/orgs/NaCode-Studios/projects/5), with one repository milestone per tier, so
  it cannot drift from a second copy in the repository.

### Internal

- `io.lettuce:lettuce-core` 6.5.1 → **7.6.0**. Lettuce 7 no longer allows the RediSearch keywords to be
  an enum, so `RedisStore` builds them as arguments instead. Contained entirely within
  `kmemo-store-redis`; no public API changed and no `.api` file moved.
- Gradle wrapper 8.14.5 → **9.6.1**.
- `com.vanniktech.maven.publish` 0.35.0 → 0.37.0, `me.champeau.jmh` 0.7.2 → 0.7.3, and a
  `logback-classic` bump in the test-and-tooling group.
- `CODEOWNERS` now names the `@NaCode-Studios/libraries` team rather than a single account, which was
  deadlocking every pull request the sole maintainer opened.

## [1.0.0] - 2026-07-22

`1.0.0` is the **Tier 5 "quality & the road to `1.0`"** release, and the milestone it leads to: CI, supply
chain and test depth brought up to a mature OSS standard, and a written stability commitment. From here,
the public API is backwards-compatible within the `1.x` line — see [STABILITY.md](STABILITY.md).

### Added

- Property-based tests (M15): kotest-property invariants for the `Vectors` maths (normalize → unit
  length, dot symmetry, cosine scale-invariance, non-finite rejection) and the `Text` tokenizer.
- Linters (M15): ktlint and detekt as CI gates. ktlint is configured to the two rules the project wants
  (no wildcard imports, the 120-column limit) with its opinionated formatting set left off, since the
  codebase has a deliberate house style; detekt runs `buildUponDefaultConfig` with a small library-aware
  config and a per-module baseline so it gates new smells, not existing ones.
- CI & supply chain (M15): a JDK 17/21/23 build matrix; `.github/dependabot.yml` (Gradle + Actions); and
  a dependency-review CVE gate on PRs.
- Docs (M15/M16): `docs/CORPUS.md` (the process for growing the blind corpus splits without contaminating
  them) and `STABILITY.md` (the semver/stability policy — now in effect for `1.x` — the Java-interop
  position, and the rationale behind every default).

### Internal

- **Stability**: as of `1.0`, no breaking change to a stable public API without a major version bump.
- Releases stay **tag-driven** (no SNAPSHOT publishing), matching the convention used across NaCode
  Studios' libraries.
- Coverage is deferred: Kover's 0.9.x line is not yet compatible with the Kotlin 2.4 Gradle plugin. The
  corpus regression gate, property-based tests, ktlint and detekt carry the quality bar until then.

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

[Unreleased]: https://github.com/NaCode-Studios/Kmemo/compare/v2.0.0...HEAD
[2.1.0]: https://github.com/NaCode-Studios/Kmemo/compare/v2.0.0...v2.1.0
[2.0.0]: https://github.com/NaCode-Studios/Kmemo/compare/v1.1.0...v2.0.0
[1.1.0]: https://github.com/NaCode-Studios/Kmemo/compare/v1.0.0...v1.1.0
[1.0.0]: https://github.com/NaCode-Studios/Kmemo/compare/v0.5.0...v1.0.0
[0.5.0]: https://github.com/NaCode-Studios/Kmemo/compare/v0.4.0...v0.5.0
[0.4.0]: https://github.com/NaCode-Studios/Kmemo/compare/v0.3.0...v0.4.0
[0.3.0]: https://github.com/NaCode-Studios/Kmemo/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/NaCode-Studios/Kmemo/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/NaCode-Studios/Kmemo/releases/tag/v0.1.0
