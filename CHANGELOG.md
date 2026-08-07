# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versioning follows
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [2.3.0] - 2026-08-07

Tier 11 and Tier 12: the evidence leaves the repository, and the third that gets through.

**This release closes two tiers, and after `1.0` that is a statement about traceability rather than
about versioning.** Versions follow API impact from `1.0` onwards, and this one is additive, so it
would be a minor whichever tiers it carried. The two are together because they are one argument:
Tier 11 took the evidence out of this repository, and Tier 12 spent it re-deciding four things about
the guards, three of which came out against the change. Splitting them would have published the
questions in one release and the answers in another. Traceability is preserved the way it was for
`2.2.0`: every entry below carries its milestone id, and the board reads `2.3.0` on both tiers.

### Added

- **The corpora and the guard rules published as a standard (M43, M44).** Everything this project knew
  was knowable only by running this repository: the corpora lived in a test resource directory in a
  shape invented here, read by a class that exists nowhere else, and the eleven guards were rules about
  text with only their Kotlin written down. A library is judged on what it does and a standard on what
  other people can do with it, and the thing worth standardising is the metric and the data rather than
  the Kotlin.
  `spec/` carries the corpus schema, the false-hit metric with its both-directions rule and its tuning
  prohibition, each guard's rule stated so it can be implemented without reading this code, 1,122
  conformance vectors, and the English markers as data. It ships as `kmemo-corpus-<version>.zip` with a
  SHA-256 per file, built by `./gradlew corpusBundle`.
  **`tools/corpus-runner` is the proof that the specification is enough**: a Python implementation
  written from `spec/guards/SPEC.md`, importing nothing from the JVM, reproducing every vector and
  every corpus figure to the pair. Writing it is what surfaced the three marker lists the rules read
  and the vocabulary pack does not carry, now named in the specification as implementation-defined
  rather than left to be discovered.
- **A blind question corpus large enough to see a change (M54).** The blind evidence was 86 and 102
  near misses, which supports a rate about nine points wide in each direction, so a real five-point
  improvement and a lucky run were the same measurement. Every question-register figure also came from
  a corpus written here, since PAWS is declarative Wikipedia prose.
  Quora Question Pairs closes both. Typed by the public, labelled by Quora, years before this project
  existed, filtered to the pairs a similarity threshold would surface by a character 4-gram rule fixed
  before a guard was run against the result: 5,296 pairs, 2,500 of them near misses. Fetched and never
  committed, under the policy the PAWS split already carries.
  **Every rate now carries its 95% Wilson interval**, in the printed reports and in the JSON artifact,
  and every split carries a standing: `in-sample`, `retired` or `blind`.
- **`kmemo-otel` (M46).** OpenTelemetry metrics, and one span per lookup with a child per stage that
  ran, recorded with explicit timestamps from the durations the cache already measured. The listener
  runs inline on the calling coroutine, so a lookup lands under whatever span the caller was already
  in and a verifier call appears in their trace as the model call it is. `kmemo-core` gains no
  dependency. The attribute names are proposed as a convention under `gen_ai.cache.*`, argued in
  `docs/OTEL-CONVENTIONS.md`. JVM-only, because there is no OpenTelemetry API on Maven Central a
  multiplatform module can depend on, and the module says so in its own build file.
- **A threat model (M48).** `EntryCipher` shipped so a regulated deployment could cache and the
  document their reviewer asks for did not exist. `docs/THREAT-MODEL.md` names the assets, the
  adversaries and the trust boundaries, and its residual-disclosure section is the part worth reading:
  the embedding is not encrypted and cannot be, tags and metadata pass through in the clear, the
  exact-match layer holds plaintext in memory, a `CachePolicy` runs before the write and not before the
  embedding, and a hosted verifier sends both prompts out of the process.
- **`ConfidenceVerifier` (M56).** The verifier takes paraphrases kept from 88% to 45% on one split, and
  until now the only dial was off. A cross-encoder produces a probability and throws it away at a
  threshold somebody else chose; this returns it, with a threshold the caller sets. Fail-closed is
  unchanged and asserted: a check that could not complete still rejects.
