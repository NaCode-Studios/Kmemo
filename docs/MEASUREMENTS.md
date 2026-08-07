# What has been measured

Kmemo's argument is that a semantic cache can refuse the wrong answer, and an argument like that is only
worth what its evidence is worth. The README carries the headline figures. This document carries the
rest: the methodology, the per-axis breakdowns, and the results that came out badly.

Every number here carries the command that produces it, and every one this build can render is
recorded in [figures.json](figures.json) with the exact line the document must contain, so a
measurement that moves fails a build until the prose is edited. [CORPUS.md](CORPUS.md) describes the
labelled data and the rules that keep it honest.

## The guard chain, per corpus

Five splits, and the column that matters most is the one saying what each number is worth. Every rate
carries its 95% Wilson interval, because a rate from 102 pairs and a rate from 4,464 are not the same
kind of number and a table that prints them in one column says they are.

| Split | Standing | Written by |
| --- | --- | --- |
| tuned | in-sample | this project, with the guards in view |
| held-out | retired | this project, after the guards existed; failures since read |
| validation | retired | this project, blind; failures since read |
| qqp | blind | the public, on Quora, labelled by Quora, 2015 to 2017 |
| external | blind | Google Research, 2019, PAWS-Wiki `test` |

| Split | Near misses rejected | Paraphrases kept |
| --- | --- | --- |
| tuned | in-sample, not evidence | in-sample, not evidence |
| held-out | 71% ±9 (61/86) | 88% ±10 (37/42) |
| validation | 68% ±9 (69/102) | 88% ±9 (45/51) |
| qqp | 65% ±2 (1634/2500) | 79% ±2 (2205/2796) |
| external | 14% ±1 (647/4464) | 79% ±1 (2807/3536) |

Guard-only: no `Verifier` is in the loop, so these describe the free lexical layer rather than the cache
as a whole.

Three things in that table were not knowable before `2.3.0`.

**The question-register figure survives at scale.** The written splits reported 68% and 71% on 86 and
102 near misses, which supports an interval nine points wide in each direction. On 2,500 external near
misses in the same register the chain catches 65%, inside both. The small numbers were not lucky.

**Paraphrase retention was overstated.** The written splits say 88%; external questions say 79%. A fifth
of genuine hits are refused and paid for with a model call, and the small splits did not show it.

**Two of the old rows are retired rather than blind.** Their failures have been read, which cannot be
undone. They keep their floors as regression gates, and they stop being the number to quote.

```bash
./gradlew :kmemo-core:jvmTest --tests '*CorpusTest*'
python tools/external-corpus/fetch.py && ./gradlew :kmemo-core:jvmTest --tests '*ExternalCorpusTest*'
python tools/qqp-corpus/fetch.py && ./gradlew :kmemo-core:jvmTest --tests '*QqpCorpusTest*'
```

## What each guard contributes inside the chain

Every guard has been scored alone since `1.0` and never as a member, so an argument about whether
eleven guards earn their place had numbers on neither side. **Alone** is what a third party installing
one guard would get. **Unique** is what the chain would lose if it were removed, computed by holding
each pair against the chain with that guard taken out.

On the two blind splits:

| Guard | qqp: alone | qqp: unique | external: alone | external: unique |
| --- | --- | --- | --- | --- |
| numeric | 206 | 82 | 29 | 26 |
| unit | 1 | 0 | 0 | 0 |
| temporal | 2 | 1 | 0 | 0 |
| negation | 30 | 25 | 0 | 0 |
| antonym | 8 | 4 | 0 | 0 |
| entity | 1,003 | 215 | 524 | 511 |
| substitution | 1,192 | 339 | 42 | 38 |
| scope | 0 | 0 | 0 | 0 |
| direction | 2 | 2 | 26 | 26 |
| sub-span | 94 | 75 | 39 | 33 |
| lexical-divergence | 0 | 0 | 0 | 0 |

Read on the 188 pairs of the two written splits, the same measurement says eight guards contribute
nothing unique. Read on 6,964 it says seven of the eleven carry unique catches on questions and five on
PAWS. **The claim that most of the chain is redundant was a sample-size artefact**, and it is the
clearest thing the larger splits have corrected.

`lexical-divergence` is the exception and it stays. It has never caught anything on any split and has
cost five paraphrases in 6,471, and that zero is not evidence: every pair in every corpus was written or
selected *as a pair*, so two prompts sharing almost nothing never appear, and the only way they reach a
guard is when an embedder proposes one for the other. No corpus here can contain the case it exists
for. The argument is in the guard's own documentation, where somebody proposing to delete it will read
it.

