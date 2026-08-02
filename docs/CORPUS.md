# The corpus: kmemo's defended asset

kmemo's central claim — that its guards reject near-misses a similarity threshold cannot, without
rejecting genuine paraphrases — is only as trustworthy as the data behind it. That data is three
labelled corpora of prompt pairs, and this document is the process that keeps them honest.

## The four splits

Every pair is a `(a, b, category, kind)` where `kind` is either a **near-miss** (the two prompts need
different answers) or a **paraphrase** (they need the same answer). Three splits live in
`kmemo-core/src/jvmTest/resources/*-corpus.json`; the fourth is fetched rather than committed.

| Split | Role | May the guards be tuned against it? |
| --- | --- | --- |
| **tuned** | In-sample. The guards were written and tuned with these pairs in view. | Yes — by definition. |
| **held-out** | Out-of-sample. Written after the guards, never tuned against. | **No.** |
| **validation** | Blind. Written last, in one sitting, and never looked at while changing a guard. | **No — never.** |
| **external** | Written by somebody else, years earlier, for another purpose. | **No — and nobody here can add to it.** |

The whole value of the held-out and validation splits is that no guard was fitted to them. A single
prompt pair moved from validation into a guard's design destroys that pair's evidentiary value forever.
So the rule is simple and absolute: **you do not read the validation failures while editing a guard.**

## The external split, and the objection it answers

The three splits above are careful, and they still share one weakness that no amount of process can
remove: **the same person wrote the pairs and the guards.** They therefore test the near misses that
were thought of, not the near misses that exist. A reader deciding whether to trust this cache should
say exactly that, and until `2.1.0` there was no answer.

The external split is the answer. It is **PAWS** (Paraphrase Adversaries from Word Scrambling), Wiki
`labeled_final`, **test** split — Google Research, 2019, 8,000 pairs of which 4,464 are near misses.
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

## What CI enforces

`CorpusTest` runs on every build (and every PR — see `ci.yml`) and **fails on regression**:

- The tuned split must keep **every** paraphrase and catch at least a floor of near-misses.
- The held-out and validation splits must not drop below their recorded near-miss and paraphrase
  floors (`*_FLOOR` constants in `CorpusTest`).
- `ExternalCorpusTest` holds the external split to its own floors, set **at** the measurement rather
  than under it: nothing about it is stochastic, since the guards are pure and the dataset is pinned to
  a commit, so any movement at all is a real change somebody should have to look at.

The floors sit just under the current measurement — their job is to fail when a number moves **down**,
not to assert the number is good. A change that only helps the tuned set cannot pass unnoticed. The
machine-readable numbers are written to `build/reports/guards/guard-report.json` for diffing across
commits.

## Growing a split without contaminating it

Adding pairs is how the corpus stays representative of real traffic. Do it per split:

### Growing **tuned** (in-sample)

Free to do anytime. Add pairs, run `./gradlew :kmemo-core:jvmTest --tests '*CorpusTest*'`, and if a new
near-miss slips through, that is exactly the signal to improve a guard. Raise the tuned floor to match.

### Growing **held-out** or **validation** (out-of-sample)

This is the delicate one. To add pairs **without** contaminating the split:

1. **Write the pairs blind.** Collect them from real or realistic traffic *before* running them through
   the guards. Do not hand-pick pairs you already know a guard will catch (that inflates the score) or
   miss (that is tuning in disguise).
2. **Commit the pairs and the measurement in the same change.** Run the corpus test once, record the new
   floor, and commit both. The pairs and the number they produce arrive together.
3. **Do not edit a guard in the same change.** If the new pairs reveal a weakness, note it — but fixing
   it belongs in a *separate* change against the **tuned** split, so the out-of-sample number that
   exposed the weakness stays untainted.
4. **Never lower a floor to make CI pass.** A floor only ever moves up, and only because the measurement
   genuinely improved. Lowering it is erasing the regression it exists to catch.

### Growing **external**

You cannot, and that is its strongest property. It is somebody else's dataset at a pinned revision. The
only change anyone here can make to it is to point the script at a different one, which is a decision
that changes what every recorded number means and belongs in a pull request that says so.

The one-sentence version: **the tuned split is where you improve the guards; the held-out and validation
splits are where you find out, honestly, whether it worked; and the external split is where you find out
what somebody who owes you nothing would have measured.**

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
2. **Answers are realistic, not catchable.** What an assistant would actually reply — including the many
   answers that never name the term separating the two questions. `what is the boiling point of ethanol`
   is answered with `78.4 degrees Celsius at one atmosphere`, which no response-aware guard can use.
   Those are misses, and they stay in the denominator.

It carries a second measurement as well. Because its near misses are exactly the lookups the guards
still serve, it is also the population a `Verifier` claims, so `tools/verifier-catch-rate` scores every
one of them with a named reference verifier and `VerifierCatchRateTest` reports the catch rate. That
file records a **verdict per lookup rather than a rate**: the residual moves when a guard improves, and
a recorded percentage would go on describing the set it was taken from. The rate is computed against the
residual as it stands on the day the build runs, and it is never a floor — a build that spends a model
call per run is a build nobody keeps.

**The prompts are borrowed, never owned.** `ResponseGuardTest` asserts that every pair is still present,
verbatim and with the same label, in the split it names — so the two files cannot drift apart — and that
the response corpus covers every near miss the standard chain still serves. Add pairs to a blind split
and that second assertion fails, which is the signal to author their answers or to stop quoting the
coverage. Neither assertion touches the blind splits' own numbers: those are still measured on prompts
alone, by guards that never see an answer.