- **`MatchGuards.shortQuestions()` (M51).** `SubstitutionGuard.withHeadFloor` applies the old floor
  only to a difference in the first content word, which is where a question keeps its verb. Against
  `standard()` it costs the tuned split nothing, gains catches on both retired splits for no paraphrase
  at all, and on the external question split trades 65 catches for 36.
- **Marginal contribution per guard (M52).** `GuardReport` reports what the chain would lose without
  each guard, alongside the isolation figures it has carried since `1.0`, and the chain's per-candidate
  cost is measured for the first time.
- **Every published figure checked against the prose (M45).** `docs/figures.json` records each number
  with the command that produces it and, where the build can render it, the exact line the document
  must carry. A measurement that moves fails the build until the sentence around it is edited.
  `tools/figures/check.py` re-runs that check with no Gradle involved.

### Changed

- **The corpus reports no longer spend the split they print (M53).** `CorpusTest` printed the whole
  validation residual on every run, which is the act `docs/CORPUS.md` prohibits, performed by the
  tooling on behalf of everybody who ran the suite. The reports now print counts and category
  distributions, which guide nothing, and the pairs sit behind `-PspendABlindSplit=true`, a flag named
  for what passing it costs.
- **`held-out` and `validation` are retired (M54).** Their failures have been read and there is no way
  to un-see them. They keep their floors and stay in every report as regression gates, and they stop
  being quoted as blind evidence. The blind evidence is now the two fetched splits, which nobody here
  can add to and which are larger by an order of magnitude.
- **The GPTCache coupling digests the pairs rather than the file.** Adding a provenance field to a
  corpus document is a change to the prose around the data, and re-running a model download to record
  it is a cost with nothing on the other side.

### Internal

- **The concurrency claims are explored rather than asserted (M49).** The coalescing lock, the shared
  stream and the write-behind queue are checked with a model checker over interleavings instead of the
  single schedule `runTest` produces. None of the three is linearizable against a sequential
  specification, so the invariants are checked inside the operations and in validation functions.
  Nothing was found, which is evidence rather than proof, and two limits are recorded rather than
  worked around.
- **Two things the guard chain might have become, measured and declined (M47, M55).** A relation-slot
  reader adds zero unique catches on 6,964 blind pairs. A linear classifier over cheap features refuses
  nearly three quarters of genuine paraphrases, worse than the cross-encoder already priced. A scoring
  chain that sums what each guard nearly concluded is the more conservative decision everywhere and
  cannot name the guard that fired. `MatchGuards.standard()` and `GuardVerdict` are unchanged, and
  `docs/MEASUREMENTS.md` publishes all of it.
- **The substitution floor stays at four, and now for a measured reason (M51).** Three buys 77 catches
  on the question split and pays 63 paraphrases for them, a ratio worse than one this project already
  declined. The hypothesis came from reading the validation residual, which is exactly what the larger
  blind splits exist to check.
- **`lexical-divergence` stays, with the reason written where somebody proposing to remove it will read
  it (M52).** It has never caught anything on any split and has cost five paraphrases in 6,471, and
  that zero is not evidence: no corpus here can contain the case it exists for, because every pair was
  written or selected as a pair.

## [2.2.0] - 2026-08-07

Tier 9 and Tier 10 together: reach, cost and the regulated buyer, and the gap PAWS measured.

**This release contains two tiers, which is a deliberate exception to how this project versions.** A tag
normally closes exactly one tier, so that "when did that land" always has an answer the board can give.
Tier 9 and Tier 10 were finished within days of each other and neither is separable from the other in
practice: Tier 10's register and length work is what explains the figure Tier 9's guards produce, and
Tier 9's `longPrompts()` preset and Tier 10's `prose()` preset are two halves of one finding about one
guard. Cutting them apart would have published half of an argument twice. Traceability is preserved the
other way instead: every entry below carries its milestone id, and the board reads `2.2.0` on both tiers.

### Added

