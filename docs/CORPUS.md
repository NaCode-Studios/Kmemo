# The corpus: kmemo's defended asset

kmemo's central claim — that its guards reject near-misses a similarity threshold cannot, without
rejecting genuine paraphrases — is only as trustworthy as the data behind it. That data is three
labelled corpora of prompt pairs, and this document is the process that keeps them honest.

## The three splits

Every pair is a `(a, b, category, kind)` where `kind` is either a **near-miss** (the two prompts need
different answers) or a **paraphrase** (they need the same answer). The pairs live in
`kmemo-core/src/test/kotlin/dev/kmemo/fixtures/Corpora.kt`.

| Split | Role | May the guards be tuned against it? |
| --- | --- | --- |
| **tuned** | In-sample. The guards were written and tuned with these pairs in view. | Yes — by definition. |
| **held-out** | Out-of-sample. Written after the guards, never tuned against. | **No.** |
| **validation** | Blind. Written last, in one sitting, and never looked at while changing a guard. | **No — never.** |

The whole value of the held-out and validation splits is that no guard was fitted to them. A single
prompt pair moved from validation into a guard's design destroys that pair's evidentiary value forever.
So the rule is simple and absolute: **you do not read the validation failures while editing a guard.**

## What CI enforces

`CorpusTest` runs on every build (and every PR — see `ci.yml`) and **fails on regression**:

- The tuned split must keep **every** paraphrase and catch at least a floor of near-misses.
- The held-out and validation splits must not drop below their recorded near-miss and paraphrase
  floors (`*_FLOOR` constants in `CorpusTest`).

The floors sit just under the current measurement — their job is to fail when a number moves **down**,
not to assert the number is good. A change that only helps the tuned set cannot pass unnoticed. The
machine-readable numbers are written to `build/reports/guards/guard-report.json` for diffing across
commits.

## Growing a split without contaminating it

Adding pairs is how the corpus stays representative of real traffic. Do it per split:

### Growing **tuned** (in-sample)

Free to do anytime. Add pairs, run `./gradlew :kmemo-core:test --tests '*CorpusTest*'`, and if a new
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

The one-sentence version: **the tuned split is where you improve the guards; the held-out and validation
splits are where you find out, honestly, whether it worked.**