### What the chain costs

The other half of that argument, which nothing here had measured either. Wall clock on one JVM over
2,410 pairs in both directions, every guard run on every candidate:

| | Per candidate |
| --- | --- |
| the whole chain | ~24 µs |
| the most expensive single guard (`sub-span`) | ~3.4 µs |
| the chain without `lexical-divergence` | ~22 µs |

`SemanticCache` stops at the first rejection, so a lookup pays the full figure only when every guard
abstains, which is the case that ends in a hit. Against an embedding round trip of 50 to 200 ms, the
guard layer is not where a lookup's time goes, and performance is not a reason to remove a guard.

```bash
./gradlew :kmemo-core:jvmTest --tests '*GuardChainCostTest*'
```

## The substitution floor, argued and then measured

`SubstitutionGuard` refuses to look below four content words, and the reason written beside it was a
verb: `define recursion` against `explain recursion` is a synonym rather than a swap. That is an
argument about **which** word differs, applied as a bound on **how many** words there are. Real
questions are short, so the proxy silences the guard on much of the traffic it exists for.

Read from the mechanism the floor is a crossover: the evidence is the *agreeing* part, which grows with
every extra word, against the risk that the one differing position is a synonym, which does not shrink
with length. That settles two things and not a third. A floor must exist, and two is below it, because
one agreeing word is no agreement. Where three sits against four is where a structural quantity crosses
an empirical one, and no reasoning about the guard produces it.

So it was measured, on the splits nobody here can tune.

| Split | Standing | minTokens 4 | minTokens 3 | 3, first position exempt |
| --- | --- | --- | --- | --- |
| tuned | in-sample | 76 caught, 46 kept | 76, 45 | 76, 46 |
| held-out | retired | 61, 37 | 65, 37 | 64, 37 |
| validation | retired | 69, 45 | 84, 44 | 81, 45 |
| qqp | blind | 1,634, 2,205 | 1,711, 2,142 | 1,699, 2,169 |
| external | blind | 647, 2,807 | 647, 2,807 | 647, 2,807 |

**Three is a trade, not a gain, and the trade is worse than one already declined.** It buys 77 catches
on the question split and pays 63 paraphrases for them, and costs the tuned split a paraphrase it has
never lost. That ratio is 1.2 to 1, against the 2.9 to 1 this project refused for `WordOrderGuard` in
`2.2.0`. The floor stays at four.

The validation column is why M54 came first. Read there alone, the change looks like fourteen points of
catch rate for one paraphrase, and that split's failures had been read while the hypothesis was being
formed. A number that large, published and then found to be an artefact of the corpus it was chosen
against, is the specific embarrassment the larger blind splits exist to prevent.

**What the mechanism does point at ships as a preset.** If the floor's subject is the verb, the bound
belongs on the verb, and in a question the verb sits at the head. `SubstitutionGuard.withHeadFloor`
applies the old floor only to a difference in the first content word:

| Split | Near misses caught | Paraphrases kept |
| --- | --- | --- |
| qqp | 1634 → 1699 | 2205 → 2169 |

Free on the tuned split, three and twelve extra catches on the two retired splits for no paraphrase at
all, and on external questions sixty-five wrong answers stopped for thirty-six extra API calls.
`MatchGuards.shortQuestions()` is the chain that opts in and `standard()` is untouched, because whether
that trade is worth it is a question about a domain rather than about a corpus.

```bash
./gradlew :kmemo-core:jvmTest --tests '*SubstitutionFloorTest*'
```

## Against a threshold-only cache, and against GPTCache

Every configuration is handed the same candidate pair and asked only whether to serve it, which controls
for the embedding model more tightly than matching embedders would.

| Corpus | Configuration | Precision | Recall | F1 | False-hit rate |
| --- | --- | --- | --- | --- | --- |
| held-out | threshold-only | 0.328 | 1.000 | 0.494 | **1.000** |
| held-out | `standard()` | 0.597 | 0.881 | 0.712 | **0.291** |
| held-out | `strict()` | 0.589 | 0.786 | 0.673 | **0.267** |
| held-out | GPTCache `OnnxModelEvaluation` | 0.513 | 0.476 | 0.494 | **0.221** |
| validation | threshold-only | 0.333 | 1.000 | 0.500 | **1.000** |
| validation | `standard()` | 0.577 | 0.882 | 0.698 | **0.324** |
| validation | `strict()` | 0.544 | 0.725 | 0.622 | **0.304** |
| validation | GPTCache `OnnxModelEvaluation` | 0.676 | 0.451 | 0.541 | **0.108** |