- **The PAWS number attacked, against a target registered first (M34).** `2.1.0` published a figure this
  project had to answer for: PAWS rejects 14% of its near misses where the blind internal splits reach
  68%. The explanation shipped with it is sound and is not a defence, because deliberately high lexical
  overlap is the precise case this library exists for.
  The target, the boundary case and the failure case were written into `docs/MEASUREMENTS.md` in a commit
  that changed no guard, because a number chosen after seeing the result is a description rather than a
  target. `WordOrderGuard` was the attempt, chosen from how PAWS is built rather than from what its pairs
  contain: its near misses carry the same words in a different arrangement, which is the one shape the
  chain is blind to, because every guard in it but one compares sets and that one requires the order to
  match.
  **It cleared the target and failed the constraint.** Rejection went from 14.5% to 39.7%, past the 25%
  registered, and it was paid for with 390 paraphrases on PAWS and one on held-out. That is the boundary
  case as defined before the attempt, so the guard ships and **no preset carries it**: `standard()` is
  byte-for-byte what it was and every figure above it still holds. The line this draws is the deliverable:
  Kmemo catches near misses that arise in traffic and does not catch adversarially constructed ones at a
  price worth paying.
- **The guards scored per register (M35).** The catch rates differ between the written splits and PAWS by
  a factor of nearly five, and how much of that is register and how much is difficulty was unanswerable,
  because no guard had ever been scored per register on any corpus. Every pair is now filed by register,
  by published rules rather than by hand, and `GuardReport` carries precision and recall per guard per
  register across all four splits.
  **Register does not explain the gap.** The written splits are 72% to 90% questions and PAWS is 99%
  declarative prose, and where they overlap PAWS has 43 questions on which the chain catches 9% against
  65% on validation's. What it does explain is one guard: on declarative prose `substitution` rejects 493
  paraphrases to catch 42, a precision of 8%, where on questions it runs at 98 to 100%.
  `MatchGuards.prose()` is `standard()` without it, twelve paraphrases kept per catch lost on prose and
  two thirds of the protection given up on questions, which is why it is a preset.
- **The verifier's price, beside its catch rate (M36).** A cache sold on saving model calls owes the
  reader the cost of its safety net, and the catch rate alone let a reader construct the worst case with
  no way to rule it out. Invocations per lookup, tokens per invocation and tokens per avoided false hit
  are now reported across all four splits: on the residual the reference verifier was shown, 116
  invocations caught 91 false hits for 1,996 tokens, **22 tokens each**. Every invocation rate is
  published as the upper bound it is, because these corpora are 56% to 67% near misses by construction
  and real traffic is not.
- **The guard TCK held to a guard unlike the ones it came from (M38).** Nothing had been written against
  `kmemo-guard-tck`, so what it proved was that it accepts the guards it was extracted from. A stateful,
  case-sensitive guard on its own tokenizer passes ten of its eleven properties unmodified, which is the
  reassuring half: the suite is about verdicts rather than about how a verdict is reached. The one it
  fails is the false-rejection ceiling, whose default is zero because kmemo's guards were tuned until they
  met it; the knob to declare a different trade existed and the failure message did not name it, and now
  does. Three rules that were real and unwritten are in the suite's documentation: asynchrony is refused
  by the type, state is allowed as long as remembering changes no verdict, and thread safety is required
  by `MatchGuard` and checked by nothing here.
- **What a cache removes from a retrieval pipeline (M42).** Every other figure here comes from a benchmark
  this repository wrote for itself. On SQuAD v1.1 dev, fetched rather than vendored: 400 paragraphs, 2,410
  questions, retrieval by nearest paragraph, generation returning the labelled answer. A second run of the
  same questions makes **no model calls at all**. And the guards survive contact with retrieved context,
  which was the specific worry: a threshold-only cache serves 12 wrong answers there, the guards halve
  that to 6, and folding the retrieved document into the key takes it to 2. Those two are the residual
  nothing prompt-side can reach.
- **`forTenant`, and a key space per customer (M37).** The cache key carried the prompt, the conversation
  and, since `2.1.0`, the embedder. It did not carry who was asking, so a deployment serving more than one
  customer had one key space shared between all of them. Two tenants asking a byte-identical question got
  one entry, and on the exact-match fast path the second was served the first one's answer **without
  similarity, without guards and without the verifier**, because skipping all three is what that path is
  for. Every safety layer in this library sat downstream of a key collision it could not see. The
  identical-prompt case is the obvious one; the dangerous one is a prompt carrying retrieved context, where
  two tenants ask the same question of different documents and the answer belongs to somebody else's data.
  `SemanticCache.forTenant(id)` returns a `TenantedCache` through which nothing can reach another tenant's
  entries, on every path including the fast one, and `requireTenant = true` refuses any read or write that
  did not come through one. A view rather than a parameter, because a parameter is something somebody can
  omit, and an isolation property that depends on nobody making a mistake later is the thing this library
  refuses to accept anywhere else. A missing tenant is a distinct state rather than a default, since a
  default is what silently reintroduces the shared space.
  The tenant is folded into the scope the `CacheStore` sees, which puts the isolation inside the library
  rather than in the wiring: every store partitions by scope already, including one somebody else wrote, so
  nothing is re-implemented per backend and nothing can be configured wrongly. Both are additive and off by
  default, so a single-tenant cache is unchanged.

