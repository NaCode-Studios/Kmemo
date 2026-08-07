"""Fetches the second external split: high-overlap question pairs kmemo did not write.

Run it from this directory once the environment in README.md exists:

    .venv/bin/python fetch.py

It writes ../../build/qqp-corpus/qqp-questions.json, in the same shape as kmemo's own corpora, and
prints the SHA-256 of both the source file and the converted one. `QqpCorpusTest` reads the converted
file and enforces a floor on it.

## Why a second external split

The first one, PAWS, answers the objection that the same person wrote the pairs and the guards. It
cannot answer a second one, because PAWS is declarative Wikipedia prose and the guards read prompts,
usually questions. Every question-register number this project publishes still comes from a corpus it
wrote, and the two blind splits behind them hold 86 and 102 near misses, which is too few to tell a
five-point change from noise.

Quora Question Pairs closes both gaps at once. The questions were typed by strangers on a public site
between 2015 and 2017, the duplicate labels were applied by Quora rather than by anyone here, and the
GLUE validation split alone carries 40,430 pairs. Label 1 is "these two questions have the same
answer", which is exactly a cacheable paraphrase; label 0 is "they do not", which is exactly a near
miss.

## The selection rule, and why one is needed

Most label-0 pairs in QQP are two unrelated questions. A semantic cache never sees those, because the
similarity threshold rejects them before a guard is asked, so scoring the guards against them would
measure a case that does not arise and would report a catch rate far higher than the one a deployment
gets.

So the pairs are filtered to the ones a threshold would surface, by a rule that is fixed here rather
than tuned: **keep the pair when its two questions share at least 60% of their character 4-grams**,
Jaccard, over the raw strings with whitespace collapsed. Characters rather than words, and the raw
string rather than the tokenizer, so that the rule does not borrow any part of a guard's own
machinery.

Two consequences, both stated rather than discovered later:

1. **The filter is not neutral for one guard.** `lexical-divergence` fires when two prompts share
   almost nothing, and this rule keeps only pairs that share a great deal, so that guard is close to
   silent on this split by construction. The bias runs against the score rather than for it: the
   filter removes the pairs that are easiest to catch.
2. **The pairs and the labels are external; the selection is not.** Quora wrote the questions and
   labelled them. This repository chose the threshold, once, before running a single guard against the
   result, and has not moved it since. That is weaker than PAWS, where nobody here can change
   anything, and stronger than any split written in this repository.

## What the labels are worth

QQP's labels are crowd-applied and the dataset's own documentation calls them noisy. Published
estimates of the disagreement rate sit near a twentieth of the pairs. That noise puts a ceiling on
any score measured here, in both directions, and it is a reason to read a change in the number rather
than the number itself.
"""

from __future__ import annotations

import hashlib
import io
import json
import sys
import urllib.request
from pathlib import Path

import pyarrow.parquet as pq

HERE = Path(__file__).resolve().parent
OUT = HERE.parent.parent / "build" / "qqp-corpus" / "qqp-questions.json"

# Pinned to a commit, for the same reason the PAWS fetch is: a floor in CI is a promise that a number
# cannot move without somebody deciding it should, and a dataset that can be re-uploaded underneath
# you breaks that promise silently.
DATASET = "nyu-mll/glue"
REVISION = "bcdcba79d07bc864c1c254ccfcedcce55bcc9a8c"
FILE = "qqp/validation-00000-of-00001.parquet"
URL = f"https://huggingface.co/datasets/{DATASET}/resolve/{REVISION}/{FILE}"

# The bytes this measurement was taken against.
SOURCE_SHA256 = "efd86a539c412d74874ee451573d7bd142f56c47fe36de033b9f367d8bb0fa71"

# The selection rule. Fixed before any guard was run against the result; see the module docstring.
GRAM = 4
MIN_OVERLAP = 0.6


def grams(text: str) -> set[str]:
    padded = " " + " ".join(text.lower().split()) + " "
    return {padded[i : i + GRAM] for i in range(max(0, len(padded) - GRAM + 1))}


def overlap(a: str, b: str) -> float:
    left, right = grams(a), grams(b)
    if not left or not right:
        return 0.0
    return len(left & right) / len(left | right)


def main() -> int:
    print(f"fetching {DATASET}@{REVISION[:8]} {FILE}")
    raw = urllib.request.urlopen(URL, timeout=300).read()

    digest = hashlib.sha256(raw).hexdigest()
    print(f"  {len(raw):,} bytes, sha256 {digest}")
    if digest != SOURCE_SHA256:
        print(f"  ERROR: expected {SOURCE_SHA256}", file=sys.stderr)
        print("  The pinned revision no longer serves the bytes this floor was set against.",
              file=sys.stderr)
        return 1

    table = pq.read_table(io.BytesIO(raw)).to_pydict()
    rows = zip(table["question1"], table["question2"], table["label"])
    pairs = [
        {
            "a": a,
            "b": b,
            # GLUE QQP label 1 is "duplicate", meaning one answer serves both, which is a cacheable
            # paraphrase. Label 0 is "not duplicate", which after the overlap filter is a pair that
            # looks alike and needs different answers: a near miss.
            "shouldMatch": label == 1,
            "category": "qqp",
        }
        for a, b, label in rows
        if label in (0, 1) and overlap(a, b) >= MIN_OVERLAP
    ]

    document = {
        "about": (
            "Quora Question Pairs, GLUE validation split, filtered to pairs whose two questions "
            "share at least 60% of their character 4-grams. Questions typed by the public and "
            "labelled by Quora, years before kmemo existed. Fetched by tools/qqp-corpus/fetch.py, "
            "never committed: the licence stays with the dataset. No guard may be tuned against it, "
            "ever, and no failure from it may be read while a guard is being changed."
        ),
        "dataset": DATASET,
        "revision": REVISION,
        "file": FILE,
        "sourceSha256": digest,
        "selection": {
            "rule": "character 4-gram Jaccard over the whitespace-collapsed lowercased strings",
            "gram": GRAM,
            "minOverlap": MIN_OVERLAP,
        },
        "pairs": pairs,
    }

    OUT.parent.mkdir(parents=True, exist_ok=True)
    text = json.dumps(document, indent=2, ensure_ascii=False) + "\n"
    OUT.write_text(text, encoding="utf-8")

    near = sum(1 for p in pairs if not p["shouldMatch"])
    print(f"  wrote {OUT}: {len(pairs):,} pairs, {near:,} near misses, {len(pairs) - near:,} paraphrases")
    print(f"  converted sha256 {hashlib.sha256(text.encode('utf-8')).hexdigest()}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