The false-hit rate is the share of near misses that were served. A threshold-only cache serves all of
them, which is not a straw man: it is what every "add a semantic cache" tutorial builds.

**Against GPTCache the result is a trade, not a win.** Its ONNX cross-encoder serves fewer near misses
than `standard()` on both splits, so it is the stricter filter. It buys that by refusing more than half
the genuine paraphrases it is shown, where `standard()` keeps 88%, so the cache does roughly half the
work. Which of those you want depends on what a wrong answer costs you.

Two notes on that row. GPTCache's *default* evaluator is `SearchDistanceEvaluation`, which scores the
vector distance retrieval already produced: that is the threshold-only row under another name, and
re-running it would measure the embedding model rather than the cache. And cross-runtime latency is
deliberately absent, because a JVM against Python wall-clock figure compares runtimes while appearing to
compare caches.

```bash
./gradlew :kmemo-core:jvmTest --tests '*ComparativeBenchmarkTest*'
```

The GPTCache row is measured out of band, because GPTCache is a Python package that downloads a model on
first use and CI is a JVM build. See [tools/gptcache-comparison](../tools/gptcache-comparison). The
recorded numbers carry the SHA-256 of the corpus files they were taken against, and the build fails if a
corpus changes without the harness being re-run.

## The PAWS target, registered before the attempt

`2.1.0` published a number this project has to answer for: PAWS rejects 647 of 4,464 near misses, 14%,
against roughly 68% on the blind internal splits. The release notes explained it, and the explanation is
sound as far as it goes and is not a defence. Deliberately high lexical overlap is the precise case this
library exists for, and answering "that corpus is hard" concedes the thesis.

So there are two honest outcomes and this section commits to finding out which one is true rather than to
a particular one. Either a guard chain can be built that holds on high-overlap pairs, in which case the
current one is undertuned. Or it cannot, in which case the library needs a stated boundary: it catches
the near misses that arise in traffic, it does not catch adversarially constructed ones, and here is
where the line is.

**The target is written down here before any guard was changed**, because a number chosen after seeing
the result is a description rather than a target. What is already known, and what informs it, is the
per-register and per-length work above: register does not explain the gap, length explains much of it,
and `entity` alone already catches 11.8% of the declarative near misses at 73% precision where the whole
chain catches 14.5%.

| Outcome | Definition |
| --- | --- |
| **Success** | PAWS near-miss rejection reaches **25%**, roughly a doubling, **and** paraphrase retention stays at or above today's 79%, **and** no written split loses a catch or a paraphrase. |
| **Boundary** | Rejection improves but retention on PAWS falls below 79%, or any written split regresses. The gain was bought from the API-bill column, which is not an improvement this project counts. |
| **Failure** | Rejection stays under 25% under those constraints. |

25% rather than the 68% the internal splits reach, because PAWS is adversarial by construction and
parity with realistic traffic is not a reasonable bar. A doubling is falsifiable, it is far short of
parity, and it is stated before the attempt rather than after it.

**Failure is publishable and will be published.** The failure mode to avoid is the quiet one: a guard
tuned until PAWS improves, shipped without checking what it did to the other three splits. A cache that
rejects more paraphrases has not got better, it has moved cost from the wrong-answer column to the
API-bill column, and those two columns are not interchangeable.

### The result: the boundary case

The attempt was `WordOrderGuard`, and it was chosen from how PAWS is built rather than from what its
pairs contain. PAWS is Paraphrase Adversaries from Word Scrambling: its near misses carry the same words
in a different arrangement, which is the one shape the chain is structurally blind to, because every
guard in it but one compares sets and that one requires the order to match. `Flights from New York to
Miami` and `Flights from Miami to New York` differ in nothing any of them can see.

| Split | Near misses caught | Paraphrases kept |
| --- | --- | --- |
| tuned | 76 → 76 (91.6% → 91.6%) | 46 → 46 (100% → 100%) |
| held-out | 61 → 61 (70.9% → 70.9%) | 37 → 36 (88.1% → **85.7%**) |
| validation | 69 → 70 (67.6% → 68.6%) | 45 → 45 (88.2% → 88.2%) |
| **external** | 647 → 1,772 (14.5% → **39.7%**) | 2,807 → 2,417 (79.4% → **68.4%**) |