- **The guards measured against prompt length (M28).** `2.1.0` shipped an external split whose
  breakdown said something nobody followed up: `substitution` rejected 498 of 3,536 PAWS paraphrases
  against 2 of 51 on validation, from a guard that had not changed. The release notes called it a
  register difference, which is a label covering at least three things that would each need a different
  answer. It was mostly length. Filing every pair in every split by the mean length of its two prompts
  and measuring each band separately, `substitution` rejects paraphrases at 0% below 48 characters, 12%
  between 48 and 95, and 15% from 96 characters up. The written splits are almost entirely below 48 and
  PAWS is almost entirely above 96, so the two averages were describing different lengths. Where the
  bands overlap they read 10% and 12%.
  `GuardReport` now carries a `byLength` breakdown across all four splits, written to
  `build/reports/guards/guard-length-report.json` beside the existing report, with the bands that
  contain nothing kept rather than dropped: an axis that stops at the last measurement reads as
  coverage. `GuardLengthTest` prints the table and asserts that banding partitions a corpus rather than
  sampling it.
  Past 214 characters there was nothing to measure at all, so the report derives one: both sides of
  every PAWS pair wrapped in an identical retrieved-context envelope at 512, 1024 and 2048 characters,
  which leaves the difference between them untouched and only buries it. `substitution` holds at 15%
  across all three, so there is no cliff further up. `entity` and `direction` do move, from 6% to 10%
  and 0% to 4%, because both treat the first word of the text they are handed as a sentence opener and
  a question with passages in front of it no longer has one. That is documented rather than fixed: it
  needs a way to tell a guard where the question starts, which this API does not have. The derived
  splits measure dilution and are not a fifth independent score.
- **`MatchGuards.longPrompts()`**, the chain for traffic whose prompts carry retrieved context. It is
  `standard()` with `SubstitutionGuard` bounded at `LONG_PROMPT_MAX_TOKENS`, so past a dozen content
  words the guard abstains instead of rejecting on one differing word. On the external split it gives
  up 12 of 647 catches and keeps 125 more of 3,536 paraphrases, roughly ten kept per catch lost; on the
  derived envelope splits, between 6 and 8 given up for between 57 and 61 kept. It changes nothing on
  the three written splits, because none of their prompts reaches the bound. The bound was placed on
  the tuned corpus, which is the split that exists to be fitted, and measured everywhere else;
  `SubstitutionBoundTest` holds all of that.

- **Concurrent-miss coalescing on the streaming path (M29).** M26 put the cache on a streaming path and
  named the hole it left: coalescing did not apply to `getOrPutStreaming`. That hole is where the
  traffic is. A cold cache under load is the case coalescing exists for, and streaming is the path a
  chat product serves users on, so the cache was coalescing the calls that cost least and letting
  through the ones that cost most.
  Fifty concurrent `getOrPutStreaming` calls for one new prompt in one scope now make **one** provider
  call. The first collector opens the stream; the rest attach, are replayed whatever has already
  arrived, and then follow it live. Attaching rather than waiting for completion is the whole point: a
  streaming caller made to wait for the end is paying the latency they streamed to avoid.
  M26's rules hold to the letter. A provider that throws after n chunks fails **every** attached
  collector and writes nothing. The provider never runs more than one chunk ahead of the fastest
  collector, which is what an uncoalesced collection did implicitly by driving the provider from the
  caller's own coroutine, so a caller who walks away still stops the stream rather than leaving a
  complete answer to be written behind their back. What did change is who the stream belongs to: it is
  stopped when the **last** collector leaves rather than the first, because dropping fifty people's
  answer when one of them closes a tab is a behaviour nobody would choose deliberately.
  An attached caller sees the provider's own chunk boundaries whatever `StreamReplay` it asked for,
  since there is one live stream and `StreamReplay` describes how a *stored* answer is cut up.
  `coalesceConcurrentMisses = false` restores a provider stream per caller.

