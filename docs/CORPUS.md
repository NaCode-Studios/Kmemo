# The corpus: kmemo's defended asset

kmemo's central claim is that its guards reject near-misses a similarity threshold cannot, without
rejecting genuine paraphrases. That claim is only as trustworthy as the data behind it. That data is three
labelled corpora of prompt pairs, and this document is the process that keeps them honest.

## The five splits, and the three standings

Every pair is a `(a, b, category, kind)` where `kind` is either a **near-miss** (the two prompts need
different answers) or a **paraphrase** (they need the same answer). The shape is published as
[`spec/corpus/SCHEMA.json`](../spec/corpus/SCHEMA.json) and the measurement as
[`spec/corpus/METRIC.md`](../spec/corpus/METRIC.md). Three splits live in
`kmemo-core/src/jvmTest/resources/*-corpus.json`; two are fetched rather than committed.

A split's **standing** is what its number may be quoted as, and it is part of the number rather than
part of the folklore. It is a field in the file, printed in every report and carried in the JSON
artifact.

| Standing | What it means |
| --- | --- |
| `in-sample` | The guards were written with these pairs in view. The score measures the fitting. |
| `retired` | Out of sample once; its failures have since been read. A regression gate, no longer evidence. |
| `blind` | No failure from it has been read, and nobody here can add to it. |

| Split | Standing | Written by |
| --- | --- | --- |
| **tuned** | `in-sample` | this project, with the guards in view |
| **held-out** | `retired` | an adversarial review given the guard sources; its failures were read while guards were fixed |
| **validation** | `retired` | an author shown no guard source; its failures were printed by the corpus report on every run |
| **qqp** | `blind` | the public, on Quora, labelled by Quora, 2015 to 2017 |
| **external** | `blind` | Google Research, 2019, for a purpose unrelated to caching |

The rule that governs a blind split is simple and absolute: **you do not read its failures while
editing a guard.** A single pair moved from a blind split into a guard's design destroys that pair's
evidentiary value forever, and there is no way to un-see one.

## Retirement, and why two splits are in it

A blind split is **spent** the moment somebody reads which pairs it fails on. Not damaged, not
suspect: spent, because from then on every subsequent change has had the opportunity to be aimed at it,
and nobody can prove otherwise, including the person who made the change.

The policy, settled in `2.3.0` rather than left to judgement:

**A split whose failures have been read is retired, never repaired.** Growing it does not help, because
the pairs somebody read are still in it and the guards fitted to them are still shipped. Splitting off
the unread half is worse, because it publishes a number under the old split's name.

**A retired split keeps everything except its voice.** Its floors stand, it stays in every report, and
it goes on failing a build when a rate moves down. A spent split is the best regression gate this
repository has. What it stops being is the number to quote as blind.

**Retirement is announced with its cause.** `held-out`'s failures were read while guards were being
fixed. `validation`'s were printed in full by `CorpusTest` on every run, so the tooling performed the
prohibited act on behalf of everybody who ran the suite, and they were read while M51's substitution
floor was being investigated.

**A retired split is replaced, not merely mourned.** Blind evidence has to keep arriving, so the
replacement was found rather than written: two external datasets, an order of magnitude larger, that
nobody here can add to.

## How large a split has to be

A rate is an estimate, and a hundred pairs support an estimate about nine points wide in each
direction. Two of this project's headline numbers used to sit on 86 and 102 near misses, so a genuine
five-point improvement and a lucky run were the same measurement, and nothing said so.

Every rate now carries its 95% Wilson interval, everywhere it is printed. Around a rate near 68%,
telling five points from noise takes roughly **1,340** near misses. The written splits are two orders
of magnitude under that; `qqp` holds 2,500 and `external` holds 4,464. `CorpusTest` asserts the first
fact and `QqpCorpusTest` asserts the second, so neither can drift without the build saying so.

### The derived envelope splits

There is a fifth thing in the reports and it is not a split. `external+rag512`, `external+rag1024` and
`external+rag2048` are the external pairs with both sides wrapped in the same retrieved-context
envelope: a fixed instruction, a block of passages drawn from other pairs in the same split, and the
original prompt as the question. The envelope is byte-identical on the two sides, so the only
difference between the long prompts is still the difference between the short ones, and the label
carries over untouched.

They exist because every pair anybody has written or fetched for this project is between 19 and 214
characters, and the audience that most needs the guards is caching prompts ten times that. What they
can say is whether a guard's behaviour changes when the same evidence is diluted. What they cannot say
is anything about how good the guards are: they contain no near miss anybody wrote and no paraphrase
anybody judged. **They are never quoted as a score**, and the reports print them under their own
heading for that reason. Adding to them means changing `LongPromptCorpus`, which is code, not data,
and the change shows up as a moved number in every band at once.

## The external split, and the objection it answers

The three splits above are careful, and they still share one weakness that no amount of process can
remove: **the same person wrote the pairs and the guards.** They therefore test the near misses that
were thought of, not the near misses that exist. A reader deciding whether to trust this cache should
say exactly that, and until `2.1.0` there was no answer.

