# The question split: pairs this repository did not write, in the register it was built for

`tools/external-corpus` fetches PAWS, which answers the objection that the same person wrote the
pairs and the guards. It leaves two things open, and this script closes both.

**PAWS is prose and the guards read prompts.** Its pairs are declarative Wikipedia sentences; the
guards were built for questions typed quickly by a person. Every question-register figure this
project publishes therefore still comes from a corpus it wrote itself.

**The blind question splits are too small to see a change.** They hold 86 and 102 near misses. At
that size a catch rate carries a 95% interval about nine points wide in each direction, so a real
five-point improvement and a lucky run are the same measurement.

`fetch.py` downloads a split that is external, in the right register, and an order of magnitude
larger.

## Running it

```bash
python3.11 -m venv .venv
.venv/bin/pip install -r requirements.txt
.venv/bin/python fetch.py
```

It writes `../../build/qqp-corpus/qqp-questions.json`, which `QqpCorpusTest` reads. Without it that
test skips with a sentence saying so; in CI, which passes `-PqqpCorpusRequired=true`, it fails
instead.

## What it fetches

**Quora Question Pairs**, the GLUE `validation` split, 40,430 pairs. The questions were typed by the
public on Quora between 2015 and 2017 and the duplicate labels were applied by Quora, years before
this library existed and for a purpose that has nothing to do with caching. Label 1 means one answer
serves both questions, which is a cacheable paraphrase; label 0 means it does not, which is a near
miss.

The revision is pinned to a commit and the script checks the SHA-256 of the bytes it downloaded.

## The selection rule

Most label-0 pairs in QQP are two unrelated questions. A semantic cache never sees those, because the
similarity threshold rejects them before any guard is asked. Scoring the guards against them would
measure a case that does not arise and would report a catch rate no deployment gets.

So the pairs are filtered to the ones a threshold would surface: **keep the pair when its two
questions share at least 60% of their character 4-grams**, Jaccard, over the lowercased strings with
whitespace collapsed. That leaves 5,296 pairs, of which 2,500 are near misses, with a median length of
51 characters.

Characters rather than words, and the raw string rather than the tokenizer, so the rule borrows no
part of a guard's own machinery. Two consequences belong beside it rather than in a footnote:

**The filter is not neutral for one guard.** `lexical-divergence` fires when two prompts share almost
nothing, and this keeps only pairs that share a great deal, so that guard is near-silent here by
construction. The bias runs against the score, not for it: the filter removes the pairs that would
have been easiest to catch.

**The pairs and the labels are external; the selection is not.** Quora wrote the questions and
labelled them. This repository chose one threshold, once, before running a single guard against the
result. That is weaker than PAWS, where nobody here can change anything, and stronger than any split
written here.

## What the labels are worth

QQP's labels are crowd-applied and the dataset's own card calls them noisy. Published estimates put
the disagreement rate near a twentieth of the pairs. That noise caps any score taken here in both
directions, which is a reason to read a change in the number rather than the number itself.

## Why it is fetched and not committed

The pairs are somebody else's work under somebody else's terms, so they stay theirs. Fetching keeps
the licence with the dataset and keeps this repository from carrying a copy of a corpus it did not
write.

## The rule that governs it

The same one the other blind splits carry, with the clause PAWS added: **no guard may ever be tuned
against it, and no failure from it may be read while a guard is being changed.** `docs/CORPUS.md`
states what the reports may and may not print.