**The target was cleared and the constraint was not.** PAWS rejection reached 39.7%, well past the 25%
registered, and it was paid for with 390 paraphrases there and one on held-out. That is the boundary case
as it was defined before the attempt: the gain came out of the API-bill column, and this project does not
count that as an improvement.

So the guard ships and **no preset carries it**. `MatchGuards.standard()` is byte-for-byte what it was, so
every figure above it is still the figure it was. A caller whose domain makes a wrong answer expensive
enough to buy at that price can add `WordOrderGuard()` to their chain deliberately, with the trade in
front of them.

### The boundary this draws

The honest statement the milestone asked for, now that it has a measurement behind it rather than a
guess. **Kmemo catches near misses that arise in traffic. It does not catch adversarially constructed
ones at a price worth paying.** The line is at roughly 40%: reaching it costs a ninth of the genuine
paraphrases on the same corpus, and the guard chain has no way to tell a reversed relation from a
reordered clause without reading the sentence, which is what a cross-encoder does and what a lexical
chain by definition does not.

That is not an argument for stopping. It is the number that says what the next thing would have to be:
something that reads structure rather than tokens, at a cost between a regular expression and a
transformer, and nothing in this repository is that yet.

```bash
python tools/external-corpus/fetch.py && ./gradlew :kmemo-core:jvmTest --tests '*PawsTargetTest*'
```

## What register does to the guards, and what it does not explain

Register is a property of a deployment, not of a benchmark. A support assistant sees questions, a command
palette sees imperatives, a search box sees noun phrases, and a retrieval-augmented pipeline compares
retrieved passages, which are prose. The guards were tuned on the first of those. Every corpus pair is
filed by register, by the published rules in
[`Registers.kt`](../kmemo-core/src/jvmTest/kotlin/dev/kmemo/fixtures/Registers.kt) rather than by hand,
and every guard is scored per register.

**Register does not explain the PAWS gap.** The three written splits are 72% to 90% questions and PAWS is
99% declarative prose, so the two are almost never compared like for like. Where they do overlap, PAWS
has 43 questions, and on those the chain catches **9%** against 65% on validation's questions. PAWS is
harder in both registers, and slightly harder in the one the guards were tuned for. Whatever the gap is,
it is difficulty; calling it register was a guess, and it has now been checked.

**One guard is responsible for most of the register spread.** On declarative prose `substitution` rejects
493 genuine paraphrases to catch 42 near misses, a precision of **8%**. On the written question corpora
the same guard runs at 98 to 100% and is the strongest in the chain. That is a mechanism that does not
hold on the register rather than a tuning problem: the guard rejects when two prompts have the same
content words in the same order and differ in exactly one position, which in a question means a swapped
term and in prose means a synonym.

`MatchGuards.prose()` is `standard()` without it. The trade, in both directions:

| Split | Near misses caught | Paraphrases kept |
| --- | --- | --- |
| tuned (questions) | 76 → 75 | 46 → 46 |
| held-out (questions) | **61 → 22** | 37 → 38 |
| validation (questions) | **69 → 27** | 45 → 47 |
| external, declarative only | 645 → 607 | **2,787 → 3,254** |

Twelve paraphrases kept for each catch lost on prose; two thirds of the protection given up for one or
two on questions. That asymmetry is why it is a preset and `standard()` is untouched.

```bash
./gradlew :kmemo-core:jvmTest --tests '*GuardRegisterTest*' --tests '*RegisterPresetTest*'
```

## What prompt length does to the guards

Most of the gap between the external row and the other three is prompt length, not subject matter. The
three written splits run from 19 to 85 characters and PAWS runs from 32 to 214. Filing every pair by the
mean length of its two prompts, `substitution` rejects genuine paraphrases at 0% below 48 characters, 12%
between 48 and 95, and 15% from 96 characters up. The validation split is 76% shorter than 48 characters
and PAWS is 69% longer than 96, so the two averages that looked like 4% against 14% were describing
different lengths. In the one band where they overlap they read 10% and 12%.

The mechanism is the guard's own arithmetic: one differing word out of five is a term somebody swapped,
one out of forty is a word somebody chose differently, and the guard counts differing positions without
asking what share of the prompt one position is. `MatchGuards.longPrompts()` bounds it at twelve content
words. On the external split that gives up 12 of 647 catches and keeps 125 more of 3,536 paraphrases,
about ten kept for each one lost, and it changes nothing on the three written splits.