The external split is the answer. It is **PAWS** (Paraphrase Adversaries from Word Scrambling), Wiki
`labeled_final`, **test** split. Google Research, 2019, 8,000 pairs of which 4,464 are near misses.
PAWS exists to measure whether a model can tell a paraphrase from a near-paraphrase when word overlap
is deliberately high, which is the one case a similarity threshold cannot separate and the case every
guard here was built for. It predates kmemo by four years and was assembled by people who had never
heard of it.

**It is fetched, never vendored.** `tools/external-corpus/fetch.py` downloads it from a pinned dataset
revision, verifies the SHA-256 of the bytes, and writes the converted pairs to
`build/external-corpus/`. The licence stays with the dataset and this repository does not grow a copy
of somebody else's corpus. Without the fetch, `ExternalCorpusTest` skips and says so; CI passes
`-PexternalCorpusRequired=true`, which turns absence into a failure, because a floor nobody notices has
stopped running is not a floor.

**The number is much worse than the other three, and that is the finding.** The guards reject 647 of
4,464 near misses here (14%) and keep 2,807 of 3,536 paraphrases (79%), against roughly 68% and 88% on
the blind internal splits. Two things are true about that gap and both belong next to it:

1. **A corpus built by an adversary to defeat lexical overlap is harder than one written from realistic
   traffic.** That is what PAWS is for, and a lower score against it is the expected result rather than
   a surprise.
2. **The register does not match.** PAWS pairs are declarative Wikipedia sentences; the guards read
   prompts, usually questions, usually typed quickly. It shows in the breakdown: `substitution` alone
   rejects 498 paraphrases here against 2 on the validation split, because a rearranged encyclopaedia
   sentence carries far more substituted content words than a rephrased question does.

Neither observation makes the number smaller, and neither is a reason to leave it out. A lower figure
from a harder source is worth more than another figure from the same source, and this is the only one
on the page that nobody here could have tuned. The README reports it beside the other three with the
dataset named.

## The question split, and what its selection rule costs

`external` answers the objection that the same person wrote the pairs and the guards. It cannot answer
a second one: PAWS is declarative Wikipedia prose and the guards read prompts, usually questions, so
every question-register figure still came from a corpus written here.

`qqp` is **Quora Question Pairs**, GLUE `validation`, 40,430 pairs typed by the public and labelled by
Quora years before this project existed. Label 1 means one answer serves both, which is a cacheable
paraphrase; label 0 means it does not, which is a near miss.

Most label-0 pairs are two unrelated questions, and a cache never sees those because the similarity
threshold rejects them before a guard is asked. So the pairs are filtered to the ones a threshold would
surface: **the two questions must share at least 60% of their character 4-grams**, Jaccard, over the
lowercased strings with whitespace collapsed. That leaves 5,296 pairs, 2,500 of them near misses, with a
median length of 51 characters. Characters rather than words, and the raw string rather than the
tokenizer, so the rule borrows no part of a guard's own machinery.

Three things belong beside that number rather than in a footnote.

**The selection is not neutral for one guard.** `lexical-divergence` fires when two prompts share almost
nothing, and this keeps only pairs that share a great deal, so it is silent here by construction. The
bias runs against the score: the filter removes the pairs that would have been easiest to catch.

**The pairs and the labels are external; the selection is not.** Nobody here can change PAWS at all.
Here this repository chose one threshold, once, before running a guard against the result. That is
weaker than PAWS and stronger than anything written here, and it is stated rather than blurred.

**The labels are crowd-applied and noisy.** The dataset's own card says so, and published estimates put
the disagreement near a twentieth of the pairs. That caps any score taken here in both directions, which
is a reason to read a change in the number rather than the number itself.

## What a report may print

The rule against reading a blind split's failures is usually broken by a report rather than by a person.
`CorpusTest` printed the whole validation residual on every run, so anybody who ran the suite read them,
and that is how the split was spent.

**No test prints an individual pair from a split that is not `in-sample`.** What the reports print
instead is the count and the distribution by category, which answer the question a reader has and guide
nothing, because nobody can aim a guard at a category without seeing the pairs.

The pairs stay reachable, because the day somebody is allowed to look at them, which is when a split is
being retired or replaced, they have to be there. `-PspendABlindSplit=true` prints them, and the flag is
named for what passing it costs rather than for what it shows.

| Report | May print pairs | Prints |
| --- | --- | --- |
| `CorpusTest` | tuned only | rates with intervals, per-guard alone and unique, residual counts by category |
| `ExternalCorpusTest`, `QqpCorpusTest` | never | rates with intervals, per-guard alone and unique |
| `GuardLengthTest`, `GuardRegisterTest` | never | per-band and per-register rates and counts |
| `SubstitutionFloorTest` | never | the floor ladder, per split |
| the guard TCK compliance report | never | one confusion matrix per corpus, with intervals |

## What CI enforces

`CorpusTest` runs on every build, and on every PR (see `ci.yml`), and **fails on regression**:

- The tuned split must keep **every** paraphrase and catch at least a floor of near-misses.
- The held-out and validation splits must not drop below their recorded near-miss and paraphrase
  floors (`*_FLOOR` constants in `CorpusTest`).
