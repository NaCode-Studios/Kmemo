# What has been measured

Kmemo's argument is that a semantic cache can refuse the wrong answer, and an argument like that is only
worth what its evidence is worth. The README carries the headline figures. This document carries the
rest: the methodology, the per-axis breakdowns, and the results that came out badly.

Every number here is reproducible from this repository with the command printed beside it.
[CORPUS.md](CORPUS.md) describes the labelled data and the rules that keep it honest.

## The guard chain, per corpus

| Split | Written by | Near misses rejected | Paraphrases kept |
| --- | --- | --- | --- |
| tuned | this project, with the guards in view | in-sample, not evidence | in-sample, not evidence |
| held-out | this project, after the guards existed | 71% | 88% |
| validation | this project, blind | 68% | 88% |
| **external**, PAWS-Wiki `test` | **Google Research, 2019** | **14%** (647/4,464) | **79%** (2,807/3,536) |

Guard-only: no `Verifier` is in the loop, so these describe the free lexical layer rather than the cache
as a whole.

```bash
./gradlew :kmemo-core:jvmTest --tests '*CorpusTest*'
python tools/external-corpus/fetch.py && ./gradlew :kmemo-core:jvmTest --tests '*ExternalCorpusTest*'
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