- **The cache reports what it saved (M31).** The README did arithmetic that turns a hit rate into
  money, by hand, in prose, with numbers the reader had to supply from their own invoice. That is the
  calculation anybody deciding whether to adopt this library actually cares about, and the library
  could not do it: `CacheStats` counted lookups, hits, misses and rejections, and none of that is
  money. A hit on a two hundred token answer from a cheap model and a hit on a four thousand token
  answer from an expensive one were one increment each.
  `TokenPrices` is what a caller declares per scope, and `CacheStats.savings` is what comes back:
  amount, currency, hits, token counts, and the prices the figure was computed from. The token counts
  are read from the served entry's `metadata` by keys the caller names, so a saving is the cost of the
  *specific* call that was avoided rather than an average applied to a hit count. `CacheEvent.Hit`
  carries `saved` and `currency` for the one hit, and `kmemo-micrometer` publishes
  `kmemo.cache.saved`, tagged by currency and not by scope, for the reason that adapter tags nothing
  by scope.
  Three things it deliberately does not do. It ships no table of provider prices: they change weekly, a
  vendored list is wrong the month after it ships, and a cache that quietly reports the wrong saving is
  worse than one that reports none. It never counts a write, because a write is a call somebody made
  rather than a call somebody avoided. And it never adds two currencies together. Hits whose entries
  carry no token counts are reported as `hitsMissingTokenCounts` next to the amount, since that is the
  one way the figure can be quietly wrong and a total that is too small should say why.