**There is no cliff further up.** Wrapping both sides of every PAWS pair in an identical retrieval
envelope, which leaves the difference between them untouched and only buries it, holds `substitution` at
15% at 512, 1,024 and 2,048 characters. What does move is a different pair of guards, and no preset can
bound it away: `entity` goes from 6% to 10% and `direction` from 0% to 4%, because both treat the first
word of the text they are handed as a sentence opener and stop exempting it once a question has passages
in front of it. Fixing that needs a way to tell a guard where the question starts, which this API does
not have.

Two limits. The envelope splits are derived from PAWS rather than written, so they measure dilution and
are not a fifth independent score. And **nothing here measures a written prompt longer than 214
characters**: every long figure comes from wrapping short pairs, which is why the report prints its empty
bands instead of stopping at the last one with data in it.

```bash
python tools/external-corpus/fetch.py && ./gradlew :kmemo-core:jvmTest --tests '*GuardLengthTest*'
```

## What the verifier catches

About a third of near misses get past the guards on the blind splits: 25 of 86 on held-out, 33 of 102 on
validation. That residual is what an optional `Verifier` exists for.

| Corpus | Residual the guards serve | The verifier stops | False-hit rate | Paraphrases kept |
| --- | --- | --- | --- | --- |
| held-out | 50 lookups | 40 (80%) | 0.291 → **0.058** | 0.881 → **0.452** |
| validation | 66 lookups | 51 (77%) | 0.324 → **0.074** | 0.882 → **0.686** |

Measured against a named reference model rather than a hypothetical one, and it fails closed: a timeout
or an error rejects rather than serving something unconfirmed.

### And what it costs

A cache sold on saving model calls owes the reader the price of its safety net. The catch rate above says
how many wrong answers the verifier prevents; on its own it lets a reader construct a worst case and gives
them no way to rule it out.

| Split | Lookups | Invocations | Per lookup | Tokens per call | Tokens spent |
| --- | --- | --- | --- | --- | --- |
| tuned | 129 | 53 | 0.41 | 17.7 | 938 |
| held-out | 128 | 62 | 0.48 | 20.8 | 1,292 |
| validation | 153 | 78 | 0.51 | 16.6 | 1,296 |
| external | 8,000 | 6,624 | 0.83 | 37.3 | 247,059 |

On the residual the reference verifier was actually shown, across held-out and validation: 116
invocations, **91 false hits avoided**, 1,996 tokens, which is **22 tokens per avoided false hit**.

**Every invocation rate in that table is an upper bound, and the reason is the corpora rather than the
chain.** These splits are 56% to 67% near misses by construction, because a corpus of realistic traffic
would be almost all paraphrases and would measure nothing. The verifier runs on what the guards could not
settle, so a corpus built out of hard pairs sends it most of the lookups. Traffic whose near-miss share is
a few per cent sends it a few per cent.

Tokens are counted the way the guards tokenize, which is the count this repository can state exactly. A
provider's tokenizer differs by a constant nobody here can know, so convert with your own before comparing
22 tokens against what a wrong answer costs you. That comparison is the decision this figure exists to
hand back.

```bash
./gradlew :kmemo-core:jvmTest --tests '*VerifierCostTest*'
```

## What admission costs

`AdmissionPolicy` stores an answer on the second sighting of a prompt rather than the first. Replayed
over 20,000 requests across 4,000 distinct prompts drawn Zipf(s=1.0) over rank, exact repeats only, one
entry per distinct prompt and no eviction:

| Policy | Hit rate | Store size | Writes held back |
| --- | --- | --- | --- |
| none (the default) | 85.2% | 2,965 | 0 |
| admit on the 2nd sighting | 76.1% | 1,907 | 2,883 |
| admit on the 3rd | 70.2% | 1,224 | 4,738 |

A third off the store for nine points of hit rate, or three fifths off for fifteen. On a flatter
distribution than this one it is worth less.

```bash
./gradlew :kmemo-core:jvmTest --tests '*AdmissionWorkloadTest*'
```

## What a cache removes from a retrieval pipeline, and what it gets wrong there

Every other number here comes from a benchmark this repository wrote for itself, against corpora chosen
to exercise the guards. None of them answers the question a reader arrives with: on a
retrieval-augmented pipeline, how much does this remove, and how much of what it serves is wrong because
the retrieved context differed while the question did not. That second failure is one this project's own
corpora are **structurally unable to produce**, because their pairs are two prompts and a verdict and a
RAG false hit needs two prompts that are the same and two documents that are not.

