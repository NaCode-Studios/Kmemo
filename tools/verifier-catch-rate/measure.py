"""Runs a named reference `Verifier` over the lookups kmemo's guards let through.

Run it from this directory once the environment in README.md exists:

    .venv/bin/python measure.py

It writes ../../kmemo-core/src/test/resources/verifier-reference.json, a verdict per lookup.
VerifierCatchRateTest reads that file, recomputes the guard residual from the current guards, and
reports the catch rate over exactly the lookups that are still residual today.

## Why a verdict per lookup and not a catch rate

`Verifier` is a caller-supplied seam, so a catch rate only means something against a named
implementation and a named model. It also only means something against a named *population*, and that
population moves: improve a prompt-side guard and the residual shrinks, which would leave a recorded
percentage quietly describing a set that no longer exists.

So this file records no percentage at all. It records what the reference verifier decided about each
lookup, and the JVM side intersects that with the residual it computes on the day it runs.

## What it is measured on

`response-corpus.json`, whose near misses are exactly the lookups `MatchGuards.standard()` still serves
on the two blind splits — a fact `ResponseGuardTest` asserts on every build. Its paraphrases are the
lookups the guards keep, and they are scored too: a verifier that refuses everything has a perfect
catch rate, so the number is worthless without the cost beside it.
"""

from __future__ import annotations

import hashlib
import json
import platform
import sys
from datetime import date
from pathlib import Path

from sentence_transformers import CrossEncoder

HERE = Path(__file__).resolve().parent
RESOURCES = HERE.parent.parent / "kmemo-core" / "src" / "test" / "resources"
CORPUS = RESOURCES / "response-corpus.json"

# A duplicate-question cross-encoder, asked the question a Verifier is asked: do these two prompts
# have the same correct answer? Named here and named next to every number it produces, because a
# catch rate from an unnamed model describes whichever model happened to run.
MODEL = "cross-encoder/quora-distilroberta-base"

# The model emits a probability that the pair is a duplicate. Half is the ordinary reading of it, and
# choosing anything else would be tuning the reference implementation against the corpus.
SERVE_THRESHOLD = 0.5

# Mechanical, not semantic: it asks whether the model works, never whether it agrees with kmemo about
# a hard pair. Neither sentence is from the corpora.
TRIVIAL_RESTATEMENT = ("How do I reverse a list in Python?", "In Python, how can I reverse a list?")
UNRELATED = ("How do I reverse a list in Python?", "What is the capital of France?")


def verify(model: CrossEncoder, pairs: list[tuple[str, str]]) -> list[float]:
    return [float(score) for score in model.predict(pairs, show_progress_bar=False)]


def check(model: CrossEncoder) -> dict[str, float]:
    """Refuses to continue unless the reference verifier demonstrably discriminates."""
    restatement, unrelated = verify(model, [TRIVIAL_RESTATEMENT, UNRELATED])
    if restatement < SERVE_THRESHOLD:
        sys.exit(
            f"the reference verifier scored a trivial restatement at {restatement:.4f}, below its own "
            f"{SERVE_THRESHOLD} serving threshold. It would refuse everything, and a catch rate of "
            "100% measured that way describes nothing. Refusing to measure."
        )
    if unrelated >= SERVE_THRESHOLD:
        sys.exit(
            f"the reference verifier scored two unrelated sentences at {unrelated:.4f}, at or above "
            f"its {SERVE_THRESHOLD} serving threshold. It would serve everything. Refusing to measure."
        )
    return {"trivialRestatement": restatement, "unrelated": unrelated}


def versions() -> dict[str, str]:
    import sentence_transformers
    import torch
    import transformers

    return {
        "python": platform.python_version(),
        "sentence-transformers": sentence_transformers.__version__,
        "transformers": transformers.__version__,
        "torch": torch.__version__,
    }


def main() -> None:
    model = CrossEncoder(MODEL)
    sanity = check(model)
    print(f"reference verifier verified: restatement {sanity['trivialRestatement']:.4f}, "
          f"unrelated {sanity['unrelated']:.4f}")

    raw = CORPUS.read_bytes()
    pairs = json.loads(raw)["pairs"]

    # Both directions, because either prompt may be the one already cached when the other arrives.
    lookups = []
    for pair in pairs:
        lookups.append((pair, pair["a"], pair["b"]))
        lookups.append((pair, pair["b"], pair["a"]))

    scores = verify(model, [(query, cached) for _, query, cached in lookups])

    verdicts = []
    for (pair, query, cached), score in zip(lookups, scores):
        verdicts.append({
            "split": pair["split"],
            "shouldMatch": pair["shouldMatch"],
            "query": query,
            "cached": cached,
            "score": round(score, 4),
            "served": score >= SERVE_THRESHOLD,
        })

    near_misses = [v for v in verdicts if not v["shouldMatch"]]
    paraphrases = [v for v in verdicts if v["shouldMatch"]]
    caught = sum(1 for v in near_misses if not v["served"])
    kept = sum(1 for v in paraphrases if v["served"])
    print(f"over the whole response corpus: caught {caught}/{len(near_misses)} near-miss lookups, "
          f"kept {kept}/{len(paraphrases)} paraphrase lookups")
    print("the number that gets published is computed on the JVM side, over the residual as it stands")
    print("on the day the build runs")

    payload = {
        "measuredOn": date.today().isoformat(),
        "note": (
            "A verdict per lookup, not a catch rate. The rate is computed by VerifierCatchRateTest "
            "over the residual it recomputes. Reproduce with tools/verifier-catch-rate/README.md."
        ),
        "referenceImplementation": "sentence_transformers.CrossEncoder, used as a dev.kmemo.Verifier",
        "model": MODEL,
        "decisionRule": f"serve when the duplicate probability is >= {SERVE_THRESHOLD}",
        "sanityCheck": sanity,
        "versions": versions(),
        "responseCorpusSha256": hashlib.sha256(raw).hexdigest(),
        "verdicts": verdicts,
    }
    destination = RESOURCES / "verifier-reference.json"
    destination.write_text(json.dumps(payload, indent=2, ensure_ascii=False) + "\n")
    print(f"wrote {destination}")


if __name__ == "__main__":
    main()