- `ExternalCorpusTest` and `QqpCorpusTest` hold the fetched splits to their own floors, set **at** the
  measurement rather than under it: nothing about them is stochastic, since the guards are pure and both
  datasets are pinned to a commit whose bytes the fetch scripts verify, so any movement at all is a real
  change somebody should have to look at.
- `GuardSpecTest` holds the published specification to the shipped guards: a rule that changes without
  `spec/guards/vectors.json` being regenerated fails the build, and regenerating it is a diff somebody
  approves rather than a number that moved.
- `PublishedFiguresTest` holds the prose to the measurements. Every figure it can render is checked
  against the exact line `README.md` and `docs/MEASUREMENTS.md` must carry, so a rate that moves fails
  the build until the sentence around it is edited. `tools/figures/check.py` re-runs that half with no
  Gradle involved.

The floors sit just under the current measurement. Their job is to fail when a number moves **down**,
not to assert the number is good. A change that only helps the tuned set cannot pass unnoticed. The
machine-readable numbers are written to `build/reports/guards/guard-report.json` for diffing across
commits.

## Growing a split without contaminating it

Adding pairs is how the corpus stays representative of real traffic. Do it per split:

### Growing **tuned** (in-sample)

Free to do anytime. Add pairs, run `./gradlew :kmemo-core:jvmTest --tests '*CorpusTest*'`, and if a new
near-miss slips through, that is exactly the signal to improve a guard. Raise the tuned floor to match.

### Growing a blind split (should one ever be written here again)

This is the delicate one. To add pairs **without** contaminating the split:

1. **Write the pairs blind.** Collect them from real or realistic traffic *before* running them through
   the guards. Do not hand-pick pairs you already know a guard will catch (that inflates the score) or
   miss (that is tuning in disguise).
2. **Commit the pairs and the measurement in the same change.** Run the corpus test once, record the new
   floor, and commit both. The pairs and the number they produce arrive together.
3. **Do not edit a guard in the same change.** If the new pairs reveal a weakness, note it, but fixing
   it belongs in a *separate* change against the **tuned** split, so the out-of-sample number that
   exposed the weakness stays untainted.
4. **Never lower a floor to make CI pass.** A floor only ever moves up, and only because the measurement
   genuinely improved. Lowering it is erasing the regression it exists to catch.

### Growing **qqp** or **external**

You cannot, and that is their strongest property. Both are somebody else's dataset at a pinned revision.
The only change anyone here can make is to point a script at a different one, or to move `qqp`'s
selection threshold, and either changes what every recorded number means and belongs in a pull request
that says so.

The one-sentence version: **the tuned split is where you improve the guards; the two retired splits are
where a regression is caught; and the two fetched splits are where you find out what somebody who owes
you nothing would have measured.**

## The response corpus, and why it is labelled differently

`response-corpus.json` is a fourth artifact and it is **in-sample**. It borrows prompts from the
held-out and validation splits and adds, to each side of a pair, the answer that prompt would have
received. `MatchGuards.responseAware()` is measured against it, and nothing else is.

**It is authored, and it could not have been harvested.** A semantic cache corpus records prompts; the
near misses worth catching are precisely the ones whose prompts look alike, and no public dataset pairs
those prompts with the answers that separate them. So the answers were written, and a written answer is
evidence about the person who wrote it as much as about the guard. The number that comes out of it is a
**regression check**, not a blind measurement, and it is labelled that way in the README, in the guard's
own documentation, and in the JSON report.

Two rules kept it as honest as an authored corpus can be, and both are conditions on any future change
to it:

1. **Answers are written before the guard is designed.** Every answer in the file predates
   `AnswerAnchorGuard`, so the guard could not be reverse-engineered from them. Nothing was rephrased
   afterwards to make a rejection land.
2. **Answers are realistic, not catchable.** What an assistant would actually reply, including the many
   answers that never name the term separating the two questions. `what is the boiling point of ethanol`
   is answered with `78.4 degrees Celsius at one atmosphere`, which no response-aware guard can use.
   Those are misses, and they stay in the denominator.

It carries a second measurement as well. Because its near misses are exactly the lookups the guards
still serve, it is also the population a `Verifier` claims, so `tools/verifier-catch-rate` scores every
one of them with a named reference verifier and `VerifierCatchRateTest` reports the catch rate. That
file records a **verdict per lookup rather than a rate**: the residual moves when a guard improves, and
a recorded percentage would go on describing the set it was taken from. The rate is computed against the
residual as it stands on the day the build runs, and it is never a floor: a build that spends a model
call per run is a build nobody keeps.

**The prompts are borrowed, never owned.** `ResponseGuardTest` asserts that every pair is still present,
verbatim and with the same label, in the split it names, so the two files cannot drift apart, and that
the response corpus covers every near miss the standard chain still serves. Add pairs to a blind split
and that second assertion fails, which is the signal to author their answers or to stop quoting the
coverage. Neither assertion touches the blind splits' own numbers: those are still measured on prompts
alone, by guards that never see an answer.