- **`kmemo-store-qdrant` (M39).** `CacheStore` had three implementations and none of them is a vector
  database, while the teams most likely to want a semantic cache are already running one. A team doing
  retrieval-augmented generation already operates Qdrant, because that is where their documents are, and
  adding a cache meant adding Redis or Postgres as well: a second store for something that holds
  embeddings, which is what the store they already have exists to hold.
  It is built on [Kdrant](https://github.com/NaCode-Studios/Kdrant), and it takes a `QdrantClient`
  rather than connection settings, so the wire, the credential, the trust anchors and the lifecycle stay
  with the caller and the module depends on the client interface rather than on a transport. The
  collection is created on first use, single unnamed vector at `COSINE`, with payload indexes on
  `scope`, `tags` and `expiresAt`, because filtering an unindexed payload field in Qdrant is a full scan
  and every lookup filters on scope. Qdrant has no TTL, so expiry is a payload field and a filter,
  exactly as it is on Postgres.
  Two things are worth knowing before adopting it. It is a fourth store written by the authors of the
  conformance suite, so it proves what the other three prove: the argument for it is adoption friction,
  not validation. And **it has no `js` or `wasmJs` target**, which is Kdrant's stated decision rather
  than an omission, because a Qdrant reachable from a browser is reachable from anyone who opens the
  developer tools. M39's exit criterion asked for every target `kmemo-core` publishes, and that is not
  reachable; `kmemo-store-file` covers those two.
  `QdrantStoreConformanceTest` runs the whole shared suite against a real Qdrant in Docker, the way the
  Postgres and Redis stores are tested, and skips rather than fails where Docker is absent.
- **`kmemo-store-file`, a persistent store on every target `kmemo-core` publishes (M30).** The reach
  shipped in `2.0.0` and the benefit did not follow it. A phone pays for every call over a mobile
  network, a browser has no server to cache on, an edge deployment may have no reliable uplink, and all
  three lost the entire cache every time the process ended, because `InMemoryStore` was the only store
  that built off the JVM. An iOS app cached for the length of one session.
  It is an append-only journal over the in-memory index, and the shape was argued rather than assumed. A
  multiplatform SQLite driver would bring decades of somebody else's work on durability and does not
  reach `wasmJs` at all, so it cannot satisfy a store that has to follow `kmemo-core` everywhere. A
  hand-written index on disk is the wrong shape for a cache: an on-disk index exists so the working set
  can exceed memory, and a cache's working set is bounded by `maxEntries` by construction. What was
  missing was durability across a restart, which is a log. The log puts four operations on the platform
  seam, read, append, replace and delete, instead of a driver.
  The journal's format is length-prefixed rather than separated, so a prompt containing a newline, a
  comma or a quote needs no escaping and escaping is where a parser silently corrupts one entry in ten
  thousand. Records are self-delimiting, so a tail truncated by a process that died mid-append is
  dropped and everything before it is still served: turning one lost write into a lost cache is the
  wrong direction for a cache to fail in. Vectors are written as raw bits, because a float printed as a
  decimal and parsed back is not guaranteed to be the same float on every platform. Compaction rewrites
  the log as one record per live entry, through a temporary file that is moved into place.
  Three costs, stated because they decide whether it suits you. Memory holds everything, so a cache
  bigger than one process still wants Postgres or Redis. A write is a buffered append rather than an
  fsync, so a power cut can lose the last writes. And a journal is one file with one tail, so two
  processes must not share a path.
  `kmemo-store-tck` became multiplatform to carry this, so `CacheStoreContract` now runs on the JVM,
  Node, WasmJS, `macosArm64` and the iOS simulator instead of on the JVM alone. A store that is
  conformant on the JVM and untested on iOS is a store that will serve a wrong answer on a phone first.
  `InMemoryStore.entries()` is new and public: the file store compacts by writing the live set out, and
  without it would have to keep a second copy of everything to know what to write.
- **Caching an evaluation suite, measured, with no adapter (M40).** An evaluation suite runs the same
  prompts against the same model on every push, so a golden set of five hundred cases is five hundred
  model calls per run and the bill grows with the team rather than with the product. That is why
  evaluation suites get moved to a nightly job, then to a manual one, then stop running.
  The shape needed deciding rather than assuming, and the answer is the third option the milestone
  listed: a documented recipe that needs no code. Dokimos plugs in at a Spring AI `ChatModel`, a
  LangChain4j model, a Koog agent or a plain lambda, and `kmemo-spring-ai` and `kmemo-langchain4j`
  already sit at exactly those seams. The system under test is the caller's own code, so the cache goes
  in front of the caller's own model client and an adapter in either repository would wrap a seam that
  is already wrapped. The judge is the second place it pays and has the same shape, because `JudgeLM` is
  a lambda.
  `DokimosEvaluationCacheTest` measures it against the real framework rather than asserting it: a golden
  set run twice through one cache is a model call per case on the first run and **none on the second**,
  with the suite reaching the same verdict either way. That second clause is the one that matters, since
  a suite whose verdicts moved because a cache was added would be worse than a suite that costs money.
- **On-device embedding, measured and ruled out (M41).** `Embedder` names four places to get an
  implementation. Three are network providers and the fourth runs only on the JVM, and the consequence
  had never been written down: on the native targets and on wasm an embedder is always a network call,
  so a cache that exists to avoid a round trip to a model needs one to decide whether to serve. That is
  the specific thing that made this library not worth having on a phone.
  `OnDeviceEmbeddingTest` measures the alternative on `macosArm64` rather than arguing about it: one
  forward pass at `all-MiniLM-L6-v2` shape, arithmetic only, in ordinary Kotlin. **2.5 seconds per
  call** at 274 MFLOP/s, 42 MB of encoder weights fp32 and a 46 MB vocabulary table beside them. An
  embedding API answers in 50 to 200 ms from hardware slower than the machine that produced this, so a
  pure-Kotlin on-device embedder is an order of magnitude slower than the call it exists to avoid.
  The decision is that no separate library follows. A viable on-device embedder wraps a platform
  inference runtime, which means per-platform native binaries, a model format, a tokenizer and a licence
  conversation about weights: a different project from a cache, and one that would put native artifacts
  and provider SDKs under the name of a library whose argument is that it has neither. `Embedder`'s
  documentation is corrected to say so, which is what the silence needed.
- **`EntryCipher` and `EncryptedStore` (M33).** `CacheEntry.prompt` is stored verbatim and has to be:
  the guards re-read it on every hit and reading it as text is the whole mechanism. `kmemo-slf4j`
  redacts prompts by default because prompts are user input and routinely carry personal data, which is
  the right instinct applied to the one surface where it was cheap; the store is the surface where it
  matters and nothing was done there. A clinical, legal or financial deployment could veto the write
  with a `CachePolicy`, which means not caching, or encrypt the database at rest, which protects nothing
  from anyone who can read the database. So the cache did not reach the buyers whose wrong answers cost
  the most, which is an odd place for a library whose whole argument is about not serving wrong answers.
  `EntryCipher` is the seam and `EncryptedStore` is a decorator that applies it to any store. kmemo
  ships no cryptography: the key is the caller's, the algorithm is the caller's, for the same reason it
  ships no embedding model. A decorator rather than a step inside `SemanticCache`, because encryption is
  about what is persisted and persistence is what a store owns, so one implementation covers Postgres,
  Redis, HNSW and anything a third party writes. It passes `CacheStoreContract` unmodified.
  **The read path costs one decryption per candidate**, not one per lookup: the guards read every
  candidate's prompt as text, so a lookup with the default five candidates does five prompt decryptions
  and one response decryption. That is stated as a count rather than a duration, because the duration
  belongs to the cipher the caller supplies. The cheaper design, a tokenized keyed form the guards could
  read without decrypting, was measured against it and does not survive the exit criterion: keyed tokens
  are opaque, and `Text.isSameWord` absorbing typos and inflections, `NumericGuard` parsing numbers,
  `UnitGuard` mapping `km` to `kilometers` and `EntityGuard` recognising an acronym's expansion all stop
  working on them. The guard chain has to reach the verdicts it reached before or the encryption is
  worth nothing, so the decryption is the price.
  Deterministic encryption would remove the price and is refused rather than documented against:
  `EncryptedStore` encrypts a probe twice on its first write and throws if the two agree. Identical
  ciphertext means two users asked the same question, and equality across prompts is exactly what an
  attacker holding the database wants.
  Two things it does not cover, both deliberate. The embedding is stored as it is, because the store
  finds entries by comparing vectors; it is a lossy view of the prompt and an attacker with the same
  embedding model can compare a guess against it. Tags and metadata pass through, because a tag names a
  source of truth and metadata is payload the cache never reads.
- **`AdmissionPolicy`, opt-in and off by default (M32).** Every miss wrote. That is the right default
  for a cache being filled deliberately and the wrong one for a cache in front of real traffic, where
  most prompts are asked once and never again: the store fills with entries that will never be hit,
  `search` scans them, and on the exact-scan stores the cost is linear in the store size and lands on
  every request rather than on the ones that caused it. `CachePolicy` could not help, because it decides
  on the content of one prompt and one response and answers "may this be stored", never "is this worth
  storing".
  The policy keeps a fixed-size count-min sketch of what has been asked, 64 KiB whatever the traffic,
  and admits an entry on the second sighting of the exact prompt rather than the first. Counters halve
  every 100,000 sightings so the estimate follows recent traffic instead of remembering a prompt asked
  twice a year apart.
  The measurement ships with it. On 20,000 requests over 4,000 distinct prompts drawn Zipf(s=1.0) over
  rank, exact repeats only: hit rate 85.2% and 2,965 entries with no policy, 76.1% and 1,907 admitting
  on the second sighting, 70.2% and 1,224 on the third. A third off the store for nine points of hit
  rate, and it is worth less on a flatter distribution than this one.
  Two constraints, both about what admission may look at. It can only ever suppress a **write**, never
  a lookup, so a bad decision costs one future miss and can never produce a wrong answer. And the sketch
  is keyed on exact prompt text within a scope, never on similarity, because a frequency estimate that
  counted two different questions as one would be the false hit this library exists to prevent arriving
  through the write path. It does not apply to `put` or `warm`, which are a caller saying "store this"
  rather than traffic arriving, unlike `CachePolicy`, which covers every write path because a guarantee
  with one path around it is not a guarantee.
  Held-back writes are counted in `CacheStats.writesNotAdmitted`. There is deliberately no `CacheEvent`
  for them: `CacheEvent` is a sealed interface, so a new subtype would break every exhaustive `when`
  over it, and that is a source break the `2.x` line does not take for a counter that can be read from
  `stats()`.

### Changed

- **Four types gain constructor parameters or fields, so code compiled against `2.1.0` must be
  recompiled.** Source compatible throughout, which `STABILITY.md` allows at a minor and names here so a
  recompile is never a surprise. `git diff v2.1.0 v2.2.0 -- '*/api/*.api'` reports 14 removed lines and
  every one of them is a constructor or `copy` signature that gained a parameter with a default.
  - `SemanticCache` gains `prices`, `admissionPolicy` and `requireTenant`.
  - `CacheStats` gains `savings` and `writesNotAdmitted`.
  - `CacheEvent.Hit` gains `saved` and `currency`.
  - `SubstitutionGuard` gains `maxTokens`.
- `MatchGuards.standard()` is unchanged, and every published corpus figure with it. Both new presets and
  both new guards ship without touching it, which is why nothing measured before this release moved.
- **The README is a third of its former length.** It had grown to 964 lines and become a manual with a
  changelog in it, and its Roadmap section was five release-by-release narratives restating this file and
  the board. The measurements moved to `docs/MEASUREMENTS.md`, where the methodology sits beside the
  figures instead of being cut for length; the per-option rationale stays in the KDoc, where somebody
  reading about that option finds it. What is left is the argument, one code sample per shape of the API,
  the module table, the headline numbers, and a roadmap that points at the board rather than competing
  with it.

### Internal

- CI fetches a second corpus. `tools/rag-corpus/fetch.py` writes the retrieval corpus M42 measures on,
  and every Gradle call in CI now passes `-PragCorpusRequired=true` beside the external corpus flag, for
  the same reason: without it a missing file skips the measurement, and a measurement nobody notices has
  stopped running reads as a passing one.
- `actions/setup-python` 6 to 7. A major bump of a workflow action, recorded because a change that ships
  no API still changes how this project builds and releases.
- The release workflow now publishes rather than uploading. Every module called
  `publishToMavenCentral()` with no arguments, and the plugin's default is
  `automaticRelease = false`, so the job uploaded the bundle to the Central Portal as `USER_MANAGED`
  and finished green while the version waited in a queue for somebody to press a button. `2.1.0` sat
  there until it was released by hand. `automaticRelease = true` also turns on deployment validation,
  which the plugin performs only when the release is automatic, so the build now waits for the
  deployment to reach `PUBLISHED` or `FAILED` instead of ending at "uploaded". A release job that goes
  green for something that has not happened is the same failure this project spends its corpus
  discipline on.

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
- The em dashes are gone from the prose. The house register allowed them freely and that was the wrong
  call for a portfolio repository: a run of them is one of the more reliable tells of generated text,
  and these files are read by people looking for exactly that. Each one was rewritten rather than
  swapped for a comma. The pass covered `README.md`, `STABILITY.md`, `CONTRIBUTING.md`, `docs/` and,
  in this release, the new KDoc and all twelve POM descriptions. The one that stays is the README's
  `Status` label, where the dash is punctuation in a heading rather than a pause in a sentence.

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

[Unreleased]: https://github.com/NaCode-Studios/Kmemo/compare/v2.3.0...HEAD
[2.3.0]: https://github.com/NaCode-Studios/Kmemo/compare/v2.2.0...v2.3.0
[2.2.0]: https://github.com/NaCode-Studios/Kmemo/compare/v2.1.0...v2.2.0
[2.1.0]: https://github.com/NaCode-Studios/Kmemo/compare/v2.0.0...v2.1.0
[2.0.0]: https://github.com/NaCode-Studios/Kmemo/compare/v1.1.0...v2.0.0
[1.1.0]: https://github.com/NaCode-Studios/Kmemo/compare/v1.0.0...v1.1.0
[1.0.0]: https://github.com/NaCode-Studios/Kmemo/compare/v0.5.0...v1.0.0
[0.5.0]: https://github.com/NaCode-Studios/Kmemo/compare/v0.4.0...v0.5.0
[0.4.0]: https://github.com/NaCode-Studios/Kmemo/compare/v0.3.0...v0.4.0
[0.3.0]: https://github.com/NaCode-Studios/Kmemo/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/NaCode-Studios/Kmemo/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/NaCode-Studios/Kmemo/releases/tag/v0.1.0
