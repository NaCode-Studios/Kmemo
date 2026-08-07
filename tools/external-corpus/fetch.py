"""Fetches the external split: adversarial paraphrase pairs kmemo did not write.

Run it from this directory once the environment in README.md exists:

    .venv/bin/python fetch.py

It writes ../../build/external-corpus/paws-wiki-test.json, in the same shape as kmemo's own corpora,
and prints the SHA-256 of both the source file and the converted one. `ExternalCorpusTest` reads the
converted file and enforces a floor on it.

## Why a fetch and not a vendored file

The pairs are somebody else's work under somebody else's licence, so they stay theirs. Downloading
them keeps the licence with the dataset and keeps this repository from growing a copy of a corpus it
did not write, which is the same reason the corpus discipline exists in the first place. The cost is
that the split is absent on a machine that has never run this, and `ExternalCorpusTest` says so out
loud rather than passing quietly.

## Why this dataset

The three corpora in `docs/CORPUS.md` are careful about contamination and that discipline holds. What
they cannot answer is the objection that matters most to somebody deciding whether to trust the cache:
the pairs were written by the same person who wrote the guards, so they test the near misses that were
thought of rather than the near misses that exist.

PAWS is the answer to that. Google Research built it in 2019, four years before kmemo, for a purpose
that has nothing to do with caching: measuring whether a model can tell paraphrase from
near-paraphrase when word overlap is deliberately high. That is exactly the case a similarity
threshold cannot separate and exactly what every guard here was built for, and nobody involved had
ever heard of this library.

The `labeled_final` **test** split is used, never `train`. It is the half of PAWS that models are not
fitted to, so it is out of sample for the dataset's own community as well as for this one.

## The gap this does not close, and does not hide

PAWS pairs are declarative sentences from Wikipedia. kmemo's guards read prompts, usually questions,
usually from a person typing quickly. The domains do not match, and a lower number here is partly the
guards meeting a register they were not built for rather than a weakness in the guards. That is worth
knowing and is not worth hiding: it is still a number nobody here could have tuned, which is more than
can be said for any of the other three.
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
OUT = HERE.parent.parent / "build" / "external-corpus" / "paws-wiki-test.json"

# Pinned to a commit, not to `main`. A floor in CI is a promise that the number cannot move without
# somebody deciding it should, and a dataset that can be re-uploaded under you breaks that promise
# silently. This revision was published on 2024-01-04.
DATASET = "google-research-datasets/paws"
REVISION = "161ece9501cf0a11f3e48bd356eaa82de46d6a09"
FILE = "labeled_final/test-00000-of-00001.parquet"
URL = f"https://huggingface.co/datasets/{DATASET}/resolve/{REVISION}/{FILE}"

# The bytes this measurement was taken against. A mismatch means the pinned revision is not what it
# was, which is a finding rather than something to work around.
SOURCE_SHA256 = "ae342ff12bb84b84b95f468abf5db6cb7c7bd578271299fe9c99be75b8132f4d"


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
    pairs = [
        {
            "a": a,
            "b": b,
            # PAWS label 1 is "these two mean the same thing", which is exactly a cacheable
            # paraphrase. Label 0 is a pair built to look alike and mean differently, which is
            # exactly a near miss.
            "shouldMatch": label == 1,
            "category": "paws-wiki",
        }
        for a, b, label in zip(table["sentence1"], table["sentence2"], table["label"])
    ]

    document = {
        "about": (
            "PAWS (Paraphrase Adversaries from Word Scrambling), Wiki labeled_final, test split. "
            "Google Research, 2019. Fetched by tools/external-corpus/fetch.py, never committed: the "
            "licence stays with the dataset. Written years before kmemo existed, for a purpose "
            "unrelated to caching, by people who had never heard of it. No guard may be tuned "
            "against it, ever."
        ),
        "standing": "blind",
        "schema": "spec/corpus/SCHEMA.json",
        "dataset": DATASET,
        "revision": REVISION,
        "file": FILE,
        "sourceSha256": digest,
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