SQuAD v1.1 dev: 400 Wikipedia paragraphs, 2,410 questions asked about them, with the answer marked inside
the paragraph, so whether a generated answer was right is a lookup rather than a judgement and no model
has to be trusted or paid. Retrieval is nearest-paragraph, generation returns the labelled answer for
whatever it retrieved, and the cache sits in front of generation at a threshold of 0.90.

| Keyed on | Model calls, cold | Model calls, warm | Removed | Wrong answers served |
| --- | --- | --- | --- | --- |
| the question, no guards | 2,360 | 0 | **100%** | 12 |
| the question, guarded | 2,374 | 0 | **100%** | 6 |
| the question and the retrieved document | 2,380 | 0 | **100%** | **2** |

Two findings, and the second is the more valuable one.

**The saving on this workload is the whole generation step.** A second run of the same questions makes no
model calls at all, which is the shape an evaluation suite, a regression run or any replayed traffic has.

**The guards survive contact with retrieved context, and halve the wrong answers.** That was unknown, and
the worry was specific: a threshold tuned on bare questions is wrong when the retrieved context is what
makes two near-identical questions into different prompts. It turns out the guards help there rather than
hurt. Folding the retrieved document into the key, which is what `context` is for, takes it to 2, and
those last two are the residual nothing prompt-side can reach: questions that are word for word identical,
asked of different paragraphs.

```bash
python tools/rag-corpus/fetch.py && ./gradlew :examples:test --tests '*RagPipelineTest*'
```

## What a cached evaluation suite saves

An evaluation suite replays identical prompts by construction, so the hit rate is high and the false-hit
risk is at its lowest. Measured against [Dokimos](https://github.com/dokimos-dev/dokimos) on a golden set
run twice through one cache: a model call per case on the first run and **none on the second**, with the
suite reaching the same verdict either way.

```bash
./gradlew :examples:test --tests '*Dokimos*'
```

## On-device embedding, and why it is not offered

`Embedder` names four places to get an implementation. Three are network providers and the fourth runs
only on the JVM, so on the native targets and on wasm embedding is always a round trip. The alternative
was measured rather than assumed: one forward pass at `all-MiniLM-L6-v2` shape, arithmetic only, in
ordinary Kotlin, on `macosArm64`.

| | |
| --- | --- |
| Work per call | 679 MFLOP over a 32-token sequence |
| Fastest of three runs | **2.5 seconds** |
| Throughput | 274 MFLOP/s |
| Encoder weights | 42 MB fp32, 10 MB int8 |
| Vocabulary table | 46 MB fp32 on top |

An embedding API answers in 50 to 200 ms, from a phone, which is slower hardware than the machine that
produced this. A pure-Kotlin on-device embedder is an order of magnitude slower than the call it exists
to avoid, and no amount of tuning closes a gap that size. A viable one wraps a platform inference
runtime, which means per-platform native binaries, a model format, a tokenizer and a licence conversation
about weights: a different project from a cache.

```bash
./gradlew :kmemo-core:macosArm64Test
```

## Turning a hit rate into money

The cache does the first part itself. Declare what a call costs in a scope and `CacheStats.savings`
reports what the hits in it did not cost, from the token counts on the entries that were served rather
than from an average applied to a hit count. Only hits ever add to the figure, never writes.

The rest needs an input no library has. At **Q** queries a day, a hit rate of **H**, and a model call
costing **C**:

```
saved per day      = Q × H × C
false hits per day = Q × H × (near-miss share of your traffic) × false-hit rate
```

At 100,000 queries a day, a 40% hit rate and $0.002 a call, the cache saves **$80 a day**. If 5% of those
hits are near misses rather than paraphrases, a threshold-only cache serves **2,000 wrong answers a day**;
`standard()` serves about **650**, and `responseAware()` about **600**.

The GPTCache row cannot be read off the false-hit rate alone, which is why the saving sits beside it.
Facing the same 2,000 near-miss lookups its evaluator serves about **220**, a third of `standard()`. But
it also refuses half the genuine paraphrases, so the $80 falls to about **$41 a day**. Roughly $39 a day
of extra model calls, and a transformer inference on every candidate, to avoid around 440 wrong answers.

Whether that trade is acceptable depends on what a wrong answer costs in your domain, which is the one
input no benchmark can supply. Shadow mode puts *your* traffic on that axis before you serve a single
cached answer.
