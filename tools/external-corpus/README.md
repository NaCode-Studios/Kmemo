# The external split: pairs this repository did not write

`docs/CORPUS.md` describes three labelled corpora and the discipline that keeps them honest. That
discipline holds, and it still cannot answer the objection a reader should have: every pair was
written by the same person who wrote the guards, so they test the near misses that were *thought of*
rather than the near misses that *exist*.

`fetch.py` downloads a fourth split that nobody here had any hand in.

## Running it

```bash
python3.11 -m venv .venv
.venv/bin/pip install -r requirements.txt
.venv/bin/python fetch.py
```

It writes `../../build/external-corpus/paws-wiki-test.json`, which `ExternalCorpusTest` reads. Without
it that test skips with a sentence saying so; in CI, which passes `-PexternalCorpusRequired=true`, it
fails instead, because a floor nobody notices has stopped running is not a floor.

## What it fetches, and why that one

**PAWS**, Paraphrase Adversaries from Word Scrambling, Wiki `labeled_final`, **test** split. Google
Research, 2019.

It is the right shape for a reason that has nothing to do with luck. PAWS exists to measure whether a
model can tell a paraphrase from a near-paraphrase *when word overlap is deliberately high*, which is
precisely the case a similarity threshold cannot separate and precisely what every guard here was
built for. It was assembled four years before kmemo, for a purpose unrelated to caching, by people who
had never heard of this library.

The `test` split, never `train`: it is the half of PAWS that models are not fitted to, so it is out of
sample for the dataset's own field as well as for this one. 8,000 pairs, 4,464 of them near misses.

The revision is pinned to a commit rather than to `main`. A floor in CI is a promise that a number
cannot move without somebody deciding it should, and a dataset that can be re-uploaded underneath you
breaks that promise silently. The script also checks the SHA-256 of the bytes it downloaded and stops
if they are not the ones the floor was set against.

## Why it is fetched and not committed

The pairs are somebody else's work under somebody else's licence, so they stay theirs. Fetching keeps
the licence with the dataset and keeps this repository from carrying a copy of a corpus it did not
write. PAWS is distributed by Google under terms that permit free use with acknowledgement, which this
file is part of.

## The rule that governs it

It sits under the validation split's rule with one clause added: **no guard may ever be tuned against
it, and no failure from it may be read while editing one.** The other three splits are protected by a
process. This one is protected by the same process and by the fact that nobody here can add to it,
which is the strongest guarantee any of them has.

## What the number means, and what it does not

The guards score far worse here than on the three internal splits, and most of the gap is not a
weakness in the guards. PAWS pairs are **declarative sentences from Wikipedia**; kmemo's guards read
**prompts**, usually questions, usually typed quickly by a person. The register is not the one they
were built for, and it shows in the per-guard breakdown: `substitution` alone rejects 498 paraphrases
here against 2 on the validation split, because a Wikipedia sentence rearranged still carries far more
substituted content words than a rephrased question does.

So it is a hard number from a hard source, and it is the only one on the page that nobody here could
have tuned. Both facts belong beside it. `docs/CORPUS.md` and the README carry them.
